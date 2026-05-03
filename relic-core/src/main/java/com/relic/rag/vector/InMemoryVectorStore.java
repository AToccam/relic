package com.relic.rag.vector;

import com.relic.rag.model.DocumentChunk;
import com.relic.rag.model.RetrievalResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一期默认向量库：内存实现。
 *
 * <p>优势是零外部依赖、便于联调；限制是进程重启后数据丢失，不适合生产持久化场景。
 */
@Slf4j
public class InMemoryVectorStore implements VectorStore {

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public String getName() {
        return "memory";
    }

    @Override
    public void addDocuments(List<DocumentChunk> chunks, List<List<Double>> embeddings) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        if (embeddings == null || chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunks 与 embeddings 数量不匹配");
        }

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            List<Double> vector = embeddings.get(i);
            if (chunk == null || vector == null || vector.isEmpty()) {
                continue;
            }
            entries.add(new Entry(chunk, List.copyOf(vector)));
        }
        log.info("【RAG】向量入库完成: store={}, inserted={}, total={}", getName(), chunks.size(), entries.size());
    }

    @Override
    public List<RetrievalResult> similaritySearch(List<Double> queryEmbedding, int topK, Set<String> sourceIds) {
        // 当前使用余弦相似度 + TopK，后续可扩展阈值过滤和重排。
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            return List.of();
        }

        int limit = Math.max(1, topK);
        Set<String> sourceFilter = sourceIds == null ? Set.of() : sourceIds;

        List<RetrievalResult> scored = new ArrayList<>();
        for (Entry entry : entries) {
            if (!sourceFilter.isEmpty() && !sourceFilter.contains(entry.chunk.getSourceId())) {
                continue;
            }
            double score = cosineSimilarity(queryEmbedding, entry.embedding);
            scored.add(RetrievalResult.builder().chunk(entry.chunk).score(score).build());
        }

        scored.sort(Comparator.comparingDouble(RetrievalResult::getScore).reversed());
        if (scored.size() <= limit) {
            return scored;
        }
        return scored.subList(0, limit);
    }

    @Override
    public void deleteBySourceId(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return;
        }
        entries.removeIf(entry -> sourceId.equals(entry.chunk.getSourceId()));
    }

    @Override
    public int size() {
        return entries.size();
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        int len = Math.min(a.size(), b.size());
        if (len == 0) {
            return 0;
        }

        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < len; i++) {
            double av = a.get(i);
            double bv = b.get(i);
            dot += av * bv;
            normA += av * av;
            normB += bv * bv;
        }

        if (normA <= 0 || normB <= 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record Entry(DocumentChunk chunk, List<Double> embedding) {
    }
}
