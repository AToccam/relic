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
    private static final Pattern MULTI_FILE_COUNT_PATTERN = Pattern.compile("生成\\s*([2-9]|[1-9]\\d|两|二|三|四|五|六|七|八|九)\\s*个?\\s*(文件|文档)");

    @Autowired
    private ToolExecutor toolExecutor;

    public String askWithTools(AiProvider provider, List<Map<String, Object>> messages) {
        if (!provider.supportsTools()) {
            log.debug("Provider {} does not support tools, fallback to plain ask", provider.getName());
            return provider.ask(messages);
        }

        List<Map<String, Object>> conversation = new ArrayList<>(messages);
        List<Map<String, Object>> tools = selectToolsForRequest(messages);
        CreateGuard createGuard = buildCreateGuard(messages);

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ToolCallResult result = provider.askWithTools(conversation, tools);
            List<ToolCallResult.ToolCall> executable = filterExecutableToolCalls(result.getToolCalls());

            if (result.hasToolCalls() && !executable.isEmpty()) {
                log.info("[tool-call] provider={}, round={}, count={}", provider.getName(), round + 1, executable.size());
                conversation.add(result.toAssistantMessage());
                executeAndAppend(executable, conversation, createGuard);
            } else if (result.hasToolCalls()) {
                log.warn("[tool-call] provider={}, round={} got invalid tool calls only", provider.getName(), round + 1);
            } else {
                return result.getContentString();
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

        List<Map<String, Object>> conversation = new ArrayList<>(messages);
        List<Map<String, Object>> tools = selectToolsForRequest(messages);
        CreateGuard createGuard = buildCreateGuard(messages);

        boolean anyContentSent = false;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ToolCallResult result = provider.streamWithTools(conversation, tools, onChunk);
            List<ToolCallResult.ToolCall> executable = filterExecutableToolCalls(result.getToolCalls());

            if (result.getContent().length() > 0) {
                anyContentSent = true;
            }

            if (result.hasToolCalls() && !executable.isEmpty()) {
                log.info("[tool-call-stream] provider={}, round={}, count={}", provider.getName(), round + 1, executable.size());
                conversation.add(result.toAssistantMessage());

                for (ToolCallResult.ToolCall tc : executable) {
                    onChunk.accept("\n🔧 正在调用 " + tc.getName() + "...\n");

                    String toolResult = executeToolWithGuard(tc, createGuard);
                    logToolResult(tc.getName(), toolResult);
                    emitDownloadLinkIfPresent(toolResult, onChunk);

                    Map<String, Object> toolMsg = new HashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", tc.getId());
                    toolMsg.put("content", removeDownloadUrlLine(toolResult));
                    conversation.add(toolMsg);
                }
                onChunk.accept("\n");
            } else if (result.hasToolCalls()) {
                log.warn("[tool-call-stream] provider={}, round={} got invalid tool calls only", provider.getName(), round + 1);
            } else {
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

    private void executeAndAppend(List<ToolCallResult.ToolCall> toolCalls,
                                  List<Map<String, Object>> conversation,
                                  CreateGuard createGuard) {
        for (ToolCallResult.ToolCall tc : toolCalls) {
            String result = executeToolWithGuard(tc, createGuard);
            logToolResult(tc.getName(), result);

            Map<String, Object> toolMsg = new HashMap<>();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", tc.getId());
            toolMsg.put("content", removeDownloadUrlLine(result));
            conversation.add(toolMsg);
        }
    }

    private String executeToolWithGuard(ToolCallResult.ToolCall tc, CreateGuard guard) {
        String toolName = tc.getName();
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

    private boolean hasDownloadUrl(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return false;
        }
        return DOWNLOAD_URL_PATTERN.matcher(toolResult).find();
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
        onChunk.accept("\n已生成文件：[点击下载](" + url + ")\n");
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

    /**
     * Deterministic create tool selection:
     * chart intent -> create_mermaid_chart_file
     * non-chart    -> create_text_file
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> selectToolsForRequest(List<Map<String, Object>> messages) {
        boolean chartIntent = looksLikeChartIntent(extractLatestUserText(messages));
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

            if (chartIntent && "create_text_file".equals(name)) {
                continue;
            }
            if (!chartIntent && "create_mermaid_chart_file".equals(name)) {
                continue;
            }
            selected.add(tool);
        }

        log.info("[tool-select] mode={}, create_tool={}",
                chartIntent ? "chart" : "text",
                chartIntent ? "create_mermaid_chart_file" : "create_text_file");
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
    private String removeDownloadUrlLine(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return toolResult;
        }
        return toolResult
                .replaceAll("(?m)^DOWNLOAD_URL:\\s*\\S+\\s*$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static final class CreateGuard {
        private final int maxCreates;
        private int createdCount;

        private CreateGuard(int maxCreates) {
            this.maxCreates = maxCreates;
            this.createdCount = 0;
        }
    }
}
