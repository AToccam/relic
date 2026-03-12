package com.relic.service;

import com.relic.tool.ToolCallService;
import com.relic.util.MessageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * AI 路由服务：管理所有 AiProvider，统一调度。
 * 支持两种模式：
 * - single: 仅 DeepSeek 直接回答
 * - multi:  Kimi + Qwen 并行分析 → DeepSeek 聚合后流式输出
 */
@Slf4j
@Service
public class AiRouterService {

    public enum Mode { SINGLE, MULTI }

    private static final String AGGREGATOR = "deepseek";
    private static final List<String> ADVISORS = List.of("kimi", "qwen");

    private volatile Mode currentMode = Mode.SINGLE;
    //SINGLE 模式下，直接使用 DeepSeek；MULTI 模式下，先让 Kimi 和 Qwen 分析，再由 DeepSeek 聚合输出

    private final Map<String, AiProvider> providerMap = new LinkedHashMap<>();

    @Autowired
    private ToolCallService toolCallService;

    @Autowired
    public AiRouterService(List<AiProvider> providers) {
        for (AiProvider p : providers) {
            providerMap.put(p.getName(), p);
            log.info("注册 AI 提供者: {}", p.getName());
        }
    }

    // ==================== 模式管理 ====================

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

    /** 使用指定 provider 进行单轮问答 */
    public String ask(String providerName, String prompt) {
        return getProvider(providerName).ask(prompt);
    }

    // ==================== 自动模式调度 ====================

    /** 根据当前模式自动选择流式输出方式 */
    public void streamAuto(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        if (currentMode == Mode.MULTI) {
            streamMulti(messages, onChunk);
        } else {
            streamSingle(messages, onChunk);
        }
    }

    /** 根据当前模式自动选择同步问答方式 */
    public String askAuto(List<Map<String, Object>> messages) {
        if (currentMode == Mode.MULTI) {
            return askMulti(messages);
        } else {
            List<Map<String, Object>> enriched = MessageHelper.ensureToolSystemPrompt(messages);
            return toolCallService.askWithTools(getProvider(AGGREGATOR), enriched);
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
     * 3. 将各方观点注入 system prompt，交给 DeepSeek 流式聚合输出
     */
    public void streamMulti(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        String userQuestion = extractLatestUserMessage(messages);
        log.info("【多AI协同】用户提问: {}", userQuestion);

        // 先推一条提示，让前端知道我们在工作
        onChunk.accept("🤔 正在收集多个 AI 的观点，请稍候...\n\n");

        // 并行收集 advisor 回复，同时发心跳保活
        Map<String, String> advisorReplies = collectAdvisorRepliesWithHeartbeat(userQuestion, onChunk);

        // 构建包含多方观点的消息列表，传给 DeepSeek 流式输出
        List<Map<String, Object>> aggregatedMessages = MessageHelper.buildAggregatedMessages(messages, advisorReplies);
        log.info("【多AI协同】已收集 {} 个顾问回复，交由 DeepSeek 聚合", advisorReplies.size());

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

    /** 并行调用所有 advisor，收集回复（对外暴露，可用于测试） */
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
                return (String) messages.get(i).get("content");
            }
        }
        return "";
    }
}
