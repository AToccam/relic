package com.relic.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.websearch.dto.WebSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class BochaSearchService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DomesticBaikeFallbackSearchService domesticBaikeFallbackSearchService;

    @Value("${relic.web-search.bocha.api-key:${BOCHA_API_KEY:}}")
    private String apiKey;

    @Value("${relic.web-search.bocha.url:https://api.bochaai.com/v1/web-search}")
    private String apiUrl;

    @Value("${relic.web-search.bocha.freshness:noLimit}")
    private String freshness;

    @Value("${relic.web-search.timeout-ms:12000}")
    private int timeoutMs;

    @Value("${relic.web-search.max-results:8}")
    private int maxResults;

    public List<WebSearchResult> search(String keyword, Integer requestedLimit) throws IOException {
        String normalizedKeyword = normalizeKeyword(keyword);
        int limit = normalizeLimit(requestedLimit);

        if (!StringUtils.hasText(apiKey)) {
            log.warn("Bocha API key 未配置，使用 360百科兜底: keyword={}", normalizedKeyword);
            return domesticBaikeFallbackSearchService.search(normalizedKeyword, limit);
        }

        try {
            List<WebSearchResult> results = searchBocha(normalizedKeyword, limit);
            if (!results.isEmpty()) {
                appendDomesticBaikeResults(results, normalizedKeyword, limit);
                return results;
            }
            log.info("Bocha 搜索结果为空，使用 360百科兜底: keyword={}", normalizedKeyword);
            return domesticBaikeFallbackSearchService.search(normalizedKeyword, limit);
        } catch (Exception e) {
            log.warn("Bocha 搜索失败，使用 360百科兜底: keyword={}, reason={}", normalizedKeyword, e.getMessage());
            List<WebSearchResult> fallback = domesticBaikeFallbackSearchService.search(normalizedKeyword, limit);
            if (!fallback.isEmpty()) {
                return fallback;
            }
            throw e instanceof IOException io ? io : new IOException("Bocha 搜索失败: " + e.getMessage(), e);
        }
    }

    private List<WebSearchResult> searchBocha(String keyword, int limit) throws IOException, InterruptedException {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("query", keyword);
        requestBody.put("freshness", StringUtils.hasText(freshness) ? freshness : "noLimit");
        requestBody.put("summary", true);
        requestBody.put("count", limit);

        String jsonBody = OBJECT_MAPPER.writeValueAsString(requestBody);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(3000, timeoutMs)))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofMillis(Math.max(3000, timeoutMs)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey.trim())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(readableBochaError(response.statusCode(), response.body()));
        }

        JsonNode root = OBJECT_MAPPER.readTree(response.body());
        JsonNode codeNode = root.get("code");
        if (codeNode != null && codeNode.isNumber()) {
            int code = codeNode.asInt();
            if (code != 0 && code != 200) {
                throw new IOException(readableBochaError(code, response.body()));
            }
        }

        JsonNode values = root.path("data").path("webPages").path("value");
        if (!values.isArray()) {
            return List.of();
        }

        List<WebSearchResult> results = new ArrayList<>();
        int sequence = 0;
        for (JsonNode item : values) {
            String title = firstText(text(item, "name"), text(item, "title"));
            String url = text(item, "url");
            String snippet = firstText(text(item, "snippet"), text(item, "summary"));
            String summary = text(item, "summary");
            String siteName = text(item, "siteName");
            String datePublished = firstText(text(item, "datePublished"), text(item, "dateLastCrawled"));

            if (!StringUtils.hasText(title) || !isHttpUrl(url)) {
                continue;
            }

            results.add(WebSearchResult.builder()
                    .id(firstText(text(item, "id"), String.valueOf(sequence + 1)))
                    .title(title)
                    .url(url)
                    .snippet(snippet)
                    .summary(summary)
                    .siteName(siteName)
                    .datePublished(datePublished)
                    .score(score(item, keyword, title, snippet, url, sequence))
                    .build());
            sequence++;
            if (results.size() >= limit) {
                break;
            }
        }
        return results;
    }

    private void appendDomesticBaikeResults(List<WebSearchResult> results, String keyword, int limit) {
        try {
            List<WebSearchResult> domesticBaikeResults = domesticBaikeFallbackSearchService.search(keyword, limit);
            if (domesticBaikeResults.isEmpty()) {
                return;
            }

            Set<String> existingUrls = new HashSet<>();
            for (WebSearchResult result : results) {
                existingUrls.add(normalizeUrl(result.getUrl()));
            }

            for (WebSearchResult domesticBaikeResult : domesticBaikeResults) {
                String normalizedUrl = normalizeUrl(domesticBaikeResult.getUrl());
                if (existingUrls.add(normalizedUrl)) {
                    results.add(domesticBaikeResult);
                }
            }
        } catch (Exception e) {
            log.warn("360百科追加搜索失败: keyword={}, reason={}", keyword, e.getMessage());
        }
    }

    private String readableBochaError(int status, String body) {
        String detail = extractErrorDetail(body);
        String prefix = switch (status) {
            case 401 -> "Bocha API Key 无效或未授权";
            case 403 -> "Bocha API 无权限或余额不足";
            case 429 -> "Bocha API 请求过于频繁";
            default -> status >= 500 ? "Bocha API 服务异常" : "Bocha API 请求失败";
        };
        return prefix + " (HTTP " + status + ")" + (StringUtils.hasText(detail) ? ": " + detail : "");
    }

    private String extractErrorDetail(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            return firstText(
                    root.path("message").asText(""),
                    root.path("msg").asText(""),
                    root.path("error").path("message").asText(""),
                    root.path("error").asText(""),
                    root.path("log_id").asText(""));
        } catch (Exception ignored) {
            return body.length() > 300 ? body.substring(0, 300) : body;
        }
    }

    private String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            throw new IllegalArgumentException("keyword 不能为空");
        }
        return keyword.trim();
    }

    private int normalizeLimit(Integer requestedLimit) {
        int configuredMax = Math.max(1, maxResults);
        int limit = requestedLimit == null ? configuredMax : requestedLimit;
        return Math.max(1, Math.min(limit, configuredMax));
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean isHttpUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String normalizeUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        String normalized = url.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private double score(JsonNode item, String keyword, String title, String snippet, String url, int sequence) {
        JsonNode scoreNode = item.get("score");
        if (scoreNode != null && scoreNode.isNumber()) {
            return scoreNode.asDouble();
        }

        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        String lowerSnippet = snippet.toLowerCase(Locale.ROOT);
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        double score = Math.max(0.0, 1.0 - sequence * 0.04);
        if (lowerTitle.contains(lowerKeyword)) {
            score += 3.0;
        }
        if (lowerSnippet.contains(lowerKeyword)) {
            score += 1.8;
        }
        if (lowerUrl.contains(lowerKeyword)) {
            score += 0.6;
        }
        return Math.round(score * 10000.0) / 10000.0;
    }
}
