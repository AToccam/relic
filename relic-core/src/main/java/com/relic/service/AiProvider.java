package com.relic.service;

import com.relic.dto.ToolCallResult;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 统一 AI 提供者接口，所有 AI 服务均实现此接口。
 * 为后续多 AI 并行回答提供统一调用入口。
 */
public interface AiProvider {

    //提供者标识名，如 "deepseek""qwen"
    String getName();

    //单轮简单问答 
    String ask(String prompt);

    //基于多轮消息列表问答（默认实现：取最后一条 user 消息调 ask(prompt)）
    default String ask(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                return ask((String) messages.get(i).get("content"));
            }
        }
        return ask("");
    }

    //流式输出（默认实现：一次性返回全部内容）
    default void stream(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        String result = ask(messages);
        onChunk.accept(result);
    }

    // 是否支持真正的流式输出
    default boolean supportsStream() {
        return false;
    }

    // Tool Calling 支持（可选实现）
    // 是否支持 OpenAI 兼容的 function calling / tools
    default boolean supportsTools() {
        return false;
    }

    /**
     * 非流式调用（带 tools 参数），返回结构化结果。
     * 默认：忽略 tools，返回纯文本结果。
     */
    default ToolCallResult askWithTools(List<Map<String, Object>> messages,
                                        List<Map<String, Object>> tools) {
        String content = ask(messages);
        return ToolCallResult.textOnly(content);
    }

    /**
     * 流式调用（带 tools 参数），流式输出文本内容，同时返回可能的工具调用信息。
     * 默认：忽略 tools，走普通 stream。
     */
    default ToolCallResult streamWithTools(List<Map<String, Object>> messages,
                                            List<Map<String, Object>> tools,
                                            Consumer<String> onChunk) throws Exception {
        stream(messages, onChunk);
        return ToolCallResult.textOnly(null);
    }
}
