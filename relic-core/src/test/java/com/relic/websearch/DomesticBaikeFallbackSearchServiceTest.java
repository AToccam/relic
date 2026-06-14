package com.relic.websearch;

import com.relic.websearch.dto.WebSearchResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomesticBaikeFallbackSearchServiceTest {

    @Test
    void parseResultsExtractsDomesticBaikeDocItemsAndSkipsDuplicates() {
        String html = """
                <html><body>
                  <div class="result">
                    <h3><a href="/doc/493732.html" title="武汉大学_360百科">武汉大学_360百科</a></h3>
                    <p class="summary">武汉大学是教育部直属重点综合性大学。</p>
                  </div>
                  <div class="result">
                    <a href="https://baike.so.com/doc/493732.html?src=search">重复词条</a>
                    <p>重复 URL 不应该进入结果。</p>
                  </div>
                  <div class="result">
                    <a href="https://baike.so.com/doc/1128786-1194141.html">360百科_360百科</a>
                    <p>360百科是专业的中文百科。</p>
                  </div>
                  <div class="result">
                    <a href="https://example.com/doc/1">外部百科</a>
                    <p>非 360百科词条不应该进入结果。</p>
                  </div>
                </body></html>
                """;

        Document document = Jsoup.parse(html, "https://baike.so.com/search/?q=%E6%AD%A6%E6%B1%89%E5%A4%A7%E5%AD%A6");
        List<WebSearchResult> results = new DomesticBaikeFallbackSearchService()
                .parseResults(document, "武汉大学", 5);

        assertEquals(2, results.size());
        assertEquals("武汉大学", results.getFirst().getTitle());
        assertEquals("https://baike.so.com/doc/493732.html", results.getFirst().getUrl());
        assertEquals("360百科", results.getFirst().getSiteName());
        assertTrue(results.getFirst().getSnippet().contains("教育部直属重点综合性大学"));
        assertTrue(results.getFirst().getScore() > results.get(1).getScore());
    }
}
