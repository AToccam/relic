package com.relic.service;

import com.relic.dto.ToolCallResult;
import com.relic.util.MessageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
public abstract class OpenAiCompatibleService implements AiProvider {
    // 支持自定义temperature，默认0.7
    protected double getTemperature() { return 0.7; }

    protected abstract String getApiKey();

    protected abstract String getUrl();

    protected abstract String getModel();

    protected String providerDisplayName() {
        return getName();
    }

    protected boolean toolCallEnabled() {
        return true;
    }

    // 429/overloaded 的额外重试次数（总尝试次数 = 1 + retry）
    protected int getOverloadRetryTimes() {
        return 2;
    }

    protected long getOverloadRetryBaseDelayMs() {
        return 1200L;
    }

    @Override
    public boolean supportsTools() {
        return toolCallEnabled();
    }

    @Override
    public boolean supportsStream() {
        return true;
    }

    @Override
    public String ask(String prompt) {
        return ask(List.of(Map.of("role", "user", "content", prompt)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public String ask(List<Map<String, Object>> messages) {
        try {
            Map<String, Object> choice = callOnce(messages, null);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            return MessageHelper.extractTextContent(message.get("content"));
        } catch (Exception e) {
            return "连接 " + providerDisplayName() + " 失败：" + e.getMessage();
        }
    }

    @Override
    public void stream(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        streamWithTools(messages, null, onChunk);
    }

    @Override
    public ToolCallResult askWithTools(List<Map<String, Object>> messages,
                                       List<Map<String, Object>> tools) {
        if (!supportsTools()) {
            return ToolCallResult.textOnly(ask(messages));
        }

        try {
            Map<String, Object> choice = callOnce(messages, tools);
            return parseToolCallResult(choice);
        } catch (Exception e) {
            log.error("{} askWithTools 失败", providerDisplayName(), e);
            return ToolCallResult.textOnly("连接 " + providerDisplayName() + " 失败：" + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult streamWithTools(List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools,
                                          Consumer<String> onChunk) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(getApiKey());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", getModel());
        requestBody.put("messages", messages);
        requestBody.put("temperature", getTemperature());
        requestBody.put("stream", true);
        applyToolPayload(requestBody, messages, tools);

        String jsonBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getUrl()))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + getApiKey())
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        int maxAttempts = Math.max(1, getOverloadRetryTimes() + 1);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return parseStreamResponse(response.body(), onChunk, messages, tools);
            }

            String errorBody;
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                errorBody = errReader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }

            if (isRetryableOverload(response.statusCode(), errorBody) && attempt < maxAttempts) {
                long delayMs = getOverloadRetryBaseDelayMs() * attempt;
                log.warn("{} 发生 429/overloaded，第 {}/{} 次重试，{}ms 后继续", providerDisplayName(), attempt, maxAttempts, delayMs);
                sleepQuietly(delayMs);
                continue;
            }

            throw new RuntimeException(providerDisplayName() + " API 错误 (HTTP "
                    + response.statusCode() + "): " + errorBody);
        }

