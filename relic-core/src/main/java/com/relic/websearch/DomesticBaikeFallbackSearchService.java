package com.relic.websearch;

import com.relic.websearch.dto.WebSearchResult;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DomesticBaikeFallbackSearchService {

    private static final String SOURCE_NAME = "360百科";

    @Value("${relic.web-search.domestic-baike.url:https://baike.so.com/search/}")
    private String searchUrl;

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
            Connection.Response response = Jsoup.connect(searchUrl)
                    .userAgent(userAgent)
                    .referrer("https://baike.so.com/")
                    .timeout(Math.max(3000, timeoutMs))
                    .ignoreContentType(true)
                    .followRedirects(true)
                    .data("q", normalizedKeyword)
                    .execute();

            Document document = response.parse();
            return parseResults(document, normalizedKeyword, limit);
        } catch (Exception e) {
            throw new IOException("360百科兜底搜索失败: " + e.getMessage(), e);
        }
    }

    List<WebSearchResult> parseResults(Document document, String keyword, int limit) {
        if (document == null || limit <= 0) {
            return List.of();
        }

        Map<String, WebSearchResult> results = new LinkedHashMap<>();
        for (Element link : document.select("a[href]")) {
            String url = normalizeBaikeUrl(resolveHref(link));
            if (!isBaikeDocUrl(url) || results.containsKey(url)) {
                continue;
            }

            String title = cleanTitle(firstText(link.attr("title"), link.ownText(), link.text()));
            if (!StringUtils.hasText(title) || isBoilerplateTitle(title)) {
                continue;
            }

            String snippet = extractSnippet(link, title);
            int sequence = results.size();
            results.put(url, WebSearchResult.builder()
                    .id("360baike-" + (sequence + 1))
                    .title(title)
                    .url(url)
                    .snippet(snippet)
                    .summary(snippet)
                    .siteName(SOURCE_NAME)
                    .score(relevanceScore(keyword, title, snippet, sequence))
                    .build());

            if (results.size() >= limit) {
                break;
            }
        }

        return new ArrayList<>(results.values());
    }

    private String extractSnippet(Element link, String title) {
        Element container = findResultContainer(link, title);
        String snippet = "";
        if (container != null) {
            snippet = firstNonEmptyText(container.select(".desc, .summary, .abstract, p"));
            if (!StringUtils.hasText(snippet)) {
                snippet = container.text();
            }
        }
        snippet = cleanSnippet(snippet, title);
        return StringUtils.hasText(snippet) ? snippet : SOURCE_NAME + "词条";
    }

    private Element findResultContainer(Element link, String title) {
        Element best = link.parent();
        Element current = link.parent();
        for (int depth = 0; current != null && depth < 6; depth++) {
            String className = current.className().toLowerCase(Locale.ROOT);
            if (className.contains("result") || className.contains("list") || className.contains("item")) {
                return current;
            }

            String text = normalizeWhitespace(current.text());
            int docLinkCount = current.select("a[href*=doc]").size();
            if (text.length() > title.length() + 20 && docLinkCount <= 4) {
                best = current;
            }
            current = current.parent();
        }
        return best;
    }

    private String firstNonEmptyText(Iterable<Element> elements) {
        for (Element element : elements) {
            String text = normalizeWhitespace(element.text());
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return "";
    }

    private String resolveHref(Element link) {
        String href = link.attr("href").trim();
        String absolute = link.absUrl("href");
        if (StringUtils.hasText(absolute)) {
            return absolute;
        }
        if (href.startsWith("//")) {
            return "https:" + href;
        }
        if (href.startsWith("/")) {
            return "https://baike.so.com" + href;
        }
        return href;
    }

    private String normalizeBaikeUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        String normalized = url.trim();
        int fragmentIndex = normalized.indexOf('#');
        if (fragmentIndex >= 0) {
            normalized = normalized.substring(0, fragmentIndex);
        }
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isBaikeDocUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lower.matches("https?://baike\\.so\\.com/doc/\\d.*");
    }

    private String cleanTitle(String raw) {
        String title = normalizeWhitespace(raw);
        title = title.replaceAll("(?i)[_\\-—|]?\\s*360百科\\s*$", "");
        title = title.replace("免费编辑", "");
        title = title.replace("添加义项名", "");
        return normalizeWhitespace(title);
    }

    private boolean isBoilerplateTitle(String title) {
        String normalized = normalizeWhitespace(title);
        return normalized.length() < 2
                || "添加义项".equals(normalized)
                || "编辑".equals(normalized)
                || "锁定".equals(normalized)
                || "账号".equals(normalized)
                || "登录".equals(normalized);
    }

    private String cleanSnippet(String raw, String title) {
        String snippet = normalizeWhitespace(raw);
        if (!StringUtils.hasText(snippet)) {
            return "";
        }
        snippet = snippet.replace(title, "");
        snippet = snippet.replace("360百科", "");
        snippet = snippet.replace("免费编辑", "");
        snippet = snippet.replace("添加义项", "");
        snippet = snippet.replace("锁定", "");
        snippet = normalizeWhitespace(snippet);
        return snippet.length() > 500 ? snippet.substring(0, 500) : snippet;
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

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
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
}
