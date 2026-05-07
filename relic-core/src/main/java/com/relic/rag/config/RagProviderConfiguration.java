package com.relic.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.rag.embedding.EmbeddingProvider;
import com.relic.rag.embedding.HashingEmbeddingProvider;
import com.relic.rag.embedding.OpenAiCompatibleEmbeddingProvider;
import com.relic.rag.vector.ChromaVectorStore;
import com.relic.rag.vector.InMemoryVectorStore;
import com.relic.rag.vector.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RAG Provider 装配配置。
 *
 * <p>通过配置文件切换 EmbeddingProvider 与 VectorStore 实现。
 */
@Configuration
public class RagProviderConfiguration {

    @Bean(name = "ragRestTemplate")
    public RestTemplate ragRestTemplate(@Value("${relic.rag.http.connect-timeout-ms:10000}") int connectTimeoutMs,
                                        @Value("${relic.rag.http.read-timeout-ms:60000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(1000, connectTimeoutMs));
        requestFactory.setReadTimeout(Math.max(1000, readTimeoutMs));
        return new RestTemplate(requestFactory);
    }

    @Bean
    @ConditionalOnProperty(prefix = "relic.rag.embedding", name = "provider", havingValue = "hashing", matchIfMissing = true)
    public EmbeddingProvider hashingEmbeddingProvider() {
        return new HashingEmbeddingProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "relic.rag.embedding", name = "provider", havingValue = "openai-compatible")
    public EmbeddingProvider openAiCompatibleEmbeddingProvider(
            @Qualifier("ragRestTemplate") RestTemplate restTemplate,
            @Value("${relic.rag.embedding.openai-compatible.url:https://api.openai.com/v1/embeddings}") String url,
            @Value("${relic.rag.embedding.openai-compatible.model:text-embedding-3-small}") String model,
            @Value("${relic.rag.embedding.openai-compatible.api-key:${relic.deepseek.api-key:${relic.qwen.api-key:${relic.kimi.api-key:}}}}") String apiKey,
            @Value("${relic.rag.embedding.openai-compatible.dimensions:1536}") int dimensions) {
        return new OpenAiCompatibleEmbeddingProvider(
                restTemplate,
                url,
                model,
                apiKey,
                dimensions);
    }

    @Bean
    @ConditionalOnProperty(prefix = "relic.rag.vector", name = "provider", havingValue = "memory", matchIfMissing = true)
    public VectorStore inMemoryVectorStore() {
        return new InMemoryVectorStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = "relic.rag.vector", name = "provider", havingValue = "chroma")
    public VectorStore chromaVectorStore(
            @Qualifier("ragRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${relic.rag.vector.chroma.scheme:http}") String scheme,
            @Value("${relic.rag.vector.chroma.host:127.0.0.1}") String host,
            @Value("${relic.rag.vector.chroma.port:8000}") int port,
            @Value("${relic.rag.vector.chroma.api-prefix:/api/v1}") String apiPrefix,
            @Value("${relic.rag.vector.chroma.collection-name:relic_documents}") String collectionName,
            @Value("${relic.rag.vector.expected-dimensions:1536}") int expectedDimensions) {
        return new ChromaVectorStore(
                restTemplate,
                objectMapper,
                scheme,
                host,
                port,
                apiPrefix,
                collectionName,
                expectedDimensions);
    }
}
