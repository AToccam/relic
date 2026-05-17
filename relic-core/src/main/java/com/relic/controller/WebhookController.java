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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@RestController
public class WebhookController {

    private static final int MAX_HISTORY = 8; //最大历史条数

    private final ConcurrentHashMap<String, ReentrantLock> conversationLocks = new ConcurrentHashMap<>();

    @Autowired
    private AiRouterService aiRouter;

    @Autowired
    private ChatHistoryService chatHistoryService;

    @Autowired
    private SkillCommandService skillCommandService;

    @Value("${relic.request.timeout-ms:180000}")
    private long requestTimeoutMs;

    private final ObjectMapper mapper = new ObjectMapper();

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
                "status", reply.contains("失败") ? "fail" : "ok",
                "costMs", costTime,
                "reply", reply
        );
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
        String workingDirectory = request == null ? null : request.getWorkingDirectory();

        if (messages.isEmpty()) {
            messages.add(MessageHelper.buildUserMessage(""));
        }

        messages = MessageHelper.applySlidingWindow(messages, MAX_HISTORY);
        messages = MessageHelper.ensureDisplayMarkdownPrompt(messages);
        log.info("【最终发送给 AI 的记忆条数】: {}", messages.size());
        log.info("【当前最新提问】: {}", messages.get(messages.size() - 1).get("content"));

        ReentrantLock conversationLock = conversationLocks.computeIfAbsent(conversationId, ignored -> new ReentrantLock());
        if (!conversationLock.tryLock()) {
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

        final List<Map<String, Object>> finalMessages = messages;
        long effectiveTimeoutMs = Math.max(10_000L, requestTimeoutMs);
        SseEmitter emitter = new SseEmitter(effectiveTimeoutMs);
        String chatId = "chatcmpl-" + System.currentTimeMillis();
        String modelName = aiRouter.getProviderNameForMessages(finalMessages);
        long created = System.currentTimeMillis() / 1000;
        AtomicBoolean emitterActive = new AtomicBoolean(true);
        AtomicBoolean citationsSent = new AtomicBoolean(false);
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

    private void releaseConversationLock(String conversationId, ReentrantLock lock, AtomicBoolean released) {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        try {
            lock.unlock();
        } finally {
            conversationLocks.remove(conversationId, lock);
        }
    }

    @GetMapping("/chat/conversations")
    public Map<String, Object> listConversations() {
        return Map.of("items", chatHistoryService.listConversations());
    }

    @PostMapping("/chat/conversations/rename")
    public Map<String, Object> renameConversation(@RequestBody Map<String, String> request) {
        String conversationId = chatHistoryService.normalizeConversationId(request.get("conversationId"));
        String newName = request.getOrDefault("newName", "");
        boolean ok = chatHistoryService.renameConversation(conversationId, newName);
        return Map.of("ok", ok);
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
