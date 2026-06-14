package com.relic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.dto.ChatCompletionRequest;
import com.relic.dto.OpenClawRequest;
import com.relic.rag.model.Citation;
import com.relic.service.AiRouterService;
import com.relic.service.ChatHistoryService;
import com.relic.service.SkillCommandService;
import com.relic.tool.ToolExecutor;
import com.relic.util.MessageHelper;
import com.relic.util.OpenAiResponseBuilder;
import com.relic.util.RequestDeadline;
import com.relic.util.TimeoutMessages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
public class WebhookController {

    private static final int MAX_HISTORY = 8; //最大历史条数

    private final ConcurrentHashMap<String, Semaphore> conversationLocks = new ConcurrentHashMap<>();

    @Autowired
    private AiRouterService aiRouter;

    @Autowired
    private ChatHistoryService chatHistoryService;

    @Autowired
    private SkillCommandService skillCommandService;

    @Value("${relic.request.timeout-ms:180000}")
    private long requestTimeoutMs;

    @Value("${relic.request.max-concurrent-streams:8}")
    private int maxConcurrentStreams;

    @Value("${relic.multimodal.audio.max-base64-chars:10485760}")
    private int maxAudioBase64Chars;

    @Value("${relic.multimodal.audio.allowed-formats:mp3,mpeg,wav,webm,ogg,m4a,mp4}")
    private String allowedAudioFormatsConfig;

    private Semaphore streamSemaphore;

    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void initStreamSemaphore() {
        int permits = Math.max(1, maxConcurrentStreams);
        streamSemaphore = new Semaphore(permits);
        log.info("[stream-limit] max concurrent streams: {}", permits);
    }

    //模式切换接口Single/Multi
    @GetMapping("/mode")
    public Map<String, Object> getMode() {
        return Map.of(
                "mode", aiRouter.getMode().name().toLowerCase(),
                "singleProvider", aiRouter.getSingleProviderName(),
                "multiLeader", aiRouter.getMultiLeaderProviderName(),
                "multiAdvisors", aiRouter.getAdvisors(),
                "availableProviders", aiRouter.getProviderNames()
        );
    }

    @PostMapping("/mode")
    public Map<String, Object> setMode(@RequestBody Map<String, Object> request) {
        String modeStr = Objects.toString(request.getOrDefault("mode", "single"), "single");
        String singleProvider = request.get("singleProvider") == null
                ? null
                : request.get("singleProvider").toString();
        String multiLeader = request.get("multiLeader") == null
                ? null
                : request.get("multiLeader").toString();

        AiRouterService.Mode mode = "multi".equalsIgnoreCase(modeStr)
                ? AiRouterService.Mode.MULTI
                : AiRouterService.Mode.SINGLE;
        aiRouter.setMode(mode);

        if (singleProvider != null && !singleProvider.isBlank()) {
            aiRouter.setSingleProviderName(singleProvider);
        }

        if (multiLeader != null && !multiLeader.isBlank()) {
            aiRouter.setMultiLeaderProviderName(multiLeader);
        }

        Object advisorsObj = request.get("multiAdvisors");
        if (advisorsObj instanceof List<?> rawList) {
            List<String> advisors = new ArrayList<>();
            for (Object item : rawList) {
                if (item != null) {
                    advisors.add(item.toString());
                }
            }
            if (!advisors.isEmpty()) {
                aiRouter.setAdvisors(advisors);
            }
        }

        log.info("模式已切换为: {}", mode);
        return Map.of(
                "mode", mode.name().toLowerCase(),
                "singleProvider", aiRouter.getSingleProviderName(),
                "multiLeader", aiRouter.getMultiLeaderProviderName(),
                "multiAdvisors", aiRouter.getAdvisors(),
                "availableProviders", aiRouter.getProviderNames()
        );
    }


