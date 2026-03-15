package com.relic.service;

import com.relic.tool.ToolCallService;
import com.relic.util.MessageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * AI 路由服务：管理所有 AiProvider，统一调度。
 * 支持两种模式：
 * - single: 仅主模型（Qwen）直接回答
 * - multi:  Advisor 并行分析 → 主模型（Qwen）聚合后输出
 */
@Slf4j
@Service
public class AiRouterService {

    public enum Mode { SINGLE, MULTI }

    private static final String AGGREGATOR = "qwen";
    private static final List<String> ADVISORS = List.of("deepseek", "kimi");

    private volatile Mode currentMode = Mode.MULTI; //默认多 AI 协同模式
    //SINGLE 模式下，直接使用主模型；MULTI 模式下，先让 advisor 分析，再由主模型聚合输出

    private final Map<String, AiProvider> providerMap = new LinkedHashMap<>();
    private final SemanticRouter semanticRouter;
    private final Optional<OllamaLocalService> localFallbackService;

    @Value("${relic.router.fast-use-local:true}")
    private boolean fastUseLocal;

    @Autowired
    private ToolCallService toolCallService;

    @Autowired
    public AiRouterService(List<AiProvider> providers,
                           SemanticRouter semanticRouter,
                           ObjectProvider<OllamaLocalService> localFallbackProvider) {
        this.semanticRouter = semanticRouter;
        this.localFallbackService = Optional.ofNullable(localFallbackProvider.getIfAvailable());
        for (AiProvider p : providers) {
            providerMap.put(p.getName(), p);
            log.info("注册 AI 提供者: {}", p.getName());
        }
    }

    //模式管理
    public Mode getMode() { return currentMode; }

    public void setMode(Mode mode) {
        this.currentMode = mode;
        log.info("AI 模式已切换为: {}", mode);
    }

