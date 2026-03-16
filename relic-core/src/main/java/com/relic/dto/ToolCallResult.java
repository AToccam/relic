package com.relic.dto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 返回结果的结构化表示，支持普通文本和工具调用两种情况。
 * 用于在 AiProvider 和 ToolCallService 之间传递数据。
 */
public class ToolCallResult {

    private String finishReason;
    private final StringBuilder content = new StringBuilder();
    private final List<ToolCall> toolCalls = new ArrayList<>();

    // 工具调用详情
    public static class ToolCall {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public StringBuilder getArguments() { return arguments; }
        public String getArgumentsString() { return arguments.toString(); }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("id", id);
            map.put("type", "function");
            Map<String, Object> func = new HashMap<>();
            func.put("name", name != null ? name : "");
            func.put("arguments", arguments.toString());
            map.put("function", func);
            return map;
        }
    }

    //工厂方法

    public static ToolCallResult textOnly(String text) {
        ToolCallResult r = new ToolCallResult();
        r.finishReason = "stop";
        if (text != null) r.content.append(text);
        return r;
    }

    // Getter

    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }

    public StringBuilder getContent() { return content; }
    public String getContentString() { return content.toString(); }

    public List<ToolCall> getToolCalls() { return toolCalls; }

    public boolean hasToolCalls() {
        return "tool_calls".equals(finishReason) && !toolCalls.isEmpty();
    }

    // 构建 assistant 消息（含 tool_calls），用于回传给 AI 
    public Map<String, Object> toAssistantMessage() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        if (!toolCalls.isEmpty()) {
            msg.put("tool_calls", toolCalls.stream().map(ToolCall::toMap).toList());
        }
        String c = content.toString();
        msg.put("content", c.isEmpty() ? null : c);
        // 修复Kimi工具调用API，补全reasoning_content字段
        msg.put("reasoning_content", c.isEmpty() ? "工具调用推理内容" : c);
        return msg;
    }
}
