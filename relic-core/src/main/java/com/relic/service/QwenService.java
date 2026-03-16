package com.relic.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QwenService extends OpenAiCompatibleService {

    private final String API_KEY = "sk-8c46bed4d0324d12a6c44ba32f113d8e";
    private final String URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    @Value("${relic.qwen.model:qwen-plus}")
    private String model;

    @Override
    public String getName() { return "qwen"; }

    @Override
    protected String getApiKey() {
        return API_KEY;
    }

    @Override
    protected String getUrl() {
        return URL;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String providerDisplayName() {
        return "Qwen";
    }
}
