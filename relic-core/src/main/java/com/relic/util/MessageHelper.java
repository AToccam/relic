package com.relic.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 消息构建与清洗工具类，从 WebhookController 中抽离。
 */
public final class MessageHelper {

    private MessageHelper() {}

    //清洗 OpenAI 格式的 rawMessages：切除 metadata / 时间戳注入，同时保留 tool 相关字段
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> cleanRawMessages(List<Map<String, Object>> rawMessages) {
        List<Map<String, Object>> clean = new ArrayList<>();
        if (rawMessages == null) return clean;

        for (Map<String, Object> msg : rawMessages) {
            String role = (String) msg.get("role");

            // 跳过 tool 消息中缺少 tool_call_id 的无效条目（历史残留）
            if ("tool".equals(role)) {
                if (msg.get("tool_call_id") != null) {
                    Map<String, Object> toolMsg = new java.util.HashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", msg.get("tool_call_id"));
                    toolMsg.put("content", extractTextContent(msg.get("content")));
                    clean.add(toolMsg);
                }
                // 否则跳过这条无效 tool 消息
                continue;
            }

            String text = extractTextContent(msg.get("content"));

            // 切除 OpenClaw 注入的 metadata 和时间戳
            if ("user".equals(role) && text.contains("Sender (untrusted metadata):")) {
                text = text.replaceAll("Sender \\(untrusted metadata\\):[\\s\\S]*?```json[\\s\\S]*?```\\s*", "");
                text = text.replaceAll("\\[.*?\\]\\s*", "");
            }

            // 保留 assistant 消息中的 tool_calls 字段
            if ("assistant".equals(role) && msg.get("tool_calls") != null) {
                Map<String, Object> assistantMsg = new java.util.HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", text.trim().isEmpty() ? null : text.trim());
                assistantMsg.put("tool_calls", msg.get("tool_calls"));
                clean.add(assistantMsg);
                continue;
            }

            clean.add(Map.of(
                    "role", role == null ? "user" : role,
                    "content", text.trim()
            ));
        }
        return clean;
    }

    //从 content 字段提取纯文本（兼容 string / array<{type,text}> 两种格式）
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

    //滑动窗口：仅保留最近 maxHistory 条消息
    public static List<Map<String, Object>> applySlidingWindow(List<Map<String, Object>> messages, int maxHistory) {
        if (messages.size() > maxHistory) {
            return new ArrayList<>(messages.subList(messages.size() - maxHistory, messages.size()));
        }
        return messages;
    }

    //构建单轮对话消息列表
    public static List<Map<String, Object>> buildSingleTurnMessages(String userMessage) {
        return List.of(buildUserMessage(userMessage));
    }

    //工具引导 system prompt，供 SINGLE 模式注入
    private static final String TOOL_SYSTEM_PROMPT =
            "你是一个强大的 AI 助手。你可以使用以下工具来辅助回答用户的问题：\n"
            + "- web_search: 搜索互联网获取最新信息、新闻、技术资料等\n"
            + "- create_text_file: 在工作区创建文本文件\n"
            + "- read_file: 读取工作区中的文件内容\n"
            + "- list_files: 列出工作区中的文件和文件夹\n"
            + "当用户的问题需要实时信息或文件操作时，请主动调用相应工具。";

    /**
     * 确保消息列表包含工具引导的 system prompt。
     * 如果列表中已有 system 消息则不重复添加。
     */
    public static List<Map<String, Object>> ensureToolSystemPrompt(List<Map<String, Object>> messages) {
        for (Map<String, Object> msg : messages) {
            if ("system".equals(msg.get("role"))) {
                return messages; // 已有 system prompt，不重复注入
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(Map.of("role", "system", "content", TOOL_SYSTEM_PROMPT));
        result.addAll(messages);
        return result;
    }

    //构建 user 角色消息
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
          .append("给出更全面、更准确的最终回答。如果某个助手的回答有明显错误，请指出并纠正。\n")
          .append("你同时拥有以下工具能力，当你认为需要额外信息（尤其是实时信息）时请主动调用：\n")
          .append("- web_search: 搜索互联网获取最新信息\n")
          .append("- create_text_file: 创建文本文件\n")
          .append("- read_file: 读取文件内容\n")
          .append("- list_files: 列出文件和文件夹\n")
          .append("如果各助手的回复已经足够全面，可以不调用工具直接回答。\n\n");
        sb.append("=== 各 AI 助手的回复 ===\n\n");

        for (Map.Entry<String, String> entry : advisorReplies.entrySet()) {
            sb.append("【").append(entry.getKey()).append(" 的回复】\n");
            // 截断过长的 advisor 回复，避免请求体过大导致 Leader 响应缓慢
            String reply = entry.getValue();
            if (reply.length() > 2000) {
                reply = reply.substring(0, 2000) + "...（已截断）";
            }
            sb.append(reply).append("\n\n");
        }

        sb.append("=== 请综合以上回复，给出你的最终回答 ===");

        List<Map<String, Object>> result = new ArrayList<>();
        result.add(Map.of("role", "system", "content", sb.toString()));
        result.addAll(originalMessages);
        return result;
    }
}
