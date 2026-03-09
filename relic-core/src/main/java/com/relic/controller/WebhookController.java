package com.relic.controller;

import com.relic.dto.OpenClawRequest;
import com.relic.service.GeminiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    // 自动注入 Gemini 服务
    @Autowired
    private GeminiService geminiService;

    @PostMapping("/openclaw")
    public String receiveMessage(@RequestBody OpenClawRequest request) {
        if (!"chat.send".equals(request.getMethod())) {
            return "ok";
        }

        String userMessage = request.getParams().getMessage();
        log.info("前端指令 {}", userMessage);

        // ================= 核心变化在这里 =================
        log.info("连接Gemini");
        String aiReply = geminiService.askGemini(userMessage);

        log.info("回复内容: \n{}", aiReply);
        // ==================================================

        return "success";
    }
}