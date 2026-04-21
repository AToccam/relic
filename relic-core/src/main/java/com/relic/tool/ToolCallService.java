package com.relic.tool;

import com.relic.dto.ToolCallResult;
import com.relic.service.AiProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    private static final Pattern DOWNLOAD_URL_PATTERN = Pattern.compile("DOWNLOAD_URL:\\s*(\\S+)");
    private static final Pattern INLINE_CHART_PATTERN = Pattern.compile(
            ToolExecutor.INLINE_CHART_MARKER + "\\s*\\R([\\s\\S]*)",
            Pattern.MULTILINE);
    private static final Pattern STRUCTURED_CHART_PATTERN = Pattern.compile(
            ToolExecutor.STRUCTURED_CHART_MARKER + "(\\{.*})");
    private static final Pattern MERMAID_FENCE_PATTERN = Pattern.compile("```mermaid\\s*\\R([\\s\\S]*?)\\R?```");
    private static final Pattern MULTI_FILE_COUNT_PATTERN = Pattern.compile("生成\\s*([2-9]|[1-9]\\d|两|二|三|四|五|六|七|八|九)\\s*个?\\s*(文件|文档)");

    @Autowired
    private ToolExecutor toolExecutor;

    public String askWithTools(AiProvider provider, List<Map<String, Object>> messages) {
        if (!provider.supportsTools()) {
            log.debug("Provider {} does not support tools, fallback to plain ask", provider.getName());
            return provider.ask(messages);
        }

        List<Map<String, Object>> enrichedMessages = enrichChartRevisionContext(messages);
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
                if (createGuard.chartCount >= 1) {
                    return directToolOutput.toString();
                }
            } else if (result.hasToolCalls()) {
                log.warn("[tool-call] provider={}, round={} got invalid tool calls only", provider.getName(), round + 1);
            } else {
                return directToolOutput + result.getContentString();
            }
        }

        log.warn("[tool-call] reached max rounds={}", MAX_TOOL_ROUNDS);
        return "工具调用轮次超过限制";
    }

    public void streamWithTools(AiProvider provider,
                                List<Map<String, Object>> messages,
                                Consumer<String> onChunk) throws Exception {
        if (!provider.supportsTools()) {
            log.debug("Provider {} does not support tools, fallback to plain stream", provider.getName());
            provider.stream(messages, onChunk);
            return;
        }

        List<Map<String, Object>> enrichedMessages = enrichChartRevisionContext(messages);
        List<Map<String, Object>> conversation = new ArrayList<>(enrichedMessages);
        List<Map<String, Object>> tools = selectToolsForRequest(enrichedMessages);
        CreateGuard createGuard = buildCreateGuard(enrichedMessages);
        boolean chartRetryRequired = false;
        String chartRetryToolName = "render_mermaid_chart";

        boolean anyContentSent = false;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            StringBuilder suppressedContent = new StringBuilder();
            Consumer<String> contentSink = chartRetryRequired ? suppressedContent::append : onChunk;
            ToolCallResult result = provider.streamWithTools(conversation, tools, contentSink);
            List<ToolCallResult.ToolCall> executable = filterExecutableToolCalls(result.getToolCalls());

            if (!chartRetryRequired && result.getContent().length() > 0) {
                anyContentSent = true;
            }

            if (result.hasToolCalls() && !executable.isEmpty()) {
                log.info("[tool-call-stream] provider={}, round={}, count={}", provider.getName(), round + 1, executable.size());
                conversation.add(result.toAssistantMessage());
                chartRetryRequired = false;

                for (ToolCallResult.ToolCall tc : executable) {
                    onChunk.accept("\n🔧 正在调用 " + tc.getName() + "...\n");

                    String toolResult = executeToolWithGuard(tc, createGuard);
                    logToolResult(tc.getName(), toolResult);
                    emitDownloadLinkIfPresent(toolResult, onChunk);
                    if (hasStructuredChart(toolResult)) {
                        emitStructuredChartIfPresent(toolResult, onChunk);
                    } else {
                        emitInlineChartIfPresent(toolResult, onChunk);
                    }
                    boolean retryRequired = isChartValidationError(toolResult);
                    boolean chartGenerated = isChartTool(tc.getName()) && hasInlineChart(toolResult);

                    Map<String, Object> toolMsg = new HashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", tc.getId());
                    toolMsg.put("content", removeSpecialToolLines(toolResult));
                    conversation.add(toolMsg);

                    if (retryRequired) {
                        chartRetryRequired = true;
                        chartRetryToolName = tc.getName();
                    }
                    if (chartGenerated) {
                        onChunk.accept("\n");
                        return;
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
                    log.warn("[tool-call-stream] chart retry required but provider returned natural language only: {}", suppressedContent);
                    onChunk.accept("⚠️ 图表生成失败：AI 没有按要求重新调用图表工具。请再试一次，或要求它直接输出完整 Mermaid 图表源码。");
                    return;
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

    private String executeAndAppend(List<ToolCallResult.ToolCall> toolCalls,
                                    List<Map<String, Object>> conversation,
                                    CreateGuard createGuard) {
        StringBuilder directOutput = new StringBuilder();
        for (ToolCallResult.ToolCall tc : toolCalls) {
            String result = executeToolWithGuard(tc, createGuard);
            logToolResult(tc.getName(), result);
            directOutput.append(buildDirectToolOutput(result));

            Map<String, Object> toolMsg = new HashMap<>();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", tc.getId());
            toolMsg.put("content", removeSpecialToolLines(result));
            conversation.add(toolMsg);
        }
        return directOutput.toString();
    }

    private String executeToolWithGuard(ToolCallResult.ToolCall tc, CreateGuard guard) {
        String toolName = tc.getName();
        if (isChartTool(toolName) && guard.chartCount >= 1) {
            return "Chart generation limit reached for this user turn. Wait for the user next message before generating another chart.";
        }
        if (isCreateTool(toolName) && guard.createdCount >= guard.maxCreates) {
            return "已达到本次请求可创建文件上限(" + guard.maxCreates + ")。如需一次生成多个文件，请明确说明“生成多个文件”。";
        }

        String result = toolExecutor.execute(toolName, tc.getArgumentsString());

        if (isCreateTool(toolName) && hasDownloadUrl(result)) {
            guard.createdCount++;
        }
        if (isChartTool(toolName) && hasInlineChart(result)) {
            guard.chartCount++;
        }
        return result;
    }

    private boolean isCreateTool(String toolName) {
        return "create_text_file".equals(toolName) || "create_mermaid_chart_file".equals(toolName);
    }

    private boolean isChartTool(String toolName) {
        return "render_mermaid_chart".equals(toolName) || "create_mermaid_chart_file".equals(toolName);
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

    private boolean isChartValidationError(String toolResult) {
        return toolResult != null && toolResult.contains(ToolExecutor.CHART_VALIDATION_ERROR_MARKER);
    }

    private Map<String, Object> buildChartRetryInstruction(String failedToolName) {
        String retryToolName = "create_mermaid_chart_file".equals(failedToolName)
                ? "create_mermaid_chart_file"
                : "render_mermaid_chart";
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content",
                "The previous " + failedToolName + " call failed validation. "
                        + "You must immediately call " + retryToolName + " again. "
                        + "Do not answer in natural language. "
                        + "Use content/mermaidSource with complete Mermaid syntax, and every placeholder node id must have a topic-specific display label, e.g. P1[actual meaning], D1[actual meaning], E1[actual meaning].");
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
     * chart intent -> create_mermaid_chart_file
     * non-chart    -> create_text_file
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> selectToolsForRequest(List<Map<String, Object>> messages) {
        String latestUserText = extractLatestUserText(messages);
        boolean chartIntent = looksLikeChartIntent(latestUserText)
                || (looksLikeChartRevisionIntent(latestUserText) && !extractLatestChartSource(messages).isBlank());
        boolean fileOutputIntent = looksLikeFileOutputIntent(latestUserText);
        List<Map<String, Object>> allTools = ToolDefinitions.getAll();
        List<Map<String, Object>> selected = new ArrayList<>();

        for (Map<String, Object> tool : allTools) {
            Object fnObj = tool.get("function");
            if (!(fnObj instanceof Map<?, ?> fnMap)) {
                selected.add(tool);
                continue;
            }
            Object nameObj = ((Map<String, Object>) fnMap).get("name");
            String name = nameObj == null ? "" : nameObj.toString();

            if (chartIntent) {
                if ("render_mermaid_chart".equals(name)
                        || "read_file".equals(name)
                        || "list_files".equals(name)
                        || (fileOutputIntent && "create_mermaid_chart_file".equals(name))) {
                    selected.add(tool);
                }
                continue;
            }

            if ("create_text_file".equals(name)
                    || "read_file".equals(name)
                    || "list_files".equals(name)) {
                selected.add(tool);
            }
        }

        log.info("[tool-select] mode={}, chart_file_output={}, primary_tool={}",
                chartIntent ? "chart" : "text",
                fileOutputIntent,
                chartIntent ? "render_mermaid_chart" : "create_text_file");
        return selected;
    }

    private CreateGuard buildCreateGuard(List<Map<String, Object>> messages) {
        String latestUserText = extractLatestUserText(messages);
        boolean explicitMulti = looksLikeExplicitMultiFileIntent(latestUserText);
        int maxCreates = explicitMulti ? EXPLICIT_MULTI_CREATE_LIMIT : DEFAULT_CREATE_LIMIT;
        log.info("[create-guard] explicitMulti={}, maxCreates={}", explicitMulti, maxCreates);
        return new CreateGuard(maxCreates);
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
            return content.toString();
        }
        return "";
    }
    private boolean looksLikeChartIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();
        return t.contains("\u56fe\u8868")
                || t.contains("\u753b\u56fe")
                || t.contains("\u5173\u7cfb\u56fe")
                || t.contains("\u7ed3\u6784\u56fe")
                || t.contains("\u793a\u610f\u56fe")
                || t.contains("\u8111\u56fe")
                || t.contains("\u601d\u7ef4\u5bfc\u56fe")
                || t.contains("\u6d41\u7a0b\u56fe")
                || t.contains("\u67f1\u72b6\u56fe")
                || t.contains("\u6298\u7ebf\u56fe")
                || t.contains("\u997c\u56fe")
                || t.contains("mermaid")
                || t.contains("chart")
                || t.contains("diagram")
                || t.contains("flowchart")
                || t.contains("\u53ef\u89c6\u5316")
                || t.contains("\u6570\u636e\u5bf9\u6bd4");
    }

    private boolean looksLikeChartRevisionIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();
        return t.contains("\u4fee\u6539")
                || t.contains("\u8c03\u6574")
                || t.contains("\u5b8c\u5584")
                || t.contains("\u7ee7\u7eed")
                || t.contains("\u91cd\u753b")
                || t.contains("\u6362\u6210")
                || t.contains("\u6539\u6210")
                || t.contains("\u6539\u4e00\u4e0b")
                || t.contains("revise")
                || t.contains("update")
                || t.contains("modify")
                || t.contains("redraw");
    }

    private boolean looksLikeFileOutputIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();
        return t.contains("\u4fdd\u5b58")
                || t.contains("\u6587\u4ef6")
                || t.contains("\u4e0b\u8f7d")
                || t.contains("\u5bfc\u51fa")
                || t.contains("markdown")
                || t.contains(".md")
                || t.contains(" md")
                || t.contains("save")
                || t.contains("file")
                || t.contains("download")
                || t.contains("export");
    }

    private boolean looksLikeExplicitMultiFileIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();

        if (t.contains("\u591a\u4e2a\u6587\u4ef6")
                || t.contains("\u591a\u4efd\u6587\u4ef6")
                || t.contains("\u591a\u4efd\u6587\u6863")
                || t.contains("\u6279\u91cf\u751f\u6210")
                || t.contains("\u5206\u522b\u751f\u6210")
                || t.contains("\u5206\u522b\u8f93\u51fa")
                || t.contains("\u6bcf\u4e2a\u90fd\u4fdd\u5b58")
                || t.contains("\u6bcf\u4e2a\u90fd\u751f\u6210")
                || t.contains("\u5404\u751f\u6210\u4e00\u4e2a")
                || t.contains("multiple files")
                || t.contains("separate files")
                || t.contains("for each")) {
            return true;
        }

        return MULTI_FILE_COUNT_PATTERN.matcher(text).find();
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
        private int createdCount;
        private int chartCount;

        private CreateGuard(int maxCreates) {
            this.maxCreates = maxCreates;
            this.createdCount = 0;
            this.chartCount = 0;
        }
    }
}
