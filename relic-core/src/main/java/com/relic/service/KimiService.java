package com.relic.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class KimiService implements AiProvider {

    private final String API_KEY = "sk-lbvuX4lkXbAj5pyVGGbmcEVP5jnDgjicPipqXh5o0IdxjNEh";
    private final String URL = "https://api.moonshot.cn/v1/chat/completions";

    @Override
    public String getName() { return "kimi"; }

    @Override
    public String ask(String prompt) {
        return askKimi(prompt);
    }

    @SuppressWarnings("unchecked")
    public String askKimi(String prompt) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(API_KEY);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "moonshot-v1-8k");
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", false);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            Map<String, Object> response = restTemplate.postForObject(URL, entity, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            return "连接 Kimi 失败：" + e.getMessage();
        }
    }
}
