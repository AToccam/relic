package com.relic.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KimiService extends OpenAiCompatibleService {
    @Override
    protected double getTemperature() {
        // kimi-k2.5模型只允许temperature=1
        return 1.0;
    }

    private final String API_KEY = "sk-lbvuX4lkXbAj5pyVGGbmcEVP5jnDgjicPipqXh5o0IdxjNEh";
    private final String URL = "https://api.moonshot.cn/v1/chat/completions";

    @Value("${relic.kimi.model:moonshot-v1-8k}")
    private String model;

    @Override
    public String getName() { return "kimi"; }

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
        return "Kimi";
    }

    @Override
    public boolean supportsMultimodal() {
        return true;
    }

    @Override
    protected boolean shouldForceToolChoice(List<Map<String, Object>> messages) {
        // Moonshot/Kimi 在开启 thinking 时不接受 tool_choice=required。
        // 保持 tools 透传并让服务端自动决定是否调用工具。
        return false;
    }
}
