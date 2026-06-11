package com.relic.websearch;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class WebPageTextExtractor {

    public WebPageContent extract(String url,
                                  String requestTitle,
                                  String requestSnippet,
                                  String keyword,
                                  Document document,
                                  int maxContentChars) {
        document.select("script, style, noscript, svg, canvas, nav, header, footer, form, aside").remove();

        String title = firstText(requestTitle, meta(document, "meta[property=og:title]"), document.title(), url);
        Element main = document.selectFirst("article, main, [role=main], .article, .post, .entry-content, .content");
        if (main == null && document.body() != null) {
            main = document.body();
        }

        String content = main == null ? "" : normalizeText(main.wholeText());
        if (!StringUtils.hasText(content) && document.body() != null) {
            content = normalizeText(document.body().text());
        }
        if (content.length() > maxContentChars) {
            content = content.substring(0, maxContentChars).trim() + "\n\n（网页内容较长，已截取前 " + maxContentChars + " 字符用于演示。）";
        }

        return WebPageContent.builder()
                .url(url)
                .title(title)
                .snippet(firstText(requestSnippet, meta(document, "meta[name=description]"), ""))
                .keyword(keyword == null ? "" : keyword.trim())
                .content(content)
                .build();
    }

    private String meta(Document document, String cssQuery) {
        Element element = document.selectFirst(cssQuery);
        if (element == null) {
            return "";
        }
        return element.attr("content");
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00A0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }
}