        throw new RuntimeException(providerDisplayName() + " 服务暂时繁忙，请稍后重试");
    }

    @SuppressWarnings("unchecked")
    private ToolCallResult parseStreamResponse(InputStream responseBody,
                                               Consumer<String> onChunk,
                                               List<Map<String, Object>> messages,
                                               List<Map<String, Object>> tools) throws Exception {
        ToolCallResult result = new ToolCallResult();
        Map<Integer, ToolCallResult.ToolCall> callByIndex = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }

                String data = trimmed.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }

                Map<String, Object> chunk;
                try {
                    chunk = new com.fasterxml.jackson.databind.ObjectMapper().readValue(data, Map.class);
                } catch (Exception ignored) {
                    // 跳过心跳或非 JSON 片段
                    continue;
                }

                List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                if (choices == null || choices.isEmpty()) {
                    continue;
                }

                Map<String, Object> choice = choices.get(0);
                String finishReason = (String) choice.get("finish_reason");
                if (finishReason != null) {
                    result.setFinishReason(finishReason);
                }

                Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
                if (delta == null) {
                    continue;
                }

                Object contentObj = delta.get("content");
                String content = MessageHelper.extractTextContent(contentObj);
                if (content != null && !content.isBlank()) {
                    result.getContent().append(content);
                    onChunk.accept(content);
                }

                List<Map<String, Object>> toolCallDeltas =
                        (List<Map<String, Object>>) delta.get("tool_calls");
                if (toolCallDeltas == null || toolCallDeltas.isEmpty()) {
                    continue;
                }

                for (Map<String, Object> tcDelta : toolCallDeltas) {
                    int index = resolveToolCallIndex(tcDelta, callByIndex.size());
                    ToolCallResult.ToolCall tc = callByIndex.computeIfAbsent(index, k -> new ToolCallResult.ToolCall());
                    String id = (String) tcDelta.get("id");
                    if (id != null) {
                        tc.setId(id);
                    }

                    Map<String, Object> function = (Map<String, Object>) tcDelta.get("function");
                    if (function == null) {
                        continue;
                    }

                    String name = (String) function.get("name");
                    if (name != null) {
                        tc.setName(name);
                    }

                    String args = (String) function.get("arguments");
                    if (args != null) {
                        tc.getArguments().append(args);
                    }
                }
            }
        }

        result.getToolCalls().clear();
        callByIndex.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .forEach(result.getToolCalls()::add);

        if ("tool_calls".equals(result.getFinishReason()) && !hasExecutableToolCall(result)) {
            ToolCallResult fallback = fallbackToolCallsByNonStream(messages, tools);
            if (fallback != null && hasExecutableToolCall(fallback)) {
                log.info("{} 流式 tool_calls 无有效工具名，已回退非流式补偿解析", providerDisplayName());
                return fallback;
            }
        }

        return result;
    }

    private ToolCallResult fallbackToolCallsByNonStream(List<Map<String, Object>> messages,
                                                        List<Map<String, Object>> tools) {
        try {
            Map<String, Object> choice = callOnce(messages, tools);
            return parseToolCallResult(choice);
        } catch (Exception e) {
            log.warn("{} 非流式补偿解析失败: {}", providerDisplayName(), e.getMessage());
            return null;
        }
    }

    private boolean hasExecutableToolCall(ToolCallResult result) {
        if (result == null || result.getToolCalls().isEmpty()) {
            return false;
        }
        for (ToolCallResult.ToolCall tc : result.getToolCalls()) {
            if (tc != null && tc.getName() != null && !tc.getName().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private int resolveToolCallIndex(Map<String, Object> tcDelta, int defaultValue) {
        Object idx = tcDelta.get("index");
        if (idx instanceof Number number) {
            return number.intValue();
        }
        if (idx instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> callOnce(List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(getApiKey());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", getModel());
        requestBody.put("messages", messages);
        requestBody.put("temperature", getTemperature());
        requestBody.put("stream", false);
        applyToolPayload(requestBody, messages, tools);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        int maxAttempts = Math.max(1, getOverloadRetryTimes() + 1);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, Object> response = restTemplate.postForObject(getUrl(), entity, Map.class);
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                return choices.get(0);
            } catch (HttpStatusCodeException e) {
                String body = e.getResponseBodyAsString();
                int status = e.getStatusCode().value();
                if (isRetryableOverload(status, body) && attempt < maxAttempts) {
                    long delayMs = getOverloadRetryBaseDelayMs() * attempt;
                    log.warn("{} 非流式请求遇到 429/overloaded，第 {}/{} 次重试，{}ms 后继续", providerDisplayName(), attempt, maxAttempts, delayMs);
                    sleepQuietly(delayMs);
                    continue;
                }
                throw new RuntimeException(providerDisplayName() + " API 错误 (HTTP " + status + "): " + body, e);
            }
        }

        throw new RuntimeException(providerDisplayName() + " 服务暂时繁忙，请稍后重试");
    }

    protected boolean isRetryableOverload(int statusCode, String responseBody) {
        if (statusCode != 429) {
            return false;
        }
        String body = responseBody == null ? "" : responseBody.toLowerCase(Locale.ROOT);
        return body.isBlank() || body.contains("overload") || body.contains("engine_overloaded");
    }

    protected void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected void applyToolPayload(Map<String, Object> requestBody,
                                    List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }

        requestBody.put("tools", tools);
        if (shouldForceToolChoice(messages)) {
            requestBody.put("tool_choice", "required");
        }
    }

    protected boolean shouldForceToolChoice(List<Map<String, Object>> messages) {
        if (hasToolInteraction(messages)) {
            return false;
        }

        String latestUserText = extractLatestUserText(messages).toLowerCase(Locale.ROOT);
        if (latestUserText.isBlank()) {
            return false;
        }

        return latestUserText.contains("调用工具")
                || latestUserText.contains("使用工具")
                || latestUserText.contains("读取文件")
                || latestUserText.contains("帮我列出")
                || latestUserText.contains("列出工作区")
                || latestUserText.contains("创建文件")
                || latestUserText.contains("生成文件")
                || latestUserText.contains("图表")
                || latestUserText.contains("画图")
                || latestUserText.contains("关系图")
                || latestUserText.contains("流程图")
                || latestUserText.contains("思维导图")
                || latestUserText.contains("mermaid")
                || latestUserText.contains("chart")
                || latestUserText.contains("diagram")
                || latestUserText.contains("flowchart")
                || latestUserText.contains("list_files")
                || latestUserText.contains("read_file")
                || latestUserText.contains("create_text_file")
                || latestUserText.contains("create_mermaid_chart_file");
    }

    private boolean hasToolInteraction(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }

        for (Map<String, Object> msg : messages) {
            if ("tool".equals(msg.get("role"))) {
                return true;
            }

            if ("assistant".equals(msg.get("role")) && msg.get("tool_calls") != null) {
                return true;
            }
        }
        return false;
    }

    private String extractLatestUserText(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"user".equals(msg.get("role"))) {
                continue;
            }
            return extractTextParts(msg.get("content")).trim();
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private String extractTextParts(Object contentObj) {
        if (contentObj instanceof String str) {
            return str;
        }

        if (contentObj instanceof List<?> list) {
            List<String> textParts = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> part = (Map<String, Object>) raw;
                if ("text".equals(part.get("type")) && part.get("text") != null) {
                    textParts.add(part.get("text").toString());
                }
            }
            return String.join("\n", textParts);
        }

        return "";
    }

    @SuppressWarnings("unchecked")
    protected ToolCallResult parseToolCallResult(Map<String, Object> choice) {
        Map<String, Object> message = (Map<String, Object>) choice.get("message");

        ToolCallResult result = new ToolCallResult();
        result.setFinishReason((String) choice.get("finish_reason"));

        String content = MessageHelper.extractTextContent(message.get("content"));
        if (content != null && !content.isBlank()) {
            result.getContent().append(content);
        }

        if ("tool_calls".equals(result.getFinishReason()) && message.get("tool_calls") != null) {
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
            for (Map<String, Object> tc : toolCalls) {
                ToolCallResult.ToolCall call = new ToolCallResult.ToolCall();
                call.setId((String) tc.get("id"));
                Map<String, Object> function = (Map<String, Object>) tc.get("function");
                if (function != null) {
                    call.setName((String) function.get("name"));
                    Object arguments = function.get("arguments");
                    if (arguments != null) {
                        call.getArguments().append(arguments.toString());
                    }
                }
                result.getToolCalls().add(call);
            }
        }

        return result;
    }
}
