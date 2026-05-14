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
    /**
     * 用户在前端指定的"当前工作目录"绝对路径。
     * 若为空则沿用默认 workspace。AI 工具创建/读取文件时，相对路径将以该目录为基础解析。
     */
    private String workingDirectory;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RagConfig {
        private Boolean enabled;
        private List<String> sourceIds;
    }
}
