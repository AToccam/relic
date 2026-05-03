package com.relic.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIndexState {

    private String sourceId;
    private IndexStatus status;
    private String message;
    private int chunkCount;
    private Instant updatedAt;
}
