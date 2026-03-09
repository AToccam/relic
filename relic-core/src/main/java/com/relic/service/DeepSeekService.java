package com.relic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
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
    public String ask(String prompt) {
        return askDeepSeek(List.of(Map.of("role", "user", "content", prompt)));
    }

    @Override
    public String ask(List<Map<String, Object>> messages) {
        return askDeepSeek(messages);
    }

    @Override
    public void stream(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        streamDeepSeek(messages, onChunk);
    }

    @Override
    public boolean supportsStream() { return true; }

    public String askDeepSeek(List<Map<String, Object>> messages) {
        RestTemplate restTemplate = new RestTemplate();
        //请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(API_KEY);

        //构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "deepseek-chat");
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", false);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            // 设置国内直连代理
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");

            // 发送请求并剥离 JSON 获取文本
            Map response = restTemplate.postForObject(URL, entity, Map.class);
            List<Map> choices = (List<Map>) response.get("choices");
            Map message = (Map) choices.get(0).get("message");

            return (String) message.get("content");

        } catch (Exception e) {
            return "连接 DeepSeek 失败：" + e.getMessage();
        }
    }


     //流式调用 DeepSeek，逐块回调内容
    @SuppressWarnings("unchecked")
    public void streamDeepSeek(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "deepseek-chat");
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", true);

        ObjectMapper objectMapper = new ObjectMapper();
        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<java.io.InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;

                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;

                Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                    if (delta != null) {
                        String content = (String) delta.get("content");
                        if (content != null) {
                            onChunk.accept(content);
                        }
                    }
                }
            }
        }
    }
}