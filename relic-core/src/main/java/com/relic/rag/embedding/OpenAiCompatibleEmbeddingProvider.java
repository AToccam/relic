package com.relic.rag.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI 兼容 Embedding Provider。
 *
 * <p>使用标准 /v1/embeddings 接口，可兼容 OpenAI、DeepSeek、Ollama(OpenAI 兼容模式) 等。
 */
@Slf4j
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private final RestTemplate restTemplate;
    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final int configuredDimensions;
    private final AtomicInteger runtimeDimensions = new AtomicInteger(-1);

    public OpenAiCompatibleEmbeddingProvider(RestTemplate restTemplate,
                                             String endpoint,
                                             String model,
                                             String apiKey,
                                             int configuredDimensions) {
        this.restTemplate = restTemplate;
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey;
        this.configuredDimensions = configuredDimensions;
    }

    @Override
    public String getName() {
        return "openai-compatible";
    }

    @Override
    public int dimensions() {
        int runtime = runtimeDimensions.get();
        if (runtime > 0) {
            return runtime;
        }
        return configuredDimensions;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Double> embed(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("Embedding API Key 未配置，请检查 relic.rag.embedding.openai-compatible.api-key");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", model,
                "input", text
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        Map<String, Object> response = restTemplate.postForObject(endpoint, entity, Map.class);
        if (response == null) {
            throw new IllegalStateException("Embedding API 返回为空");
        }

        Object dataObj = response.get("data");
        if (!(dataObj instanceof List<?> dataList) || dataList.isEmpty()) {
            throw new IllegalStateException("Embedding API 返回 data 为空");
        }

        Object firstObj = dataList.get(0);
        if (!(firstObj instanceof Map<?, ?> firstMap)) {
            throw new IllegalStateException("Embedding API 返回格式异常: data[0]");
        }

        Object embeddingObj = ((Map<String, Object>) firstMap).get("embedding");
        if (!(embeddingObj instanceof List<?> rawVector) || rawVector.isEmpty()) {
            throw new IllegalStateException("Embedding API 返回 embedding 为空");
        }

        List<Double> vector = new ArrayList<>(rawVector.size());
        for (Object item : rawVector) {
            if (item instanceof Number number) {
                vector.add(number.doubleValue());
            } else {
                try {
                    vector.add(Double.parseDouble(String.valueOf(item)));
                } catch (Exception e) {
                    throw new IllegalStateException("Embedding 向量存在非数字元素: " + item);
                }
            }
        }

        validateDimensions(vector.size());
        return vector;
    }

    @Override
    public List<List<Double>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<List<Double>> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }

    private void validateDimensions(int actualDimensions) {
        if (configuredDimensions > 0 && actualDimensions != configuredDimensions) {
            throw new IllegalStateException(
                    "Embedding 维度不匹配，期望=" + configuredDimensions + "，实际=" + actualDimensions);
        }

        int current = runtimeDimensions.get();
        if (current < 0) {
            runtimeDimensions.compareAndSet(-1, actualDimensions);
            current = runtimeDimensions.get();
            log.info("【RAG】Embedding 运行时维度确认: provider={}, model={}, dimensions={}",
                    getName(), model, current);
        }

        if (current != actualDimensions) {
            throw new IllegalStateException(
                    "Embedding 维度在运行时发生变化，历史=" + current + "，当前=" + actualDimensions);
        }
    }
}
