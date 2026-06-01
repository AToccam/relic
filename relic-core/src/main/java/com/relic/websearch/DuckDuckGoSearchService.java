package com.relic.websearch;

import com.relic.websearch.dto.WebSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
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
            return parseResults(document, normalizedKeyword, limit);
        } catch (Exception e) {
            log.warn("DuckDuckGo 搜索失败: keyword={}, reason={}", normalizedKeyword, e.getMessage());
            throw new IOException("DuckDuckGo 搜索失败: " + e.getMessage(), e);
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

    private record ResultCandidate(String title, String url, String snippet, double score, int sequence) {
    }

}
