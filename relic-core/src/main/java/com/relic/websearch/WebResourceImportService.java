package com.relic.websearch;

import com.relic.resource.SavedResource;
import com.relic.resource.WorkspaceResourceService;
import com.relic.websearch.dto.ImportWebResourceRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class WebResourceImportService {

    private final WebPageFetchService webPageFetchService;
    private final WorkspaceResourceService workspaceResourceService;

    public SavedResource importResource(ImportWebResourceRequest request) throws IOException {
        if (request == null || !StringUtils.hasText(request.getUrl())) {
            throw new IllegalArgumentException("url 不能为空");
        }

        WebPageContent content = webPageFetchService.fetch(
                request.getUrl(),
                request.getTitle(),
                request.getSnippet(),
                request.getKeyword());

        return workspaceResourceService.saveWebResource(
                content.getTitle(),
                content.getUrl(),
                content.getSnippet(),
                content.getKeyword(),
                content.getContent());
    }
}