    //统一 AI 连通性测试
    @PostMapping("/test/ai")
    public Map<String, Object> testAi(@RequestBody Map<String, String> request) {
        String provider = request.getOrDefault("provider", "qwen");
        String prompt = request.getOrDefault("prompt", "你好，请用一句话介绍你自己");
        log.info("【{} 连通性测试】prompt: {}", provider, prompt);

        long startTime = System.currentTimeMillis();
        String reply = aiRouter.ask(provider, prompt);
        long costTime = System.currentTimeMillis() - startTime;

        log.info("【{} 测试完成】耗时: {} ms, 返回: {}", provider, costTime, reply);

        return Map.of(
                "provider", provider,
                "status", isAiTestFailure(reply) ? "fail" : "ok",
                "costMs", costTime,
                "reply", reply
        );
    }

    private boolean isAiTestFailure(String reply) {
        if (reply == null || reply.isBlank()) {
            return true;
        }
        String lower = reply.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("failed")
                || lower.contains("failure")
                || lower.contains("api error")
                || lower.contains("api key")
                || lower.contains("not configured")
                || lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("失败")
                || lower.contains("错误");
    }

    // 多 AI 协同测试（查看各 advisor 原始回复）
    @PostMapping("/test/multi")
    public Map<String, Object> testMulti(@RequestBody Map<String, String> request) {
        String prompt = request.getOrDefault("prompt", "你好，请用一句话介绍你自己");
        log.info("【多AI协同测试】prompt: {}", prompt);

        long startTime = System.currentTimeMillis();
        Map<String, String> advisorReplies = aiRouter.collectAdvisorReplies(prompt);
        long costTime = System.currentTimeMillis() - startTime;

        log.info("【多AI协同测试完成】耗时: {} ms", costTime);
        advisorReplies.forEach((name, reply) ->
                log.info("  {} -> {}", name, reply));

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("costMs", costTime);
        result.put("advisors", advisorReplies);
        return result;
    }


    //OpenClaw Webhook
    @PostMapping("/openclaw")
    public Map<String, Object> receiveMessage(@RequestBody OpenClawRequest request) {
        if (!"chat.send".equals(request.getMethod())) {
            return Map.of("status", "ignored");
        }

        String userMessage = request.getParams().getMessage();
        log.info("收到来自 OpenClaw 的前端指令: {}", userMessage);

        List<Map<String, Object>> messages = MessageHelper.buildSingleTurnMessages(userMessage);
        messages = skillCommandService.rewriteForEnabledSkillCommand(messages);
        String aiReply;
        try {
            long startTime = System.currentTimeMillis();
            aiReply = aiRouter.askAuto(messages);
            long costTime = System.currentTimeMillis() - startTime;
            log.info("AI 调用成功，耗时: {} ms", costTime);
            log.info("AI 返回内容: {}", aiReply);

            if (aiReply == null || aiReply.trim().isEmpty()) {
                aiReply = "AI 返回了空内容，请检查 API 密钥或网络余额。";
            }
        } catch (Exception e) {
            log.error("调用 AI API 时发生异常", e);
            aiReply = "后端请求出错：" + e.getMessage();
        }

        return Map.of(
                "type", "res",
                "id", request.getId(),
                "result", Map.of("text", aiReply)
        );
    }

