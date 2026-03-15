package com.relic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

/**
 * 本地 Ollama 微型模型服务：
 * 1) 语义路由分类（LocalIntentClassifier）
 * 2) 上游 API 不可用时的本地简答兜底
 */
@Slf4j
@Service
public class OllamaLocalService implements LocalIntentClassifier {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${relic.router.local-model.endpoint:http://127.0.0.1:11434}")
    private String endpoint;

    @Value("${relic.router.local-model.model:qwen3.5:2B}")
    private String model;

    @Value("${relic.router.local-model.classify-timeout-ms:3000}")
    private int classifyTimeoutMs;

    @Value("${relic.router.local-model.answer-timeout-ms:12000}")
    private int answerTimeoutMs;

    @Value("${relic.router.local-model.disable-thinking:true}")
    private boolean disableThinking;

    @Override
    public Optional<SemanticRouter.RoutePath> classify(String userMessage) {
        String system = "你是路由分类器。只能输出 FAST、TOOL_FIRST 或 DEEP 之一，不要输出其他任何字符。";
        String prompt = "用户请求：\n" + userMessage + "\n\n"
                + "分类规则：\n"
                + "- 需要查实时信息、搜索、文件读写、列目录等操作 => TOOL_FIRST\n"
                + "- 闲聊、问候、简单问答 => FAST\n"
                + "- 复杂架构分析、深度设计、长文推理 => DEEP\n\n"
                + "请直接输出分类标签：";

        try {
            String raw = generate(system, prompt, 12, 0.0, classifyTimeoutMs);
            String normalized = normalizeLabel(raw);
            return switch (normalized) {
                case "FAST" -> Optional.of(SemanticRouter.RoutePath.FAST);
                case "TOOL_FIRST" -> Optional.of(SemanticRouter.RoutePath.TOOL_FIRST);
                case "DEEP" -> Optional.of(SemanticRouter.RoutePath.DEEP);
                default -> Optional.empty();
            };
        } catch (Exception e) {
            log.warn("Ollama 本地分类失败，回退规则路由: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public String simpleAnswer(String userMessage) {
        String system = "你是本地离线应急助手。请直接给出简洁、可执行、尽量正确的回答，不要编造实时数据。";
        String prompt = "用户问题：\n" + userMessage + "\n\n"
                + "请给出简要回答（尽量 120 字以内）；如果需要联网实时信息，明确说明当前为离线兜底回答。";

        try {
            String reply = generate(system, prompt, 220, 0.2, answerTimeoutMs);
            if (reply == null || reply.trim().isEmpty()) {
                return "当前云端 AI 暂不可用，本地模型也未返回内容，请稍后重试。";
            }
            return reply.trim();
        } catch (Exception e) {
            log.warn("Ollama 本地兜底回答失败: {}", e.getMessage());
            return "当前云端 AI 暂不可用，且本地应急模型调用失败，请稍后重试。";
        }
    }

    private String generate(String system,
                            String prompt,
                            int numPredict,
                            double temperature,
                            int timeoutMs)
            throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("system", system);
        body.put("prompt", prompt);
        body.put("stream", false);
        if (disableThinking) {
            body.put("think", false);
        }
        body.put("options", Map.of(
                "temperature", temperature,
                "num_predict", numPredict
        ));

        String jsonBody = objectMapper.writeValueAsString(body);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveGenerateUrl()))
                .timeout(Duration.ofMillis(Math.max(800, timeoutMs * 2L)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("ollama-http-" + response.statusCode() + ": " + response.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(response.body(), Map.class);
        return extractText(parsed);
    }

    private String resolveGenerateUrl() {
        String base = endpoint == null ? "" : endpoint.trim();
        if (base.endsWith("/api/generate")) {
            return base;
        }
        if (base.endsWith("/")) {
            return base + "api/generate";
        }
        return base + "/api/generate";
    }

    private String normalizeLabel(String raw) {
        if (raw == null) return "";
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (t.contains("TOOL_FIRST")) return "TOOL_FIRST";
        if (t.contains("DEEP")) return "DEEP";
        if (t.contains("FAST")) return "FAST";

        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("tool") || lower.contains("工具") || lower.contains("搜索") || lower.contains("文件")) {
            return "TOOL_FIRST";
        }
        if (lower.contains("deep") || lower.contains("复杂") || lower.contains("深度") || lower.contains("架构") || lower.contains("分析")) {
            return "DEEP";
        }
        if (lower.contains("fast") || lower.contains("闲聊") || lower.contains("问候") || lower.contains("简单")) {
            return "FAST";
        }
        return t;
    }

    private String extractText(Map<String, Object> parsed) {
        Object response = parsed.get("response");
        if (response != null) {
            String text = response.toString().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }

        Object thinking = parsed.get("thinking");
        if (thinking != null) {
            String text = thinking.toString().trim();
            if (!text.isEmpty()) {
                log.warn("Ollama response 为空，使用 thinking 字段作为降级输出");
                return text;
            }
        }
        return "";
    }
}