    // ==================== Provider 查询 ====================
    public AiProvider getProvider(String name) {
        AiProvider provider = providerMap.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("未知的 AI 提供者: " + name);
        }
        return provider;
    }

    public Set<String> getProviderNames() {
        return providerMap.keySet();
    }

    // 使用指定 provider 进行单轮问答，测试用
    public String ask(String providerName, String prompt) {
        return getProvider(providerName).ask(prompt);
    }

    //自动模式调度
    //根据当前模式自动选择流式输出方式
    //SINGLE: 强制快车道；MULTI: 智能分流（快车道/工具优先/多专家）
    public void streamAuto(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        SemanticRouter.RouteDecision decision = null;
        try {
            Optional<SemanticRouter.RouteDecision> multimodalDecision = forcePrimaryForMultimodal(messages);
            if (multimodalDecision.isPresent()) {
                decision = multimodalDecision.get();
                log.info("【语义路由-多模态优先】path={}, reason={}", decision.path(), decision.reason());
                streamSingle(messages, onChunk);
                return;
            }

            if (currentMode == Mode.SINGLE) {
                decision = semanticRouter.decide(messages);
                log.info("【语义路由-SINGLE】path={}, reason={}", decision.path(), decision.reason());

                if (decision.path() == SemanticRouter.RoutePath.FAST
                        && fastUseLocal && localFallbackService.isPresent()) {
                    streamFastLocal(messages, onChunk);
                } else {
                    streamSingle(messages, onChunk);
                }
                return;
            }

            decision = semanticRouter.decide(messages);
            log.info("【语义路由】path={}, reason={}", decision.path(), decision.reason());

            switch (decision.path()) {
                case TOOL_FIRST -> streamSingle(messages, onChunk);
                case FAST -> {
                    if (fastUseLocal && localFallbackService.isPresent()) {
                        streamFastLocal(messages, onChunk);
                    } else {
                        streamSingle(messages, onChunk);
                    }
                }
                case DEEP -> streamMulti(messages, onChunk);
            }
        } catch (Exception e) {
            if (decision != null
                    && decision.path() == SemanticRouter.RoutePath.FAST
                    && isUpstreamConnectivityIssue(e)) {
                fallbackStreamAnswer(messages, onChunk, describeThrowable(e));
                return;
            }
            throw e;
        }
    }

    //根据当前模式自动选择同步问答方式
    public String askAuto(List<Map<String, Object>> messages) {
        SemanticRouter.RouteDecision decision = null;
        try {
            String result;
            Optional<SemanticRouter.RouteDecision> multimodalDecision = forcePrimaryForMultimodal(messages);
            if (multimodalDecision.isPresent()) {
                decision = multimodalDecision.get();
                log.info("【语义路由-多模态优先】path={}, reason={}", decision.path(), decision.reason());
                List<Map<String, Object>> enriched = MessageHelper.ensureToolSystemPrompt(messages);
                result = toolCallService.askWithTools(getProvider(AGGREGATOR), enriched);
                return result;
            }

            if (currentMode == Mode.SINGLE) {
                decision = semanticRouter.decide(messages);
                log.info("【语义路由-SINGLE】path={}, reason={}", decision.path(), decision.reason());

                if (decision.path() == SemanticRouter.RoutePath.FAST
                        && fastUseLocal && localFallbackService.isPresent()) {
                    result = askFastLocal(messages);
                } else {
                    List<Map<String, Object>> enriched = MessageHelper.ensureToolSystemPrompt(messages);
                    result = toolCallService.askWithTools(getProvider(AGGREGATOR), enriched);
                }
            } else {
                decision = semanticRouter.decide(messages);
                log.info("【语义路由】path={}, reason={}", decision.path(), decision.reason());
                if (decision.path() == SemanticRouter.RoutePath.DEEP) {
                    result = askMulti(messages);
                } else if (decision.path() == SemanticRouter.RoutePath.FAST
                        && fastUseLocal && localFallbackService.isPresent()) {
                    result = askFastLocal(messages);
                } else {
                    List<Map<String, Object>> enriched = MessageHelper.ensureToolSystemPrompt(messages);
                    result = toolCallService.askWithTools(getProvider(AGGREGATOR), enriched);
                }
            }

            if (decision != null
                    && decision.path() == SemanticRouter.RoutePath.FAST
                    && isUpstreamFailureText(result)) {
                return fallbackTextAnswer(messages, result);
            }
            return result;
        } catch (Exception e) {
            if (decision != null
                    && decision.path() == SemanticRouter.RoutePath.FAST
                    && isUpstreamConnectivityIssue(e)) {
                return fallbackTextAnswer(messages, describeThrowable(e));
            }
            throw e;
        }
    }

    // ==================== 单 AI 模式 ====================

    public void streamSingle(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        List<Map<String, Object>> enriched = MessageHelper.ensureToolSystemPrompt(messages);
        toolCallService.streamWithTools(getProvider(AGGREGATOR), enriched, onChunk);
    }

    // ==================== 多 AI 协同模式 ====================

    /**
     * 多 AI 协同流式输出：
     * 1. 提取用户最新提问
     * 2. Kimi + Qwen 并行处理（期间发心跳保活）
    * 3. 将各方观点注入 system prompt，交给主模型流式聚合输出
     */
    public void streamMulti(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        String userQuestion = extractLatestUserMessage(messages);
        log.info("【多AI协同】用户提问: {}", userQuestion);

        // 先推一条提示，让前端知道我们在工作
        onChunk.accept("🤔 正在收集多个 AI 的观点，请稍候...\n\n");

        // 并行收集 advisor 回复，同时发心跳保活
        Map<String, String> advisorReplies = collectAdvisorRepliesWithHeartbeat(userQuestion, onChunk);

        // 构建包含多方观点的消息列表，传给主模型流式输出
        List<Map<String, Object>> aggregatedMessages = MessageHelper.buildAggregatedMessages(messages, advisorReplies);
        log.info("【多AI协同】已收集 {} 个顾问回复，交由Leader聚合", advisorReplies.size());

        onChunk.accept("✅ 已收集完毕，正在生成最终回答...\n\n");
        toolCallService.streamWithTools(getProvider(AGGREGATOR), aggregatedMessages, onChunk);
    }

    /** 多 AI 协同同步输出（非流式） */
    public String askMulti(List<Map<String, Object>> messages) {
        String userQuestion = extractLatestUserMessage(messages);
        Map<String, String> advisorReplies = collectAdvisorReplies(userQuestion);
        List<Map<String, Object>> aggregatedMessages = MessageHelper.buildAggregatedMessages(messages, advisorReplies);
        return toolCallService.askWithTools(getProvider(AGGREGATOR), aggregatedMessages);
    }

    /** 并行调用所有 advisor，收集回复（带心跳保活） */
    private Map<String, String> collectAdvisorRepliesWithHeartbeat(String userQuestion, Consumer<String> onChunk) {
        Map<String, String> replies = new ConcurrentHashMap<>();

        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                ADVISORS.stream()
                        .filter(providerMap::containsKey)
                        .map(name -> CompletableFuture.runAsync(() -> {
                            try {
                                long start = System.currentTimeMillis();
                                log.info("【多AI协同】正在请求 {} ...", name);
                                String reply = getProvider(name).ask(userQuestion);
                                long cost = System.currentTimeMillis() - start;
                                log.info("【多AI协同】{} 回复完成，耗时 {} ms", name, cost);
                                replies.put(name, reply);
                            } catch (Exception e) {
                                log.error("【多AI协同】{} 调用失败", name, e);
                                replies.put(name, "（" + name + " 未能给出回复）");
                            }
                        }))
                        .toArray(CompletableFuture[]::new)
        );

        // 每 5 秒发一次心跳，防止前端超时断开
        while (!allDone.isDone()) {
            try {
                allDone.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                // 还没完成，发心跳
                try { onChunk.accept(""); } catch (Exception ignored) {}
            } catch (Exception e) {
                log.error("【多AI协同】等待 advisor 回复异常", e);
                break;
            }
        }

        return replies;
    }

    //并行调用所有 advisor，收集回复（对外暴露，可用于测试） 
    public Map<String, String> collectAdvisorReplies(String userQuestion) {
        Map<String, String> replies = new ConcurrentHashMap<>();

        List<CompletableFuture<Void>> futures = ADVISORS.stream()
                .filter(providerMap::containsKey)
                .map(name -> CompletableFuture.runAsync(() -> {
                    try {
                        long start = System.currentTimeMillis();
                        log.info("【多AI协同】正在请求 {} ...", name);
                        String reply = getProvider(name).ask(userQuestion);
                        long cost = System.currentTimeMillis() - start;
                        log.info("【多AI协同】{} 回复完成，耗时 {} ms", name, cost);
                        replies.put(name, reply);
                    } catch (Exception e) {
                        log.error("【多AI协同】{} 调用失败", name, e);
                        replies.put(name, "（" + name + " 未能给出回复）");
                    }
                }))
                .toList();

        // 等待所有 advisor 完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return replies;
    }

    private String extractLatestUserMessage(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                return MessageHelper.extractTextContent(messages.get(i).get("content"));
            }
        }
        return "";
    }

    private Optional<SemanticRouter.RouteDecision> forcePrimaryForMultimodal(List<Map<String, Object>> messages) {
        if (!hasMultimodalInput(messages)) {
            return Optional.empty();
        }
        return Optional.of(new SemanticRouter.RouteDecision(
                SemanticRouter.RoutePath.DEEP,
                "multimodal-primary"));
    }

    @SuppressWarnings("unchecked")
    private boolean hasMultimodalInput(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"user".equals(msg.get("role"))) {
                continue;
            }
            return hasMediaInContent(msg.get("content"));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean hasMediaInContent(Object content) {
        if (content instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> part = (Map<String, Object>) raw;
                String type = String.valueOf(part.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
                if (type.contains("image") || type.contains("audio")
                        || part.containsKey("image_url") || part.containsKey("input_audio")
                        || part.containsKey("audio_url")) {
                    return true;
                }
            }
        }
        if (content instanceof Map<?, ?> raw) {
            Map<String, Object> part = (Map<String, Object>) raw;
            String type = String.valueOf(part.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
            return type.contains("image") || type.contains("audio")
                    || part.containsKey("image_url") || part.containsKey("input_audio")
                    || part.containsKey("audio_url");
        }
        return false;
    }

    private boolean isUpstreamFailureText(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("连接 qwen 失败")
            || lower.contains("qwen api 错误")
            || lower.contains("连接 deepseek 失败")
            || lower.contains("deepseek api 错误")
                || lower.contains("connect")
                || lower.contains("refused");
    }

    private boolean isUpstreamConnectivityIssue(Throwable e) {
        Throwable curr = e;
        while (curr != null) {
            if (curr instanceof ConnectException
                    || curr instanceof UnknownHostException
                    || curr instanceof UnresolvedAddressException) {
                return true;
            }
            String cls = curr.getClass().getName();
            if ("java.net.ConnectException".equals(cls)
                    || "java.nio.channels.UnresolvedAddressException".equals(cls)
                    || "java.net.UnknownHostException".equals(cls)) {
                return true;
            }
            if (curr.getMessage() != null) {
                String lower = curr.getMessage().toLowerCase(Locale.ROOT);
                if (lower.contains("connect")
                        || lower.contains("refused")
                        || lower.contains("unresolved")
                        || lower.contains("unknown host")) {
                    return true;
                }
            }
            curr = curr.getCause();
        }
        return false;
    }

    private String fallbackTextAnswer(List<Map<String, Object>> messages, String reason) {
        String question = extractLatestUserMessage(messages);
        if (localFallbackService.isPresent()) {
            log.warn("【本地兜底】检测到上游异常，转 Ollama 简答。原因: {}", reason);
            return "连接失败，当前调用本地模型。\n" + localFallbackService.get().simpleAnswer(question);
        }
        return "云端 AI 暂不可用：" + reason;
    }

    private void fallbackStreamAnswer(List<Map<String, Object>> messages,
                                      Consumer<String> onChunk,
                                      String reason) {
        String question = extractLatestUserMessage(messages);
        if (localFallbackService.isPresent()) {
            log.warn("【本地兜底-流式】检测到上游异常，转 Ollama 简答。原因: {}", reason);
            String answer = localFallbackService.get().simpleAnswer(question);
            onChunk.accept("\n\n连接失败，当前调用本地模型。\n\n");
            onChunk.accept(answer);
            return;
        }
        onChunk.accept("\n\n⚠️ 云端 AI 暂不可用，且未配置本地兜底模型。\n");
    }

    private String askFastLocal(List<Map<String, Object>> messages) {
        String question = extractLatestUserMessage(messages);
        return "⚡️快速模式：当前调用本地模型。\n" + localFallbackService.get().simpleAnswer(question);
    }

    private void streamFastLocal(List<Map<String, Object>> messages, Consumer<String> onChunk) {
        String question = extractLatestUserMessage(messages);
        String answer = localFallbackService.get().simpleAnswer(question);
        onChunk.accept("⚡️快速模式：当前调用本地模型。\n\n");
        onChunk.accept(answer);
    }

    private String describeThrowable(Throwable e) {
        if (e == null) return "unknown";
        StringBuilder sb = new StringBuilder(e.getClass().getSimpleName());
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            sb.append(": ").append(e.getMessage());
        }
        Throwable cause = e.getCause();
        if (cause != null) {
            sb.append(" | cause=").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                sb.append(": ").append(cause.getMessage());
            }
        }
        return sb.toString();
    }
}
