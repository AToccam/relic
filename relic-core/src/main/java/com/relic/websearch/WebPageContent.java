package com.relic.websearch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebPageContent {

    private String url;
    private String title;
    private String snippet;
    private String keyword;
    private String content;
}
