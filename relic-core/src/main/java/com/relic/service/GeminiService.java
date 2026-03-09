package com.relic.service;

import org.springframework.stereotype.Service;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;

@Service
public class GeminiService {

    private final String API_KEY = "AIzaSyCFCldUlylxEKLvMvKa1S7wH5LlZeF8u0M";

    public String askGemini(String prompt) {
        try {
            //代理流量
            System.setProperty("https.proxyHost", "127.0.0.1");
            System.setProperty("https.proxyPort", "7897");

            Client client = Client.builder()
                    .apiKey(API_KEY)
                    .build();

            GenerateContentResponse response = client.models.generateContent(
                    "gemini-3-flash-preview",
                    prompt,
                    null
            );

            return response.text();

        } catch (Exception e) {
            return "连接失败" + e.getMessage();
        }
    }
}

//目前能实现向google进行通信，但是apikey暂时被限制使用