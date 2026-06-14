package com.relic.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.websearch.dto.WebSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class DuckDuckGoSearchService {

    private static final String SEARCH_URL = "https://duckduckgo.com/html/";
    private static final String WIKIPEDIA_OPENSEARCH_URL = "https://%s.wikipedia.org/w/api.php";
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

        try {
            Document document = Jsoup.connect(SEARCH_URL)
                    .userAgent(userAgent)
                    .timeout(Math.max(3000, timeoutMs))
                    .data("q", normalizedKeyword)
                    .get();
            List<WebSearchResult> results = parseResults(document, normalizedKeyword, limit);
            if (!results.isEmpty()) {
                return results;
            }
            log.info("DuckDuckGo 搜索结果为空，尝试 Wikipedia fallback: keyword={}", normalizedKeyword);
            return searchWikipedia(normalizedKeyword, limit);
        } catch (Exception e) {
            log.warn("DuckDuckGo 搜索失败，尝试 Wikipedia fallback: keyword={}, reason={}", normalizedKeyword, e.getMessage());
            try {
                return searchWikipedia(normalizedKeyword, limit);
            } catch (Exception fallbackError) {
                throw new IOException("联网搜索失败: " + fallbackError.getMessage(), fallbackError);
            }
        }
    }

    List<WebSearchResult> parseResults(Document document, String keyword, int limit) {
        List<ResultCandidate> candidates = new ArrayList<>();
        List<Element> resultElements = document.select("div.result, div.web-result, .result");
        int sequence = 0;
        for (Element result : resultElements) {
            Element titleEl = result.selectFirst("a.result__a, h2 a, a[data-testid=result-title-a]");
            if (titleEl == null) {
                continue;
            }

            String title = titleEl.text().trim();
            String url = normalizeDuckDuckGoUrl(titleEl.attr("href"));
            if (!StringUtils.hasText(title) || !isHttpUrl(url)) {
                continue;
            }

            Element snippetEl = result.selectFirst(".result__snippet, a.result__snippet, .result__body, .snippet");
            String snippet = snippetEl == null ? "" : snippetEl.text().trim();
            double score = relevanceScore(keyword, title, snippet, url, sequence);
            candidates.add(new ResultCandidate(title, url, snippet, score, sequence));
            sequence++;
        }

        return rankAndFilter(candidates, limit);
    }

    private List<WebSearchResult> rankAndFilter(List<ResultCandidate> candidates, int limit) {
        Map<String, ResultCandidate> dedupedByUrl = new LinkedHashMap<>();
        for (ResultCandidate candidate : candidates) {
            dedupedByUrl.merge(candidate.url(), candidate, (oldValue, newValue) ->
                    newValue.score() > oldValue.score() ? newValue : oldValue);
        }

        Map<String, Integer> hostCounts = new HashMap<>();
        List<ResultCandidate> ranked = dedupedByUrl.values().stream()
                .sorted(Comparator
                        .comparingDouble(ResultCandidate::score).reversed()
                        .thenComparingInt(ResultCandidate::sequence))
                .filter(candidate -> {
                    String host = hostOf(candidate.url());
                    int count = hostCounts.getOrDefault(host, 0);
                    if (count >= 2) {
                        return false;
                    }
                    hostCounts.put(host, count + 1);
                    return true;
                })
                .limit(limit)
                .toList();

        List<WebSearchResult> results = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            ResultCandidate candidate = ranked.get(i);
            results.add(WebSearchResult.builder()
                    .id(String.valueOf(i + 1))
                    .title(candidate.title())
                    .url(candidate.url())
                    .snippet(candidate.snippet())
                    .score(candidate.score())
                    .build());
        }
        return results;
    }

    private double relevanceScore(String keyword, String title, String snippet, String url, int sequence) {
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        String lowerSnippet = snippet.toLowerCase(Locale.ROOT);
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        double score = Math.max(0.0, 1.0 - sequence * 0.04);

        for (String term : terms(keyword)) {
            if (lowerTitle.contains(term)) {
                score += 3.0;
            }
            if (lowerSnippet.contains(term)) {
                score += 1.8;
            }
            if (lowerUrl.contains(term)) {
                score += 0.6;
            }
        }
        return Math.round(score * 10000.0) / 10000.0;
    }

    private List<String> terms(String keyword) {
        String lower = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        for (String raw : lower.split("[\\s,，。.;；:：!！?？]+")) {
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

    private String normalizeDuckDuckGoUrl(String href) {
        if (!StringUtils.hasText(href)) {
            return "";
        }

        String clean = href.trim();
        if (clean.startsWith("//")) {
            clean = "https:" + clean;
        } else if (clean.startsWith("/")) {
            clean = "https://duckduckgo.com" + clean;
        }

        try {
            URI uri = URI.create(clean);
            boolean duckDuckGoRedirect = "duckduckgo.com".equalsIgnoreCase(uri.getHost())
                    && uri.getPath() != null
                    && uri.getPath().startsWith("/l/");
            String query = uri.getRawQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    int idx = part.indexOf('=');
                    if (idx <= 0) {
                        continue;
                    }
                    String name = URLDecoder.decode(part.substring(0, idx), StandardCharsets.UTF_8);
                    if ("uddg".equals(name)) {
                        return URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8);
                    }
                }
            }
            if (duckDuckGoRedirect) {
                return "";
            }
        } catch (Exception ignored) {
            return "";
        }
        return clean;
    }

    private boolean isHttpUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
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

    private List<WebSearchResult> searchWikipedia(String keyword, int limit) throws IOException {
        List<WebSearchResult> results = searchWikipediaLanguage(keyword, limit, containsCjk(keyword) ? "zh" : "en");
        if (!results.isEmpty()) {
            return results;
        }
        String fallbackLanguage = containsCjk(keyword) ? "en" : "zh";
        return searchWikipediaLanguage(keyword, limit, fallbackLanguage);
    }

    private List<WebSearchResult> searchWikipediaLanguage(String keyword, int limit, String language) throws IOException {
        try {
            Connection.Response response = Jsoup.connect(String.format(WIKIPEDIA_OPENSEARCH_URL, language))
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

            List<ResultCandidate> candidates = new ArrayList<>();
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
                    candidates.add(new ResultCandidate(title, url, snippet, relevanceScore(keyword, title, snippet, url, i), i));
                }
            }
            return rankAndFilter(candidates, limit);
        } catch (Exception e) {
            throw new IOException("Wikipedia fallback 搜索失败: " + e.getMessage(), e);
        }
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

    private record ResultCandidate(String title, String url, String snippet, double score, int sequence) {
    }

}
