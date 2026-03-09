package com.relic.service;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class DeepSeekService {

    private final String API_KEY = "sk-d7cbb8c351964fab8c6a7d8709e9da7b";
    private final String URL = "https://api.deepseek.com/chat/completions";

    public String askDeepSeek(String prompt) {
        RestTemplate restTemplate = new RestTemplate();
        //请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(API_KEY);

        //构建请求体
        Map<String, Object> requestBody = Map.of(
                "model", "deepseek-chat",
                "messages", List.of(
                        // 你可以在这里给你的系统植入“灵魂”
                        Map.of("role", "system", "content", "这是relic项目的系统测试"),
                        Map.of("role", "user", "content", prompt)
                ),
                "stream", false // 先用非流式，一次性拿到完整回复
        );

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
}