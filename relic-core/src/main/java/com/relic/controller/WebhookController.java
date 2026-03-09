package com.relic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
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
        List<Map<String, Object>> messages = buildSingleTurnMessages(userMessage);
        log.info("收到来自 OpenClaw 的前端指令: {}", userMessage);

        log.info("正在连接 DeepSeek");
        String aiReply = "";
        try {
            // 记录开始时间
            long startTime = System.currentTimeMillis();

            aiReply = deepSeekService.askDeepSeek(messages);

            // 记录结束时间与结果
            long costTime = System.currentTimeMillis() - startTime;
            log.info("DeepSeek 调用成功，耗时: {} ms", costTime);
            log.info("DeepSeek 返回内容: {}", aiReply);

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

    // openai 格式回复：真正的流式 SSE 输出
    @PostMapping(value = "/v1/chat/completions")
    @SuppressWarnings("unchecked")
    public SseEmitter handleOpenAIRequest(@RequestBody Map<String, Object> request) {
        List<Map<String, Object>> rawMessages = (List<Map<String, Object>>) request.get("messages");
        List<Map<String, Object>> cleanMessages = new ArrayList<>();

        if (rawMessages != null) {
            for (Map<String, Object> msg : rawMessages) {
                String role = (String) msg.get("role");
                String text = extractTextContent(msg.get("content"));

                // 切除 OpenClaw 注入的 metadata 和时间戳
                if ("user".equals(role) && text.contains("Sender (untrusted metadata):")) {
                    text = text.replaceAll("Sender \\(untrusted metadata\\):[\\s\\S]*?```json[\\s\\S]*?```\\s*", "");
                    text = text.replaceAll("\\[.*?\\]\\s*", "");
                }

                cleanMessages.add(Map.of(
                        "role", role == null ? "user" : role,
                        "content", text.trim()
                ));
            }
        }

        if (cleanMessages.isEmpty()) {
            cleanMessages.add(buildUserMessage(""));
        }

        log.info("【上下文记忆条数】: {}", cleanMessages.size());
        log.info("【当前最新提问】: {}", cleanMessages.get(cleanMessages.size() - 1).get("content"));

        SseEmitter emitter = new SseEmitter(120_000L);
        String chatId = "chatcmpl-" + System.currentTimeMillis();
        long created = System.currentTimeMillis() / 1000;
        ObjectMapper mapper = new ObjectMapper();

        emitter.onCompletion(() -> log.info("【SSE 连接已关闭】"));
        emitter.onTimeout(() -> log.warn("【SSE 连接超时】"));

        Thread.startVirtualThread(() -> {
            try {
                log.info("【流式连接 DeepSeek 中...】");
                // 逐块转发 DeepSeek 内容
                deepSeekService.streamDeepSeek(cleanMessages, content -> {
                    try {
                        Map<String, Object> chunk = buildChunk(chatId, created, Map.of("content", content), null);
                        emitter.send(SseEmitter.event().data(mapper.writeValueAsString(chunk), MediaType.APPLICATION_JSON));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

                // 发送结束标记
                Map<String, Object> stopChunk = buildChunk(chatId, created, Map.of(), "stop");
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(stopChunk), MediaType.APPLICATION_JSON));
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

    @SuppressWarnings("unchecked")
    private String extractTextContent(Object contentObj) {
        if (contentObj instanceof String str) {
            return str;
        }
        if (contentObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                    Object text = map.get("text");
                    return text == null ? "" : text.toString();
                }
            }
        }
        return "";
    }

    private List<Map<String, Object>> buildSingleTurnMessages(String userMessage) {
        return List.of(buildUserMessage(userMessage));
    }

    private Map<String, Object> buildUserMessage(String content) {
        return Map.of("role", "user", "content", content == null ? "" : content);
    }
}