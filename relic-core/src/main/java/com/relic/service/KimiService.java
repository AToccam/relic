package com.relic.service;

import com.relic.dto.ToolCallResult;
import com.relic.util.MessageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
public class KimiService implements AiProvider {

    private final String API_KEY = "sk-lbvuX4lkXbAj5pyVGGbmcEVP5jnDgjicPipqXh5o0IdxjNEh";
    private final String URL = "https://api.moonshot.cn/v1/chat/completions";

    @Value("${relic.kimi.model:moonshot-v1-8k}")
    private String model;

    @Override
    public String getName() { return "kimi"; }

    @Override
    public boolean supportsTools() { return true; }

    @Override
    public String ask(String prompt) {
        return ask(List.of(Map.of("role", "user", "content", prompt)));
    }

    @SuppressWarnings("unchecked")
    public String ask(List<Map<String, Object>> messages) {
        try {
            Map<String, Object> choice = callOnce(messages, null);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            return MessageHelper.extractTextContent(message.get("content"));
        } catch (Exception e) {
            return "连接 Kimi 失败：" + e.getMessage();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult askWithTools(List<Map<String, Object>> messages,
                                       List<Map<String, Object>> tools) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(API_KEY);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", false);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            Map<String, Object> response = restTemplate.postForObject(URL, entity, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            Map<String, Object> choice = choices.get(0);
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
        } catch (Exception e) {
            log.error("Kimi askWithTools 失败", e);
            return ToolCallResult.textOnly("连接 Kimi 失败：" + e.getMessage());
        }
    }

    @Override
    public ToolCallResult streamWithTools(List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools,
                                          Consumer<String> onChunk) {
        ToolCallResult result = askWithTools(messages, tools);
        String text = result.getContentString();
        if (text != null && !text.isEmpty()) {
            onChunk.accept(text);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callOnce(List<Map<String, Object>> messages,
                                         List<Map<String, Object>> tools) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(API_KEY);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", false);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        Map<String, Object> response = restTemplate.postForObject(URL, entity, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        return choices.get(0);
    }
}
