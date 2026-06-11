package com.relic.websearch;

import com.relic.websearch.dto.WebSearchResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDuckGoSearchServiceTest {

    @Test
    void parseResultsDecodesDuckDuckGoRedirectAndRanksRelevantItems() {
        String encodedUrl = URLEncoder.encode("https://example.com/rag-guide", StandardCharsets.UTF_8);
        String html = """
                <html><body>
                  <div class="result">
                    <a class="result__a" href="/l/?uddg=%s">Spring Boot RAG 教程</a>
                    <a class="result__snippet">Spring Boot 接入 RAG 和文件索引的完整教程。</a>
                  </div>
                  <div class="result">
                    <a class="result__a" href="https://other.example.com/news">无关新闻</a>
                    <a class="result__snippet">今天的天气很好。</a>
                  </div>
                  <div class="result">
                    <a class="result__a" href="https://example.com/rag-guide">重复结果</a>
                    <a class="result__snippet">重复 URL 应该被去重。</a>
                  </div>
                  <div class="result">
                    <a class="result__a" href="/l/?ad=unexpected">DuckDuckGo 广告跳转</a>
                    <a class="result__snippet">没有 uddg 的跳转不应进入结果。</a>
                  </div>
                </body></html>
                """.formatted(encodedUrl);

        Document document = Jsoup.parse(html);
        List<WebSearchResult> results = new DuckDuckGoSearchService()
                .parseResults(document, "Spring Boot RAG 教程", 5);

        assertEquals(2, results.size());
        assertEquals("https://example.com/rag-guide", results.getFirst().getUrl());
        assertTrue(results.getFirst().getScore() > results.get(1).getScore());
        assertTrue(results.stream().noneMatch(item -> item.getUrl().contains("duckduckgo.com/l/")));
    }
}
