package com.relic.websearch.dto;

import lombok.Data;

@Data
public class ImportWebResourceRequest {

    private String url;
    private String title;
    private String snippet;
    private String keyword;
}
