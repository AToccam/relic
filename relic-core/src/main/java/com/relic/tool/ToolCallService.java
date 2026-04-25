package com.relic.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.dto.ToolCallResult;
import com.relic.service.AiProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool-calling service that is decoupled from specific AI providers.
 */
@Slf4j
@Service
public class ToolCallService {

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
    private static final Pattern FILENAME_PATTERN = Pattern.compile("([\\w\\-\\u4e00-\\u9fa5./]+\\.(?:md|txt|docx))", Pattern.CASE_INSENSITIVE);
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
            "download", "export");
    private static final List<String> DOCX_OUTPUT_KEYWORDS = List.of(
            "word", "docx", ".docx", "\u751f\u6210\u6587\u6863", "\u521b\u5efa\u6587\u6863", "\u5199\u4e00\u4efd\u6587\u6863",
            "\u5bfc\u51fa\u6587\u6863", "\u751f\u6210\u62a5\u544a", "\u521b\u5efa\u62a5\u544a", "\u5199\u4e00\u4efd\u62a5\u544a",
            "\u53ef\u4e0b\u8f7d\u6587\u6863", "\u6587\u6863\u6587\u4ef6", "\u62a5\u544a\u6587\u4ef6",
            "create document", "generate document", "write a document", "create report", "generate report");
    private static final List<String> TEXT_FILE_OUTPUT_KEYWORDS = List.of(
            "markdown", ".md", " md", "\u751f\u6210md", "\u751f\u6210 md", "\u6587\u672c\u6587\u4ef6", "text file", ".txt");
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String askWithTools(AiProvider provider, List<Map<String, Object>> messages) {
        IntentDecision decision = decideIntent(messages);
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
                && !decision.chartIntent()
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
            String extension = decision.docxOutputIntent() ? ".docx" : ".md";
            List<Map<String, Object>> promptMessages = buildDeterministicFilePrompt(messages, extension);
            String raw = provider.ask(promptMessages);
            FileDraft draft = parseFileDraft(raw, latestUserText, extension);

            Map<String, Object> args = new HashMap<>();
            args.put("filename", draft.filename());
            args.put("title", draft.title());
            args.put("content", draft.content());

            String toolName = decision.docxOutputIntent() ? "create_docx_file" : "create_text_file";
            String toolResult = toolExecutor.execute(toolName, objectMapper.writeValueAsString(args));
            return buildDirectToolOutput(toolResult);
        } catch (Exception e) {
            log.warn("[deterministic-file] create failed: {}", e.getMessage());
            return "⚠️ 文件生成失败：" + e.getMessage();
        }
    }

    private List<Map<String, Object>> buildDeterministicFilePrompt(List<Map<String, Object>> messages, String extension) {
        List<Map<String, Object>> promptMessages = new ArrayList<>();
        String contentRules = ".docx".equalsIgnoreCase(extension)
                ? "content must be human-readable document body in plain text or Markdown-style structure. "
                + "Do not output HTML, XML, CSS, JavaScript, MHTML, or office markup. "
                + "Do not include tags such as <html>, <head>, <style>, <body>, <table> or <!DOCTYPE>. "
                + "Use headings, paragraphs, bullet lists and simple Markdown tables only."
                : "content must be the full body of the file.";
        promptMessages.add(Map.of(
                "role", "system",
                "content", "You are generating exactly one downloadable file for the user. "
                        + "Do not mention tools, function calls, workspace checks, or fake code like list_files(). "
                        + "Return JSON only with keys filename, title, content. "
                        + "Do not wrap JSON in markdown fences. "
                        + "filename must be a short workspace-relative file name ending with " + extension + ". "
                        + contentRules + " "
                        + "If the user did not specify a filename, choose a concise descriptive one."
        ));
        promptMessages.addAll(messages);
        return promptMessages;
    }

    private FileDraft parseFileDraft(String raw, String latestUserText, String extension) {
        String response = raw == null ? "" : raw.trim();
        String jsonCandidate = extractJsonObject(response);
        if (!jsonCandidate.isBlank()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(jsonCandidate, new TypeReference<>() {});
                String filename = normalizeGeneratedFilename(firstNonBlank(
                        asText(parsed.get("filename")),
                        asText(parsed.get("file_path")),
                        asText(parsed.get("path")),
                        inferFilenameFromUserTextSmart(latestUserText, extension)
                ), extension);
                String title = firstNonBlank(
                        asText(parsed.get("title")),
                        inferTitleFromFilename(filename)
                );
                String content = firstNonBlank(
                        asText(parsed.get("content")),
                        asText(parsed.get("body")),
                        stripCodeFence(response)
                );
                return new FileDraft(filename, title, content);
            } catch (Exception ignored) {
                // fall through to text fallback
            }
        }

        String filename = inferFilenameFromUserTextSmart(latestUserText, extension);
        String title = inferTitleFromFilename(filename);
        return new FileDraft(filename, title, stripCodeFence(response));
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

    private String inferFilenameFromUserTextSmart(String latestUserText, String extension) {
        String fallback = ".docx".equalsIgnoreCase(extension) ? "document.docx" : "document.md";
        if (latestUserText == null || latestUserText.isBlank()) {
            return fallback;
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

        String result = toolExecutor.execute(toolName, tc.getArgumentsString());

        if (isCreateTool(toolName) && hasDownloadUrl(result)) {
            guard.createdCount++;
        }
        return result;
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
        String url = matcher.group(1);
        onChunk.accept(formatDownloadLink(url));
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
            output.append(buildFileReplyIntro(toolResult));
            output.append(formatDownloadLink(downloadMatcher.group(1)));
        }

        Matcher structuredMatcher = STRUCTURED_CHART_PATTERN.matcher(toolResult);
        if (structuredMatcher.find()) {
            output.append("\n")
                    .append(ToolExecutor.STRUCTURED_CHART_MARKER)
                    .append(structuredMatcher.group(1))
                    .append("\n");
        } else {
            Matcher chartMatcher = INLINE_CHART_PATTERN.matcher(toolResult);
            if (chartMatcher.find()) {
            String markdown = chartMatcher.group(1).trim();
            if (!markdown.isBlank()) {
                output.append("\n").append(markdown).append("\n");
            }
            }
        }

        return output.toString();
    }

    private String formatDownloadLink(String url) {
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
            List<Map<String, Object>> fallbackTools = new ArrayList<>();
            for (Map<String, Object> tool : allTools) {
                Object fnObj = tool.get("function");
                if (!(fnObj instanceof Map<?, ?> fnMap)) {
                    continue;
                }
                Object nameObj = ((Map<String, Object>) fnMap).get("name");
                String name = nameObj == null ? "" : nameObj.toString();
                if ("render_mermaid_chart".equals(name)
                        || "create_text_file".equals(name)
                        || "create_docx_file".equals(name)
                        || "read_file".equals(name)
                        || "list_files".equals(name)) {
                    fallbackTools.add(tool);
                }
            }
            log.info("[tool-select] mode={}, matched={}, primary_tool=none, fallback_tools={}",
                    decision.outputMode(), decision.matchedRules(), fallbackTools.size());
            return fallbackTools;
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

        boolean explicitFileOutputIntent = !forcedInlineChartIntent && looksLikeFileOutputIntent(latestUserText);
        addIf(matchedRules, explicitFileOutputIntent, "FILE_OUTPUT");
        boolean fileOutputIntent = !noFileOutputIntent && explicitFileOutputIntent;
        boolean explicitTextFileOutputIntent = fileOutputIntent && looksLikeTextFileOutputIntent(latestUserText);
        addIf(matchedRules, explicitTextFileOutputIntent, "TEXT_FILE_OUTPUT");
        boolean docxOutputIntent = fileOutputIntent && !explicitTextFileOutputIntent && looksLikeDocxOutputIntent(latestUserText);
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
        } else if (chartIntent && fileOutputIntent) {
            outputMode = OutputMode.CHART_FILE_OUTPUT;
        } else if (chartIntent) {
            outputMode = OutputMode.CHART;
        } else if (fileOutputIntent) {
            outputMode = OutputMode.FILE_OUTPUT;
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
        if (mentionsMarkdownFile(t) || mentionsDocxFile(t) || containsAny(t, FILE_OUTPUT_KEYWORDS)) {
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
                || containsFileVerbAndObject(lowered);
    }

    private boolean looksLikeDocxOutputIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        return containsAny(lowered, DOCX_OUTPUT_KEYWORDS)
                || mentionsDocxFile(lowered);
    }

    private boolean looksLikeTextFileOutputIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        return containsAny(lowered, TEXT_FILE_OUTPUT_KEYWORDS)
                || mentionsMarkdownFile(lowered);
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
                || text.contains("word文档")
                || text.contains("word 文件")
                || text.contains("word file")
                || text.contains("word document");
    }

    private boolean containsFileVerbAndObject(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        List<String> fileVerbs = List.of("生成", "创建", "写", "导出", "保存", "给我生成", "给我写");
        List<String> fileObjects = List.of("文件", "文档", "报告", "markdown", "md", "docx", "word");
        return containsAny(text, fileVerbs) && containsAny(text, fileObjects);
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

    private record FileDraft(String filename, String title, String content) {}

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
