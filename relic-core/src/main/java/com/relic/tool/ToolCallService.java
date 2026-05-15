package com.relic.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.dto.ToolCallResult;
import com.relic.service.AiProvider;
import com.relic.util.RequestDeadline;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tool-calling service that is decoupled from specific AI providers.
 */
@Service
public class ToolCallService {

    private static final Logger log = LoggerFactory.getLogger(ToolCallService.class);

    private static final int MAX_TOOL_ROUNDS = 10;
    private static final int DEFAULT_CREATE_LIMIT = 1;
    private static final int EXPLICIT_MULTI_CREATE_LIMIT = 3;
    private static final int DEFAULT_CHART_LIMIT = 1;
    private static final int EXPLICIT_MULTI_CHART_LIMIT = 3;

    private static final Pattern DOWNLOAD_URL_PATTERN = Pattern.compile("DOWNLOAD_URL:\\s*(\\S+)");
    private static final Pattern CREATED_FILE_PATTERN = Pattern.compile(
            "(?:File created:|File overwritten:|Word document created:|Word document overwritten:)\\s*(.+)");
    private static final Pattern INLINE_CHART_PATTERN = Pattern.compile(
            ToolExecutor.INLINE_CHART_MARKER + "\\s*\\R([\\s\\S]*)",
            Pattern.MULTILINE);
    private static final Pattern STRUCTURED_CHART_PATTERN = Pattern.compile(
            ToolExecutor.STRUCTURED_CHART_MARKER + "(\\{.*})");
    private static final Pattern MERMAID_FENCE_PATTERN = Pattern.compile("```mermaid\\s*\\R([\\s\\S]*?)\\R?```");
    private static final Pattern FILENAME_PATTERN = Pattern.compile("([a-zA-Z]:[/\\\\][\\w\\-\\u4e00-\\u9fa5./\\\\]*\\.(?:md|txt|docx|csv|json|xml|html|py|js|java|ts|log)|[\\w\\-\\u4e00-\\u9fa5./]+\\.(?:md|txt|docx|csv|json|xml|html|py|js|java|ts|log))", Pattern.CASE_INSENSITIVE);
    // 绝对路径：要求基名是 ASCII，避免把前缀的中文 prose 误吞入文件名
    private static final Pattern ABSOLUTE_PATH_PATTERN = Pattern.compile(
            "([a-zA-Z]:[/\\\\](?:[\\w\\-._\\u4e00-\\u9fa5]+[/\\\\])*[A-Za-z0-9_\\-]+\\.[a-zA-Z0-9]{1,10})");
    // 仅匹配盘符目录前缀（如 E:/、E:/folder/）
    private static final Pattern DRIVE_DIR_PATTERN = Pattern.compile(
            "([a-zA-Z]:[/\\\\](?:[\\w\\-._\\u4e00-\\u9fa5]+[/\\\\])*)");
    // ASCII 基名 + 已知扩展，用于跨 prose 提取
    private static final Pattern ASCII_FILENAME_PATTERN = Pattern.compile(
            "((?:[\\w\\-._\\u4e00-\\u9fa5]+[/\\\\])*[A-Za-z0-9_\\-]+\\.(?:md|txt|docx|csv|json|xml|html|py|js|java|ts|log))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_FILENAME_PATTERN = Pattern.compile("([\\w\\-\\u4e00-\\u9fa5./\\\\]+\\.(?:png|jpe?g|gif|bmp|webp))", Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_FILE_COUNT_PATTERN = Pattern.compile("生成\\s*([2-9]|[1-9]\\d|两|二|三|四|五|六|七|八|九)\\s*个?\\s*(文件|文档)");

    private static final Pattern MULTI_CHART_EN_PATTERN = Pattern.compile("(?i)(?:draw|create|generate|render|make)\\s*(?:[2-9]|[1-9]\\d)\\s*(?:charts|diagrams)");
    private static final Pattern MULTI_CHART_COUNT_EN_PATTERN = Pattern.compile("(?i)(?:[2-9]|[1-9]\\d)\\s*(?:charts|diagrams)");
    private static final Pattern MULTI_CHART_COUNT_ZH_PATTERN = Pattern.compile("(?:[2-9]|[1-9]\\d)\\s*(?:\u4e2a|\u5f20)?\\s*(?:\u56fe|\u56fe\u8868|\u56fe\u793a)");

    private static final List<String> CHART_KEYWORDS = List.of(
            "\u56fe\u8868", "\u753b\u56fe", "\u5173\u7cfb\u56fe", "\u7ed3\u6784\u56fe", "\u793a\u610f\u56fe", "\u56fe\u793a",
            "\u5bf9\u6bd4\u56fe", "\u6bd4\u8f83\u56fe", "\u5bf9\u7167\u56fe", "\u533a\u522b\u56fe", "\u5dee\u5f02\u56fe",
            "\u6bd4\u4f8b\u56fe", "\u5206\u5e03\u56fe", "\u5360\u6bd4\u56fe", "\u8d8b\u52bf\u56fe", "\u65f6\u95f4\u7ebf",
            "\u7518\u7279\u56fe", "\u5e8f\u5217\u56fe", "\u65f6\u5e8f\u56fe", "\u7c7b\u56fe", "\u5b9e\u4f53\u5173\u7cfb",
            "er\u56fe", "\u72b6\u6001\u56fe", "\u65c5\u7a0b\u56fe", "\u8c61\u9650\u56fe", "\u6851\u57fa\u56fe",
            "\u67b6\u6784\u56fe", "\u770b\u677f\u56fe", "\u5757\u56fe", "\u7ef4\u6069\u56fe", "\u8111\u56fe",
            "\u601d\u7ef4\u5bfc\u56fe", "\u6d41\u7a0b\u56fe", "\u67f1\u72b6\u56fe", "\u6298\u7ebf\u56fe", "\u997c\u56fe",
            "mermaid", "chart", "diagram", "flowchart", "graph", "mindmap", "timeline", "gantt", "sequence diagram",
            "sequencediagram", "class diagram", "classdiagram", "er diagram", "erdiagram", "state diagram",
            "statediagram", "journey", "quadrant", "sankey", "architecture", "kanban", "block diagram",
            "blockdiagram", "venn", "xychart", "pie chart", "bar chart", "line chart", "comparison chart",
            "compare chart", "\u53ef\u89c6\u5316", "\u6570\u636e\u5bf9\u6bd4");
    private static final List<String> GENERIC_CHART_ACTIONS = List.of(
            "\u505a\u4e2a", "\u505a\u4e00\u4e2a", "\u753b\u4e2a", "\u753b\u4e00\u4e2a",
            "\u751f\u6210", "\u751f\u6210\u4e00\u4e2a", "\u751f\u6210\u4e00\u4efd", "\u521b\u5efa", "\u5236\u4f5c", "\u7ed9\u6211\u751f\u6210");
    private static final List<String> WEAK_CHART_ACTIONS = List.of(
            "\u68b3\u7406", "\u6574\u7406", "\u5c55\u793a", "\u5448\u73b0", "\u603b\u89c8", "\u7ed3\u6784\u5316",
            "\u53ef\u89c6\u5316", "\u505a\u6210", "\u753b\u4e00\u4e0b", "\u753b\u4e0b", "\u4e00\u5f20", "\u4e00\u9875",
            "visualize", "show", "overview", "map out");
    private static final List<String> WEAK_CHART_OBJECTS = List.of(
            "\u5173\u7cfb", "\u7ed3\u6784", "\u6d41\u7a0b", "\u8def\u5f84", "\u6f14\u53d8", "\u5bf9\u6bd4", "\u903b\u8f91",
            "\u6846\u67b6", "\u94fe\u8def", "\u8c03\u7528", "\u8109\u7edc", "relationship", "structure", "process",
            "flow", "logic", "framework", "overview");
    private static final List<String> PLAIN_TEXT_HINTS = List.of(
            "\u7528\u6587\u5b57", "\u7eaf\u6587\u672c", "\u4e0d\u8981\u753b\u56fe", "\u4e0d\u7528\u753b\u56fe",
            "\u522b\u753b\u56fe", "\u4e0d\u8981\u56fe", "\u4e0d\u7528\u56fe", "plain text", "no chart", "no diagram");
    private static final List<String> CHART_REVISION_KEYWORDS = List.of(
            "\u4fee\u6539", "\u8c03\u6574", "\u5b8c\u5584", "\u7ee7\u7eed", "\u91cd\u753b", "\u6362\u6210", "\u6539\u6210",
            "\u6539\u4e00\u4e0b", "revise", "update", "modify", "redraw");
    private static final List<String> FILE_OUTPUT_KEYWORDS = List.of(
            "\u4fdd\u5b58", "\u4e0b\u8f7d", "\u5bfc\u51fa", "\u751f\u6210\u6587\u4ef6", "\u4fdd\u5b58\u6210\u6587\u4ef6",
            "\u5b58\u6210\u6587\u4ef6", "\u5b58\u4e3a\u6587\u4ef6", "\u751f\u6210md", "\u751f\u6210 md",
            "\u751f\u6210\u6587\u6863", "\u521b\u5efa\u6587\u6863", "\u5199\u4e00\u4efd\u6587\u6863", "\u5bfc\u51fa\u6587\u6863",
            "\u751f\u6210\u62a5\u544a", "\u521b\u5efa\u62a5\u544a", "\u5199\u4e00\u4efd\u62a5\u544a",
            "markdown", ".md", " md", "save", "save as file", "create file", "generate file",
            "create document", "generate document", "write a document", "create report", "generate report",
            "document file", "report file", "downloadable document", "downloadable report", "download", "export");
    private static final List<String> DOCX_OUTPUT_KEYWORDS = List.of(
            "word", "docx", ".docx", "word\u6587\u4ef6", "word \u6587\u4ef6", "word\u6587\u6863", "word \u6587\u6863",
            "docx\u6587\u4ef6", "docx \u6587\u4ef6", "docx\u6587\u6863", "docx \u6587\u6863",
            "\u751f\u6210\u6587\u6863", "\u521b\u5efa\u6587\u6863", "\u5199\u4e00\u4efd\u6587\u6863",
            "\u5bfc\u51fa\u6587\u6863", "\u751f\u6210\u62a5\u544a", "\u521b\u5efa\u62a5\u544a", "\u5199\u4e00\u4efd\u62a5\u544a",
            "\u53ef\u4e0b\u8f7d\u6587\u6863", "\u6587\u6863\u6587\u4ef6", "\u62a5\u544a\u6587\u4ef6",
            "create document", "generate document", "write a document", "create report", "generate report",
            "document file", "report file", "downloadable document", "downloadable report");
    private static final List<String> TEXT_FILE_OUTPUT_KEYWORDS = List.of(
            "markdown", ".md", " md", "\u751f\u6210md", "\u751f\u6210 md", "\u6587\u672c\u6587\u4ef6", "text file", ".txt",
            "txt\u6587\u4ef6", "txt\u683c\u5f0f", "\u7eaf\u6587\u672c");
    private static final List<String> TXT_EXTENSION_KEYWORDS = List.of(
            ".txt", "txt\u6587\u4ef6", "txt\u683c\u5f0f", "txt file", "\u7eaf\u6587\u672c", "plain text");
    private static final List<String> NO_FILE_OUTPUT_KEYWORDS = List.of(
            "\u4e0d\u8981\u751f\u6210\u6587\u4ef6", "\u4e0d\u7528\u751f\u6210\u6587\u4ef6", "\u4e0d\u751f\u6210\u6587\u4ef6",
            "\u4e0d\u8981\u521b\u5efa\u6587\u4ef6", "\u4e0d\u7528\u521b\u5efa\u6587\u4ef6", "\u4e0d\u521b\u5efa\u6587\u4ef6",
            "\u4e0d\u8981\u4fdd\u5b58", "\u4e0d\u7528\u4fdd\u5b58", "\u4e0d\u8981\u4e0b\u8f7d", "\u4e0d\u7528\u4e0b\u8f7d",
            "\u76f4\u63a5\u5728\u9875\u9762", "\u76f4\u63a5\u5728\u804a\u5929", "\u76f4\u63a5\u56de\u590d",
            "do not create file", "do not generate file", "don't create file", "don't generate file",
            "no file", "inline only", "reply directly");
    private static final List<String> TABLE_KEYWORDS = List.of("\u8868\u683c", "\u5bf9\u6bd4\u8868", "\u6570\u636e\u8868", "table");
    private static final List<String> INLINE_KEYWORDS = List.of("\u9875\u9762", "\u5c55\u793a", "\u663e\u793a", "\u76f4\u63a5", "\u804a\u5929", "\u56de\u590d", "inline", "reply", "show");
    private static final List<String> WORKSPACE_READ_KEYWORDS = List.of(
            "\u8bfb\u53d6\u6587\u4ef6", "\u67e5\u770b\u6587\u4ef6", "\u770b\u4e0b\u6587\u4ef6", "\u6253\u5f00\u6587\u4ef6",
            "\u5217\u51fa\u6587\u4ef6", "\u5217\u51fa\u5de5\u4f5c\u533a", "\u5de5\u4f5c\u533a",
            "read file", "list files", "workspace", "read_file", "list_files");
    private static final List<String> MULTI_FILE_KEYWORDS = List.of(
            "\u591a\u4e2a\u6587\u4ef6", "\u591a\u4efd\u6587\u4ef6", "\u591a\u4efd\u6587\u6863", "\u6279\u91cf\u751f\u6210",
            "\u5206\u522b\u751f\u6210", "\u5206\u522b\u8f93\u51fa", "\u6bcf\u4e2a\u90fd\u4fdd\u5b58",
            "\u6bcf\u4e2a\u90fd\u751f\u6210", "\u5404\u751f\u6210\u4e00\u4e2a", "multiple files", "separate files", "for each");
    private static final List<String> MULTI_CHART_KEYWORDS = List.of(
            "\u591a\u4e2a\u56fe", "\u591a\u5f20\u56fe", "\u51e0\u4e2a\u56fe", "\u51e0\u5f20\u56fe", "\u5206\u522b\u753b",
            "\u5206\u522b\u505a", "\u5206\u522b\u751f\u6210", "\u5404\u753b\u4e00\u4e2a", "\u6bcf\u4e2a\u90fd\u753b",
            "\u4e00\u4e2a\u4e00\u4e2a\u753b", "multiple charts", "multiple diagrams", "separate charts",
            "separate diagrams", "for each chart", "for each diagram");

    @Autowired
    private ToolExecutor toolExecutor;

    @Value("${relic.tools.execution-timeout-ms:60000}")
    private long toolExecutionTimeoutMs;

    @Value("${relic.tools.executor.pool-size:4}")
    private int toolExecutorPoolSize;

    @Value("${relic.tools.executor.queue-size:32}")
    private int toolExecutorQueueSize;

    private ExecutorService toolWorkerExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initToolWorkerExecutor() {
        int poolSize = Math.max(1, toolExecutorPoolSize);
        int queueSize = Math.max(1, toolExecutorQueueSize);
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "relic-tool-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        toolWorkerExecutor = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdownToolWorkerExecutor() {
        if (toolWorkerExecutor != null) {
            toolWorkerExecutor.shutdownNow();
        }
    }

    public String askWithTools(AiProvider provider, List<Map<String, Object>> messages) {
        IntentDecision decision = decideIntent(messages);
        if (shouldCreateChartFileDeterministically(decision)) {
            return createChartFileDeterministically(provider, messages, decision);
        }
        if (shouldCreateFileDeterministically(decision)) {
            return createFileDeterministically(provider, messages, decision);
        }
        if (!provider.supportsTools()) {
            log.debug("Provider {} does not support tools, fallback to plain ask", provider.getName());
            return provider.ask(messages);
        }

        List<Map<String, Object>> enrichedMessages = enrichChartFileOutputContext(enrichChartRevisionContext(messages));
        List<Map<String, Object>> conversation = new ArrayList<>(enrichedMessages);
        List<Map<String, Object>> tools = selectToolsForRequest(enrichedMessages);
        CreateGuard createGuard = buildCreateGuard(enrichedMessages);
        StringBuilder directToolOutput = new StringBuilder();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ToolCallResult result = provider.askWithTools(conversation, tools);
            List<ToolCallResult.ToolCall> executable = filterExecutableToolCalls(result.getToolCalls());

            if (result.hasToolCalls() && !executable.isEmpty()) {
                log.info("[tool-call] provider={}, round={}, count={}", provider.getName(), round + 1, executable.size());
                conversation.add(result.toAssistantMessage());
                directToolOutput.append(executeAndAppend(executable, conversation, createGuard));
                if (shouldStopAfterTools(createGuard)) {
                    return directToolOutput.toString();
                }
            } else if (result.hasToolCalls()) {
                log.warn("[tool-call] provider={}, round={} got invalid tool calls only", provider.getName(), round + 1);
            } else {
                if (createGuard.chartFileOutput && createGuard.createdCount == 0) {
                    log.warn("[tool-call] chart file output has no file yet; suppressing natural content and forcing file creation: {}", result.getContentString());
                    conversation.add(buildChartFileCreationInstruction(createGuard));
                    continue;
                }
                return directToolOutput + result.getContentString();
            }
        }

        log.warn("[tool-call] reached max rounds={}", MAX_TOOL_ROUNDS);
        return "工具调用轮次超过限制";
    }

