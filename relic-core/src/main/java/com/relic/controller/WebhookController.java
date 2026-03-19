package com.relic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.dto.OpenClawRequest;
import com.relic.service.AiRouterService;
import com.relic.service.ChatHistoryService;
import com.relic.util.MessageHelper;
import com.relic.util.OpenAiResponseBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
public class WebhookController {

    private static final int MAX_HISTORY = 8; //最大历史条数

    @Autowired
    private AiRouterService aiRouter;

    @Autowired
    private ChatHistoryService chatHistoryService;

    private final ObjectMapper mapper = new ObjectMapper();

    //模式切换接口Single/Multi
    @GetMapping("/mode")
    public Map<String, Object> getMode() {
        return Map.of(
                "mode", aiRouter.getMode().name().toLowerCase(),
                "singleProvider", aiRouter.getSingleProviderName(),
                "availableProviders", aiRouter.getProviderNames()
        );
    }

    @PostMapping("/mode")
    public Map<String, Object> setMode(@RequestBody Map<String, String> request) {
        String modeStr = request.getOrDefault("mode", "single");
        String singleProvider = request.get("singleProvider");
        AiRouterService.Mode mode = "multi".equalsIgnoreCase(modeStr)
                ? AiRouterService.Mode.MULTI
                : AiRouterService.Mode.SINGLE;
        aiRouter.setMode(mode);
        if (singleProvider != null && !singleProvider.isBlank()) {
            aiRouter.setSingleProviderName(singleProvider);
        }
        log.info("模式已切换为: {}", mode);
        return Map.of(
                "mode", mode.name().toLowerCase(),
                "singleProvider", aiRouter.getSingleProviderName()
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
    @SuppressWarnings("unchecked")
    public SseEmitter handleOpenAIRequest(@RequestBody Map<String, Object> request) {
        String conversationId = chatHistoryService.normalizeConversationId((String) request.get("conversationId"));
        List<Map<String, Object>> rawMessages = (List<Map<String, Object>>) request.get("messages");
        List<Map<String, Object>> messages = MessageHelper.cleanRawMessages(rawMessages);

        if (messages.isEmpty()) {
            messages.add(MessageHelper.buildUserMessage(""));
        }

        messages = MessageHelper.applySlidingWindow(messages, MAX_HISTORY);
        log.info("【最终发送给 AI 的记忆条数】: {}", messages.size());
        log.info("【当前最新提问】: {}", messages.get(messages.size() - 1).get("content"));

        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("user".equals(msg.get("role"))) {
                chatHistoryService.appendMessage(conversationId, "user", msg.get("content"));
                break;
            }
        }

        final List<Map<String, Object>> finalMessages = messages;
        SseEmitter emitter = new SseEmitter(180_000L);
        String chatId = "chatcmpl-" + System.currentTimeMillis();
        String modelName = aiRouter.getProviderNameForMessages(finalMessages);
        long created = System.currentTimeMillis() / 1000;
        AtomicBoolean emitterActive = new AtomicBoolean(true);
        StringBuilder assistantOutput = new StringBuilder();

        emitter.onCompletion(() -> {
            emitterActive.set(false);
            log.info("【SSE 连接已关闭】");
        });

        Thread streamThread = Thread.startVirtualThread(() -> {
            try {
                log.info("【流式连接 AI 中...】模式: {}", aiRouter.getMode());
                aiRouter.streamAuto(finalMessages, content -> {
                    if (!emitterActive.get()) {
                        throw new UncheckedIOException(new IOException("SSE 连接已关闭，终止流式输出"));
                    }
                    assistantOutput.append(content);
                    try {
                        Map<String, Object> chunk = OpenAiResponseBuilder.buildChunk(
                            chatId, created, modelName, Map.of("content", content), null);
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
                            Map.of("content", "\n\n⚠️ 后端处理异常: " + e.getMessage()), null);
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
            }
        });

        // SSE 超时时中断虚拟线程，防止上游流式读取的阻塞
        emitter.onTimeout(() -> {
            emitterActive.set(false);
            streamThread.interrupt();
            log.warn("【SSE 连接超时，已中断流式线程】");
        });

        return emitter;
    }

    @GetMapping("/chat/conversations")
    public Map<String, Object> listConversations() {
        return Map.of("items", chatHistoryService.listConversations());
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