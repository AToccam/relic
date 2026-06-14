package com.relic.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QwenService extends OpenAiCompatibleService {

    private static final String FALLBACK_API_KEY = "sk-8c46bed4d0324d12a6c44ba32f113d8e";

    @Value("${relic.qwen.api-key:}")
    private String apiKey;

    @Value("${relic.qwen.url:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}")
    private String url;

    @Value("${relic.qwen.model:qwen-plus}")
    private String model;

    @Override
    public String getName() { return "qwen"; }

    @Override
    protected String getApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        return FALLBACK_API_KEY;
    }

    @Override
    protected String getUrl() {
        return url;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String providerDisplayName() {
        return "Qwen";
    }

    @Override
    public boolean supportsMultimodal() {
        String normalizedModel = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return normalizedModel.contains("-vl-") || normalizedModel.contains("omni");
    }
}