    public void streamWithTools(AiProvider provider,
                                List<Map<String, Object>> messages,
                                Consumer<String> onChunk) throws Exception {
        IntentDecision decision = decideIntent(messages);
        if (shouldCreateChartFileDeterministically(decision)) {
            onChunk.accept(createChartFileDeterministically(provider, messages, decision));
            return;
        }
        if (shouldCreateFileDeterministically(decision)) {
            onChunk.accept(createFileDeterministically(provider, messages, decision));
            return;
        }
        if (!provider.supportsTools()) {
            log.debug("Provider {} does not support tools, fallback to plain stream", provider.getName());
            provider.stream(messages, onChunk);
            return;
        }

        List<Map<String, Object>> enrichedMessages = enrichChartFileOutputContext(enrichChartRevisionContext(messages));
        List<Map<String, Object>> conversation = new ArrayList<>(enrichedMessages);
        List<Map<String, Object>> tools = selectToolsForRequest(enrichedMessages);
        if (tools.isEmpty()) {
            provider.stream(enrichedMessages, onChunk);
            return;
        }

        CreateGuard createGuard = buildCreateGuard(enrichedMessages);
        boolean chartRetryRequired = false;
        String chartRetryToolName = "render_mermaid_chart";

        boolean anyContentSent = false;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            StringBuilder bufferedContent = new StringBuilder();
            Consumer<String> contentSink = bufferedContent::append;
            ToolCallResult result = provider.streamWithTools(conversation, tools, contentSink);
            List<ToolCallResult.ToolCall> executable = filterExecutableToolCalls(result.getToolCalls());

            if (result.hasToolCalls() && !executable.isEmpty()) {
                log.info("[tool-call-stream] provider={}, round={}, count={}", provider.getName(), round + 1, executable.size());
                conversation.add(result.toAssistantMessage());
                chartRetryRequired = false;

                for (ToolCallResult.ToolCall tc : executable) {
                    if (isBlockedRepeatedChartCall(tc, createGuard)) {
                        conversation.add(buildToolMessage(tc, repeatedChartBlockedMessage()));
                        conversation.add(buildChartFileCreationInstruction(createGuard));
                        continue;
                    }

                    onChunk.accept("\n🔧 正在调用 " + tc.getName() + "...\n");

                    String toolResult = executeToolWithGuard(tc, createGuard);
                    boolean retryRequired = isChartValidationError(toolResult);
                    ChartQualityResult qualityResult = validateChartQuality(toolResult);
                    if (!retryRequired && !qualityResult.ok() && createGuard.chartRepairCount < 1) {
                        retryRequired = true;
                        createGuard.chartRepairCount++;
                        toolResult = ToolExecutor.CHART_VALIDATION_ERROR_MARKER + " " + qualityResult.message();
                    }
                    boolean chartGenerated = isChartTool(tc.getName()) && hasInlineChart(toolResult);

                    Map<String, Object> toolMsg = new HashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", tc.getId());
                    toolMsg.put("content", buildToolMessageContent(tc.getName(), toolResult, createGuard));
                    conversation.add(toolMsg);

                    if (retryRequired) {
                        chartRetryRequired = true;
                        chartRetryToolName = tc.getName();
                    }
                    if (chartGenerated) {
                        createGuard.chartCount++;
                        logToolResult(tc.getName(), toolResult);
                        if (!createGuard.chartFileOutput) {
                            emitDownloadLinkIfPresent(toolResult, onChunk);
                            if (hasStructuredChart(toolResult)) {
                                emitStructuredChartIfPresent(toolResult, onChunk);
                            } else {
                                emitInlineChartIfPresent(toolResult, onChunk);
                            }
                            onChunk.accept("\n");
                        }
                        if (!createGuard.chartFileOutput && createGuard.chartCount >= createGuard.maxCharts) {
                            return;
                        }
                        continue;
                    }
                    if (!retryRequired) {
                        logToolResult(tc.getName(), toolResult);
                        emitDownloadLinkIfPresent(toolResult, onChunk);
                        if (isCreateTool(tc.getName()) && hasDownloadUrl(toolResult)) {
                            onChunk.accept("\n");
                            return;
                        }
                    }
                }
                if (chartRetryRequired) {
                    conversation.add(buildChartRetryInstruction(chartRetryToolName));
                }
                onChunk.accept("\n");
            } else if (result.hasToolCalls()) {
                log.warn("[tool-call-stream] provider={}, round={} got invalid tool calls only", provider.getName(), round + 1);
            } else {
                if (chartRetryRequired) {
                    log.warn("[tool-call-stream] chart retry required but provider returned natural language only: {}", bufferedContent);
                    onChunk.accept("⚠️ 图表生成失败：AI 没有按要求重新调用图表工具。请再试一次，或要求它直接输出完整 Mermaid 图表源码。");
                    return;
                }
                if (createGuard.chartFileOutput && createGuard.createdCount == 0) {
                    log.warn("[tool-call-stream] chart file output has no file yet; suppressing natural content and forcing file creation: {}", bufferedContent);
                    conversation.add(buildChartFileCreationInstruction(createGuard));
                    continue;
                }
                if (bufferedContent.length() > 0) {
                    anyContentSent = true;
                    onChunk.accept(bufferedContent.toString());
                }
                if (!anyContentSent && result.getContent().length() == 0) {
                    log.warn("[tool-call-stream] empty content, finishReason={}", result.getFinishReason());
                    onChunk.accept("⚠️ AI 未返回有效内容，请稍后重试。");
                }
                return;
            }
        }