    // OpenAI 兼容格式，流式 SSE 
    @PostMapping(value = "/v1/chat/completions")
    public SseEmitter handleOpenAIRequest(@RequestBody ChatCompletionRequest request) {
        String conversationId = chatHistoryService.normalizeConversationId(request == null ? null : request.getConversationId());
        List<Map<String, Object>> rawMessages = request == null ? null : request.getMessages();
        List<Map<String, Object>> messages = MessageHelper.cleanRawMessages(rawMessages);
        ChatCompletionRequest.RagConfig ragConfig = request == null ? null : request.getRagConfig();
        Boolean toolsEnabled = request == null ? null : request.getToolsEnabled();
        String workingDirectory = normalizeWorkingDirectory(request == null ? null : request.getWorkingDirectory());

        if (messages.isEmpty()) {
            messages.add(MessageHelper.buildUserMessage(""));
        }

        messages = MessageHelper.applySlidingWindow(messages, MAX_HISTORY);
        messages = MessageHelper.ensureDisplayMarkdownPrompt(messages);
        log.info("【最终发送给 AI 的记忆条数】: {}", messages.size());
        log.info("【当前最新提问】: {}", messages.get(messages.size() - 1).get("content"));

        String audioValidationError = validateAudioInputs(messages);
        if (audioValidationError != null) {
            log.warn("[audio-input] rejected invalid audio input: {}", audioValidationError);
            return buildImmediateSseMessage(messages, audioValidationError);
        }

        Semaphore conversationLock = conversationLocks.computeIfAbsent(conversationId, ignored -> new Semaphore(1));
        if (!conversationLock.tryAcquire()) {
            log.warn("[conversation-lock] conversation {} is already streaming, rejecting concurrent request", conversationId);
            return buildImmediateSseMessage(messages, "当前会话正在生成，请稍后再试。");
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("user".equals(msg.get("role"))) {
                chatHistoryService.appendMessage(conversationId, "user", msg.get("content"));
                break;
            }
        }

        messages = skillCommandService.rewriteForEnabledSkillCommand(messages);

        AtomicBoolean streamPermitReleased = new AtomicBoolean(false);
        if (!streamSemaphore.tryAcquire()) {
            log.warn("[stream-limit] max concurrent streams reached, rejecting conversation {}", conversationId);
            releaseConversationLock(conversationId, conversationLock, new AtomicBoolean(false));
            return buildImmediateSseMessage(messages, "当前请求较多，请稍后再试。");
        }

        final List<Map<String, Object>> finalMessages = messages;
        long effectiveTimeoutMs = Math.max(10_000L, requestTimeoutMs);
        SseEmitter emitter = new SseEmitter(effectiveTimeoutMs);
        String chatId = "chatcmpl-" + System.currentTimeMillis();
        String modelName = aiRouter.getProviderNameForMessages(finalMessages);
        long created = System.currentTimeMillis() / 1000;
        AtomicBoolean emitterActive = new AtomicBoolean(true);
        AtomicBoolean citationsSent = new AtomicBoolean(false);
        AtomicBoolean fallbackSent = new AtomicBoolean(false);
        AtomicBoolean lockReleased = new AtomicBoolean(false);
        StringBuilder assistantOutput = new StringBuilder();

        emitter.onCompletion(() -> {
            emitterActive.set(false);
            log.info("【SSE 连接已关闭】");
        });

        Thread streamThread = Thread.startVirtualThread(() -> {
            try {
                RequestDeadline.start(effectiveTimeoutMs);
                ToolExecutor.setWorkingDirectoryContext(workingDirectory);
                log.info("【流式连接 AI 中...】模式: {}, 工作目录: {}",
                        aiRouter.getMode(),
                        workingDirectory == null || workingDirectory.isBlank() ? "(默认workspace)" : workingDirectory);
                aiRouter.streamAuto(finalMessages, ragConfig, toolsEnabled, content -> {
                    if (!emitterActive.get()) {
                        throw new UncheckedIOException(new IOException("SSE 连接已关闭，终止流式输出"));
                    }
                    assistantOutput.append(content);
                    try {
                        List<Citation> citations = null;
                        if (citationsSent.compareAndSet(false, true)) {
                            citations = aiRouter.getCurrentCitations();
                        }
                        Map<String, Object> chunk = OpenAiResponseBuilder.buildChunk(
                            chatId, created, modelName, Map.of("content", content), null, citations);
                        Map<String, Object> fallback = aiRouter.getCurrentFallbackInfo();
                        if (fallback != null && fallbackSent.compareAndSet(false, true)) {
                            chunk.put("fallback", fallback);
                        }
                        emitter.send(SseEmitter.event()
                                .data(mapper.writeValueAsString(chunk), MediaType.APPLICATION_JSON));
                    } catch (IOException e) {
                        emitterActive.set(false);
                        throw new UncheckedIOException(e);
                    }
                });

                Map<String, Object> stopChunk = OpenAiResponseBuilder.buildChunk(
            chatId, created, modelName, Map.of(), "stop");
                emitter.send(SseEmitter.event()
                        .data(mapper.writeValueAsString(stopChunk), MediaType.APPLICATION_JSON));
                emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
                chatHistoryService.appendMessage(conversationId, "assistant", assistantOutput.toString());
                emitter.complete();
                log.info("【流式响应完成】");
            } catch (Exception e) {
                if (!emitterActive.get()) {
                    log.warn("【流式响应】SSE 已关闭，丢弃后续处理: {}", e.getMessage());
                    return;
                }
                log.error("【流式响应异常】", e);
                try {
                    Map<String, Object> errChunk = OpenAiResponseBuilder.buildChunk(
                        chatId, created, modelName,
                            Map.of("content", "\n\n" + TimeoutMessages.requestError(e)), null);
                    emitter.send(SseEmitter.event()
                            .data(mapper.writeValueAsString(errChunk), MediaType.APPLICATION_JSON));
                    Map<String, Object> stopChunk2 = OpenAiResponseBuilder.buildChunk(
                        chatId, created, modelName, Map.of(), "stop");
                    emitter.send(SseEmitter.event()
                            .data(mapper.writeValueAsString(stopChunk2), MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
                    emitter.complete();
                } catch (Exception ex) {
                    log.warn("【发送错误消息也失败】SSE 可能已关闭: {}", ex.getMessage());
                }
            } finally {
                RequestDeadline.clear();
                ToolExecutor.clearWorkingDirectoryContext();
                releaseStreamPermit(streamPermitReleased);
                releaseConversationLock(conversationId, conversationLock, lockReleased);
            }
        });

        emitter.onTimeout(() -> {
            emitterActive.set(false);
            streamThread.interrupt();
            log.warn("[SSE] request timed out after {} ms, stream thread interrupted", effectiveTimeoutMs);
            try {
                Map<String, Object> errChunk = OpenAiResponseBuilder.buildChunk(
                        chatId, created, modelName,
                        Map.of("content", "\n\n" + TimeoutMessages.SSE_TIMEOUT), null);
                emitter.send(SseEmitter.event()
                        .data(mapper.writeValueAsString(errChunk), MediaType.APPLICATION_JSON));
                Map<String, Object> stopChunk = OpenAiResponseBuilder.buildChunk(
                        chatId, created, modelName, Map.of(), "stop");
                emitter.send(SseEmitter.event()
                        .data(mapper.writeValueAsString(stopChunk), MediaType.APPLICATION_JSON));
                emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
            } catch (Exception e) {
                log.warn("[SSE] failed to send timeout message: {}", e.getMessage());
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    private String normalizeWorkingDirectory(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return null;
        }

        String trimmed = workingDirectory.trim();
        try {
            Path path = Path.of(trimmed).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                return path.toString();
            }
            log.warn("[working-directory] invalid directory '{}', falling back to default workspace", trimmed);
            return null;
        } catch (InvalidPathException e) {
            log.warn("[working-directory] invalid path '{}', falling back to default workspace: {}", trimmed, e.getMessage());
            return null;
        }
    }

    private SseEmitter buildImmediateSseMessage(List<Map<String, Object>> messages, String message) {
        long effectiveTimeoutMs = Math.max(10_000L, requestTimeoutMs);
        SseEmitter emitter = new SseEmitter(effectiveTimeoutMs);
        String chatId = "chatcmpl-" + System.currentTimeMillis();
        String modelName = aiRouter.getProviderNameForMessages(messages);
        long created = System.currentTimeMillis() / 1000;

        Thread.startVirtualThread(() -> {
            try {
                Map<String, Object> chunk = OpenAiResponseBuilder.buildChunk(
                        chatId, created, modelName, Map.of("content", message), null);
                emitter.send(SseEmitter.event()
                        .data(mapper.writeValueAsString(chunk), MediaType.APPLICATION_JSON));
                Map<String, Object> stopChunk = OpenAiResponseBuilder.buildChunk(
                        chatId, created, modelName, Map.of(), "stop");
                emitter.send(SseEmitter.event()
                        .data(mapper.writeValueAsString(stopChunk), MediaType.APPLICATION_JSON));
                emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
                emitter.complete();
            } catch (Exception e) {
                log.warn("[SSE] failed to send immediate message: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private String validateAudioInputs(List<Map<String, Object>> messages) {
        Set<String> allowedFormats = allowedAudioFormats();
        int safeMaxBase64Chars = Math.max(1024, maxAudioBase64Chars);
        for (Map<String, Object> message : messages) {
            Object content = message.get("content");
            if (!(content instanceof List<?> parts)) {
                continue;
            }
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> rawPart)) {
                    continue;
                }
                if (!"input_audio".equals(String.valueOf(rawPart.get("type")))
                        && !rawPart.containsKey("input_audio")) {
                    continue;
                }
                Object audioObj = rawPart.get("input_audio");
                if (!(audioObj instanceof Map<?, ?> audio)) {
                    return "语音输入格式不正确，请重新上传音频。";
                }
                String data = Objects.toString(audio.get("data"), "").trim();
                String format = Objects.toString(audio.get("format"), "").trim().toLowerCase();
                if (data.isEmpty()) {
                    return "语音输入为空，请重新上传音频。";
                }
                if (data.length() > safeMaxBase64Chars) {
                    return "语音输入文件过大，请压缩或缩短录音后重试。";
                }
                if (format.isEmpty() || !allowedFormats.contains(format)) {
                    return "暂不支持该语音格式，请使用 mp3、wav、webm、ogg、m4a 或 mp4。";
                }
            }
        }
        return null;
    }

    private Set<String> allowedAudioFormats() {
        Set<String> formats = new LinkedHashSet<>();
        String config = allowedAudioFormatsConfig == null ? "" : allowedAudioFormatsConfig;
        for (String item : config.split(",")) {
            String normalized = item.trim().toLowerCase();
            if (!normalized.isEmpty()) {
                formats.add(normalized);
            }
        }
        if (formats.isEmpty()) {
            formats.addAll(List.of("mp3", "mpeg", "wav", "webm", "ogg", "m4a", "mp4"));
        }
        return formats;
    }

    private void releaseConversationLock(String conversationId, Semaphore lock, AtomicBoolean released) {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        try {
            lock.release();
        } finally {
            conversationLocks.remove(conversationId, lock);
        }
    }

    private void releaseStreamPermit(AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            streamSemaphore.release();
        }
    }

    @GetMapping("/chat/conversations")
    public Map<String, Object> listConversations(@RequestParam(value = "archived", defaultValue = "false") boolean archived) {
        return Map.of("items", chatHistoryService.listConversations(archived));
    }

    @PostMapping("/chat/conversations/rename")
    public Map<String, Object> renameConversation(@RequestBody Map<String, String> request) {
        String conversationId = chatHistoryService.normalizeConversationId(request.get("conversationId"));
        String newName = request.getOrDefault("newName", "");
        boolean ok = chatHistoryService.renameConversation(conversationId, newName);
        return Map.of("ok", ok);
    }

    @PostMapping("/chat/conversations/archive")
    public Map<String, Object> archiveConversation(@RequestBody Map<String, Object> request) {
        String rawConversationId = Objects.toString(request.get("conversationId"), "").trim();
        if (rawConversationId.isEmpty()) {
            return Map.of("ok", false, "error", "conversationId is required");
        }
        String conversationId = chatHistoryService.normalizeConversationId(rawConversationId);
        Object archivedObj = request.get("archived");
        boolean archived = archivedObj == null || Boolean.parseBoolean(String.valueOf(archivedObj));
        boolean ok = chatHistoryService.archiveConversation(conversationId, archived);
        return Map.of("ok", ok, "archived", archived);
    }

    @DeleteMapping("/chat/conversations")
    public Map<String, Object> deleteConversation(@RequestParam("conversationId") String conversationId) {
        String normalized = chatHistoryService.normalizeConversationId(conversationId);
        boolean ok = chatHistoryService.deleteConversation(normalized);
        return Map.of("ok", ok);
    }

    @GetMapping("/chat/history")
    public Map<String, Object> getHistory(@RequestParam("conversationId") String conversationId) {
        String normalized = chatHistoryService.normalizeConversationId(conversationId);
        return Map.of(
                "conversationId", normalized,
                "messages", chatHistoryService.getHistory(normalized)
        );
    }
}
