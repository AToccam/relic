package com.relic.service;

import java.util.Optional;

/**
 * 本地小模型分类器扩展点。
 *
 * 当前仅定义接口，未来可接入本地部署模型（如 Ollama/vLLM）做意图分类。
 */
public interface LocalIntentClassifier {

    /**
     * @param userMessage 最新用户消息
     * @return 若本地分类器给出确定结论，则返回路由路径；否则返回 empty 让上层回退到规则路由
     */
    Optional<SemanticRouter.RoutePath> classify(String userMessage);
}
