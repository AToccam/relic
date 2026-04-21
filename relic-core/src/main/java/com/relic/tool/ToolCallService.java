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
    private static final int DEFAULT_CHART_LIMIT = 1;
    private static final int EXPLICIT_MULTI_CHART_LIMIT = 3;

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
                if (createGuard.chartCount >= createGuard.maxCharts) {
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
                    toolMsg.put("content", removeSpecialToolLines(toolResult));
                    conversation.add(toolMsg);

                    if (retryRequired) {
                        chartRetryRequired = true;
                        chartRetryToolName = tc.getName();
                    }
                    if (chartGenerated) {
                        createGuard.chartCount++;
                        logToolResult(tc.getName(), toolResult);
                        emitDownloadLinkIfPresent(toolResult, onChunk);
                        if (hasStructuredChart(toolResult)) {
                            emitStructuredChartIfPresent(toolResult, onChunk);
                        } else {
                            emitInlineChartIfPresent(toolResult, onChunk);
                        }
                        onChunk.accept("\n");
                        if (createGuard.chartCount >= createGuard.maxCharts) {
                            return;
                        }
                        continue;
                    }
                    if (!retryRequired) {
                        logToolResult(tc.getName(), toolResult);
                        emitDownloadLinkIfPresent(toolResult, onChunk);
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

            ChartQualityResult qualityResult = validateChartQuality(result);
            if (!qualityResult.ok() && createGuard.chartRepairCount < 1) {
                createGuard.chartRepairCount++;
                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tc.getId());
                toolMsg.put("content", ToolExecutor.CHART_VALIDATION_ERROR_MARKER + " " + qualityResult.message());
                conversation.add(toolMsg);
                conversation.add(buildChartRetryInstruction(tc.getName()));
                continue;
            }

            directOutput.append(buildDirectToolOutput(result));
            if (isChartTool(tc.getName()) && hasInlineChart(result)) {
                createGuard.chartCount++;
            }

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
     * chart intent -> render_mermaid_chart by default
     * chart + explicit file/export/download intent -> also allow create_mermaid_chart_file
     * non-chart -> create_text_file
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> selectToolsForRequest(List<Map<String, Object>> messages) {
        String latestUserText = extractLatestUserText(messages);
        boolean chartIntent = looksLikeChartIntent(latestUserText)
                || (looksLikeAmbiguousFollowUp(latestUserText) && recentAssistantMentionsChart(messages))
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
        boolean explicitMultiFiles = looksLikeExplicitMultiFileIntent(latestUserText);
        boolean explicitMultiCharts = looksLikeExplicitMultiChartIntent(latestUserText);
        int maxCreates = explicitMultiFiles ? EXPLICIT_MULTI_CREATE_LIMIT : DEFAULT_CREATE_LIMIT;
        int maxCharts = explicitMultiCharts ? EXPLICIT_MULTI_CHART_LIMIT : DEFAULT_CHART_LIMIT;
        log.info("[create-guard] explicitMultiFiles={}, explicitMultiCharts={}, maxCreates={}, maxCharts={}",
                explicitMultiFiles, explicitMultiCharts, maxCreates, maxCharts);
        return new CreateGuard(maxCreates, maxCharts);
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
                || t.contains("\u56fe\u793a")
                || t.contains("\u5bf9\u6bd4\u56fe")
                || t.contains("\u6bd4\u8f83\u56fe")
                || t.contains("\u5bf9\u7167\u56fe")
                || t.contains("\u533a\u522b\u56fe")
                || t.contains("\u5dee\u5f02\u56fe")
                || t.contains("\u6bd4\u4f8b\u56fe")
                || t.contains("\u5206\u5e03\u56fe")
                || t.contains("\u5360\u6bd4\u56fe")
                || t.contains("\u8d8b\u52bf\u56fe")
                || t.contains("\u65f6\u95f4\u7ebf")
                || t.contains("\u7518\u7279\u56fe")
                || t.contains("\u5e8f\u5217\u56fe")
                || t.contains("\u65f6\u5e8f\u56fe")
                || t.contains("\u7c7b\u56fe")
                || t.contains("\u5b9e\u4f53\u5173\u7cfb")
                || t.contains("er\u56fe")
                || t.contains("\u72b6\u6001\u56fe")
                || t.contains("\u65c5\u7a0b\u56fe")
                || t.contains("\u8c61\u9650\u56fe")
                || t.contains("\u6851\u57fa\u56fe")
                || t.contains("\u67b6\u6784\u56fe")
                || t.contains("\u770b\u677f\u56fe")
                || t.contains("\u5757\u56fe")
                || t.contains("\u7ef4\u6069\u56fe")
                || t.contains("\u8111\u56fe")
                || t.contains("\u601d\u7ef4\u5bfc\u56fe")
                || t.contains("\u6d41\u7a0b\u56fe")
                || t.contains("\u67f1\u72b6\u56fe")
                || t.contains("\u6298\u7ebf\u56fe")
                || t.contains("\u997c\u56fe")
                || t.contains("\u505a\u4e2a") && t.contains("\u56fe")
                || t.contains("\u505a\u4e00\u4e2a") && t.contains("\u56fe")
                || t.contains("\u753b\u4e2a") && t.contains("\u56fe")
                || t.contains("\u753b\u4e00\u4e2a") && t.contains("\u56fe")
                || t.contains("mermaid")
                || t.contains("chart")
                || t.contains("diagram")
                || t.contains("flowchart")
                || t.contains("graph")
                || t.contains("mindmap")
                || t.contains("timeline")
                || t.contains("gantt")
                || t.contains("sequence diagram")
                || t.contains("sequencediagram")
                || t.contains("class diagram")
                || t.contains("classdiagram")
                || t.contains("er diagram")
                || t.contains("erdiagram")
                || t.contains("state diagram")
                || t.contains("statediagram")
                || t.contains("journey")
                || t.contains("quadrant")
                || t.contains("sankey")
                || t.contains("architecture")
                || t.contains("kanban")
                || t.contains("block diagram")
                || t.contains("blockdiagram")
                || t.contains("venn")
                || t.contains("xychart")
                || t.contains("pie chart")
                || t.contains("bar chart")
                || t.contains("line chart")
                || t.contains("comparison chart")
                || t.contains("compare chart")
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
        String t = text.toLowerCase();
        return t.contains("\u4fdd\u5b58")
                || t.contains("\u4e0b\u8f7d")
                || t.contains("\u5bfc\u51fa")
                || t.contains("\u751f\u6210\u6587\u4ef6")
                || t.contains("\u4fdd\u5b58\u6210\u6587\u4ef6")
                || t.contains("\u5b58\u6210\u6587\u4ef6")
                || t.contains("\u5b58\u4e3a\u6587\u4ef6")
                || t.contains("\u751f\u6210md")
                || t.contains("\u751f\u6210 md")
                || t.contains("markdown")
                || t.contains(".md")
                || t.contains(" md")
                || t.contains("save")
                || t.contains("save as file")
                || t.contains("create file")
                || t.contains("generate file")
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

    private boolean looksLikeExplicitMultiChartIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();

        if (t.contains("\u591a\u4e2a\u56fe")
                || t.contains("\u591a\u5f20\u56fe")
                || t.contains("\u51e0\u4e2a\u56fe")
                || t.contains("\u51e0\u5f20\u56fe")
                || t.contains("\u5206\u522b\u753b")
                || t.contains("\u5206\u522b\u505a")
                || t.contains("\u5206\u522b\u751f\u6210")
                || t.contains("\u5404\u753b\u4e00\u4e2a")
                || t.contains("\u6bcf\u4e2a\u90fd\u753b")
                || t.contains("\u4e00\u4e2a\u4e00\u4e2a\u753b")
                || t.contains("multiple charts")
                || t.contains("multiple diagrams")
                || t.contains("separate charts")
                || t.contains("separate diagrams")
                || t.contains("for each chart")
                || t.contains("for each diagram")) {
            return true;
        }

        return Pattern.compile("(?i)(?:draw|create|generate|render|make)\\s*(?:[2-9]|[1-9]\\d)\\s*(?:charts|diagrams)")
                .matcher(text)
                .find()
                || Pattern.compile("(?i)(?:[2-9]|[1-9]\\d)\\s*(?:charts|diagrams)")
                .matcher(text)
                .find()
                || Pattern.compile("(?:[2-9]|[1-9]\\d)\\s*(?:\u4e2a|\u5f20)?\\s*(?:\u56fe|\u56fe\u8868|\u56fe\u793a)")
                .matcher(text)
                .find();
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
        private int createdCount;
        private int chartCount;
        private int chartRepairCount;

        private CreateGuard(int maxCreates, int maxCharts) {
            this.maxCreates = maxCreates;
            this.maxCharts = maxCharts;
            this.createdCount = 0;
            this.chartCount = 0;
            this.chartRepairCount = 0;
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
