package com.relic.websearch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchResult {

    private String id;
    private String title;
    private String url;
    private String snippet;
    private double score;
}
