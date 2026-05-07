package com.relic.rag.api.dto;

import com.relic.rag.model.DocumentIndexState;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class IndexStatusResponse {

    private String sourceId;
    private String status;
    private String message;
    private int chunkCount;
    private Instant updatedAt;

    public static IndexStatusResponse from(DocumentIndexState state) {
        return IndexStatusResponse.builder()
                .sourceId(state.getSourceId())
                .status(state.getStatus().name())
                .message(state.getMessage())
                .chunkCount(state.getChunkCount())
                .updatedAt(state.getUpdatedAt())
                .build();
    }
}
