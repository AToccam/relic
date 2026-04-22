package com.relic.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容聊天请求体。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatCompletionRequest {

    private String conversationId;
    private List<Map<String, Object>> messages;
    private RagConfig ragConfig;
    private Boolean toolsEnabled;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RagConfig {
        private Boolean enabled;
        private List<String> sourceIds;
    }
}
