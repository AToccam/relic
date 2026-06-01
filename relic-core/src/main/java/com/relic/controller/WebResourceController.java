package com.relic.controller;

import com.relic.resource.SavedResource;
import com.relic.resource.WorkspaceResourceService;
import com.relic.websearch.DuckDuckGoSearchService;
import com.relic.websearch.WebResourceImportService;
import com.relic.websearch.dto.ImportWebResourceRequest;
import com.relic.websearch.dto.WebSearchRequest;
import com.relic.websearch.dto.WebSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/web-resources")
@RequiredArgsConstructor
public class WebResourceController {

    private final DuckDuckGoSearchService duckDuckGoSearchService;
    private final WebResourceImportService webResourceImportService;
    private final WorkspaceResourceService workspaceResourceService;

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody WebSearchRequest request) throws IOException {
        String keyword = requireKeyword(request == null ? null : request.getKeyword());
        List<WebSearchResult> results = duckDuckGoSearchService.search(keyword, request == null ? null : request.getLimit());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("keyword", keyword);
        response.put("items", results);
        return response;
    }

    @PostMapping("/import")
    public Map<String, Object> importResource(@RequestBody ImportWebResourceRequest request) throws IOException {
        SavedResource savedResource = webResourceImportService.importResource(request);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.putAll(workspaceResourceService.toResponseMap(savedResource));
        response.put("sourceId", savedResource.metadata().getRelativePath());
        return response;
    }

    private String requireKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            throw new IllegalArgumentException("keyword 不能为空");
        }
        return keyword.trim();
    }
}