        log.warn("[tool-call-stream] reached max rounds={}", MAX_TOOL_ROUNDS);
        onChunk.accept("⚠️ 工具调用轮次超过限制，已停止处理。");
    }

    private boolean shouldCreateFileDeterministically(IntentDecision decision) {
        return decision != null
                && decision.fileOutputIntent()
                && (!decision.chartIntent() || decision.docxOutputIntent())
                && !decision.workspaceReadIntent()
                && decision.maxCreates() <= 1;
    }

    private boolean shouldCreateChartFileDeterministically(IntentDecision decision) {
        return decision != null
                && decision.outputMode() == OutputMode.CHART_FILE_OUTPUT
                && !decision.workspaceReadIntent()
                && decision.maxCreates() <= 1;
    }

    private boolean shouldStopAfterTools(CreateGuard guard) {
        if (guard.chartFileOutput) {
            return guard.createdCount > 0;
        }
        return guard.chartCount >= guard.maxCharts || guard.createdCount > 0;
    }

    private String createFileDeterministically(AiProvider provider,
                                               List<Map<String, Object>> messages,
                                               IntentDecision decision) {
        try {
            String latestUserText = extractLatestUserText(messages);
            String extension = decision.docxOutputIntent() ? ".docx" : inferTextFileExtension(latestUserText);
            List<String> uploadedImagePaths = extractUploadedImagePaths(messages);
            List<Map<String, Object>> promptMessages = buildDeterministicFilePrompt(messages, extension, uploadedImagePaths);
            String raw = provider.ask(promptMessages);
            FileDraft draft = parseFileDraft(raw, latestUserText, extension);
            String content = decision.docxOutputIntent()
                    ? stripMermaidBlocks(ensureImageReferences(draft.content(), uploadedImagePaths))
                    : draft.content();

            Map<String, Object> args = new HashMap<>();
            args.put("filename", draft.filename());
            args.put("title", draft.title());
            args.put("content", content);

            String toolName = decision.docxOutputIntent() ? "create_docx_file" : "create_text_file";
            String toolResult = executeToolWithTimeout(toolName, objectMapper.writeValueAsString(args));
            return buildDirectFileOutput(toolResult, draft.replyMessage(), decision.docxOutputIntent() && decision.chartIntent());
        } catch (Exception e) {
            log.warn("[deterministic-file] create failed: {}", e.getMessage());
            return "⚠️ 文件生成失败：" + e.getMessage();
        }
    }

    private String createChartFileDeterministically(AiProvider provider,
                                                    List<Map<String, Object>> messages,
                                                    IntentDecision decision) {
        try {
            String latestUserText = extractLatestUserText(messages);
            String extension = decision.docxOutputIntent() ? ".docx" : inferTextFileExtension(latestUserText);
            List<Map<String, Object>> promptMessages = buildDeterministicChartFilePrompt(messages, extension);
            String raw = provider.ask(promptMessages);
            ChartFileDraft draft = parseChartFileDraft(raw, latestUserText, extension);

            Map<String, Object> chartArgs = new HashMap<>();
            chartArgs.put("title", draft.chartTitle());
            chartArgs.put("content", draft.mermaidSource());
            String chartResult = executeToolWithTimeout("render_mermaid_chart", objectMapper.writeValueAsString(chartArgs));
            if (isChartValidationError(chartResult) || !hasInlineChart(chartResult)) {
                return "⚠️ 图表生成失败：" + removeSpecialToolLines(chartResult);
            }

            String chartSource = extractChartSourceFromToolResult(chartResult);
            String content = decision.docxOutputIntent()
                    ? stripMermaidBlocks(draft.content())
                    : ensureMermaidBlock(draft.content(), chartSource);

            Map<String, Object> fileArgs = new HashMap<>();
            fileArgs.put("filename", draft.filename());
            fileArgs.put("title", draft.title());
            fileArgs.put("content", content);

            String toolName = decision.docxOutputIntent() ? "create_docx_file" : "create_text_file";
            String fileResult = executeToolWithTimeout(toolName, objectMapper.writeValueAsString(fileArgs));
            return buildDirectFileOutput(fileResult, draft.replyMessage(), decision.docxOutputIntent() && decision.chartIntent());
        } catch (Exception e) {
            log.warn("[deterministic-chart-file] create failed: {}", e.getMessage());
            return "⚠️ 带图表文档生成失败：" + e.getMessage();
        }
    }

    private List<Map<String, Object>> buildDeterministicChartFilePrompt(List<Map<String, Object>> messages, String extension) {
        List<Map<String, Object>> promptMessages = new ArrayList<>();
        String overrideDir = com.relic.tool.ToolExecutor.currentWorkingDirectory();
        boolean overrideActive = overrideDir != null && !overrideDir.isBlank();
        String filenameRule = overrideActive
                ? "filename must be a short bare file name (no directory, no drive letter) ending with " + extension
                + ". The system will automatically place the file under the user's selected working directory ("
                + overrideDir + "). Do not include any absolute path or drive prefix even if the user's message mentions one. "
                : "filename must be a short workspace-relative file name ending with " + extension + ". ";
        promptMessages.add(Map.of(
                "role", "system",
                "content", "You are generating exactly one downloadable file that includes exactly one Mermaid chart. "
                        + "Return JSON only with keys filename, title, content, chartTitle, mermaidSource, reply. "
                        + "reply must be an object with key message. "
                        + "reply.message must be one short sentence confirming that the file was generated. "
                        + "Do not summarize content or chart labels in reply.message. "
                        + "Do not wrap JSON in markdown fences. "
                        + filenameRule
                        + "content must be valid Markdown. Use '# Title' and '## Section' headings with one blank line before and after headings. "
                        + "Use '-' for bullet lists, '1.' for numbered lists, and blank lines between paragraphs. "
                        + "Never concatenate headings, list items, tables, or paragraphs on the same line. "
                        + "mermaidSource must be raw Mermaid syntax only, not a fenced block. "
                        + "Use complete Mermaid syntax with meaningful visible labels. "
                        + "Do not mention tools, function calls, workspace checks, or downloads."
        ));
        promptMessages.addAll(messages);
        return promptMessages;
    }

    private ChartFileDraft parseChartFileDraft(String raw, String latestUserText, String extension) {
        String response = raw == null ? "" : raw.trim();
        String jsonCandidate = extractJsonObject(response);
        if (!jsonCandidate.isBlank()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(jsonCandidate, new TypeReference<>() {});
                String filename = normalizeGeneratedFilename(stripDriveIfOverrideActive(firstNonBlank(
                        asText(parsed.get("filename")),
                        asText(parsed.get("file_path")),
                        asText(parsed.get("path")),
                        inferFilenameFromUserTextSmart(latestUserText, extension)
                )), extension);
                String title = firstNonBlank(asText(parsed.get("title")), inferTitleFromFilename(filename));
                String content = firstNonBlank(asText(parsed.get("content")), asText(parsed.get("body")));
                String replyMessage = extractStructuredReplyMessage(parsed);
                String chartTitle = firstNonBlank(asText(parsed.get("chartTitle")), asText(parsed.get("chart_title")), title);
                String mermaidSource = firstNonBlank(
                        asText(parsed.get("mermaidSource")),
                        asText(parsed.get("mermaid_source")),
                        asText(parsed.get("source")),
                        extractFirstMermaidSource(response)
                );
                if (content.isBlank()) {
                    content = "# " + title + "\n\n" + "## 关键模块说明\n\n- 请查看下方图表了解整体结构。";
                }
                content = normalizeGeneratedMarkdown(content, title);
                if (mermaidSource.isBlank()) {
                    mermaidSource = buildFallbackMermaidSource(chartTitle);
                }
                return new ChartFileDraft(filename, title, content, chartTitle, stripMermaidFence(mermaidSource), replyMessage);
            } catch (Exception ignored) {
                // fall through to fallback
            }
        }

        String filename = inferFilenameFromUserTextSmart(latestUserText, extension);
        String title = inferTitleFromFilename(filename);
        String mermaidSource = firstNonBlank(extractFirstMermaidSource(response), buildFallbackMermaidSource(title));
        String content = normalizeGeneratedMarkdown(stripMermaidFence(response), title);
        return new ChartFileDraft(filename, title, content, title, mermaidSource, "");
    }

    private String ensureMermaidBlock(String content, String mermaidSource) {
        String body = content == null ? "" : content.trim();
        String source = stripMermaidFence(mermaidSource);
        if (body.contains("```mermaid")) {
            return body;
        }
        if (body.isBlank()) {
            body = "## 图表说明";
        }
        return body + "\n\n## 图表\n\n```mermaid\n" + source + "\n```\n";
    }

    private String extractFirstMermaidSource(String text) {
        Matcher matcher = MERMAID_FENCE_PATTERN.matcher(text == null ? "" : text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private String stripMermaidBlocks(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return MERMAID_FENCE_PATTERN.matcher(content)
                .replaceAll("")
                .replaceAll("(?m)^\\s*" + Pattern.quote(ToolExecutor.STRUCTURED_CHART_MARKER) + "\\{.*}\\s*$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String stripMermaidFence(String text) {
        String value = text == null ? "" : text.trim();
        Matcher matcher = MERMAID_FENCE_PATTERN.matcher(value);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return value
                .replaceFirst("^```mermaid\\s*", "")
                .replaceFirst("\\s*```$", "")
                .trim();
    }

    private String buildFallbackMermaidSource(String title) {
        String safeTitle = firstNonBlank(title, "报告结构");
        return "flowchart TD\n"
                + "    A[" + escapeMermaidLabel(safeTitle) + "] --> B[核心模块]\n"
                + "    A --> C[关键流程]\n"
                + "    A --> D[输出结果]\n";
    }

    private String escapeMermaidLabel(String text) {
        return (text == null ? "" : text)
                .replace("[", " ")
                .replace("]", " ")
                .replace("\"", "'")
                .trim();
    }

    private List<Map<String, Object>> buildDeterministicFilePrompt(List<Map<String, Object>> messages,
                                                                   String extension,
                                                                   List<String> uploadedImagePaths) {
        List<Map<String, Object>> promptMessages = new ArrayList<>();
        boolean docx = ".docx".equalsIgnoreCase(extension);
        boolean hasImages = uploadedImagePaths != null && !uploadedImagePaths.isEmpty();
        String contentRules = ".docx".equalsIgnoreCase(extension)
                ? "content must be valid Markdown for a document body. "
                + "Do not output HTML, XML, CSS, JavaScript, MHTML, or office markup. "
                + "Do not include tags such as <html>, <head>, <style>, <body>, <table> or <!DOCTYPE>. "
                + "Use '# Title', '## Section', normal paragraphs, '- ' bullet lists, '1. ' numbered lists, simple Markdown tables, and Markdown image syntax only. "
                + "Do not include Mermaid charts, relationship diagrams, flowcharts, or generated chart images in Word/docx content. "
                + "Put one blank line before and after each heading, list, table, and paragraph. "
                + "Never concatenate headings, list items, tables, images, or paragraphs on the same line."
                : ".txt".equalsIgnoreCase(extension)
                        ? "content must be plain text without any Markdown formatting. "
                        + "Do not use '#' for headings, '*' or '_' for bold/italic, '-' for bullet lists, or any other Markdown syntax. "
                        + "Use plain text with line breaks and spacing for structure."
                        : "content must be the full body of the file as valid Markdown. "
                        + "Use '# Title', '## Section', normal paragraphs, '- ' bullet lists, '1. ' numbered lists and simple Markdown tables. "
                        + "Put one blank line before and after each heading, list, table, and paragraph. "
                        + "Never concatenate headings, list items, tables, or paragraphs on the same line.";
        String imageRules = docx && hasImages
                ? " Uploaded image paths available for embedding: " + String.join(", ", uploadedImagePaths) + ". "
                + "When the user asks for a Word/docx document, include each relevant uploaded image in content using Markdown image syntax like ![caption](workspace-relative-path). "
                + "Use the exact paths listed above. Do not use data URLs for document images."
                : "";
        String overrideDir = com.relic.tool.ToolExecutor.currentWorkingDirectory();
        boolean overrideActive = overrideDir != null && !overrideDir.isBlank();
        String filenameRule = overrideActive
                ? "filename must be a short bare file name (no directory, no drive letter) ending with " + extension
                + ". The system will automatically place the file under the user's selected working directory ("
                + overrideDir + "). Do not include any absolute path or drive prefix even if the user's message mentions one."
                : "filename must be a short workspace-relative file name ending with " + extension + ".";
        promptMessages.add(Map.of(
                "role", "system",
                "content", "You are generating exactly one downloadable file for the user. "
                        + "Do not mention tools, function calls, workspace checks, or fake code like list_files(). "
                        + "Return JSON only with keys filename, title, content, reply. "
                        + "reply must be an object with key message. "
                        + "reply.message must be one short sentence confirming that the file was generated. "
                        + "Do not summarize content in reply.message. "
                        + "Do not wrap JSON in markdown fences. "
                        + filenameRule + " "
                        + contentRules + " "
                        + imageRules + " "
                        + "If the user did not specify a filename, choose a concise descriptive one."
        ));
        promptMessages.addAll(messages);
        return promptMessages;
    }

    private List<String> extractUploadedImagePaths(List<Map<String, Object>> messages) {
        List<String> paths = new ArrayList<>();
        if (messages == null) {
            return paths;
        }
        for (Map<String, Object> message : messages) {
            if (message == null) {
                continue;
            }
            collectUploadedImagePaths(message.get("content"), paths);
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    private void collectUploadedImagePaths(Object content, List<String> paths) {
        if (content instanceof String text) {
            collectImagePathsFromText(text, paths);
            return;
        }
        if (!(content instanceof List<?> parts)) {
            return;
        }
        for (Object part : parts) {
            if (!(part instanceof Map<?, ?> rawPart)) {
                continue;
            }
            Map<String, Object> partMap = (Map<String, Object>) rawPart;
            Object type = partMap.get("type");
            if ("text".equals(type)) {
                collectImagePathsFromText(asText(partMap.get("text")), paths);
            }
            if ("input_file".equals(type)) {
                Object inputFile = partMap.get("input_file");
                if (inputFile instanceof Map<?, ?> rawFile) {
                    addImagePath(asText(((Map<String, Object>) rawFile).get("filename")), paths);
                }
            }
        }
    }

    private void collectImagePathsFromText(String text, List<String> paths) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String line : text.split("\\R")) {
            int prefix = line.indexOf("Uploaded image files in workspace:");
            if (prefix >= 0) {
                String value = line.substring(prefix + "Uploaded image files in workspace:".length()).trim();
                int instruction = value.indexOf(". If creating");
                if (instruction >= 0) {
                    value = value.substring(0, instruction);
                }
                for (String item : value.split(",")) {
                    addImagePath(item.trim(), paths);
                }
            }
        }
        Matcher matcher = IMAGE_FILENAME_PATTERN.matcher(text);
        while (matcher.find()) {
            addImagePath(matcher.group(1), paths);
        }
    }

    private void addImagePath(String path, List<String> paths) {
        String clean = path == null ? "" : path.trim();
        if (clean.isBlank() || !isSupportedDocumentImagePath(clean) || paths.contains(clean)) {
            return;
        }
        paths.add(clean);
    }

    private boolean isSupportedDocumentImagePath(String path) {
        String lowered = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lowered.endsWith(".png")
                || lowered.endsWith(".jpg")
                || lowered.endsWith(".jpeg")
                || lowered.endsWith(".gif")
                || lowered.endsWith(".bmp")
                || lowered.endsWith(".webp");
    }

    private String ensureImageReferences(String content, List<String> uploadedImagePaths) {
        if (uploadedImagePaths == null || uploadedImagePaths.isEmpty()) {
            return content == null ? "" : content;
        }
        String result = content == null ? "" : content;
        StringBuilder missing = new StringBuilder();
        int index = 1;
        for (String path : uploadedImagePaths) {
            if (path == null || path.isBlank() || result.contains(path)) {
                index++;
                continue;
            }
            if (missing.isEmpty()) {
                missing.append("\n\n## Images\n\n");
            }
            missing.append("![Image ").append(index).append("](").append(path).append(")\n\n");
            index++;
        }
        return missing.isEmpty() ? result : result.stripTrailing() + missing;
    }

    private FileDraft parseFileDraft(String raw, String latestUserText, String extension) {
        String response = raw == null ? "" : raw.trim();
        String jsonCandidate = extractJsonObject(response);
        if (!jsonCandidate.isBlank()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(jsonCandidate, new TypeReference<>() {});
                String filename = normalizeGeneratedFilename(stripDriveIfOverrideActive(firstNonBlank(
                        asText(parsed.get("filename")),
                        asText(parsed.get("file_path")),
                        asText(parsed.get("path")),
                        inferFilenameFromUserTextSmart(latestUserText, extension)
                )), extension);
                String title = firstNonBlank(
                        asText(parsed.get("title")),
                        inferTitleFromFilename(filename)
                );
                String content = firstNonBlank(
                        asText(parsed.get("content")),
                        asText(parsed.get("body")),
                        stripCodeFence(response)
                );
                String replyMessage = extractStructuredReplyMessage(parsed);
                return new FileDraft(filename, title, normalizeGeneratedMarkdown(content, title), replyMessage);
            } catch (Exception ignored) {
                // fall through to text fallback
            }
        }

        String filename = inferFilenameFromUserTextSmart(latestUserText, extension);
        String title = inferTitleFromFilename(filename);
        return new FileDraft(filename, title, normalizeGeneratedMarkdown(stripCodeFence(response), title), "");
    }

    private String normalizeGeneratedMarkdown(String content, String title) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('｜', '|')
                .trim();

        normalized = normalized.replaceAll("(?m)([^\\n])\\s*(#{1,6})(?=\\S)", "$1\n\n$2 ");
        normalized = normalized.replaceAll("(?m)^(\\s{0,3}#{1,6})([^\\s#])", "$1 $2");
        normalized = normalized.replaceAll("(?m)^(\\s{0,3}#{1,6}\\s+.*?)(\\s+#{1,6})\\s*$", "$1");
        normalized = normalized.replaceAll("([。；;!！?？])\\s*([一二三四五六七八九十]+、)", "$1\n\n$2");
        normalized = normalized.replaceAll("([。；;!！?？])\\s*(\\d+[.)]\\s+)", "$1\n\n$2");
        normalized = normalized.replaceAll("([：:；;。!！?？])\\s+-\\s+", "$1\n\n- ");

        List<String> lines = Stream.of(normalized.split("\\n"))
                .map(String::stripTrailing)
                .collect(Collectors.toCollection(ArrayList::new));
        List<String> output = new ArrayList<>();
        boolean hasHeading = false;
        boolean previousBlank = true;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isBlank()) {
                if (!previousBlank) {
                    output.add("");
                    previousBlank = true;
                }
                continue;
            }

            line = normalizeMarkdownLine(line);
            boolean currentBlock = isMarkdownBlockLine(line);
            boolean previousBlock = !output.isEmpty() && isMarkdownBlockLine(output.get(output.size() - 1));

            if (isMarkdownHeading(line)) {
                hasHeading = true;
            }
            if (currentBlock && !previousBlank && !previousBlock) {
                output.add("");
            }
            output.add(line);
            previousBlank = false;

            String next = i + 1 < lines.size() ? normalizeMarkdownLine(lines.get(i + 1).trim()) : "";
            if (currentBlock && !next.isBlank() && !isSameMarkdownGroup(line, next)) {
                output.add("");
                previousBlank = true;
            }
        }

        while (!output.isEmpty() && output.get(output.size() - 1).isBlank()) {
            output.remove(output.size() - 1);
        }

        String result = String.join("\n", output).replaceAll("\\n{3,}", "\n\n").trim();
        if (!hasHeading && title != null && !title.isBlank()) {
            result = "# " + stripMarkdownSyntax(title).trim() + "\n\n" + result;
        }
        return result + "\n";
    }

    private String stripMarkdownSyntax(String text) {
        return text == null ? "" : text
                .replaceAll("^#{1,6}\\s*", "")
                .replaceAll("\\s*#{1,6}$", "")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .trim();
    }

    @SuppressWarnings("unchecked")
    private String extractStructuredReplyMessage(Map<String, Object> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            return "";
        }
        Object reply = parsed.get("reply");
        if (reply instanceof Map<?, ?> rawReply) {
            Map<String, Object> replyMap = (Map<String, Object>) rawReply;
            String message = firstNonBlank(
                    asText(replyMap.get("message")),
                    asText(replyMap.get("text")),
                    asText(replyMap.get("summary"))
            );
            if (!message.isBlank()) {
                return message;
            }
        }
        return firstNonBlank(
                asText(parsed.get("replyMessage")),
                asText(parsed.get("reply_message")),
                asText(parsed.get("message"))
        );
    }

    private String normalizeMarkdownLine(String line) {
        String value = line == null ? "" : line.trim();
        value = value.replaceFirst("^(#{1,6})([^\\s#])", "$1 $2");
        value = value.replaceAll("\\s+#{1,6}$", "");
        value = value.replaceFirst("^([一二三四五六七八九十]+)、\\s*", "## $1、");
        return value;
    }

    private boolean isMarkdownBlockLine(String line) {
        return isMarkdownHeading(line)
                || isMarkdownListLine(line)
                || looksLikeMarkdownTableLine(line)
                || line.startsWith("```");
    }

    private boolean isMarkdownHeading(String line) {
        return line != null && line.matches("^#{1,6}\\s+.+");
    }

    private boolean isMarkdownListLine(String line) {
        return line != null && line.matches("^([-*+]\\s+.+|\\d+[.)]\\s+.+)");
    }

    private boolean looksLikeMarkdownTableLine(String line) {
        return line != null && line.contains("|") && line.chars().filter(ch -> ch == '|').count() >= 2;
    }

    private boolean isSameMarkdownGroup(String current, String next) {
        if (current == null || next == null || next.isBlank()) {
            return false;
        }
        if (looksLikeMarkdownTableLine(current) && looksLikeMarkdownTableLine(next)) {
            return true;
        }
        return isMarkdownListLine(current) && isMarkdownListLine(next);
    }

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String candidate = text.trim();
        if (candidate.startsWith("```")) {
            candidate = candidate.replaceFirst("^```(?:json)?\\s*", "");
            candidate = candidate.replaceFirst("\\s*```$", "");
        }

        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return candidate.substring(start, end + 1);
        }
        return "";
    }

    private String stripCodeFence(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.trim()
                .replaceFirst("^```(?:json|markdown|md|text)?\\s*", "")
                .replaceFirst("\\s*```$", "")
                .trim();
    }

    /**
     * 当用户已在 UI 选择工作目录时，剥离绝对路径前缀，仅保留 basename，
     * 让最终的 resolveAndValidateWritePath 能落到工作目录中。
     */
    private String stripDriveIfOverrideActive(String filename) {
        if (filename == null || filename.isBlank()) {
            return filename;
        }
        if (!com.relic.tool.ToolExecutor.hasWorkingDirectoryOverride()) {
            return filename;
        }
        String normalized = filename.replace('\\', '/');
        // 形如 E:/foo/bar.txt 或 /abs/path/bar.txt 都视为绝对路径
        boolean drive = normalized.length() >= 2
                && Character.isLetter(normalized.charAt(0))
                && normalized.charAt(1) == ':';
        boolean unixAbs = normalized.startsWith("/");
        if (!drive && !unixAbs) {
            return filename;
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private String inferFilenameFromUserTextSmart(String latestUserText, String extension) {
        String fallback = ".docx".equalsIgnoreCase(extension) ? "document.docx"
                : ".txt".equalsIgnoreCase(extension) ? "document.txt" : "document.md";
        if (latestUserText == null || latestUserText.isBlank()) {
            return fallback;
        }

        boolean overrideActive = com.relic.tool.ToolExecutor.hasWorkingDirectoryOverride();

        // 1. 完整绝对路径（如 E:/folder/report.docx）
        Matcher absMatcher = ABSOLUTE_PATH_PATTERN.matcher(latestUserText);
        if (absMatcher.find()) {
            String absolutePath = absMatcher.group(1).replace('\\', '/');
            // 用户已选择工作目录时，UI 选择优先：剥离绝对路径前缀，仅保留 basename
            if (overrideActive) {
                int slash = absolutePath.lastIndexOf('/');
                String basename = slash >= 0 ? absolutePath.substring(slash + 1) : absolutePath;
                return normalizeGeneratedFilename(basename, extension);
            }
            return absolutePath;
        }

        // 2. 用户分别提到目录前缀和文件名（如 "在 E:/ 下创建 test.txt"），合并两者
        Matcher driveMatcher = DRIVE_DIR_PATTERN.matcher(latestUserText);
        if (driveMatcher.find()) {
            Matcher asciiFile = ASCII_FILENAME_PATTERN.matcher(latestUserText);
            // 用户已选择工作目录时，忽略消息里的盘符前缀，使用工作目录解析
            if (overrideActive) {
                if (asciiFile.find()) {
                    String fileOnly = asciiFile.group(1).replace('\\', '/');
                    while (fileOnly.startsWith("/")) {
                        fileOnly = fileOnly.substring(1);
                    }
                    return normalizeGeneratedFilename(fileOnly, extension);
                }
            } else {
                String driveDir = driveMatcher.group(1).replace('\\', '/');
                if (!driveDir.endsWith("/")) {
                    driveDir = driveDir + "/";
                }
                if (asciiFile.find()) {
                    String fileOnly = asciiFile.group(1).replace('\\', '/');
                    while (fileOnly.startsWith("/")) {
                        fileOnly = fileOnly.substring(1);
                    }
                    return driveDir + fileOnly;
                }
                String lowered = latestUserText.toLowerCase(Locale.ROOT);
                String slug = buildTopicSlug(lowered);
                if (!slug.isBlank()) {
                    return driveDir + normalizeGeneratedFilename(slug + extension, extension);
                }
                return driveDir + normalizeGeneratedFilename(fallback, extension);
            }
        }

        Matcher matcher = FILENAME_PATTERN.matcher(latestUserText);
        if (matcher.find()) {
            return normalizeGeneratedFilename(matcher.group(1), extension);
        }

        String lowered = latestUserText.toLowerCase(Locale.ROOT);
        String slug = buildTopicSlug(lowered);
        if (!slug.isBlank()) {
            return normalizeGeneratedFilename(slug + extension, extension);
        }
        return normalizeGeneratedFilename(fallback, extension);
    }

    private String buildTopicSlug(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        appendTopicPart(parts, text, "uk", "英国", "britain", "british", "england", "united kingdom", "uk");
        appendTopicPart(parts, text, "france", "法国", "french", "france");
        appendTopicPart(parts, text, "wechat", "微信", "wechat");
        appendTopicPart(parts, text, "history", "历史", "history");
        appendTopicPart(parts, text, "education-system", "教育体系", "education system");
        appendTopicPart(parts, text, "education", "教育", "education");
        appendTopicPart(parts, text, "politics", "政治", "politics", "political");
        appendTopicPart(parts, text, "diplomacy", "外交", "diplomacy", "diplomatic");
        appendTopicPart(parts, text, "economy", "经济", "economy", "economic", "gdp");
        appendTopicPart(parts, text, "technology", "技术", "科技", "technology", "tech");
        appendTopicPart(parts, text, "introduction", "介绍", "简介", "概述", "introduction", "overview");
        appendTopicPart(parts, text, "report", "报告", "report");
        appendTopicPart(parts, text, "guide", "指南", "guide");
        appendTopicPart(parts, text, "comparison", "对比", "比较", "comparison", "compare");
        appendTopicPart(parts, text, "relationship", "关系", "关系图", "relationship", "relations");
        appendTopicPart(parts, text, "timeline", "时间线", "timeline");

        if (!parts.isEmpty()) {
            return String.join("-", parts);
        }

        String ascii = text.replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (ascii.isBlank()) {
            return "";
        }
        if (ascii.length() > 48) {
            ascii = ascii.substring(0, 48).replaceAll("-+$", "");
        }
        return ascii;
    }

    private void appendTopicPart(List<String> parts, String text, String slug, String... hints) {
        if (parts.contains(slug)) {
            return;
        }
        for (String hint : hints) {
            if (text.contains(hint.toLowerCase(Locale.ROOT))) {
                parts.add(slug);
                return;
            }
        }
    }

    private String normalizeGeneratedFilename(String filename, String extension) {
        String value = firstNonBlank(filename, "document" + extension).trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.isBlank()) {
            value = "document" + extension;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(extension.toLowerCase(Locale.ROOT))) {
            int slash = value.lastIndexOf('/');
            String baseName = slash >= 0 ? value.substring(slash + 1) : value;
            int dot = baseName.lastIndexOf('.');
            if (dot > 0) {
                value = value.substring(0, value.length() - (baseName.length() - dot)) + extension;
            } else {
                value = value + extension;
            }
        }
        return value;
    }

    private String inferTitleFromFilename(String filename) {
        String safe = firstNonBlank(filename, "document");
        String base = safe.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0 && slash < base.length() - 1) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replace('-', ' ').replace('_', ' ').trim();
        return base.isBlank() ? "Document" : base;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String executeAndAppend(List<ToolCallResult.ToolCall> toolCalls,
                                    List<Map<String, Object>> conversation,
                                    CreateGuard createGuard) {
        StringBuilder directOutput = new StringBuilder();
        for (ToolCallResult.ToolCall tc : toolCalls) {
            if (isBlockedRepeatedChartCall(tc, createGuard)) {
                conversation.add(buildToolMessage(tc, repeatedChartBlockedMessage()));
                conversation.add(buildChartFileCreationInstruction(createGuard));
                continue;
            }

            String result = executeToolWithGuard(tc, createGuard);
            logToolResult(tc.getName(), result);

            boolean retryRequired = isChartValidationError(result);
            ChartQualityResult qualityResult = validateChartQuality(result);
            if (!retryRequired && !qualityResult.ok() && createGuard.chartRepairCount < 1) {
                retryRequired = true;
                result = ToolExecutor.CHART_VALIDATION_ERROR_MARKER + " " + qualityResult.message();
            }
            if (retryRequired && createGuard.chartRepairCount < 1) {
                createGuard.chartRepairCount++;
                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tc.getId());
                toolMsg.put("content", result);
                conversation.add(toolMsg);
                conversation.add(buildChartRetryInstruction(tc.getName()));
                continue;
            }

            if (!(createGuard.chartFileOutput && isChartTool(tc.getName()))) {
                directOutput.append(buildDirectToolOutput(result));
            }
            if (isChartTool(tc.getName()) && hasInlineChart(result)) {
                createGuard.chartCount++;
            }

            Map<String, Object> toolMsg = new HashMap<>();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", tc.getId());
            toolMsg.put("content", buildToolMessageContent(tc.getName(), result, createGuard));
            conversation.add(toolMsg);

            if (isCreateTool(tc.getName()) && hasDownloadUrl(result)) {
                break;
            }
        }
        return directOutput.toString();
    }

    private String executeToolWithGuard(ToolCallResult.ToolCall tc, CreateGuard guard) {
        String toolName = tc.getName();
        if (isBlockedRepeatedChartCall(tc, guard)) {
            return repeatedChartBlockedMessage();
        }
        if (isChartTool(toolName) && guard.chartCount >= guard.maxCharts) {
            return "Chart generation limit reached for this user turn (" + guard.maxCharts + "). Wait for the user next message before generating another chart.";
        }
        if (isCreateTool(toolName) && guard.createdCount >= guard.maxCreates) {
            return "已达到本次请求可创建文件上限(" + guard.maxCreates + ")。如需一次生成多个文件，请明确说明“生成多个文件”。";
        }

        String result = executeToolWithTimeout(toolName, tc.getArgumentsString());

        if (isCreateTool(toolName) && hasDownloadUrl(result)) {
            guard.createdCount++;
        }
        return result;
    }

    private String executeToolWithTimeout(String toolName, String arguments) {
        RequestDeadline.throwIfExpired();
        long timeoutMs = RequestDeadline.remainingMillis(Math.max(1_000L, toolExecutionTimeoutMs));
        if (timeoutMs <= 0L) {
            return "请求处理超时，请稍后重试。";
        }
        Long deadline = RequestDeadline.currentDeadlineEpochMillis();
        String workingDirectory = ToolExecutor.currentWorkingDirectory();
        CompletableFuture<String> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                RequestDeadline.setDeadlineEpochMillis(deadline);
                ToolExecutor.setWorkingDirectoryContext(workingDirectory);
                try {
                    return toolExecutor.execute(toolName, arguments);
                } finally {
                    RequestDeadline.clear();
                    ToolExecutor.clearWorkingDirectoryContext();
                }
            }, toolWorkerExecutor);
        } catch (Exception e) {
            log.warn("[tool-rejected] {} could not be scheduled: {}", toolName, e.getMessage());
            return "工具执行队列已满，请稍后重试。";
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[tool-timeout] {} exceeded {} ms", toolName, timeoutMs);
            return "工具执行超时，请稍后重试或缩小本次请求。";
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return "工具执行被中断，请稍后重试。";
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("[tool-error] {} failed: {}", toolName, cause.getMessage());
            return "工具调用失败：" + cause.getMessage();
        }
    }

    private boolean isBlockedRepeatedChartCall(ToolCallResult.ToolCall tc, CreateGuard guard) {
        return tc != null
                && guard != null
                && guard.chartFileOutput
                && guard.chartCount > 0
                && guard.createdCount == 0
                && isChartTool(tc.getName());
    }

    private Map<String, Object> buildToolMessage(ToolCallResult.ToolCall tc, String content) {
        Map<String, Object> toolMsg = new HashMap<>();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", tc.getId());
        toolMsg.put("content", content);
        return toolMsg;
    }

    private String repeatedChartBlockedMessage() {
        return "Chart already generated for this chart-document request. Do not call render_mermaid_chart again. Call the file creation tool now.";
    }

    private boolean isCreateTool(String toolName) {
        return "create_text_file".equals(toolName)
                || "create_docx_file".equals(toolName);
    }

    private boolean isChartTool(String toolName) {
        return "render_mermaid_chart".equals(toolName);
    }

    private String buildToolMessageContent(String toolName, String toolResult, CreateGuard createGuard) {
        if (createGuard.chartFileOutput && isChartTool(toolName)) {
            String source = extractChartSourceFromToolResult(toolResult);
            if (!source.isBlank()) {
                String visible = removeSpecialToolLines(toolResult);
                StringBuilder sb = new StringBuilder();
                if (visible != null && !visible.isBlank()) {
                    sb.append(visible).append("\n\n");
                }
                sb.append("Generated chart source for the document:\n```mermaid\n")
                        .append(source)
                        .append("\n```");
                return sb.toString();
            }
        }
        return removeSpecialToolLines(toolResult);
    }

    private boolean hasDownloadUrl(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return false;
        }
        return DOWNLOAD_URL_PATTERN.matcher(toolResult).find();
    }

    private boolean hasInlineChart(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return false;
        }
        return INLINE_CHART_PATTERN.matcher(toolResult).find();
    }

    private boolean hasStructuredChart(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return false;
        }
        return STRUCTURED_CHART_PATTERN.matcher(toolResult).find();
    }

    private ChartQualityResult validateChartQuality(String toolResult) {
        if (toolResult == null || toolResult.isBlank() || !hasInlineChart(toolResult)) {
            return ChartQualityResult.pass();
        }

        String source = extractChartSourceFromToolResult(toolResult);
        if (source.isBlank()) {
            return ChartQualityResult.fail("The chart result did not contain usable Mermaid source.");
        }

        String trimmed = source.stripLeading();
        String lower = trimmed.toLowerCase();
        String diagramType = detectMermaidType(lower);
        if ("unknown".equals(diagramType)) {
            return ChartQualityResult.fail("The Mermaid source must start with a supported diagram type such as flowchart, graph, sequenceDiagram, classDiagram, erDiagram, stateDiagram, mindmap, timeline, gantt, pie, xychart-beta, journey, quadrantChart, sankey-beta, block-beta, gitGraph or architecture-beta.");
        }

        int minLength = minimumUsefulLength(diagramType);
        int maxLength = maximumUsefulLength(diagramType);
        if (trimmed.length() < minLength) {
            return ChartQualityResult.fail("The chart is too small to be useful. Add enough meaningful nodes, labels or data points.");
        }
        if (trimmed.length() > maxLength) {
            return ChartQualityResult.fail("The chart is too large. Simplify it into a readable single diagram.");
        }

        if ("flowchart".equals(diagramType)) {
            int labels = countFlowchartLabels(source);
            int edges = countMatches(source, "(-->|---|<-->|==>|-.->)");
            int topLevelBranches = countTopLevelBranches(source);
            if (labels < 2) {
                return ChartQualityResult.fail("Flowcharts need at least two meaningful labeled nodes.");
            }
            if (edges < 1) {
                return ChartQualityResult.fail("Flowcharts need at least one relationship or link between nodes.");
            }
            if (labels > 18) {
                return ChartQualityResult.fail("This flowchart has too many nodes for a default summary chart. Redraw it as a concise overview with one center topic, no more than 5 top-level dimensions and no more than 18 total nodes. Keep only the most important points.");
            }
            if (edges > 24) {
                return ChartQualityResult.fail("This flowchart has too many links and is hard to scan. Redraw it with no more than 24 edges, grouping related details under a few top-level dimensions.");
            }
            if (topLevelBranches > 6) {
                return ChartQualityResult.fail("This flowchart has too many first-level branches. Merge related branches into no more than 5 top-level dimensions and keep the diagram focused.");
            }
            if (containsMeaninglessLabels(source)) {
                return ChartQualityResult.fail("The chart contains vague labels such as node1/item1/relationship1. Replace them with topic-specific labels.");
            }
            if (containsOverlongLabel(source)) {
                return ChartQualityResult.fail("Some node labels are too long. Shorten labels and split details into separate nodes.");
            }
        }

        if ("mindmap".equals(diagramType)) {
            int entries = countNonEmptyDiagramLines(source);
            if (entries < 3) {
                return ChartQualityResult.fail("Mind maps need one clear root and at least two meaningful branches.");
            }
            if (entries > 22) {
                return ChartQualityResult.fail("This mind map is too dense. Redraw it with no more than 5 top-level branches and about 20 total entries.");
            }
        }

        if (("sequenceDiagram".equals(diagramType) || "classDiagram".equals(diagramType)
                || "erDiagram".equals(diagramType) || "stateDiagram".equals(diagramType))
                && countNonEmptyDiagramLines(source) < 3) {
            return ChartQualityResult.fail("This Mermaid diagram is too sparse. Add enough participants, entities, states or relationships to make it useful.");
        }

        if ("gantt".equals(diagramType) && countMatches(source, "(?m)^\\s*[^\\n:]+\\s*:") < 2) {
            return ChartQualityResult.fail("Gantt charts need at least two scheduled items.");
        }

        if ("timeline".equals(diagramType) && countNonEmptyDiagramLines(source) < 3) {
            return ChartQualityResult.fail("Timelines need at least two meaningful events.");
        }

        if ("pie".equals(diagramType) && countMatches(source, "\"[^\"]+\"\\s*:") < 2) {
            return ChartQualityResult.fail("Pie charts need at least two labeled slices.");
        }
        if ("xychart".equals(diagramType) && !lower.contains("bar [") && !lower.contains("line [")) {
            return ChartQualityResult.fail("XY charts must include bar [...] or line [...] data.");
        }

        return ChartQualityResult.pass();
    }

    private String extractChartSourceFromToolResult(String toolResult) {
        Matcher structuredMatcher = STRUCTURED_CHART_PATTERN.matcher(toolResult);
        if (structuredMatcher.find()) {
            String source = extractJsonStringField(structuredMatcher.group(1), "source");
            if (!source.isBlank()) {
                return source;
            }
        }

        Matcher inlineMatcher = INLINE_CHART_PATTERN.matcher(toolResult);
        if (inlineMatcher.find()) {
            Matcher fenceMatcher = MERMAID_FENCE_PATTERN.matcher(inlineMatcher.group(1));
            if (fenceMatcher.find()) {
                return fenceMatcher.group(1).trim();
            }
        }

        Matcher fenceMatcher = MERMAID_FENCE_PATTERN.matcher(toolResult);
        if (fenceMatcher.find()) {
            return fenceMatcher.group(1).trim();
        }
        return "";
    }

    private boolean startsWithSupportedMermaidType(String lowerSource) {
        return lowerSource.startsWith("flowchart")
                || lowerSource.startsWith("graph")
                || lowerSource.startsWith("sequencediagram")
                || lowerSource.startsWith("classdiagram")
                || lowerSource.startsWith("erdiagram")
                || lowerSource.startsWith("statediagram")
                || lowerSource.startsWith("mindmap")
                || lowerSource.startsWith("timeline")
                || lowerSource.startsWith("gantt")
                || lowerSource.startsWith("pie")
                || lowerSource.startsWith("xychart-beta")
                || lowerSource.startsWith("journey")
                || lowerSource.startsWith("quadrantchart")
                || lowerSource.startsWith("sankey-beta")
                || lowerSource.startsWith("block-beta")
                || lowerSource.startsWith("gitgraph")
                || lowerSource.startsWith("architecture-beta");
    }

    private String detectMermaidType(String lowerSource) {
        if (lowerSource.startsWith("flowchart") || lowerSource.startsWith("graph")) {
            return "flowchart";
        }
        if (lowerSource.startsWith("sequencediagram")) {
            return "sequenceDiagram";
        }
        if (lowerSource.startsWith("classdiagram")) {
            return "classDiagram";
        }
        if (lowerSource.startsWith("erdiagram")) {
            return "erDiagram";
        }
        if (lowerSource.startsWith("statediagram")) {
            return "stateDiagram";
        }
        if (lowerSource.startsWith("mindmap")) {
            return "mindmap";
        }
        if (lowerSource.startsWith("timeline")) {
            return "timeline";
        }
        if (lowerSource.startsWith("gantt")) {
            return "gantt";
        }
        if (lowerSource.startsWith("pie")) {
            return "pie";
        }
        if (lowerSource.startsWith("xychart-beta")) {
            return "xychart";
        }
        if (lowerSource.startsWith("journey")) {
            return "journey";
        }
        if (lowerSource.startsWith("quadrantchart")) {
            return "quadrantChart";
        }
        if (lowerSource.startsWith("sankey-beta")) {
            return "sankey";
        }
        if (lowerSource.startsWith("block-beta")) {
            return "block";
        }
        if (lowerSource.startsWith("gitgraph")) {
            return "gitGraph";
        }
        if (lowerSource.startsWith("architecture-beta")) {
            return "architecture";
        }
        return "unknown";
    }

    private int minimumUsefulLength(String diagramType) {
        return switch (diagramType) {
            case "pie", "xychart", "timeline" -> 25;
            case "sequenceDiagram", "classDiagram", "erDiagram", "stateDiagram", "gantt" -> 35;
            default -> 40;
        };
    }

    private int maximumUsefulLength(String diagramType) {
        return switch (diagramType) {
            case "sequenceDiagram", "classDiagram", "erDiagram", "stateDiagram", "gantt" -> 18_000;
            case "pie", "xychart", "timeline", "journey", "quadrantChart", "sankey", "block", "gitGraph", "architecture" -> 14_000;
            case "mindmap" -> 10_000;
            default -> 12_000;
        };
    }

    private int countMatches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int countFlowchartLabels(String source) {
        return countMatches(source, "\\[[^\\]]{2,}\\]")
                + countMatches(source, "\\([^\\)]{2,}\\)")
                + countMatches(source, "\\{[^\\}]{2,}\\}")
                + countMatches(source, "\"[^\"]{2,}\"");
    }

    private int countTopLevelBranches(String source) {
        Map<String, Integer> outgoing = new HashMap<>();
        Matcher matcher = Pattern.compile("(?m)^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*(?:-->|---|<-->|==>|-.->)").matcher(source);
        while (matcher.find()) {
            String nodeId = matcher.group(1);
            outgoing.put(nodeId, outgoing.getOrDefault(nodeId, 0) + 1);
        }
        return outgoing.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private int countNonEmptyDiagramLines(String source) {
        int count = 0;
        for (String line : source.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()
                    && !trimmed.startsWith("%%")
                    && !trimmed.equalsIgnoreCase("mindmap")
                    && !trimmed.equalsIgnoreCase("timeline")
                    && !trimmed.equalsIgnoreCase("gantt")
                    && !trimmed.equalsIgnoreCase("sequenceDiagram")
                    && !trimmed.equalsIgnoreCase("classDiagram")
                    && !trimmed.equalsIgnoreCase("erDiagram")
                    && !trimmed.equalsIgnoreCase("stateDiagram")) {
                count++;
            }
        }
        return count;
    }

    private boolean containsMeaninglessLabels(String source) {
        return Pattern.compile("\\[(?:node|item|label|relationship|relation|节点|项目|关系)\\s*\\d+\\]", Pattern.CASE_INSENSITIVE)
                .matcher(source)
                .find();
    }

    private boolean containsOverlongLabel(String source) {
        Matcher matcher = Pattern.compile("\\[([^\\]]+)\\]").matcher(source);
        while (matcher.find()) {
            if (matcher.group(1).length() > 80) {
                return true;
            }
        }
        return false;
    }

    private boolean isChartValidationError(String toolResult) {
        return toolResult != null && toolResult.contains(ToolExecutor.CHART_VALIDATION_ERROR_MARKER);
    }

    private Map<String, Object> buildChartRetryInstruction(String failedToolName) {
        String retryToolName = "render_mermaid_chart";
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content",
                "The previous " + failedToolName + " call failed validation. "
                        + "You must immediately call " + retryToolName + " again. "
                        + "Do not answer in natural language. "
                        + "Use content/mermaidSource with complete Mermaid syntax, and every placeholder node id must have a topic-specific display label, e.g. P1[actual meaning], D1[actual meaning], E1[actual meaning].");
        return msg;
    }

    private Map<String, Object> buildChartFileCreationInstruction(CreateGuard createGuard) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content",
                "The chart has already been generated, but the downloadable file has not been created yet. "
                        + "You must now call create_docx_file or create_text_file exactly once according to the user's requested file type. "
                        + "Do not answer in natural language and do not output the Mermaid chart directly. "
                        + "For docx, include the previously generated Mermaid source in the content as a ```mermaid ... ``` fenced block so it is embedded into the Word document. "
                        + "Remaining file creations allowed: " + Math.max(0, createGuard.maxCreates - createGuard.createdCount) + ".");
        return msg;
    }

    private void logToolResult(String name, String result) {
        log.info("[tool-result] {} -> {}", name, result.length() > 200 ? result.substring(0, 200) + "..." : result);
    }

    private void emitDownloadLinkIfPresent(String toolResult, Consumer<String> onChunk) {
        if (toolResult == null || toolResult.isBlank()) {
            return;
        }
        Matcher matcher = DOWNLOAD_URL_PATTERN.matcher(toolResult);
        if (!matcher.find()) {
            return;
        }
        onChunk.accept(fileGeneratedMessage(matcher.group(1), false));
    }

    private void emitStructuredChartIfPresent(String toolResult, Consumer<String> onChunk) {
        if (toolResult == null || toolResult.isBlank()) {
            return;
        }
        Matcher matcher = STRUCTURED_CHART_PATTERN.matcher(toolResult);
        if (matcher.find()) {
            onChunk.accept("\n" + ToolExecutor.STRUCTURED_CHART_MARKER + matcher.group(1) + "\n");
        }
    }

    private String buildDirectToolOutput(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return "";
        }

        StringBuilder output = new StringBuilder();
        Matcher downloadMatcher = DOWNLOAD_URL_PATTERN.matcher(toolResult);
        if (downloadMatcher.find()) {
            output.append(fileGeneratedMessage(downloadMatcher.group(1), false));
        }

        Matcher structuredMatcher = STRUCTURED_CHART_PATTERN.matcher(toolResult);
        if (structuredMatcher.find()) {
            if (output.isEmpty()) {
                String chartIntro = buildChartReplyIntro(structuredMatcher.group(1));
                if (!chartIntro.isBlank()) {
                    output.append("\n").append(chartIntro).append("\n");
                }
            }
            output.append("\n")
                    .append(ToolExecutor.STRUCTURED_CHART_MARKER)
                    .append(structuredMatcher.group(1))
                    .append("\n");
        } else {
            Matcher chartMatcher = INLINE_CHART_PATTERN.matcher(toolResult);
            if (chartMatcher.find()) {
                String markdown = chartMatcher.group(1).trim();
                if (!markdown.isBlank()) {
                    if (output.isEmpty()) {
                        output.append("\n").append(summarizeChartMarkdown(markdown)).append("\n");
                    }
                    output.append("\n").append(markdown).append("\n");
                }
            }
        }

        return output.toString();
    }

    private String buildDirectFileOutput(String toolResult, String replyMessage) {
        return buildDirectFileOutput(toolResult, replyMessage, false);
    }

    private String buildDirectFileOutput(String toolResult, String replyMessage, boolean docxChartDegraded) {
        if (toolResult == null || toolResult.isBlank()) {
            return "";
        }

        Matcher downloadMatcher = DOWNLOAD_URL_PATTERN.matcher(toolResult);
        if (!downloadMatcher.find()) {
            return buildDirectToolOutput(toolResult);
        }

        return fileGeneratedMessage(downloadMatcher.group(1), docxChartDegraded);
    }

    private String sanitizeReplyMessage(String replyMessage) {
        String message = replyMessage == null ? "" : replyMessage.trim();
        if (message.isBlank()) {
            return "";
        }
        message = message
                .replaceAll("(?i)DOWNLOAD_URL:\\s*\\S+", "")
                .replaceAll("\\[[^\\]]{0,30}]\\([^)]*\\)", "")
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (message.length() > 120) {
            message = message.substring(0, 120).trim();
        }
        return message;
    }

    private String buildChartReplyIntro(String structuredPayloadJson) {
        String title = firstNonBlank(extractJsonStringField(structuredPayloadJson, "title"), "图表");
        return "已生成《" + title + "》图表，下面可以直接查看。";
    }

    private String summarizeChartMarkdown(String markdown) {
        String title = extractFirstHeading(markdown);
        return "已生成" + (title.isBlank() ? "图表" : "《" + title + "》图表") + "，下面可以直接查看。";
    }

    private String extractFirstHeading(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                return trimmed.replaceFirst("^#{1,6}\\s*", "").replaceAll("\\s*#+$", "").trim();
            }
        }
        return "";
    }

    private String formatDownloadLink(String url) {
        return fileGeneratedMessage(url, false);
    }

    private String fileGeneratedMessage(String url, boolean docxChartDegraded) {
        if (url == null || url.isBlank()) {
            return docxChartDegraded
                    ? "\n\u6587\u4ef6\u5df2\u751f\u6210\uff0c\u5f53\u524d\u751f\u6210\u7684 Word \u6587\u6863\u65e0\u56fe\u3002\n"
                    : "\n\u6587\u4ef6\u5df2\u751f\u6210\u3002\n";
        }
        if (docxChartDegraded) {
            return "\n\u6587\u4ef6\u5df2\u751f\u6210\uff0c\u5f53\u524d\u751f\u6210\u7684 Word \u6587\u6863\u65e0\u56fe\uff1a[\u70b9\u51fb\u4e0b\u8f7d](" + url + ")\n";
        }
        return "\n\u6587\u4ef6\u5df2\u751f\u6210\uff1a[\u70b9\u51fb\u4e0b\u8f7d](" + url + ")\n";
    }

    private String buildFileReplyIntro(String toolResult) {
        String filename = extractCreatedFilename(toolResult);
        if (filename.isBlank()) {
            return "\n我已经帮你整理好文件，可以直接下载查看。\n";
        }

        String displayName = humanizeFilename(filename);
        String formatLabel = detectFileFormatLabel(filename);
        if (formatLabel.isBlank()) {
            return "\n我已经帮你整理好一份" + displayName + "文件，可直接下载查看。\n";
        }
        return "\n我已经帮你整理好一份" + displayName + "的" + formatLabel + "文件，可直接下载查看。\n";
    }

    private String extractCreatedFilename(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return "";
        }
        Matcher matcher = CREATED_FILE_PATTERN.matcher(toolResult);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).trim();
    }

    private String humanizeFilename(String filename) {
        String value = firstNonBlank(filename, "文档").replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash < value.length() - 1) {
            value = value.substring(slash + 1);
        }
        int dot = value.lastIndexOf('.');
        if (dot > 0) {
            value = value.substring(0, dot);
        }
        value = value.replace('-', ' ').replace('_', ' ').trim();
        if (value.isBlank()) {
            return "文档";
        }
        return "《" + value + "》";
    }

    private String detectFileFormatLabel(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx")) {
            return "Word";
        }
        if (lower.endsWith(".md")) {
            return "Markdown";
        }
        if (lower.endsWith(".txt")) {
            return "文本";
        }
        return "";
    }

    private void emitInlineChartIfPresent(String toolResult, Consumer<String> onChunk) {
        if (toolResult == null || toolResult.isBlank()) {
            return;
        }
        Matcher matcher = INLINE_CHART_PATTERN.matcher(toolResult);
        if (!matcher.find()) {
            return;
        }
        String markdown = matcher.group(1).trim();
        if (!markdown.isBlank()) {
            onChunk.accept("\n" + markdown + "\n");
        }
    }

    private List<ToolCallResult.ToolCall> filterExecutableToolCalls(List<ToolCallResult.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        List<ToolCallResult.ToolCall> executable = new ArrayList<>();
        int ignored = 0;

        for (ToolCallResult.ToolCall tc : toolCalls) {
            if (tc == null || tc.getName() == null || tc.getName().isBlank()) {
                ignored++;
                continue;
            }
            executable.add(tc);
        }

        if (ignored > 0) {
            log.warn("[tool-call] ignored {} invalid tool call(s)", ignored);
        }
        return executable;
    }

    private List<Map<String, Object>> enrichChartRevisionContext(List<Map<String, Object>> messages) {
        String latestUserText = extractLatestUserText(messages);
        if (!looksLikeChartRevisionIntent(latestUserText)) {
            return messages;
        }

        String previousChart = extractLatestChartSource(messages);
        if (previousChart.isBlank()) {
            return messages;
        }

        List<Map<String, Object>> enriched = new ArrayList<>();
        enriched.add(Map.of(
                "role", "system",
                "content", "The user is asking to revise the previous chart. Use the previous Mermaid source as the starting point, preserve useful structure, and call render_mermaid_chart once with the revised complete Mermaid source. Previous Mermaid source:\n```mermaid\n" + previousChart + "\n```"
        ));
        enriched.addAll(messages);
        return enriched;
    }

    private List<Map<String, Object>> enrichChartFileOutputContext(List<Map<String, Object>> messages) {
        IntentDecision decision = decideIntent(messages);
        if (decision.outputMode() != OutputMode.CHART_FILE_OUTPUT) {
            return messages;
        }

        List<Map<String, Object>> enriched = new ArrayList<>();
        enriched.add(Map.of(
                "role", "system",
                "content", "The user wants a downloadable file that includes a chart. "
                        + "First call render_mermaid_chart with complete Mermaid syntax. "
                        + "Then call the requested file creation tool exactly once. "
                        + "For Word/docx output, include the chart in the document content as a ```mermaid ... ``` fenced block so the document tool can embed it visually. "
                        + "Do not stop after only rendering the inline chart."
        ));
        enriched.addAll(messages);
        return enriched;
    }

    private String extractLatestChartSource(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object content = messages.get(i).get("content");
            String text = content == null ? "" : content.toString();

            Matcher structuredMatcher = STRUCTURED_CHART_PATTERN.matcher(text);
            if (structuredMatcher.find()) {
                String source = extractJsonStringField(structuredMatcher.group(1), "source");
                if (!source.isBlank()) {
                    return source;
                }
            }

            Matcher fenceMatcher = MERMAID_FENCE_PATTERN.matcher(text);
            String found = "";
            while (fenceMatcher.find()) {
                found = fenceMatcher.group(1).trim();
            }
            if (!found.isBlank()) {
                return found;
            }
        }
        return "";
    }

    private String extractJsonStringField(String json, String fieldName) {
        if (json == null || json.isBlank()) {
            return "";
        }
        Pattern fieldPattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher matcher = fieldPattern.matcher(json);
        if (!matcher.find()) {
            return "";
        }
        return unescapeJsonString(matcher.group(1));
    }

    private String unescapeJsonString(String value) {
        return value
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    /**
     * Deterministic create tool selection:
     * chart intent -> render_mermaid_chart by default
     * non-chart -> create_text_file
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> selectToolsForRequest(List<Map<String, Object>> messages) {
        IntentDecision decision = decideIntent(messages);
        List<Map<String, Object>> allTools = ToolDefinitions.getAll();
        if (decision.outputMode() == OutputMode.PLAIN_REPLY) {
            log.info("[tool-select] mode={}, matched={}, primary_tool=none, tools=0",
                    decision.outputMode(), decision.matchedRules());
            return List.of();
        }
        List<Map<String, Object>> selected = new ArrayList<>();

        for (Map<String, Object> tool : allTools) {
            Object fnObj = tool.get("function");
            if (!(fnObj instanceof Map<?, ?> fnMap)) {
                selected.add(tool);
                continue;
            }
            Object nameObj = ((Map<String, Object>) fnMap).get("name");
            String name = nameObj == null ? "" : nameObj.toString();

            if (decision.chartIntent()) {
                if ("render_mermaid_chart".equals(name)
                        || (decision.docxOutputIntent() && "create_docx_file".equals(name))
                        || (decision.textFileOutputIntent() && "create_text_file".equals(name))
                        || "read_file".equals(name)
                        || "list_files".equals(name)) {
                    selected.add(tool);
                }
                continue;
            }

            if ((decision.docxOutputIntent() && "create_docx_file".equals(name))
                    || (decision.textFileOutputIntent() && "create_text_file".equals(name))
                    || (decision.workspaceReadIntent() && ("read_file".equals(name) || "list_files".equals(name)))) {
                selected.add(tool);
            }
        }

        log.info("[tool-select] mode={}, file_output={}, docx_output={}, text_file_output={}, workspace_read={}, max_charts={}, matched={}, primary_tool={}",
                decision.outputMode(),
                decision.fileOutputIntent(),
                decision.docxOutputIntent(),
                decision.textFileOutputIntent(),
                decision.workspaceReadIntent(),
                decision.maxCharts(),
                decision.matchedRules(),
                decision.primaryTool());
        return selected;
    }

    private CreateGuard buildCreateGuard(List<Map<String, Object>> messages) {
        IntentDecision decision = decideIntent(messages);
        log.info("[create-guard] maxCreates={}, maxCharts={}, matched={}",
                decision.maxCreates(), decision.maxCharts(), decision.matchedRules());
        return new CreateGuard(decision.maxCreates(), decision.maxCharts(), decision.outputMode() == OutputMode.CHART_FILE_OUTPUT);
    }

    private IntentDecision decideIntent(List<Map<String, Object>> messages) {
        String latestUserText = extractLatestUserText(messages);
        List<String> matchedRules = new ArrayList<>();

        boolean forcedInlineChartIntent = looksLikeForcedInlineChartIntent(latestUserText);
        addIf(matchedRules, forcedInlineChartIntent, "CHART_HARD_RULE");

        boolean noFileOutputIntent = looksLikeNoFileOutputIntent(latestUserText);
        addIf(matchedRules, noFileOutputIntent, "NO_FILE_OUTPUT");

        boolean explicitDocxOutputIntent = !forcedInlineChartIntent && looksLikeDocxOutputIntent(latestUserText);
        boolean explicitFileOutputIntent = !forcedInlineChartIntent
                && (explicitDocxOutputIntent || looksLikeFileOutputIntent(latestUserText));
        addIf(matchedRules, explicitFileOutputIntent, "FILE_OUTPUT");
        boolean fileOutputIntent = !noFileOutputIntent && explicitFileOutputIntent;
        boolean explicitTextFileOutputIntent = fileOutputIntent && looksLikeTextFileOutputIntent(latestUserText);
        addIf(matchedRules, explicitTextFileOutputIntent, "TEXT_FILE_OUTPUT");
        boolean docxOutputIntent = fileOutputIntent && !explicitTextFileOutputIntent && explicitDocxOutputIntent;
        addIf(matchedRules, docxOutputIntent, "DOCX_OUTPUT");
        boolean textFileOutputIntent = fileOutputIntent && !docxOutputIntent;

        boolean workspaceReadIntent = !noFileOutputIntent && looksLikeWorkspaceReadIntent(latestUserText);
        addIf(matchedRules, workspaceReadIntent, "WORKSPACE_READ");

        boolean inlineTableIntent = looksLikeInlineTableIntent(latestUserText) && !fileOutputIntent;
        addIf(matchedRules, inlineTableIntent, "INLINE_TABLE");

        boolean strongChartIntent = forcedInlineChartIntent || looksLikeChartIntent(latestUserText);
        addIf(matchedRules, strongChartIntent, "CHART_STRONG");

        boolean weakChartIntent = looksLikeWeakChartIntent(latestUserText);
        addIf(matchedRules, weakChartIntent, "CHART_WEAK");

        boolean ambiguousChartFollowUp = looksLikeAmbiguousFollowUp(latestUserText) && recentAssistantMentionsChart(messages);
        addIf(matchedRules, ambiguousChartFollowUp, "CHART_FOLLOW_UP");

        boolean chartRevisionIntent = looksLikeChartRevisionIntent(latestUserText) && !extractLatestChartSource(messages).isBlank();
        addIf(matchedRules, chartRevisionIntent, "CHART_REVISION");

        boolean chartIntent = strongChartIntent || weakChartIntent || ambiguousChartFollowUp || chartRevisionIntent;
        boolean explicitMultiFiles = looksLikeExplicitMultiFileIntent(latestUserText);
        addIf(matchedRules, explicitMultiFiles, "MULTI_FILE");
        boolean explicitMultiCharts = looksLikeExplicitMultiChartIntent(latestUserText);
        addIf(matchedRules, explicitMultiCharts, "MULTI_CHART");

        OutputMode outputMode;
        if (!chartIntent && (noFileOutputIntent || inlineTableIntent)) {
            outputMode = OutputMode.PLAIN_REPLY;
        } else if (chartIntent && fileOutputIntent && !docxOutputIntent) {
            outputMode = OutputMode.CHART_FILE_OUTPUT;
        } else if (fileOutputIntent) {
            outputMode = OutputMode.FILE_OUTPUT;
        } else if (chartIntent) {
            outputMode = OutputMode.CHART;
        } else if (workspaceReadIntent) {
            outputMode = OutputMode.WORKSPACE_READ;
        } else {
            outputMode = OutputMode.PLAIN_REPLY;
        }

        return new IntentDecision(
                outputMode,
                chartIntent,
                fileOutputIntent,
                docxOutputIntent,
                textFileOutputIntent,
                inlineTableIntent,
                workspaceReadIntent,
                explicitMultiFiles ? EXPLICIT_MULTI_CREATE_LIMIT : DEFAULT_CREATE_LIMIT,
                explicitMultiCharts ? EXPLICIT_MULTI_CHART_LIMIT : DEFAULT_CHART_LIMIT,
                List.copyOf(matchedRules));
    }

    private boolean looksLikeForcedInlineChartIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT).trim();
        if (looksLikeNoFileOutputIntent(t)) {
            return true;
        }
        if (mentionsMarkdownFile(t)
                || mentionsDocxFile(t)
                || containsAny(t, FILE_OUTPUT_KEYWORDS)
                || containsFileVerbAndObject(t)
                || containsDocumentVerbAndObject(t)) {
            return false;
        }
        boolean hasChartWord = t.contains("图")
                || t.contains("图表")
                || t.contains("关系图")
                || t.contains("结构图")
                || t.contains("流程图")
                || t.contains("示意图")
                || t.contains("mermaid")
                || t.contains("chart")
                || t.contains("diagram")
                || t.contains("flowchart")
                || t.contains("graph");
        if (!hasChartWord) {
            return false;
        }
        return containsAny(t, GENERIC_CHART_ACTIONS)
                || containsAny(t, WEAK_CHART_ACTIONS)
                || t.contains("帮我")
                || t.contains("给我");
    }

    private void addIf(List<String> matchedRules, boolean condition, String ruleName) {
        if (condition) {
            matchedRules.add(ruleName);
        }
    }

    private String extractLatestUserText(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"user".equals(String.valueOf(msg.get("role")))) {
                continue;
            }
            Object content = msg.get("content");
            if (content == null) {
                return "";
            }
            if (content instanceof String s) {
                return s;
            }
            String flattened = flattenMessageContent(content);
            return flattened.isBlank() ? content.toString() : flattened;
        }
        return "";
    }

    private String flattenMessageContent(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                String text = flattenMessageContent(part);
                if (!text.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(text.trim());
                }
            }
            return sb.toString();
        }
        if (content instanceof Map<?, ?> map) {
            Object text = map.get("text");
            if (text != null) {
                String nested = flattenMessageContent(text);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
            Object nestedContent = map.get("content");
            if (nestedContent != null) {
                String nested = flattenMessageContent(nestedContent);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        }
        return "";
    }
    private boolean looksLikeChartIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();
        if (containsAny(t, PLAIN_TEXT_HINTS)) {
            return false;
        }
        return containsAny(t, CHART_KEYWORDS)
                || containsChartActionWithBareImageWord(t);
    }

    private boolean looksLikeWeakChartIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();
        if (containsAny(t, PLAIN_TEXT_HINTS)) {
            return false;
        }
        return containsAny(t, WEAK_CHART_ACTIONS) && containsAny(t, WEAK_CHART_OBJECTS);
    }

    private boolean containsChartActionWithBareImageWord(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (!containsAny(text, GENERIC_CHART_ACTIONS)) {
            return false;
        }
        return text.contains("\u56fe")
                || text.contains("\u56fe\u50cf")
                || text.contains("\u793a\u610f");
    }

    private boolean looksLikeChartRevisionIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return containsAny(text.toLowerCase(), CHART_REVISION_KEYWORDS);
    }

    private boolean looksLikeAmbiguousFollowUp(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim().toLowerCase();
        return t.equals("?")
                || t.equals("？")
                || t.equals("??")
                || t.equals("？？")
                || t.equals("\u6ca1\u6709\u56fe")
                || t.equals("\u56fe\u5462")
                || t.equals("\u56fe\u5462\uff1f")
                || t.equals("\u6ca1\u663e\u793a")
                || t.equals("\u6ca1\u753b\u51fa\u6765")
                || t.contains("\u600e\u4e48\u6ca1\u6709\u56fe")
                || t.contains("where is the chart")
                || t.contains("no chart");
    }

    private boolean recentAssistantMentionsChart(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        int checked = 0;
        for (int i = messages.size() - 1; i >= 0 && checked < 4; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"assistant".equals(String.valueOf(msg.get("role")))) {
                continue;
            }
            checked++;
            String content = String.valueOf(msg.getOrDefault("content", ""));
            if (content.contains("render_mermaid_chart")
                    || content.contains(ToolExecutor.STRUCTURED_CHART_MARKER)
                    || content.contains(ToolExecutor.INLINE_CHART_MARKER)
                    || content.contains("```mermaid")
                    || looksLikeChartIntent(content)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeFileOutputIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        return containsAny(lowered, FILE_OUTPUT_KEYWORDS)
                || mentionsMarkdownFile(lowered)
                || mentionsDocxFile(lowered)
                || containsDocumentVerbAndObject(lowered)
                || containsFileVerbAndObject(lowered)
                || mentionsAbsoluteFilePath(text);
    }

    private boolean looksLikeDocxOutputIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        return containsAny(lowered, DOCX_OUTPUT_KEYWORDS)
                || mentionsDocxFile(lowered)
                || containsDocumentVerbAndObject(lowered);
    }

    private boolean looksLikeTextFileOutputIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        return containsAny(lowered, TEXT_FILE_OUTPUT_KEYWORDS)
                || mentionsMarkdownFile(lowered);
    }

    private String inferTextFileExtension(String text) {
        if (text == null || text.isBlank()) return ".md";
        String lowered = text.toLowerCase(Locale.ROOT);
        return containsAny(lowered, TXT_EXTENSION_KEYWORDS) ? ".txt" : ".md";
    }

    private boolean looksLikeNoFileOutputIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return containsAny(text.toLowerCase(), NO_FILE_OUTPUT_KEYWORDS);
    }

    private boolean looksLikeInlineTableIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();
        return containsAny(t, TABLE_KEYWORDS) && containsAny(t, INLINE_KEYWORDS);
    }

    private boolean looksLikeWorkspaceReadIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return containsAny(text.toLowerCase(), WORKSPACE_READ_KEYWORDS);
    }

    private boolean looksLikeExplicitMultiFileIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();

        if (containsAny(t, MULTI_FILE_KEYWORDS)) {
            return true;
        }

        return MULTI_FILE_COUNT_PATTERN.matcher(text).find();
    }

    private boolean looksLikeExplicitMultiChartIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();

        if (containsAny(t, MULTI_CHART_KEYWORDS)) {
            return true;
        }

        return MULTI_CHART_EN_PATTERN.matcher(text).find()
                || MULTI_CHART_COUNT_EN_PATTERN.matcher(text).find()
                || MULTI_CHART_COUNT_ZH_PATTERN.matcher(text).find();
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean mentionsMarkdownFile(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains(".md")
                || text.contains("markdown")
                || text.contains("md文件")
                || text.contains("md 文档")
                || text.contains("md文档")
                || text.contains("md 格式")
                || text.matches(".*\\bmd\\b.*");
    }

    private boolean mentionsDocxFile(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains(".docx")
                || text.contains("docx")
                || text.contains("word文件")
                || text.contains("word文档")
                || text.contains("word 文件")
                || text.contains("word 文档")
                || text.contains("word file")
                || text.contains("word document");
    }

    private boolean containsFileVerbAndObject(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        List<String> fileVerbs = List.of(
                "生成", "创建", "写", "导出", "保存", "给我生成", "给我写",
                "generate", "create", "write", "export", "save");
        List<String> fileObjects = List.of(
                "文件", "文档", "报告", "markdown", "md", "docx", "word",
                "file", "document", "report",
                ".txt", ".csv", ".json", ".xml", ".html", ".log", ".py", ".js", ".ts", ".java",
                "txt文件", "csv文件", "json文件");
        if (containsAny(text, fileVerbs) && containsAny(text, fileObjects)) {
            return true;
        }
        // 动词 + 任意带扩展名的文件名（如 "创建 test.txt"）
        return containsAny(text, fileVerbs) && FILENAME_PATTERN.matcher(text).find();
    }

    private boolean mentionsAbsoluteFilePath(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        // Windows 绝对路径，如 E:/test.txt 或 E:\folder\file.txt
        if (ABSOLUTE_PATH_PATTERN.matcher(text).find()) {
            return true;
        }
        // Unix 绝对路径且包含文件扩展名，如 /home/user/test.txt
        return text.matches(".*\\/[^\\s/]+\\.[a-zA-Z0-9]{1,10}.*");
    }

    private boolean containsDocumentVerbAndObject(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        List<String> documentVerbs = List.of(
                "\u751f\u6210", "\u521b\u5efa", "\u5199", "\u5bfc\u51fa", "\u5236\u4f5c",
                "\u7ed9\u6211\u751f\u6210", "\u7ed9\u6211\u5199",
                "generate", "create", "write", "export", "make");
        List<String> documentObjects = List.of(
                "\u6587\u6863", "\u62a5\u544a", "word", "docx", "document", "report");
        return containsAny(text, documentVerbs) && containsAny(text, documentObjects);
    }

    private String removeSpecialToolLines(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return toolResult;
        }
        String withoutInlineChart = INLINE_CHART_PATTERN.matcher(toolResult).replaceAll("");
        String withoutStructuredChart = STRUCTURED_CHART_PATTERN.matcher(withoutInlineChart).replaceAll("");
        return withoutStructuredChart
                .replaceAll("(?m)^DOWNLOAD_URL:\\s*\\S+\\s*$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static final class CreateGuard {
        private final int maxCreates;
        private final int maxCharts;
        private final boolean chartFileOutput;
        private int createdCount;
        private int chartCount;
        private int chartRepairCount;

        private CreateGuard(int maxCreates, int maxCharts, boolean chartFileOutput) {
            this.maxCreates = maxCreates;
            this.maxCharts = maxCharts;
            this.chartFileOutput = chartFileOutput;
            this.createdCount = 0;
            this.chartCount = 0;
            this.chartRepairCount = 0;
        }
    }

    private enum OutputMode {
        PLAIN_REPLY,
        CHART,
        CHART_FILE_OUTPUT,
        FILE_OUTPUT,
        WORKSPACE_READ
    }

    private record FileDraft(String filename, String title, String content, String replyMessage) {}

    private record ChartFileDraft(
            String filename,
            String title,
            String content,
            String chartTitle,
            String mermaidSource,
            String replyMessage) {}

    private record IntentDecision(
            OutputMode outputMode,
            boolean chartIntent,
            boolean fileOutputIntent,
            boolean docxOutputIntent,
            boolean textFileOutputIntent,
            boolean inlineTableIntent,
            boolean workspaceReadIntent,
            int maxCreates,
            int maxCharts,
            List<String> matchedRules) {
        private String primaryTool() {
            if (chartIntent) {
                return "render_mermaid_chart";
            }
            if (docxOutputIntent) {
                return "create_docx_file";
            }
            if (textFileOutputIntent) {
                return "create_text_file";
            }
            return workspaceReadIntent ? "read_file/list_files" : "none";
        }
    }

    private record ChartQualityResult(boolean ok, String message) {
        private static ChartQualityResult pass() {
            return new ChartQualityResult(true, "");
        }

        private static ChartQualityResult fail(String message) {
            return new ChartQualityResult(false, message);
        }
    }
}
