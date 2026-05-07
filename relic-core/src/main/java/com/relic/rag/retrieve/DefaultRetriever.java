package com.relic.rag.retrieve;

import com.relic.rag.embedding.EmbeddingProvider;
import com.relic.rag.model.RetrievalResult;
import com.relic.rag.vector.VectorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DefaultRetriever implements Retriever {

    private final EmbeddingProvider embeddingProvider;
    private final VectorStore vectorStore;

    @Override
    public List<RetrievalResult> retrieve(String query, int topK, Set<String> sourceIds) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<Double> queryVector = embeddingProvider.embed(query);
        return vectorStore.similaritySearch(queryVector, topK, sourceIds);
    }
}
