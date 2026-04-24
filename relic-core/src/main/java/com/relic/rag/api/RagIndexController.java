package com.relic.rag.api;

import com.relic.rag.api.dto.IndexDocumentRequest;
import com.relic.rag.api.dto.IndexStatusResponse;
import com.relic.rag.ingest.DocumentIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RAG 索引管理接口。
 *
 * <p>一期提供触发索引与状态查询能力，供前端文件面板展示进度使用。
 */
@RestController
@RequestMapping("/rag/index")
@RequiredArgsConstructor
public class RagIndexController {

    private final DocumentIngestionService documentIngestionService;

    /**
     * 手动触发文档索引。
     */
    @PostMapping("/manual")
    public Map<String, Object> triggerManual(@RequestBody IndexDocumentRequest request) {
        String sourceId = requireSourceId(request == null ? null : request.getSourceId());
        documentIngestionService.triggerManualIndexAsync(sourceId);
        return Map.of(
                "ok", true,
                "trigger", "manual",
                "sourceId", sourceId,
                "status", "INDEXING"
        );
    }

    /**
     * 自动触发文档索引。
     */
    @PostMapping("/auto")
    public Map<String, Object> triggerAuto(@RequestBody IndexDocumentRequest request) {
        String sourceId = requireSourceId(request == null ? null : request.getSourceId());
        documentIngestionService.triggerAutoIndexAsync(sourceId);
        return Map.of(
                "ok", true,
                "trigger", "auto",
                "sourceId", sourceId,
                "status", "INDEXING"
        );
    }

    /**
     * 查询单文档索引状态。
     */
    @GetMapping("/status")
    public IndexStatusResponse getStatus(@RequestParam("sourceId") String sourceId) {
        return IndexStatusResponse.from(documentIngestionService.getIndexState(sourceId));
    }

    /**
     * 列出当前全部索引状态。
     */
    @GetMapping("/status/list")
    public Map<String, Object> listStatuses() {
        return Map.of(
                "items", documentIngestionService.listIndexStates().stream().map(IndexStatusResponse::from).toList()
        );
    }

    private String requireSourceId(String sourceId) {
        if (!StringUtils.hasText(sourceId)) {
            throw new IllegalArgumentException("sourceId 不能为空");
        }
        return sourceId.trim();
    }
}
