package com.relic.rag.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.rag.model.DocumentChunk;
import com.relic.rag.model.RetrievalResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Chroma HTTP API 的向量存储实现。
 */
@Slf4j
public class ChromaVectorStore implements VectorStore {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiPrefix;
    private final String collectionName;
    private final int configuredDimensions;
    private final AtomicInteger runtimeDimensions = new AtomicInteger(-1);

    private volatile String collectionId;

    public ChromaVectorStore(RestTemplate restTemplate,
                             ObjectMapper objectMapper,
                             String scheme,
                             String host,
                             int port,
                             String apiPrefix,
                             String collectionName,
                             int configuredDimensions) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = buildBaseUrl(scheme, host, port);
        this.apiPrefix = normalizeApiPrefix(apiPrefix);
        this.collectionName = collectionName;
        this.configuredDimensions = configuredDimensions;
    }

    @PostConstruct
    public void init() {
        ensureCollectionId();
        log.info("【RAG】ChromaVectorStore 已启用: baseUrl={}, collection={}, dimensions={}",
                baseUrl, collectionName, configuredDimensions);
    }

    @Override
    public String getName() {
        return "chroma";
    }

    @Override
    public void addDocuments(List<DocumentChunk> chunks, List<List<Double>> embeddings) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        if (embeddings == null || embeddings.size() != chunks.size()) {
            throw new IllegalArgumentException("chunks 与 embeddings 数量不匹配");
        }

        String cid = ensureCollectionId();
        List<String> ids = new ArrayList<>(chunks.size());
        List<List<Double>> vectors = new ArrayList<>(chunks.size());
        List<String> documents = new ArrayList<>(chunks.size());
        List<Map<String, Object>> metadatas = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            List<Double> vector = embeddings.get(i);
            if (chunk == null || vector == null || vector.isEmpty()) {
                continue;
            }

            validateDimensions(vector.size());
            String chunkId = chunk.getId() == null || chunk.getId().isBlank()
                    ? chunk.getSourceId() + "#" + chunk.getChunkIndex()
                    : chunk.getId();

            ids.add(chunkId);
            vectors.add(vector);
            documents.add(chunk.getContent() == null ? "" : chunk.getContent());
            metadatas.add(sanitizeMetadata(chunk));
        }

        if (ids.isEmpty()) {
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", ids);
        body.put("embeddings", vectors);
        body.put("documents", documents);
        body.put("metadatas", metadatas);

        HttpEntity<Map<String, Object>> entity = jsonEntity(body);
        String upsertUrl = collectionBaseUrl(cid) + "/upsert";
        try {
            restTemplate.postForObject(upsertUrl, entity, Map.class);
            return;
        } catch (RestClientException e) {
            // 某些 Chroma 版本不支持 upsert，降级到 add。
            String addUrl = collectionBaseUrl(cid) + "/add";
            restTemplate.postForObject(addUrl, entity, Map.class);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RetrievalResult> similaritySearch(List<Double> queryEmbedding, int topK, Set<String> sourceIds) {
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            return List.of();
        }
        validateDimensions(queryEmbedding.size());

        String cid = ensureCollectionId();
        int limit = Math.max(1, topK);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query_embeddings", List.of(queryEmbedding));
        body.put("n_results", limit);
        body.put("include", List.of("ids", "documents", "metadatas", "distances"));

        Set<String> normalizedFilter = normalizeSourceIds(sourceIds);
        if (!normalizedFilter.isEmpty()) {
            body.put("where", buildWhereFilter(normalizedFilter));
        }

        Map<String, Object> response;
        try {
            response = restTemplate.postForObject(
                    collectionBaseUrl(cid) + "/query",
                    jsonEntity(body),
                    Map.class);
        } catch (RestClientException e) {
            // 兼容部分 Chroma 版本 where 语法差异，失败时回退无 where 查询并本地过滤。
            body.remove("where");
            response = restTemplate.postForObject(
                    collectionBaseUrl(cid) + "/query",
                    jsonEntity(body),
                    Map.class);
        }

        if (response == null) {
            return List.of();
        }

        List<List<String>> ids = castNestedStringList(response.get("ids"));
        List<List<String>> documents = castNestedStringList(response.get("documents"));
        List<List<Map<String, Object>>> metadatas = castNestedMapList(response.get("metadatas"));
        List<List<Double>> distances = castNestedDoubleList(response.get("distances"));

        List<String> docList = firstOrEmpty(documents);
        List<String> idList = firstOrEmpty(ids);
        List<Map<String, Object>> metadataList = firstOrEmptyMap(metadatas);
        List<Double> distanceList = firstOrEmpty(distances);

        List<RetrievalResult> results = new ArrayList<>();
        for (int i = 0; i < docList.size(); i++) {
            String content = docList.get(i);
            Map<String, Object> metadata = i < metadataList.size() ? metadataList.get(i) : Map.of();
            String sourceId = metadata.get("sourceId") == null ? "unknown" : String.valueOf(metadata.get("sourceId"));

            if (!normalizedFilter.isEmpty() && !normalizedFilter.contains(sourceId)) {
                continue;
            }

            int chunkIndex = parseChunkIndex(metadata.get("chunkIndex"), i);
            String chunkId = i < idList.size() ? idList.get(i) : sourceId + "#" + chunkIndex;
            double distance = i < distanceList.size() ? distanceList.get(i) : 1.0;
            double score = convertDistanceToScore(distance);

            Map<String, Object> mergedMetadata = new LinkedHashMap<>(metadata);
            mergedMetadata.put("distance", distance);

            DocumentChunk chunk = DocumentChunk.builder()
                    .id(chunkId)
                    .sourceId(sourceId)
                    .chunkIndex(chunkIndex)
                    .content(content)
                    .metadata(mergedMetadata)
                    .build();

            results.add(RetrievalResult.builder()
                    .chunk(chunk)
                    .score(score)
                    .build());
        }

        results.sort(Comparator.comparingDouble(RetrievalResult::getScore).reversed());
        if (results.size() <= limit) {
            return results;
        }
        return results.subList(0, limit);
    }

    @Override
    public void deleteBySourceId(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return;
        }
        try {
            String cid = ensureCollectionId();
            Map<String, Object> body = Map.of("where", Map.of("sourceId", sourceId));
            restTemplate.postForObject(collectionBaseUrl(cid) + "/delete", jsonEntity(body), Map.class);
        } catch (Exception e) {
            log.warn("【RAG】Chroma 删除 sourceId 失败，sourceId={}, reason={}", sourceId, e.getMessage());
        }
    }

    private synchronized String ensureCollectionId() {
        if (collectionId != null && !collectionId.isBlank()) {
            return collectionId;
        }

        String found = findCollectionIdByName();
        if (found != null) {
            this.collectionId = found;
            return found;
        }

        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("name", collectionName);
        createBody.put("metadata", Map.of("hnsw:space", "cosine"));

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> created = restTemplate.postForObject(
                    collectionRootUrl(), jsonEntity(createBody), Map.class);
            String createdId = extractCollectionId(created);
            if (createdId != null) {
                this.collectionId = createdId;
                return createdId;
            }
        } catch (RestClientException ignored) {
            // 并发启动时可能已被其他实例创建，继续查询。
        }

        found = findCollectionIdByName();
        if (found != null) {
            this.collectionId = found;
            return found;
        }

        throw new IllegalStateException("无法初始化 Chroma 集合: " + collectionName);
    }

    @SuppressWarnings("unchecked")
    private String findCollectionIdByName() {
        try {
            Object resp = restTemplate.getForObject(collectionRootUrl(), Object.class);
            if (resp instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> map)) {
                        continue;
                    }
                    String name = map.get("name") == null ? "" : String.valueOf(map.get("name"));
                    if (collectionName.equals(name)) {
                        return extractCollectionId((Map<String, Object>) map);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("【RAG】查询 Chroma 集合列表失败: {}", e.getMessage());
        }
        return null;
    }

    private String extractCollectionId(Map<String, Object> collection) {
        if (collection == null) {
            return null;
        }
        Object id = collection.get("id");
        if (id != null) {
            return String.valueOf(id);
        }
        Object uuid = collection.get("uuid");
        if (uuid != null) {
            return String.valueOf(uuid);
        }
        return null;
    }

    private Map<String, Object> buildWhereFilter(Set<String> sourceIds) {
        if (sourceIds.size() == 1) {
            return Map.of("sourceId", sourceIds.iterator().next());
        }
        return Map.of("sourceId", Map.of("$in", new ArrayList<>(sourceIds)));
    }

    private Set<String> normalizeSourceIds(Set<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String sourceId : sourceIds) {
            if (sourceId == null || sourceId.isBlank()) {
                continue;
            }
            normalized.add(sourceId.trim().replace('\\', '/'));
        }
        return normalized;
    }

    private void validateDimensions(int dimensions) {
        if (configuredDimensions > 0 && dimensions != configuredDimensions) {
            throw new IllegalStateException("向量维度不匹配，期望=" + configuredDimensions + "，实际=" + dimensions);
        }
        int current = runtimeDimensions.get();
        if (current < 0) {
            runtimeDimensions.compareAndSet(-1, dimensions);
            current = runtimeDimensions.get();
            log.info("【RAG】Chroma 运行时维度确认: {}", current);
        }
        if (current != dimensions) {
            throw new IllegalStateException("向量维度在运行时发生变化，历史=" + current + "，当前=" + dimensions);
        }
    }

    private double convertDistanceToScore(double distance) {
        if (Double.isNaN(distance) || Double.isInfinite(distance)) {
            return 0.0;
        }
        // Chroma 常见返回为距离，余弦空间下越小越相似。
        double score = 1.0 - distance;
        if (score < 0) {
            return 0;
        }
        if (score > 1) {
            return 1;
        }
        return score;
    }

    private Map<String, Object> sanitizeMetadata(DocumentChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceId", chunk.getSourceId());
        metadata.put("chunkIndex", chunk.getChunkIndex());

        if (chunk.getMetadata() != null) {
            for (Map.Entry<String, Object> entry : chunk.getMetadata().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (key == null || key.isBlank()) {
                    continue;
                }
                metadata.put(key, normalizeMetadataValue(value));
            }
        }
        return metadata;
    }

    private Object normalizeMetadataValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private int parseChunkIndex(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private List<List<String>> castNestedStringList(Object value) {
        if (!(value instanceof List<?> outer)) {
            return List.of();
        }

        List<List<String>> result = new ArrayList<>();
        for (Object item : outer) {
            if (!(item instanceof List<?> inner)) {
                continue;
            }
            List<String> line = new ArrayList<>();
            for (Object innerItem : inner) {
                line.add(innerItem == null ? "" : String.valueOf(innerItem));
            }
            result.add(line);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<List<Map<String, Object>>> castNestedMapList(Object value) {
        if (!(value instanceof List<?> outer)) {
            return List.of();
        }

        List<List<Map<String, Object>>> result = new ArrayList<>();
        for (Object item : outer) {
            if (!(item instanceof List<?> inner)) {
                continue;
            }
            List<Map<String, Object>> line = new ArrayList<>();
            for (Object innerItem : inner) {
                if (innerItem instanceof Map<?, ?> map) {
                    line.add(new HashMap<>((Map<String, Object>) map));
                } else {
                    line.add(Map.of());
                }
            }
            result.add(line);
        }
        return result;
    }

    private List<List<Double>> castNestedDoubleList(Object value) {
        if (!(value instanceof List<?> outer)) {
            return List.of();
        }

        List<List<Double>> result = new ArrayList<>();
        for (Object item : outer) {
            if (!(item instanceof List<?> inner)) {
                continue;
            }
            List<Double> line = new ArrayList<>();
            for (Object innerItem : inner) {
                if (innerItem instanceof Number number) {
                    line.add(number.doubleValue());
                } else {
                    try {
                        line.add(Double.parseDouble(String.valueOf(innerItem)));
                    } catch (Exception ignored) {
                        line.add(1.0);
                    }
                }
            }
            result.add(line);
        }
        return result;
    }

    private String collectionRootUrl() {
        return baseUrl + apiPrefix + "/collections";
    }

    private String collectionBaseUrl(String collectionId) {
        return collectionRootUrl() + "/" + collectionId;
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String buildBaseUrl(String scheme, String host, int port) {
        String safeScheme = (scheme == null || scheme.isBlank()) ? "http" : scheme.toLowerCase(Locale.ROOT);
        String safeHost = (host == null || host.isBlank()) ? "127.0.0.1" : host;
        int safePort = port > 0 ? port : 8000;
        return safeScheme + "://" + safeHost + ":" + safePort;
    }

    private String normalizeApiPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "/api/v1";
        }
        String trimmed = prefix.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private <T> List<T> firstOrEmpty(List<List<T>> nested) {
        if (nested == null || nested.isEmpty() || nested.get(0) == null) {
            return List.of();
        }
        return nested.get(0);
    }

    private List<Map<String, Object>> firstOrEmptyMap(List<List<Map<String, Object>>> nested) {
        if (nested == null || nested.isEmpty() || nested.get(0) == null) {
            return List.of();
        }
        return nested.get(0);
    }
}
