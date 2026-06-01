package com.relic.websearch;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebPageTextExtractorTest {

    @Test
    void extractPrefersArticleTextAndRemovesNoisyElements() {
        String html = """
                <html>
                  <head>
                    <title>页面标题</title>
                    <meta name="description" content="页面摘要">
                  </head>
                  <body>
                    <nav>导航内容</nav>
                    <script>alert('x')</script>
                    <article>
                      <h1>正文标题</h1>
                      <p>这里是第一段正文。</p>
                      <p>这里是第二段正文。</p>
                    </article>
                  </body>
                </html>
                """;

        Document document = Jsoup.parse(html, "https://example.com/article");
        WebPageContent content = new WebPageTextExtractor()
                .extract("https://example.com/article", "", "", "测试", document, 500);

        assertTrue(content.getContent().contains("这里是第一段正文"));
        assertTrue(content.getContent().contains("这里是第二段正文"));
        assertFalse(content.getContent().contains("导航内容"));
        assertFalse(content.getContent().contains("alert"));
    }
}
