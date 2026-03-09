package com.relic.controller;

import com.relic.dto.OpenClawRequest;
import com.relic.service.DeepSeekService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
public class WebhookController {

    @Autowired
    private DeepSeekService deepSeekService;

    // 注意：这里的返回值改成了 Map<String, Object>，Spring 会自动把它转成 JSON 返回给网关
    @PostMapping("/openclaw")
    public Map<String, Object> receiveMessage(@RequestBody OpenClawRequest request) {
        if (!"chat.send".equals(request.getMethod())) {
            return Map.of("status", "ignored");
        }

        String userMessage = request.getParams().getMessage();
        log.info("【收到来自 OpenClaw 的前端指令】: {}", userMessage);

        log.info("【正在呼叫 DeepSeek，请稍候...】");
        String aiReply = "";
        try {
            // 记录开始时间
            long startTime = System.currentTimeMillis();

            aiReply = deepSeekService.askDeepSeek(userMessage);

            // 记录结束时间与结果
            long costTime = System.currentTimeMillis() - startTime;
            log.info("【DeepSeek 调用成功】耗时: {} ms", costTime);
            log.info("【DeepSeek 返回内容】: {}", aiReply);

            // 防止返回 null 导致 OpenClaw 报错
            if (aiReply == null || aiReply.trim().isEmpty()) {
                aiReply = "DeepSeek 返回了空内容，请检查 API 密钥或网络余额。";
            }

        } catch (Exception e) {
            // 打印堆栈报错
            log.error("连接DeepSeek API 时发生异常】", e);
            aiReply = "后端请求出错：" + e.getMessage();
        }


        // 组装 OpenClaw 要求的标准回应格式
        return Map.of(
                "type", "res",               // 这是一个响应 (Response)
                "id", request.getId(),       // 必须把网关发来的流水号原样还回去，网关才知道是回复哪句话的
                "result", Map.of(
                        "text", aiReply          // 把 DeepSeek 的真实回答塞进 text 字段
                )
        );
    }

    //openai格式回复（支持流式和非流式）
    @PostMapping("/v1/chat/completions")
    public Object handleOpenAIRequest(@RequestBody Map<String, Object> request) {
        log.info("【全量请求体检测】: {}", request);

        String userMessage = extractUserMessage(request);
        log.info("提取到的纯文本用户指令: {}", userMessage);

        boolean stream = Boolean.TRUE.equals(request.get("stream"));

        if (stream) {
            log.info("【流式模式】连接 DeepSeek...");
            return handleStreamingRequest(userMessage);
        } else {
            log.info("【非流式模式】连接 DeepSeek...");
            return handleNonStreamingRequest(userMessage);
        }
    }

    // 提取用户消息（兼容字符串和数组格式）
    @SuppressWarnings("unchecked")
    private String extractUserMessage(Map<String, Object> request) {
        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");
        Map<String, Object> lastMessage = messages.get(messages.size() - 1);
        Object contentObj = lastMessage.get("content");

        if (contentObj instanceof String str) {
            return str;
        } else if (contentObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                    return (String) map.get("text");
                }
            }
        }
        return "";
    }

    // 流式响应：通过 SSE 逐块返回 DeepSeek 的回复
    private SseEmitter handleStreamingRequest(String userMessage) {
        SseEmitter emitter = new SseEmitter(120_000L);
        String chatId = "chatcmpl-" + System.currentTimeMillis();
        long created = System.currentTimeMillis() / 1000;

        emitter.onCompletion(() -> log.info("【SSE 连接已关闭】"));
        emitter.onTimeout(() -> log.warn("【SSE 连接超时】"));

        Thread.startVirtualThread(() -> {
            try {
                // 发送角色标识 chunk
                emitter.send(SseEmitter.event().data(
                        buildChunk(chatId, created, Map.of("role", "assistant"), null)));

                // 流式转发 DeepSeek 内容
                deepSeekService.streamDeepSeek(userMessage, content -> {
                    try {
                        emitter.send(SseEmitter.event().data(
                                buildChunk(chatId, created, Map.of("content", content), null)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

                // 发送结束标记
                emitter.send(SseEmitter.event().data(
                        buildChunk(chatId, created, Map.of(), "stop")));
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

    // 非流式响应：一次性返回完整回复
    private Map<String, Object> handleNonStreamingRequest(String userMessage) {
        String aiReply = "";
        try {
            long startTime = System.currentTimeMillis();
            aiReply = deepSeekService.askDeepSeek(userMessage);
            log.info("【DeepSeek 调用成功】耗时: {} ms", System.currentTimeMillis() - startTime);
            log.info("【DeepSeek 返回内容】: {}", aiReply);

            if (aiReply == null || aiReply.trim().isEmpty()) {
                aiReply = "DeepSeek 返回了空内容，请检查 API 密钥或网络余额。";
            }
        } catch (Exception e) {
            log.error("【连接 DeepSeek API 时发生异常】", e);
            aiReply = "系统内部网络请求出错：" + e.getMessage();
        }

        return Map.of(
                "id", "chatcmpl-" + System.currentTimeMillis(),
                "object", "chat.completion",
                "created", System.currentTimeMillis() / 1000,
                "model", "deepseek-local",
                "choices", List.of(
                        Map.of(
                                "index", 0,
                                "message", Map.of("role", "assistant", "content", aiReply),
                                "finish_reason", "stop"
                        )
                ),
                "usage", Map.of("prompt_tokens", 0, "completion_tokens", 0, "total_tokens", 0)
        );
    }

    // 构建 OpenAI chat.completion.chunk 格式
    private Map<String, Object> buildChunk(String id, long created, Map<String, Object> delta, String finishReason) {
        HashMap<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);

        return Map.of(
                "id", id,
                "object", "chat.completion.chunk",
                "created", created,
                "model", "deepseek-local",
                "choices", List.of(choice)
        );
    }
}