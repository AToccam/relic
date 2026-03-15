package com.relic.service;

import java.util.List;
import java.util.Map;

/**
 * 前置语义路由：在主流程开始前判断走快车道还是慢车道。
 */
public interface SemanticRouter {

    RouteDecision decide(List<Map<String, Object>> messages);

    enum RoutePath {
        FAST,
        TOOL_FIRST,
        DEEP
    }

    record RouteDecision(RoutePath path, String reason) {}
}
