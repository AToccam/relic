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
}
