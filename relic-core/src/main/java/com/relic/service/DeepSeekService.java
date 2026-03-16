package com.relic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.dto.ToolCallResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
@Service
public class DeepSeekService extends OpenAiCompatibleService {

    private final String API_KEY = "sk-d7cbb8c351964fab8c6a7d8709e9da7b";
    private final String URL = "https://api.deepseek.com/chat/completions";

    @Value("${relic.deepseek.model:deepseek-chat}")
    private String model;

    @Value("${relic.deepseek.connect-timeout-ms:20000}")
    private int connectTimeoutMs;

    @Value("${relic.deepseek.request-timeout-ms:300000}")
    private int requestTimeoutMs;

    @Value("${relic.deepseek.idle-timeout-ms:180000}")
    private int idleTimeoutMs;

    @Override
    public String getName() { return "deepseek"; }

    @Override
    protected String getApiKey() { return API_KEY; }

    @Override
    protected String getUrl() { return URL; }

    @Override
    protected String getModel() { return model; }

    @Override
    protected String providerDisplayName() { return "DeepSeek"; }

    @Override
    public boolean supportsStream() { return true; }

    @Override
    public void stream(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        streamRaw(messages, null, onChunk);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult streamWithTools(List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools,
                                           Consumer<String> onChunk) throws Exception {
        return streamRaw(messages, tools, onChunk);
    }

    @SuppressWarnings("unchecked")
    private ToolCallResult streamRaw(List<Map<String, Object>> messages,
                                      List<Map<String, Object>> tools,
                                      Consumer<String> onChunk) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", getModel());
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", true);
        applyToolPayload(requestBody, messages, tools);

        ObjectMapper objectMapper = new ObjectMapper();
        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(3000, connectTimeoutMs)))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getUrl()))
            .timeout(Duration.ofMillis(Math.max(30_000, requestTimeoutMs)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        // 检查 HTTP 状态码，非 2xx 时直接读取错误信息并抛出
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody;
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                errorBody = errReader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
            log.error("DeepSeek API 返回错误 HTTP {}: {}", response.statusCode(), errorBody);
            throw new RuntimeException("DeepSeek API 错误 (HTTP " + response.statusCode() + "): " + errorBody);
        }

        ToolCallResult result = new ToolCallResult();

        // 空闲超时看门狗：在长推理场景下需要更长等待，超时阈值可配置
        InputStream bodyStream = response.body();
        AtomicLong lastDataTime = new AtomicLong(System.currentTimeMillis());
        Timer idleWatchdog = null;
        if (idleTimeoutMs > 0) {
            idleWatchdog = new Timer("ds-idle-watchdog", true);
            final long idleLimit = idleTimeoutMs;
            final long checkPeriod = Math.max(5_000L, Math.min(15_000L, idleLimit / 6));
            idleWatchdog.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    if (System.currentTimeMillis() - lastDataTime.get() > idleLimit) {
                        log.warn("【DeepSeek】流式响应超过 {} ms 无数据，强制关闭连接", idleLimit);
                        try { bodyStream.close(); } catch (Exception ignored) {}
                        cancel();
                    }
                }
            }, checkPeriod, checkPeriod);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(bodyStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastDataTime.set(System.currentTimeMillis());
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;

                Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                if (choices == null || choices.isEmpty()) continue;

                Map<String, Object> choice = choices.get(0);
                Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
                String finishReason = (String) choice.get("finish_reason");

                if (finishReason != null) {
                    result.setFinishReason(finishReason);
                }

                if (delta != null) {
                    String content = (String) delta.get("content");
                    if (content != null) {
                        result.getContent().append(content);
                        onChunk.accept(content);
                    }

                    List<Map<String, Object>> toolCallDeltas =
                            (List<Map<String, Object>>) delta.get("tool_calls");
                    if (toolCallDeltas != null) {
                        for (Map<String, Object> tcDelta : toolCallDeltas) {
                            int index = ((Number) tcDelta.get("index")).intValue();

                            while (result.getToolCalls().size() <= index) {
                                result.getToolCalls().add(new ToolCallResult.ToolCall());
                            }

                            ToolCallResult.ToolCall tc = result.getToolCalls().get(index);

                            String id = (String) tcDelta.get("id");
                            if (id != null) tc.setId(id);

                            Map<String, Object> function =
                                    (Map<String, Object>) tcDelta.get("function");
                            if (function != null) {
                                String name = (String) function.get("name");
                                if (name != null) tc.setName(name);
                                String args = (String) function.get("arguments");
                                if (args != null) tc.getArguments().append(args);
                            }
                        }
                    }
                }
            }
        } finally {
            if (idleWatchdog != null) {
                idleWatchdog.cancel();
            }
        }

        return result;
    }
}