package com.relic.websearch;

import com.relic.resource.SavedResource;
import com.relic.resource.WorkspaceResourceService;
import com.relic.websearch.dto.ImportWebResourceRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebResourceImportService {

    private final WebPageFetchService webPageFetchService;
    private final WorkspaceResourceService workspaceResourceService;

    public SavedResource importResource(ImportWebResourceRequest request) throws IOException {
        if (request == null || !StringUtils.hasText(request.getUrl())) {
            throw new IllegalArgumentException("url 不能为空");
        }

        WebPageContent content;
        try {
            content = webPageFetchService.fetch(
                    request.getUrl(),
                    request.getTitle(),
                    request.getSnippet(),
                    request.getKeyword());
        } catch (IllegalArgumentException e) {
            content = buildFallbackContent(request, e);
        }

        return workspaceResourceService.saveWebResource(
                content.getTitle(),
                content.getUrl(),
                content.getSnippet(),
                content.getKeyword(),
                content.getContent());
    }

    private WebPageContent buildFallbackContent(ImportWebResourceRequest request, IllegalArgumentException fetchError) {
        String fallbackText = firstText(request.getSummary(), request.getSnippet());
        if (!StringUtils.hasText(fallbackText)) {
            throw fetchError;
        }

        log.warn("网页正文抓取失败，使用搜索摘要导入: url={}, reason={}", request.getUrl(), fetchError.getMessage());

        String title = firstText(request.getTitle(), request.getUrl());
        String snippet = firstText(request.getSnippet(), request.getSummary());
        StringBuilder content = new StringBuilder();
        if (StringUtils.hasText(request.getSiteName())) {
            content.append("站点: ").append(request.getSiteName().trim()).append("\n");
        }
        if (StringUtils.hasText(request.getDatePublished())) {
            content.append("发布日期: ").append(request.getDatePublished().trim()).append("\n");
        }
        if (content.length() > 0) {
            content.append("\n");
        }
        content.append(fallbackText.trim());

        return WebPageContent.builder()
                .url(request.getUrl())
                .title(title)
                .snippet(snippet)
                .keyword(request.getKeyword())
                .content(content.toString())
                .build();
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
