package com.relic.util;

import com.relic.rag.model.Citation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

//OpenAI chat.completion.chunk 格式构建工具。
public final class OpenAiResponseBuilder {

    private OpenAiResponseBuilder() {}

    // 构建一个 SSE chunk（OpenAI 兼容格式
    public static Map<String, Object> buildChunk(String id, long created, String model,
                                                  Map<String, Object> delta, String finishReason) {
        return buildChunk(id, created, model, delta, finishReason, null);
    }

    // 构建一个 SSE chunk（包含可选 citations 扩展字段）
    public static Map<String, Object> buildChunk(String id, long created, String model,
                                                  Map<String, Object> delta, String finishReason,
                                                  List<Citation> citations) {
        HashMap<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);

        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);
        if (citations != null && !citations.isEmpty()) {
            chunk.put("citations", new ArrayList<>(citations));
        }
        chunk.put("choices", List.of(choice));
        return chunk;
    }
}
