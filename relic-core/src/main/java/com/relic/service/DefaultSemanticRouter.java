package com.relic.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Component
public class DefaultSemanticRouter implements SemanticRouter {

    private static final Pattern TOOL_PATTERN = Pattern.compile(
            "(天气|气温|温度|查一下|帮我查|搜索|检索|最新|新闻|"
                + "read\\s+file|list\\s+files|create\\s+file|"
                + "读取文件|列出文件|创建文件|写入文件|"
                + "读取|读一下|打开|查看|新建|创建|写入|保存|删除|重命名|"
                + "文件|文档|目录|文件夹)",
            Pattern.CASE_INSENSITIVE);

        private static final Pattern FILE_EXT_PATTERN = Pattern.compile(
            "\\.(txt|md|markdown|json|csv|log|yaml|yml|xml|java|py|js|ts)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DEEP_PATTERN = Pattern.compile(
            "(架构|设计|优化|方案|权衡|trade-?off|对比|比较|分析|推导|重构|路线图|性能瓶颈|高并发|系统设计|完整代码|长文)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FAST_PATTERN = Pattern.compile(
            "^(你好|您好|hi|hello|早上好|晚上好|在吗|谢谢|ok|好的|收到)[!,.?，。！？ ]*$",
            Pattern.CASE_INSENSITIVE);

    private final Optional<LocalIntentClassifier> localClassifier;

    @Value("${relic.router.enable-local-classifier:false}")
    private boolean enableLocalClassifier;

    @Value("${relic.router.deep-question-min-chars:80}")
    private int deepQuestionMinChars;

    @Value("${relic.router.local-classifier-budget-ms:5000}")
    private int localClassifierBudgetMs;

    public DefaultSemanticRouter(ObjectProvider<LocalIntentClassifier> localClassifierProvider) {
        this.localClassifier = Optional.ofNullable(localClassifierProvider.getIfAvailable());
    }

    @Override
    public RouteDecision decide(List<Map<String, Object>> messages) {
        String userMessage = extractLatestUserMessage(messages).trim();
        if (userMessage.isEmpty()) {
            return new RouteDecision(RoutePath.FAST, "empty-user-message");
        }

        RouteDecision ruleDecision = decideByRules(userMessage);

        if (enableLocalClassifier && localClassifier.isPresent()) {
            try {
                Optional<RoutePath> localDecision = CompletableFuture
                        .supplyAsync(() -> localClassifier.get().classify(userMessage))
                        .get(localClassifierBudgetMs, TimeUnit.MILLISECONDS);
                if (localDecision.isPresent()) {
                    RoutePath localPath = localDecision.get();

                    // 护栏：规则已经判定为工具/深度时，不允许被本地分类器降级到 FAST
                    if (localPath == RoutePath.FAST && ruleDecision.path() != RoutePath.FAST) {
                        log.info("本地分类器命中 FAST，但规则路由为 {}，保留规则结果", ruleDecision.path());
                        return new RouteDecision(ruleDecision.path(), "local-guardrail-preserve-rule");
                    }

                    // 护栏：只有命中明显工具意图时，才允许走 TOOL_FIRST
                    if (localPath == RoutePath.TOOL_FIRST && !looksLikeToolIntent(userMessage)) {
                        log.info("本地分类器命中 TOOL_FIRST，但未检测到工具关键词，回退规则路由: {}",
                                ruleDecision.path());
                        return new RouteDecision(ruleDecision.path(), "local-guardrail");
                    }

                    return new RouteDecision(localPath, "local-classifier");
                }
            } catch (Exception e) {
                log.warn("本地分类器超时/异常，已回退规则路由: {}, budgetMs={}",
                        e.getClass().getSimpleName(), localClassifierBudgetMs);
            }
        }

        return ruleDecision;
    }

    private RouteDecision decideByRules(String userMessage) {
        if (looksLikeToolIntent(userMessage)) {
            return new RouteDecision(RoutePath.TOOL_FIRST, "tool-intent");
        }

        if (isDeepQuestion(userMessage)) {
            return new RouteDecision(RoutePath.DEEP, "deep-question");
        }

        return new RouteDecision(RoutePath.FAST, "default-fast");
    }

    private boolean looksLikeToolIntent(String userMessage) {
        return TOOL_PATTERN.matcher(userMessage).find()
                || FILE_EXT_PATTERN.matcher(userMessage).find();
    }

    private boolean isDeepQuestion(String userMessage) {
        if (FAST_PATTERN.matcher(userMessage).matches()) {
            return false;
        }
        if (DEEP_PATTERN.matcher(userMessage).find()) {
            return true;
        }
        return userMessage.length() >= deepQuestionMinChars || userMessage.contains("\n");
    }

    private String extractLatestUserMessage(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                Object content = messages.get(i).get("content");
                return content == null ? "" : content.toString();
            }
        }
        return "";
    }
}
