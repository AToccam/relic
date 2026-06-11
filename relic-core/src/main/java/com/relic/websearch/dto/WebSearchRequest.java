package com.relic.websearch.dto;

import lombok.Data;

@Data
public class WebSearchRequest {

    private String keyword;
    private Integer limit;
}
