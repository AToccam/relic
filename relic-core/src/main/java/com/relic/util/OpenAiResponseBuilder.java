package com.relic.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI chat.completion.chunk 格式构建工具。
 */
public final class OpenAiResponseBuilder {

    private OpenAiResponseBuilder() {}

    /** 构建一个 SSE chunk（OpenAI 兼容格式） */
    public static Map<String, Object> buildChunk(String id, long created, String model,
                                                  Map<String, Object> delta, String finishReason) {
        HashMap<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);

        return Map.of(
                "id", id,
                "object", "chat.completion.chunk",
                "created", created,
                "model", model,
                "choices", List.of(choice)
        );
    }
}
