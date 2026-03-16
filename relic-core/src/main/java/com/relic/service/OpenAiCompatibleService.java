package com.relic.service;

import com.relic.dto.ToolCallResult;
import com.relic.util.MessageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
public abstract class OpenAiCompatibleService implements AiProvider {

    protected abstract String getApiKey();

    protected abstract String getUrl();

    protected abstract String getModel();

    protected String providerDisplayName() {
        return getName();
    }

    protected boolean toolCallEnabled() {
        return true;
    }

    @Override
    public boolean supportsTools() {
        return toolCallEnabled();
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
    public ToolCallResult streamWithTools(List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools,
                                          Consumer<String> onChunk) throws Exception {
        ToolCallResult result = askWithTools(messages, tools);
        String text = result.getContentString();
        if (text != null && !text.isEmpty()) {
            onChunk.accept(text);
        }
        return result;
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
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", false);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        Map<String, Object> response = restTemplate.postForObject(getUrl(), entity, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        return choices.get(0);
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
