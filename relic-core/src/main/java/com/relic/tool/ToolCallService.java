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

/**
 * 独立的工具调用服务层 —— 与具体 AI 提供者解耦。
 * 职责：
 * 1. 管理工具定义（ToolDefinitions）
 * 2. 驱动"调用 AI → 检测 tool_calls → 执行工具 → 结果回传 AI"的循环
 * 3. 可搭配任意支持 tools 的 AiProvider 使用
 */
@Slf4j
@Service
public class ToolCallService {

    private static final int MAX_TOOL_ROUNDS = 10;

    @Autowired
    private ToolExecutor toolExecutor;

    /**
     * 非流式工具循环：用指定 provider 进行多轮问答，自动处理工具调用。
     *
     * @param provider 任意 AiProvider（需 supportsTools() == true 才会走工具路径）
     * @param messages 原始消息列表
     * @return AI 最终的文本回复
     */
    public String askWithTools(AiProvider provider, List<Map<String, Object>> messages) {
        if (!provider.supportsTools()) {
            log.debug("Provider {} 不支持 tools，走纯文本路径", provider.getName());
            return provider.ask(messages);
        }

        List<Map<String, Object>> conversation = new ArrayList<>(messages);
        List<Map<String, Object>> tools = ToolDefinitions.getAll();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ToolCallResult result = provider.askWithTools(conversation, tools);

            if (result.hasToolCalls()) {
                log.info("【工具调用】Provider={}, 第 {} 轮, {} 个工具",
                        provider.getName(), round + 1, result.getToolCalls().size());

                conversation.add(result.toAssistantMessage());
                executeAndAppend(result.getToolCalls(), conversation);
            } else {
                return result.getContentString();
            }
        }

        log.warn("【工具调用】达到最大轮次限制 {}", MAX_TOOL_ROUNDS);
        return "工具调用轮次超过限制";
    }

    /**
     * 流式工具循环：用指定 provider 进行流式多轮问答，自动处理工具调用。
     *
     * @param provider 任意 AiProvider（需 supportsTools() == true 才会走工具路径）
     * @param messages 原始消息列表
     * @param onChunk  流式文本回调
     */
    public void streamWithTools(AiProvider provider,
                                 List<Map<String, Object>> messages,
                                 Consumer<String> onChunk) throws Exception {
        if (!provider.supportsTools()) {
            log.debug("Provider {} 不支持 tools，走纯流式路径", provider.getName());
            provider.stream(messages, onChunk);
            return;
        }

        List<Map<String, Object>> conversation = new ArrayList<>(messages);
        List<Map<String, Object>> tools = ToolDefinitions.getAll();

        boolean anyContentSent = false;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ToolCallResult result = provider.streamWithTools(conversation, tools, onChunk);

            if (result.getContent().length() > 0) {
                anyContentSent = true;
            }

            if (result.hasToolCalls()) {
                log.info("【工具调用-流式】Provider={}, 第 {} 轮, {} 个工具",
                        provider.getName(), round + 1, result.getToolCalls().size());

                conversation.add(result.toAssistantMessage());

                for (ToolCallResult.ToolCall tc : result.getToolCalls()) {
                    onChunk.accept("\n🔧 正在调用 " + tc.getName() + "...\n");
                    String toolResult = toolExecutor.execute(tc.getName(), tc.getArgumentsString());
                    logToolResult(tc.getName(), toolResult);

                    Map<String, Object> toolMsg = new HashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", tc.getId());
                    toolMsg.put("content", toolResult);
                    conversation.add(toolMsg);
                }
                onChunk.accept("\n");
            } else {
                if (!anyContentSent && result.getContent().length() == 0) {
                    log.warn("【工具调用-流式】AI 返回了空响应，finishReason={}", result.getFinishReason());
                    onChunk.accept("⚠️ AI 未返回有效内容，请稍后重试。");
                }
                return;
            }
        }

        log.warn("【工具调用-流式】达到最大轮次限制 {}", MAX_TOOL_ROUNDS);
        onChunk.accept("⚠️ 工具调用轮次超过限制，已停止处理。");
    }

    // 内部辅助
    private void executeAndAppend(List<ToolCallResult.ToolCall> toolCalls,
                                   List<Map<String, Object>> conversation) {
        for (ToolCallResult.ToolCall tc : toolCalls) {
            log.info("【执行工具】{}: {}", tc.getName(), tc.getArgumentsString());
            String result = toolExecutor.execute(tc.getName(), tc.getArgumentsString());
            logToolResult(tc.getName(), result);

            Map<String, Object> toolMsg = new HashMap<>();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", tc.getId());
            toolMsg.put("content", result);
            conversation.add(toolMsg);
        }
    }

    private void logToolResult(String name, String result) {
        log.info("【工具结果】{}: {}", name,
                result.length() > 200 ? result.substring(0, 200) + "..." : result);
    }
}
