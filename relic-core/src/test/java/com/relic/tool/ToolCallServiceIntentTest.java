package com.relic.tool;

import com.relic.service.AiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallServiceIntentTest {

    @Test
    void bitmapImageRequestIsRejectedWithoutCallingChartTool() {
        ToolCallService service = new ToolCallService();

        String reply = service.askWithTools(null, userMessage("\u751f\u6210\u4e00\u5f20\u4ecb\u7ecd\u957f\u6c5f\u7684\u56fe\u7247"));

        assertTrue(reply.contains("\u76ee\u524d\u65e0\u6cd5\u76f4\u63a5\u751f\u6210\u56fe\u7247"));
    }

    @Test
    void treeChartRequestStillUsesChartIntent() throws Exception {
        Object decision = decideIntent("\u751f\u6210\u4e00\u5f20\u4ecb\u7ecd\u957f\u6c5f\u7684\u6811\u72b6\u56fe");

        assertEquals("CHART", invoke(decision, "outputMode").toString());
        assertEquals(Boolean.TRUE, invoke(decision, "chartIntent"));
        assertEquals(Boolean.FALSE, invoke(decision, "unsupportedBitmapImageIntent"));
    }

    @Test
    void plainBitmapImageRequestDoesNotUseChartIntent() throws Exception {
        Object decision = decideIntent("\u751f\u6210\u4e00\u5f20\u4ecb\u7ecd\u957f\u6c5f\u7684\u56fe\u7247");

        assertEquals("PLAIN_REPLY", invoke(decision, "outputMode").toString());
        assertEquals(Boolean.FALSE, invoke(decision, "chartIntent"));
        assertEquals(Boolean.TRUE, invoke(decision, "unsupportedBitmapImageIntent"));
    }

    @Test
    void naturalDirectoryListRequestUsesWorkspaceReadIntent() throws Exception {
        Object decision = decideIntent("列出当前目录中的文件");

        assertEquals("WORKSPACE_READ", invoke(decision, "outputMode").toString());
        assertEquals(Boolean.TRUE, invoke(decision, "workspaceReadIntent"));
    }

    @Test
    void directoryQuestionUsesWorkspaceReadIntent() throws Exception {
        Object decision = decideIntent("当前工作目录有哪些文件？");

        assertEquals("WORKSPACE_READ", invoke(decision, "outputMode").toString());
        assertEquals(Boolean.TRUE, invoke(decision, "workspaceReadIntent"));
    }

    @Test
    void listFilesUsesWorkingDirectoryContextForEmptyPath() throws Exception {
        Path workspace = Files.createTempDirectory("relic-workspace-");
        Path workingDirectory = Files.createTempDirectory("relic-working-dir-");
        Files.writeString(workspace.resolve("workspace-only.txt"), "workspace");
        Files.writeString(workingDirectory.resolve("working-only.txt"), "working");

        ToolExecutor executor = new ToolExecutor(null);
        ReflectionTestUtils.setField(executor, "workspacePath", workspace.toString());
        Method method = ToolExecutor.class.getDeclaredMethod("listFiles", String.class);
        method.setAccessible(true);

        try {
            ToolExecutor.setWorkingDirectoryContext(workingDirectory.toString());
            String listing = (String) method.invoke(executor, "");

            assertTrue(listing.contains("working-only.txt"));
            assertFalse(listing.contains("workspace-only.txt"));
        } finally {
            ToolExecutor.clearWorkingDirectoryContext();
        }
    }

    @Test
    void deterministicListFilesStreamEmitsToolCallNotice() throws Exception {
        Path workspace = Files.createTempDirectory("relic-workspace-");
        Files.writeString(workspace.resolve("listed.txt"), "content");

        ToolExecutor executor = new ToolExecutor(null);
        ReflectionTestUtils.setField(executor, "workspacePath", workspace.toString());

        ToolCallService service = new ToolCallService();
        ReflectionTestUtils.setField(service, "toolExecutor", executor);
        ReflectionTestUtils.setField(service, "toolExecutionTimeoutMs", 10_000L);
        ReflectionTestUtils.setField(service, "toolExecutorPoolSize", 1);
        ReflectionTestUtils.setField(service, "toolExecutorQueueSize", 4);
        service.initToolWorkerExecutor();

        StringBuilder streamed = new StringBuilder();
        try {
            ToolExecutor.setWorkingDirectoryContext(workspace.toString());
            service.streamWithTools(new NoopProvider(), userMessage("列出当前目录中的文件"), streamed::append);
        } finally {
            ToolExecutor.clearWorkingDirectoryContext();
            service.shutdownToolWorkerExecutor();
        }

        assertTrue(streamed.toString().contains("正在调用 list_files 工具"));
        assertTrue(streamed.toString().contains("listed.txt"));
    }

    @Test
    void mindmapSanitizerPreservesIndentation() throws Exception {
        ToolExecutor executor = new ToolExecutor(null);
        Method method = ToolExecutor.class.getDeclaredMethod("sanitizeMermaidSource", String.class);
        method.setAccessible(true);

        String source = String.join("\n",
                "mindmap",
                "  root((\u957f\u6c5f))",
                "    \u57fa\u672c\u6982\u51b5",
                "      \u5168\u957f6300\u516c\u91cc");

        String cleaned = (String) method.invoke(executor, source);

        assertTrue(cleaned.contains("\n  root"));
        assertTrue(cleaned.contains("\n    \u57fa\u672c\u6982\u51b5"));
        assertTrue(cleaned.contains("\n      \u5168\u957f6300\u516c\u91cc"));
        assertFalse(cleaned.contains("\nroot"));
    }

    private static Object decideIntent(String text) throws Exception {
        ToolCallService service = new ToolCallService();
        Method method = ToolCallService.class.getDeclaredMethod("decideIntent", List.class);
        method.setAccessible(true);
        return method.invoke(service, userMessage(text));
    }

    private static List<Map<String, Object>> userMessage(String text) {
        return List.of(Map.of("role", "user", "content", text));
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static final class NoopProvider implements AiProvider {
        @Override
        public String getName() {
            return "noop";
        }

        @Override
        public String ask(String prompt) {
            return "";
        }
    }
}
