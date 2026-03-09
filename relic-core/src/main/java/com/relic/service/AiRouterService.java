package com.relic.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;

/**
 * AI 路由服务：管理所有 AiProvider，统一调度。
 * 后续扩展多 AI 并行回答时，在此新增 askAll / streamAll 等方法即可。
 */
@Slf4j
@Service
public class AiRouterService {

    private static final String DEFAULT_PROVIDER = "deepseek";

    private final Map<String, AiProvider> providerMap = new LinkedHashMap<>();

    @Autowired
    public AiRouterService(List<AiProvider> providers) {
        for (AiProvider p : providers) {
            providerMap.put(p.getName(), p);
            log.info("注册 AI 提供者: {}", p.getName());
        }
    }

    public AiProvider getProvider(String name) {
        AiProvider provider = providerMap.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("未知的 AI 提供者: " + name);
        }
        return provider;
    }

    public Collection<AiProvider> getAllProviders() {
        return providerMap.values();
    }

    public Set<String> getProviderNames() {
        return providerMap.keySet();
    }

    /** 使用默认 provider 进行多轮问答 */
    public String askDefault(List<Map<String, Object>> messages) {
        return getProvider(DEFAULT_PROVIDER).ask(messages);
    }

    /** 使用默认 provider 进行流式输出 */
    public void streamDefault(List<Map<String, Object>> messages, Consumer<String> onChunk) throws Exception {
        getProvider(DEFAULT_PROVIDER).stream(messages, onChunk);
    }

    /** 使用指定 provider 进行单轮问答 */
    public String ask(String providerName, String prompt) {
        return getProvider(providerName).ask(prompt);
    }
}
