package com.relic.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.websearch.dto.WebSearchResult;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class WikipediaFallbackSearchService {

    private static final String OPENSEARCH_URL = "https://%s.wikipedia.org/w/api.php";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${relic.web-search.timeout-ms:12000}")
    private int timeoutMs;

    @Value("${relic.web-search.user-agent:Mozilla/5.0 (compatible; RelicBot/1.0)}")
    private String userAgent;

    @Value("${relic.web-search.max-results:8}")
    private int maxResults;

    public List<WebSearchResult> search(String keyword, Integer requestedLimit) throws IOException {
        String normalizedKeyword = normalizeKeyword(keyword);
        int limit = normalizeLimit(requestedLimit);
        List<WebSearchResult> results = searchLanguage(normalizedKeyword, limit, containsCjk(normalizedKeyword) ? "zh" : "en");
        if (!results.isEmpty()) {
            return results;
        }
        return searchLanguage(normalizedKeyword, limit, containsCjk(normalizedKeyword) ? "en" : "zh");
    }

    private List<WebSearchResult> searchLanguage(String keyword, int limit, String language) throws IOException {
        try {
            Connection.Response response = Jsoup.connect(String.format(OPENSEARCH_URL, language))
                    .userAgent(userAgent)
                    .timeout(Math.max(3000, timeoutMs))
                    .ignoreContentType(true)
                    .data("action", "opensearch")
                    .data("search", keyword)
                    .data("limit", String.valueOf(limit))
                    .data("namespace", "0")
                    .data("format", "json")
                    .execute();

            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            JsonNode titles = root.size() > 1 ? root.get(1) : null;
            JsonNode descriptions = root.size() > 2 ? root.get(2) : null;
            JsonNode urls = root.size() > 3 ? root.get(3) : null;
            if (titles == null || urls == null || !titles.isArray() || !urls.isArray()) {
                return List.of();
            }

            List<WebSearchResult> results = new ArrayList<>();
            int count = Math.min(Math.min(titles.size(), urls.size()), limit);
            for (int i = 0; i < count; i++) {
                String title = titles.get(i).asText("").trim();
                String url = urls.get(i).asText("").trim();
                String snippet = descriptions != null && descriptions.size() > i
                        ? descriptions.get(i).asText("").trim()
                        : "";
                if (!StringUtils.hasText(snippet)) {
                    snippet = "Wikipedia source";
                }
                if (StringUtils.hasText(title) && isHttpUrl(url)) {
                    results.add(WebSearchResult.builder()
                            .id(String.valueOf(i + 1))
                            .title(title)
                            .url(url)
                            .snippet(snippet)
                            .summary(snippet)
                            .siteName("Wikipedia")
                            .score(relevanceScore(keyword, title, snippet, i))
                            .build());
                }
            }
            return results;
        } catch (Exception e) {
            throw new IOException("Wikipedia fallback 搜索失败: " + e.getMessage(), e);
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

    private boolean isHttpUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private double relevanceScore(String keyword, String title, String snippet, int sequence) {
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        String lowerSnippet = snippet.toLowerCase(Locale.ROOT);
        double score = Math.max(0.0, 1.0 - sequence * 0.04);
        for (String term : terms(keyword)) {
            if (lowerTitle.contains(term)) {
                score += 3.0;
            }
            if (lowerSnippet.contains(term)) {
                score += 1.8;
            }
        }
        return Math.round(score * 10000.0) / 10000.0;
    }

    private List<String> terms(String keyword) {
        String lower = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        for (String raw : lower.split("[\\s,;，；、]+")) {
            String term = raw.trim();
            if (term.length() >= 2) {
                terms.add(term);
            }
        }
        if (terms.isEmpty() && lower.length() >= 2) {
            terms.add(lower);
        }
        return terms;
    }

    private boolean containsCjk(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return true;
            }
        }
        return false;
    }
}
