package com.relic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.dto.OpenClawRequest;
import com.relic.service.AiRouterService;
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

@Slf4j
@RestController
public class WebhookController {

    private static final int MAX_HISTORY = 15;

    @Autowired
    private AiRouterService aiRouter;

    private final ObjectMapper mapper = new ObjectMapper();

    // ==================== 模式切换接口 ====================

    @GetMapping("/mode")
    public Map<String, Object> getMode() {
        return Map.of(
                "mode", aiRouter.getMode().name().toLowerCase(),
                "availableProviders", aiRouter.getProviderNames()
        );
    }

    @PostMapping("/mode")
    public Map<String, Object> setMode(@RequestBody Map<String, String> request) {
        String modeStr = request.getOrDefault("mode", "single");
        AiRouterService.Mode mode = "multi".equalsIgnoreCase(modeStr)
                ? AiRouterService.Mode.MULTI
                : AiRouterService.Mode.SINGLE;
        aiRouter.setMode(mode);
        log.info("模式已切换为: {}", mode);
        return Map.of("mode", mode.name().toLowerCase());
    }

    // ==================== 统一 AI 连通性测试 ====================

    @PostMapping("/test/ai")
    public Map<String, Object> testAi(@RequestBody Map<String, String> request) {
        String provider = request.getOrDefault("provider", "deepseek");
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

    // ==================== OpenClaw Webhook ====================

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

    // ==================== OpenAI 兼容格式：流式 SSE ====================

    @PostMapping(value = "/v1/chat/completions")
    @SuppressWarnings("unchecked")
    public SseEmitter handleOpenAIRequest(@RequestBody Map<String, Object> request) {
        List<Map<String, Object>> rawMessages = (List<Map<String, Object>>) request.get("messages");
        List<Map<String, Object>> messages = MessageHelper.cleanRawMessages(rawMessages);

        if (messages.isEmpty()) {
            messages.add(MessageHelper.buildUserMessage(""));
        }

        messages = MessageHelper.applySlidingWindow(messages, MAX_HISTORY);
        log.info("【最终发送给 AI 的记忆条数】: {}", messages.size());
        log.info("【当前最新提问】: {}", messages.get(messages.size() - 1).get("content"));

        final List<Map<String, Object>> finalMessages = messages;
        SseEmitter emitter = new SseEmitter(120_000L);
        String chatId = "chatcmpl-" + System.currentTimeMillis();
        long created = System.currentTimeMillis() / 1000;

        emitter.onCompletion(() -> log.info("【SSE 连接已关闭】"));
        emitter.onTimeout(() -> log.warn("【SSE 连接超时】"));

        Thread.startVirtualThread(() -> {
            try {
                log.info("【流式连接 AI 中...】模式: {}", aiRouter.getMode());
                aiRouter.streamAuto(finalMessages, content -> {
                    try {
                        Map<String, Object> chunk = OpenAiResponseBuilder.buildChunk(
                                chatId, created, "deepseek-local", Map.of("content", content), null);
                        emitter.send(SseEmitter.event()
                                .data(mapper.writeValueAsString(chunk), MediaType.APPLICATION_JSON));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

                Map<String, Object> stopChunk = OpenAiResponseBuilder.buildChunk(
                        chatId, created, "deepseek-local", Map.of(), "stop");
                emitter.send(SseEmitter.event()
                        .data(mapper.writeValueAsString(stopChunk), MediaType.APPLICATION_JSON));
                emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
                emitter.complete();
                log.info("【流式响应完成】");
            } catch (Exception e) {
                log.error("【流式响应异常】", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}