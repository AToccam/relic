package com.relic.rag.ingest;

import com.relic.rag.embedding.EmbeddingProvider;
import com.relic.rag.model.DocumentChunk;
import com.relic.rag.model.DocumentIndexState;
import com.relic.rag.model.IndexStatus;
import com.relic.rag.vector.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档索引编排服务。
 *
 * <p>负责串联 提取 -> 分块 -> 向量化 -> 入库，并维护索引状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final DocumentTextExtractor textExtractor;
    private final DocumentSplitter splitter;
    private final EmbeddingProvider embeddingProvider;
    private final VectorStore vectorStore;

    private final ConcurrentHashMap<String, DocumentIndexState> indexStates = new ConcurrentHashMap<>();

    /**
     * 手动触发索引（异步）。
     */
    public void triggerManualIndexAsync(String sourceId) {
        triggerIndexAsync(sourceId, "manual");
    }

    /**
     * 自动触发索引（异步）。
     */
    public void triggerAutoIndexAsync(String sourceId) {
        triggerIndexAsync(sourceId, "auto");
    }

    /**
     * 统一异步触发入口。
     */
    public void triggerIndexAsync(String sourceId, String triggerType) {
        String normalizedSourceId = normalizeSourceId(sourceId);
        upsertState(normalizedSourceId, IndexStatus.INDEXING, "索引任务已排队", 0);

        Thread.startVirtualThread(() -> {
            try {
                indexNow(normalizedSourceId, triggerType);
            } catch (Exception ignored) {
                // 状态会在 indexNow 内更新为失败，这里避免传播到调用线程。
            }
        });
    }

    /**
     * 同步执行一次完整索引流程。
     */
    public DocumentIndexState indexNow(String sourceId, String triggerType) {
        String normalizedSourceId = normalizeSourceId(sourceId);
        upsertState(normalizedSourceId, IndexStatus.INDEXING, "正在索引中", 0);
        try {
            String text = textExtractor.extractBySourceId(normalizedSourceId);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceId", normalizedSourceId);
            metadata.put("trigger", triggerType == null ? "manual" : triggerType);
            metadata.put("embeddingProvider", embeddingProvider.getName());

            List<DocumentChunk> chunks = splitter.split(normalizedSourceId, text, metadata);
            if (chunks.isEmpty()) {
                return upsertState(normalizedSourceId, IndexStatus.FAILED, "文本为空，无法建立索引", 0);
            }

            // 先清理同 source 的旧向量，再写入新结果，保证重建幂等。
            vectorStore.deleteBySourceId(normalizedSourceId);
            List<String> chunkTexts = chunks.stream().map(DocumentChunk::getContent).toList();
            List<List<Double>> vectors = embeddingProvider.embedBatch(chunkTexts);
            vectorStore.addDocuments(chunks, vectors);

            DocumentIndexState done = upsertState(
                    normalizedSourceId,
                    IndexStatus.COMPLETED,
                    "索引完成，chunk 数: " + chunks.size(),
                    chunks.size());

            log.info("【RAG】文档索引完成: sourceId={}, chunks={}, vectorStore={}",
                    normalizedSourceId, chunks.size(), vectorStore.getName());
            return done;
        } catch (Exception e) {
            log.warn("【RAG】文档索引失败: sourceId={}, reason={}", normalizedSourceId, e.getMessage());
            return upsertState(normalizedSourceId, IndexStatus.FAILED, e.getMessage(), 0);
        }
    }

    /**
     * 查询单文档索引状态。
     */
    public DocumentIndexState getIndexState(String sourceId) {
        String normalizedSourceId = normalizeSourceId(sourceId);
        DocumentIndexState current = indexStates.get(normalizedSourceId);
        if (current != null) {
            return current;
        }
        return DocumentIndexState.builder()
                .sourceId(normalizedSourceId)
                .status(IndexStatus.NOT_INDEXED)
                .message("文档尚未建立索引")
                .chunkCount(0)
                .updatedAt(Instant.now())
                .build();
    }

    /**
     * 查询当前内存中的全部索引状态。
     */
    public List<DocumentIndexState> listIndexStates() {
        return indexStates.values().stream()
                .sorted((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()))
                .toList();
    }

    private DocumentIndexState upsertState(String sourceId, IndexStatus status, String message, int chunkCount) {
        DocumentIndexState state = DocumentIndexState.builder()
                .sourceId(sourceId)
                .status(status)
                .message(message)
                .chunkCount(chunkCount)
                .updatedAt(Instant.now())
                .build();
        indexStates.put(sourceId, state);
        return state;
    }

    private String normalizeSourceId(String sourceId) {
        if (!StringUtils.hasText(sourceId)) {
            throw new IllegalArgumentException("sourceId 不能为空");
        }
        String normalized = sourceId.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("sourceId 不能为空");
        }
        return normalized;
    }
}
