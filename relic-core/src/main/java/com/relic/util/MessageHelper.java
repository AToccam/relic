package com.relic.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 消息构建与清洗工具类，从 WebhookController 中抽离。
 */
public final class MessageHelper {

    private MessageHelper() {}

    /** 清洗 OpenAI 格式的 rawMessages：切除 metadata / 时间戳注入 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> cleanRawMessages(List<Map<String, Object>> rawMessages) {
        List<Map<String, Object>> clean = new ArrayList<>();
        if (rawMessages == null) return clean;

        for (Map<String, Object> msg : rawMessages) {
            String role = (String) msg.get("role");
            String text = extractTextContent(msg.get("content"));

            // 切除 OpenClaw 注入的 metadata 和时间戳
            if ("user".equals(role) && text.contains("Sender (untrusted metadata):")) {
                text = text.replaceAll("Sender \\(untrusted metadata\\):[\\s\\S]*?```json[\\s\\S]*?```\\s*", "");
                text = text.replaceAll("\\[.*?\\]\\s*", "");
            }

            clean.add(Map.of(
                    "role", role == null ? "user" : role,
                    "content", text.trim()
            ));
        }
        return clean;
    }

    /** 从 content 字段提取纯文本（兼容 string / array<{type,text}> 两种格式） */
    public static String extractTextContent(Object contentObj) {
        if (contentObj instanceof String str) return str;
        if (contentObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                    Object text = map.get("text");
                    return text == null ? "" : text.toString();
                }
            }
        }
        return "";
    }

    /** 滑动窗口：仅保留最近 maxHistory 条消息 */
    public static List<Map<String, Object>> applySlidingWindow(List<Map<String, Object>> messages, int maxHistory) {
        if (messages.size() > maxHistory) {
            return new ArrayList<>(messages.subList(messages.size() - maxHistory, messages.size()));
        }
        return messages;
    }

    /** 构建单轮对话消息列表 */
    public static List<Map<String, Object>> buildSingleTurnMessages(String userMessage) {
        return List.of(buildUserMessage(userMessage));
    }

    /** 构建 user 角色消息 */
    public static Map<String, Object> buildUserMessage(String content) {
        return Map.of("role", "user", "content", content == null ? "" : content);
    }

    /**
     * 构建多 AI 协同的聚合消息列表：
     * 在原始对话的基础上，注入 system prompt 告知 DeepSeek 参考各方观点后给出最终回答。
     */
    public static List<Map<String, Object>> buildAggregatedMessages(
            List<Map<String, Object>> originalMessages, Map<String, String> advisorReplies) {

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能聚合助手。其他 AI 助手已经针对用户的问题给出了各自的分析，请你综合参考它们的观点，")
          .append("给出更全面、更准确的最终回答。如果某个助手的回答有明显错误，请指出并纠正。\n\n");
        sb.append("=== 各 AI 助手的回复 ===\n\n");

        for (Map.Entry<String, String> entry : advisorReplies.entrySet()) {
            sb.append("【").append(entry.getKey()).append(" 的回复】\n");
            sb.append(entry.getValue()).append("\n\n");
        }

        sb.append("=== 请综合以上回复，给出你的最终回答 ===");

        List<Map<String, Object>> result = new ArrayList<>();
        result.add(Map.of("role", "system", "content", sb.toString()));
        result.addAll(originalMessages);
        return result;
    }
}
