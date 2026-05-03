package com.relic.rag.vector;

import com.relic.rag.model.DocumentChunk;
import com.relic.rag.model.RetrievalResult;

import java.util.List;
import java.util.Set;

/**
 * 向量存储抽象。
 *
 * <p>一期先使用内存实现，后续可替换为 Milvus、Weaviate 等外部向量库。
 */
public interface VectorStore {

    /**
     * @return store 名称
     */
    String getName();

    /**
     * 批量写入文档分块与对应向量。
     */
    void addDocuments(List<DocumentChunk> chunks, List<List<Double>> embeddings);

    /**
     * 按 query 向量做相似度检索。
     *
     * @param sourceIds 可选来源过滤集合；为空时表示不过滤
     */
    List<RetrievalResult> similaritySearch(List<Double> queryEmbedding, int topK, Set<String> sourceIds);

    default void deleteBySourceId(String sourceId) {
        // Optional operation for stores that support upsert by source.
    }

    default int size() {
        return -1;
    }
}
