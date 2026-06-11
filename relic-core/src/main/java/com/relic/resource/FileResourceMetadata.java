package com.relic.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileResourceMetadata {

    private String filename;
    private String relativePath;
    private String mimeType;
    private long size;
    private String updatedAt;
    private String createdAt;
    private String sourceType;
    private String originUrl;
    private String title;
    private String snippet;
    private String keyword;
}
