package com.relic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.tool.ToolExecutor;
import com.relic.tool.ToolCallService;
import com.relic.util.MessageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * AI 路由服务：管理所有 AiProvider，统一调度。
 * 支持两种模式：
 * - single: 仅主模型直接回答
 * - multi:  Advisor 并行分析 → 主模型聚合后输出
 */
@Slf4j
@Service
public class AiRouterService {

    private static final Pattern LOCAL_READABLE_FILE_PATTERN = Pattern.compile(
            "(?i)^.+\\.(txt|md|markdown|json|xml|yaml|yml|csv|log|pdf|doc|docx)$");

    private static final Set<String> READABLE_TEXT_EXTENSIONS = Set.of(
        "txt", "md", "markdown", "json", "xml", "yaml", "yml", "csv", "log",
        "pdf", "doc", "docx"
    );

    private static final Set<String> NON_TEXT_MULTIMODAL_EXTENSIONS = Set.of(
        "ppt", "pptx", "xls", "xlsx", "ods", "odp", "key", "numbers", "pages"
    );

    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg",
        "mp3", "wav", "ogg", "m4a", "flac", "aac", "webm",
        "mp4", "mov", "avi", "mkv", "m4v"
    );

    public enum Mode { SINGLE, MULTI }

    private volatile Mode currentMode = Mode.SINGLE; //默认单 AI 模式
    //SINGLE 模式下，直接使用主模型；MULTI 模式下，先让 advisor 分析，再由主模型聚合输出

    @Value("${relic.router.primary-provider:deepseek}")
    private String primaryProvider;

    @Value("${relic.router.tool-provider:qwen}")
    private String toolProvider;

    @Value("${relic.router.advisors:qwen,kimi}")
    private String advisorsConfig;

    private volatile List<String> advisors = List.of("qwen", "kimi");

    @Value("${relic.router.multimodal-providers:qwen,kimi}")
    private String multimodalProvidersConfig;

    private List<String> multimodalProviders = List.of("qwen", "kimi");

    private final Map<String, AiProvider> providerMap = new LinkedHashMap<>();
    private final SemanticRouter semanticRouter;
    private final Optional<OllamaLocalService> localFallbackService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ToolCallService toolCallService;

    @Autowired
    private ToolExecutor toolExecutor;

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

    @PostConstruct
    public void logStartupConfiguration() {
        advisors = Arrays.stream(advisorsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        multimodalProviders = Arrays.stream(multimodalProvidersConfig.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
        log.info("========== AI Router Configuration ==========");
        log.info("启动模式: {}", currentMode);
        log.info("主力模型: {}", primaryProvider);
        log.info("工具模型: {}", toolProvider);
        log.info("Advisor 列表: {}", advisors);
        log.info("多模态优先 Provider 列表: {}", multimodalProviders);
        log.info("已注册 Provider: {}", providerMap.keySet());
        log.info("本地兜底服务是否可用: {}", localFallbackService.isPresent());
        log.info("============================================");
    }

    public String getPrimaryProviderName() {
        return resolvePrimaryProviderName();
    }

    public String getSingleProviderName() {
        return resolvePrimaryProviderName();
    }

    public void setSingleProviderName(String providerName) {
        String candidate = providerName == null ? "" : providerName.trim();
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("single provider 不能为空");
        }
        if (!providerMap.containsKey(candidate)) {
            throw new IllegalArgumentException("未知的 AI 提供者: " + candidate);
        }
        this.primaryProvider = candidate;
        log.info("SINGLE 当前模型已切换为: {}", candidate);
    }

    public String getMultiLeaderProviderName() {
        String configured = toolProvider == null ? "" : toolProvider.trim();
        if (!configured.isEmpty() && providerMap.containsKey(configured)) {
            return configured;
        }
        return resolvePrimaryProviderName();
    }

    public void setMultiLeaderProviderName(String providerName) {
        String candidate = providerName == null ? "" : providerName.trim();
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("multi leader 不能为空");
        }
        if (!providerMap.containsKey(candidate)) {
            throw new IllegalArgumentException("未知的 AI 提供者: " + candidate);
        }
        this.toolProvider = candidate;
        log.info("MULTI Leader 已切换为: {}", candidate);
    }

    public List<String> getAdvisors() {
        return List.copyOf(advisors);
    }

    public void setAdvisors(List<String> advisorNames) {
        if (advisorNames == null || advisorNames.isEmpty()) {
            throw new IllegalArgumentException("advisor 列表不能为空");
        }

        List<String> normalized = advisorNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("advisor 列表不能为空");
        }

        for (String advisor : normalized) {
            if (!providerMap.containsKey(advisor)) {
                throw new IllegalArgumentException("未知的 AI 提供者: " + advisor);
            }
        }

        this.advisors = List.copyOf(normalized);
        log.info("MULTI Advisors 已更新: {}", this.advisors);
    }

    public String getProviderNameForMessages(List<Map<String, Object>> messages) {
        return resolveProviderNameForMessages(messages);
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
    //SINGLE: 主模型直答；MULTI: 智能分流（简单/工具优先/多专家）
    public void streamAuto(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        List<Map<String, Object>> preprocessed = preprocessMessagesForLocalRead(messages);
        SemanticRouter.RouteDecision decision = null;
        try {
            Optional<SemanticRouter.RouteDecision> multimodalDecision = forcePrimaryForMultimodal(preprocessed);
            if (multimodalDecision.isPresent()) {
                decision = multimodalDecision.get();
                String providerName = resolveToolProviderNameForMessages(preprocessed);
                log.info("【语义路由-多模态优先】path={}, reason={}, provider={}",
                        decision.path(), decision.reason(), providerName);
                streamSingle(preprocessed, onChunk);
                return;
            }

            if (currentMode == Mode.SINGLE) {
                decision = semanticRouter.decide(preprocessed);
                log.info("【语义路由-SINGLE】path={}, reason={}", decision.path(), decision.reason());

                streamSingle(preprocessed, onChunk);
                return;
            }

            decision = semanticRouter.decide(preprocessed);
            log.info("【语义路由】path={}, reason={}", decision.path(), decision.reason());

            switch (decision.path()) {
                case TOOL_FIRST -> {
                    log.info("【语义路由】TOOL_FIRST 在 MULTI 模式下走多 AI 协同");
                    streamMulti(preprocessed, onChunk);
                }
                case FAST -> streamSingle(preprocessed, onChunk);
            }
        } catch (Exception e) {
            if (isUpstreamConnectivityIssue(e)) {
                fallbackStreamAnswer(preprocessed, onChunk, describeThrowable(e));
                return;
            }
            throw e;
        }
    }

    //根据当前模式自动选择同步问答方式
    public String askAuto(List<Map<String, Object>> messages) {
        List<Map<String, Object>> preprocessed = preprocessMessagesForLocalRead(messages);
        SemanticRouter.RouteDecision decision = null;
        try {
            String result;
            Optional<SemanticRouter.RouteDecision> multimodalDecision = forcePrimaryForMultimodal(preprocessed);
            if (multimodalDecision.isPresent()) {
                decision = multimodalDecision.get();
                String providerName = resolveToolProviderNameForMessages(preprocessed);
                log.info("【语义路由-多模态优先】path={}, reason={}, provider={}",
                        decision.path(), decision.reason(), providerName);
                List<Map<String, Object>> enriched = MessageHelper.ensureToolSystemPrompt(preprocessed);
                result = toolCallService.askWithTools(getProvider(providerName), enriched);
                return result;
            }

            if (currentMode == Mode.SINGLE) {
                decision = semanticRouter.decide(preprocessed);
                log.info("【语义路由-SINGLE】path={}, reason={}", decision.path(), decision.reason());

                List<Map<String, Object>> enriched = MessageHelper.ensureToolSystemPrompt(preprocessed);
                result = toolCallService.askWithTools(getProvider(resolveToolProviderNameForMessages(preprocessed)), enriched);
            } else {
                decision = semanticRouter.decide(preprocessed);
                log.info("【语义路由】path={}, reason={}", decision.path(), decision.reason());
                if (decision.path() == SemanticRouter.RoutePath.TOOL_FIRST) {
                    log.info("【语义路由】TOOL_FIRST 在 MULTI 模式下走多 AI 协同");
                    result = askMulti(preprocessed);
                } else {
                    List<Map<String, Object>> enriched = MessageHelper.ensureToolSystemPrompt(preprocessed);
                    result = toolCallService.askWithTools(getProvider(resolveToolProviderNameForMessages(preprocessed)), enriched);
                }
            }

            if (isUpstreamFailureText(result)) {
                return fallbackTextAnswer(messages, result);
            }
            return result;
        } catch (Exception e) {
            if (isUpstreamConnectivityIssue(e)) {
                return fallbackTextAnswer(preprocessed, describeThrowable(e));
            }
            throw e;
        }
    }

    // ==================== 单 AI 模式 ====================

    public void streamSingle(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        List<Map<String, Object>> enriched = MessageHelper.ensureToolSystemPrompt(messages);
        String providerName = resolveToolProviderNameForMessages(messages);
        toolCallService.streamWithTools(getProvider(providerName), enriched, onChunk);
    }

    // ==================== 多 AI 协同模式 ====================

    /**
     * 多 AI 协同流式输出：
     * 1. 并行收集 advisor 回复（advisor 也允许调用工具）
     * 2. 将可用观点注入 system prompt，交给 leader 聚合输出
     */
    public void streamMulti(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        String userQuestion = extractLatestUserMessage(messages);
        log.info("【多AI协同】用户提问: {}", userQuestion);

        // 先推一条提示，让前端知道我们在工作
        onChunk.accept("🤔 正在收集多个 AI 的观点，请稍候...\n\n");

        // 并行收集 advisor 回复，同时发心跳保活
        Map<String, String> advisorReplies = collectAdvisorRepliesWithHeartbeat(messages, onChunk);

        // 构建包含多方观点的消息列表，传给主模型流式输出
        List<Map<String, Object>> aggregatedMessages = MessageHelper.buildAggregatedMessages(messages, advisorReplies);
        log.info("【多AI协同】已收集 {} 个顾问回复，交由Leader聚合", advisorReplies.size());

        onChunk.accept("✅ 已收集完毕，正在生成最终回答...\n\n");
        toolCallService.streamWithTools(getProvider(resolveToolProviderNameForMessages(aggregatedMessages)), aggregatedMessages, onChunk);
    }

    /** 多 AI 协同同步输出（非流式） */
    public String askMulti(List<Map<String, Object>> messages) {
        Map<String, String> advisorReplies = collectAdvisorReplies(messages);
        List<Map<String, Object>> aggregatedMessages = MessageHelper.buildAggregatedMessages(messages, advisorReplies);
        return toolCallService.askWithTools(getProvider(resolveToolProviderNameForMessages(aggregatedMessages)), aggregatedMessages);
    }

    /** 并行调用所有 advisor，收集回复（带心跳保活） */
    private Map<String, String> collectAdvisorRepliesWithHeartbeat(List<Map<String, Object>> messages,
                                                                   Consumer<String> onChunk) {
        Map<String, String> replies = new ConcurrentHashMap<>();

        CompletableFuture<Void> allDone = CompletableFuture.allOf(
            advisors.stream()
                        .filter(providerMap::containsKey)
                        .map(name -> CompletableFuture.runAsync(() -> {
                            try {
                                long start = System.currentTimeMillis();
                                log.info("【多AI协同】正在请求 {} ...", name);
                                String reply = askAdvisorWithTools(name, messages);
                                long cost = System.currentTimeMillis() - start;
                                if (isIgnorableAdvisorReply(reply)) {
                                    log.warn("【多AI协同】{} 回复不可用（疑似工具调用失败/拒绝），已忽略", name);
                                    return;
                                }
                                log.info("【多AI协同】{} 回复完成，耗时 {} ms", name, cost);
                                replies.put(name, reply);
                            } catch (Exception e) {
                                log.error("【多AI协同】{} 调用失败", name, e);
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
        return collectAdvisorReplies(MessageHelper.buildSingleTurnMessages(userQuestion));
    }

    //并行调用所有 advisor，收集回复（advisor 可调用工具）
    public Map<String, String> collectAdvisorReplies(List<Map<String, Object>> messages) {
        Map<String, String> replies = new ConcurrentHashMap<>();

        List<CompletableFuture<Void>> futures = advisors.stream()
                .filter(providerMap::containsKey)
                .map(name -> CompletableFuture.runAsync(() -> {
                    try {
                        long start = System.currentTimeMillis();
                        log.info("【多AI协同】正在请求 {} ...", name);
                        String reply = askAdvisorWithTools(name, messages);
                        long cost = System.currentTimeMillis() - start;
                        if (isIgnorableAdvisorReply(reply)) {
                            log.warn("【多AI协同】{} 回复不可用（疑似工具调用失败/拒绝），已忽略", name);
                            return;
                        }
                        log.info("【多AI协同】{} 回复完成，耗时 {} ms", name, cost);
                        replies.put(name, reply);
                    } catch (Exception e) {
                        log.error("【多AI协同】{} 调用失败", name, e);
                    }
                }))
                .toList();

        // 等待所有 advisor 完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return replies;
    }

    private String askAdvisorWithTools(String advisorName, List<Map<String, Object>> messages) {
        List<Map<String, Object>> enriched = MessageHelper.ensureToolSystemPrompt(messages);
        return toolCallService.askWithTools(getProvider(advisorName), enriched);
    }

    private boolean isIgnorableAdvisorReply(String reply) {
        if (reply == null || reply.isBlank()) {
            return true;
        }

        String lower = reply.toLowerCase(Locale.ROOT);
        return lower.contains("无法调用")
                || lower.contains("不能调用")
                || lower.contains("无法使用工具")
                || lower.contains("工具调用失败")
                || lower.contains("未能给出回复")
                || lower.contains("unknown tool")
                || lower.contains("unknown function")
                || lower.contains("tool call failed")
                || lower.contains("tool calling is not supported")
                || lower.contains("do not have access to tools")
                || lower.contains("i cannot call tools");
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
                SemanticRouter.RoutePath.FAST,
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
                if (isMultimodalPart(part)) {
                    return true;
                }

                if ("text".equalsIgnoreCase(String.valueOf(part.get("type")))) {
                    Object textObj = part.get("text");
                    if (textObj != null && isMultimodalTextHint(textObj.toString())) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (content instanceof Map<?, ?> raw) {
            Map<String, Object> part = (Map<String, Object>) raw;
            return isMultimodalPart(part);
        }
        if (content instanceof String str) {
            return isMultimodalTextHint(str);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean isMultimodalPart(Map<String, Object> part) {
        String type = String.valueOf(part.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
        if (type.contains("image") || type.contains("audio") || type.contains("video")) {
            return true;
        }

        if (part.containsKey("image_url") || part.containsKey("input_audio")
                || part.containsKey("audio_url") || part.containsKey("video_url")) {
            return true;
        }

        boolean hasFilePayload = part.containsKey("file_url") || part.containsKey("input_file")
                || type.contains("file") || type.contains("document");
        if (!hasFilePayload) {
            return false;
        }

        String fileNameOrUrl = extractFileNameOrUrl(part);
        String ext = extractExtension(fileNameOrUrl);
        if (!ext.isEmpty()) {
            if (READABLE_TEXT_EXTENSIONS.contains(ext)) {
                return false;
            }
            if (NON_TEXT_MULTIMODAL_EXTENSIONS.contains(ext) || MEDIA_EXTENSIONS.contains(ext)) {
                return true;
            }
        }

        String mime = extractMimeType(part);
        if (!mime.isEmpty()) {
            if (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")) {
                return true;
            }

            boolean looksLikeUnstructuredOffice = mime.contains("presentation")
                    || mime.contains("spreadsheet")
                    || mime.contains("powerpoint")
                    || mime.contains("ms-excel")
                    || mime.contains("officedocument.presentationml")
                    || mime.contains("officedocument.spreadsheetml");
            if (looksLikeUnstructuredOffice) {
                return true;
            }

            boolean looksLikeReadableText = mime.startsWith("text/")
                    || mime.equals("application/pdf")
                    || mime.contains("msword")
                    || mime.contains("officedocument.wordprocessingml");
            if (looksLikeReadableText) {
                return false;
            }
        }

        // 文件部件但无法确定类型时，默认走多模态以提升识别成功率。
        return true;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> preprocessMessagesForLocalRead(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        boolean changed = false;
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (Map<String, Object> msg : messages) {
            if (!"user".equals(msg.get("role"))) {
                out.add(msg);
                continue;
            }

            Object content = msg.get("content");
            if (!(content instanceof List<?> list)) {
                out.add(msg);
                continue;
            }

            List<Map<String, Object>> newParts = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> part = new HashMap<>((Map<String, Object>) raw);
                String filePath = extractInputFilePathForLocalRead(part);
                if (filePath == null) {
                    newParts.add(part);
                    continue;
                }

                String readResult = tryReadLocalFile(filePath);
                if (isReadFileSuccess(readResult)) {
                    changed = true;
                    newParts.add(Map.of(
                            "type", "text",
                            "text", "【本地 read_file 已读取: " + filePath + "】\n" + readResult
                    ));
                } else {
                    newParts.add(part);
                }
            }

            if (changed) {
                Map<String, Object> newMsg = new HashMap<>(msg);
                newMsg.put("content", newParts);
                out.add(newMsg);
            } else {
                out.add(msg);
            }
        }
        return changed ? out : messages;
    }

    private String extractInputFilePathForLocalRead(Map<String, Object> part) {
        String type = String.valueOf(part.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
        if (!("input_file".equals(type) || type.contains("file") || type.contains("document")
                || part.containsKey("input_file") || part.containsKey("file_url"))) {
            return null;
        }

        String filePath = extractFileNameOrUrl(part);
        if (filePath == null || filePath.isBlank()) {
            return null;
        }

        if (!LOCAL_READABLE_FILE_PATTERN.matcher(filePath).matches()) {
            return null;
        }

        return filePath;
    }

    private String tryReadLocalFile(String filePath) {
        try {
            String args = objectMapper.writeValueAsString(Map.of("filename", filePath));
            return toolExecutor.execute("read_file", args);
        } catch (Exception e) {
            return "读取文件失败: " + e.getMessage();
        }
    }

    private boolean isReadFileSuccess(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        String text = result.trim();
        return !text.startsWith("文件不存在")
                && !text.startsWith("读取文件失败")
                && !text.startsWith("安全错误")
                && !text.startsWith("该文件可能是二进制文件")
                && !text.startsWith("工具执行出错")
                && !text.startsWith("未知工具");
    }

    @SuppressWarnings("unchecked")
    private String extractFileNameOrUrl(Map<String, Object> part) {
        Object filename = part.get("filename");
        if (filename != null) {
            return filename.toString();
        }

        Object name = part.get("name");
        if (name != null) {
            return name.toString();
        }

        Object url = part.get("url");
        if (url != null) {
            return url.toString();
        }

        Object fileUrlObj = part.get("file_url");
        if (fileUrlObj instanceof Map<?, ?> raw) {
            Map<String, Object> fileUrl = (Map<String, Object>) raw;
            Object nestedUrl = fileUrl.get("url");
            if (nestedUrl != null) {
                return nestedUrl.toString();
            }
            Object nestedFilename = fileUrl.get("filename");
            if (nestedFilename != null) {
                return nestedFilename.toString();
            }
        }

        Object inputFileObj = part.get("input_file");
        if (inputFileObj instanceof Map<?, ?> raw) {
            Map<String, Object> inputFile = (Map<String, Object>) raw;
            Object nestedFilename = inputFile.get("filename");
            if (nestedFilename != null) {
                return nestedFilename.toString();
            }
        }

        return "";
    }

    @SuppressWarnings("unchecked")
    private String extractMimeType(Map<String, Object> part) {
        Object mimeType = part.get("mime_type");
        if (mimeType != null) {
            return mimeType.toString().toLowerCase(Locale.ROOT);
        }
        Object contentType = part.get("content_type");
        if (contentType != null) {
            return contentType.toString().toLowerCase(Locale.ROOT);
        }

        Object fileUrlObj = part.get("file_url");
        if (fileUrlObj instanceof Map<?, ?> raw) {
            Map<String, Object> fileUrl = (Map<String, Object>) raw;
            Object nestedMime = fileUrl.get("mime_type");
            if (nestedMime != null) {
                return nestedMime.toString().toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    private String extractExtension(String filenameOrUrl) {
        if (filenameOrUrl == null || filenameOrUrl.isBlank()) {
            return "";
        }

        String lower = filenameOrUrl.toLowerCase(Locale.ROOT);
        int q = lower.indexOf('?');
        if (q >= 0) {
            lower = lower.substring(0, q);
        }
        int hash = lower.indexOf('#');
        if (hash >= 0) {
            lower = lower.substring(0, hash);
        }

        int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        String name = slash >= 0 ? lower.substring(slash + 1) : lower;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1);
    }

    private boolean isMultimodalTextHint(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(png|jpe?g|gif|webp|bmp|svg|mp3|wav|ogg|m4a|flac|aac|mp4|mov|avi|mkv|m4v|ppt|pptx|xls|xlsx|ods|odp)\\b.*");
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
            onChunk.accept("\n\n连接失败，当前调用本地模型。\n\n");
            localFallbackService.get().streamSimpleAnswer(question, onChunk);
            return;
        }
        onChunk.accept("\n\n⚠️ 云端 AI 暂不可用，且未配置本地兜底模型。\n");
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

    private String resolvePrimaryProviderName() {
        String candidate = primaryProvider == null ? "" : primaryProvider.trim();
        if (!candidate.isEmpty() && providerMap.containsKey(candidate)) {
            return candidate;
        }

        if (providerMap.containsKey("deepseek")) {
            log.warn("配置的主模型 [{}] 不可用，自动回退到 deepseek", candidate);
            return "deepseek";
        }

        if (!providerMap.isEmpty()) {
            String fallback = providerMap.keySet().iterator().next();
            log.warn("配置的主模型 [{}] 不可用，自动回退到 {}", candidate, fallback);
            return fallback;
        }

        throw new IllegalStateException("未注册任何 AI Provider");
    }

    private String resolveProviderNameForMessages(List<Map<String, Object>> messages) {
        if (currentMode == Mode.SINGLE) {
            return resolvePrimaryProviderName();
        }
        if (hasMultimodalInput(messages)) {
            return resolveMultimodalProviderName();
        }
        return resolvePrimaryProviderName();
    }

    private String resolveToolProviderNameForMessages(List<Map<String, Object>> messages) {
        if (currentMode == Mode.SINGLE) {
            String selectedSingle = resolvePrimaryProviderName();
            return selectedSingle;
        }

        String configured = toolProvider == null ? "" : toolProvider.trim();
        if (!configured.isEmpty() && providerMap.containsKey(configured)) {
            AiProvider provider = getProvider(configured);
            if (hasMultimodalInput(messages) && !provider.supportsMultimodal()) {
                String fallback = resolveProviderNameForMessages(messages);
                log.warn("工具模型 [{}] 不支持多模态，回退到 {}", configured, fallback);
                return fallback;
            }
            return configured;
        }

        String fallback = resolveProviderNameForMessages(messages);
        log.warn("配置的工具模型 [{}] 不可用，自动回退到 {}", configured, fallback);
        return fallback;
    }

    private String resolveMultimodalProviderName() {
        for (String candidate : multimodalProviders) {
            if (!providerMap.containsKey(candidate)) {
                continue;
            }
            AiProvider provider = getProvider(candidate);
            if (provider.supportsMultimodal()) {
                return candidate;
            }
        }

        for (AiProvider provider : providerMap.values()) {
            if (provider.supportsMultimodal()) {
                log.warn("配置的多模态 Provider 不可用，自动回退到 {}", provider.getName());
                return provider.getName();
            }
        }

        String primary = resolvePrimaryProviderName();
        log.warn("未找到可用的多模态 Provider，回退主模型: {}", primary);
        return primary;
    }
}
