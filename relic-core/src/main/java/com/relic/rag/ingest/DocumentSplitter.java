package com.relic.rag.ingest;

import com.relic.rag.model.DocumentChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档分块器。
 *
 * <p>按字符窗口滑动分块，并优先在空白或句号边界切分，降低语义断裂概率。
 */
@Component
public class DocumentSplitter {

    @Value("${relic.rag.ingest.chunk-size:1000}")
    private int chunkSize;

    @Value("${relic.rag.ingest.chunk-overlap:150}")
    private int chunkOverlap;

    /**
     * 按配置的窗口和重叠长度拆分文本。
     */
    public List<DocumentChunk> split(String sourceId, String text, Map<String, Object> baseMetadata) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        int safeChunkSize = Math.max(200, chunkSize);
        int safeChunkOverlap = Math.max(0, Math.min(chunkOverlap, safeChunkSize / 2));
        String normalized = text.replace("\r\n", "\n");

        int start = 0;
        int chunkIndex = 0;
        int total = normalized.length();
        List<DocumentChunk> chunks = new ArrayList<>();

        while (start < total) {
            int tentativeEnd = Math.min(start + safeChunkSize, total);
            int end = findBoundary(normalized, start, tentativeEnd, total, safeChunkSize);
            String chunkText = normalized.substring(start, end).trim();

            if (!chunkText.isBlank()) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                if (baseMetadata != null) {
                    metadata.putAll(baseMetadata);
                }
                metadata.put("startOffset", start);
                metadata.put("endOffset", end);
                metadata.put("charLength", chunkText.length());

                chunks.add(DocumentChunk.builder()
                        .id(sourceId + "#" + chunkIndex)
                        .sourceId(sourceId)
                        .chunkIndex(chunkIndex)
                        .content(chunkText)
                        .metadata(metadata)
                        .build());
                chunkIndex++;
            }

            if (end >= total) {
                break;
            }

            int nextStart = Math.max(0, end - safeChunkOverlap);
            if (nextStart <= start) {
                nextStart = start + 1;
            }
            start = nextStart;
        }

        return chunks;
    }

    private int findBoundary(String text, int start, int tentativeEnd, int total, int safeChunkSize) {
        // 优先在后半段附近查找自然边界，找不到时按窗口硬切。
        if (tentativeEnd >= total) {
            return total;
        }

        int minBoundary = Math.max(start + (safeChunkSize / 2), start + 1);
        for (int i = tentativeEnd; i >= minBoundary; i--) {
            char c = text.charAt(i - 1);
            if (Character.isWhitespace(c) || c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                return i;
            }
        }
        return tentativeEnd;
    }
}
