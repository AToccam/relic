package com.relic.controller;

import com.relic.dto.OpenClawRequest;
import com.relic.service.DeepSeekService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/webhook")
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
        log.info("【收到来自 OpenClaw 的前端指令】: {}", userMessage);

        // 1. 呼叫 DeepSeek 获取回答
        log.info("【正在呼叫 DeepSeek，请稍候...】");
        String aiReply = deepSeekService.askDeepSeek(userMessage);
        log.info("【DeepSeek 回复完毕】: {}", aiReply);

        // 2. 组装 OpenClaw 要求的标准回应格式 (双向通信闭环！)
        return Map.of(
                "type", "res",               // 这是一个响应 (Response)
                "id", request.getId(),       // 必须把网关发来的流水号原样还回去，网关才知道是回复哪句话的
                "result", Map.of(
                        "text", aiReply          // 把 DeepSeek 的真实回答塞进 text 字段
                )
        );
    }
}