package com.relic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.dto.ToolCallResult;
import lombok.extern.slf4j.Slf4j;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
public class DeepSeekService implements AiProvider {

    private final String API_KEY = "sk-d7cbb8c351964fab8c6a7d8709e9da7b";
    private final String URL = "https://api.deepseek.com/chat/completions";

    @Override
    public String getName() { return "deepseek"; }

    @Override
    public boolean supportsStream() { return true; }

    @Override
    public boolean supportsTools() { return true; }

    // ==================== 纯文本问答 ====================

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
            return (String) message.get("content");
        } catch (Exception e) {
            return "连接 DeepSeek 失败：" + e.getMessage();
        }
    }

    @Override
    public void stream(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        streamRaw(messages, null, onChunk);
    }

    // ==================== 带工具的单次调用（不含循环） ====================

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult askWithTools(List<Map<String, Object>> messages,
                                       List<Map<String, Object>> tools) {
        try {
            Map<String, Object> choice = callOnce(messages, tools);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            String finishReason = (String) choice.get("finish_reason");

            ToolCallResult result = new ToolCallResult();
            result.setFinishReason(finishReason);

            String content = (String) message.get("content");
            if (content != null) {
                result.getContent().append(content);
            }

            if ("tool_calls".equals(finishReason) && message.get("tool_calls") != null) {
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
                for (Map<String, Object> tc : toolCalls) {
                    ToolCallResult.ToolCall call = new ToolCallResult.ToolCall();
                    call.setId((String) tc.get("id"));
                    Map<String, Object> function = (Map<String, Object>) tc.get("function");
                    call.setName((String) function.get("name"));
                    call.getArguments().append((String) function.get("arguments"));
                    result.getToolCalls().add(call);
                }
            }

            return result;
        } catch (Exception e) {
            log.error("DeepSeek askWithTools 失败", e);
            return ToolCallResult.textOnly("连接 DeepSeek 失败：" + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult streamWithTools(List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools,
                                           Consumer<String> onChunk) throws Exception {
        return streamRaw(messages, tools, onChunk);
    }

    // ==================== 底层 HTTP 调用 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> callOnce(List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(API_KEY);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "deepseek-chat");
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", false);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");

        Map<String, Object> response = restTemplate.postForObject(URL, entity, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        return choices.get(0);
    }

    @SuppressWarnings("unchecked")
    private ToolCallResult streamRaw(List<Map<String, Object>> messages,
                                      List<Map<String, Object>> tools,
                                      Consumer<String> onChunk) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "deepseek-chat");
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", true);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        ObjectMapper objectMapper = new ObjectMapper();
        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        ToolCallResult result = new ToolCallResult();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
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
        }

        return result;
    }
}