package com.relic.tool;

import java.util.List;
import java.util.Map;

/**
 * 定义所有可用工具（Function Calling）供模型调用。
 */
public final class ToolDefinitions {

    private ToolDefinitions() {}

    public static List<Map<String, Object>> getAll() {
        return List.of(
                buildTool("create_text_file",
                        "在用户工作区创建或覆盖一个文本文件。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filename", Map.of(
                                                "type", "string",
                                                "description", "文件路径（可包含子目录），例如 docs/notes.md"
                                        ),
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "写入文件的文本内容"
                                        )
                                ),
                                "required", List.of("filename", "content")
                        )),
                buildTool("read_file",
                        "读取工作区中指定文件内容（支持文本/PDF/DOC/DOCX）。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filename", Map.of(
                                                "type", "string",
                                                "description", "要读取的文件路径"
                                        )
                                ),
                                "required", List.of("filename")
                        )),
                buildTool("list_files",
                        "列出工作区中的文件和目录。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path", Map.of(
                                                "type", "string",
                                                "description", "子目录路径，留空表示根目录"
                                        )
                                ),
                                "required", List.of()
                        )),
                buildTool("create_mermaid_chart_file",
                        "创建 Mermaid 图表 Markdown 文件（支持 pie/bar/line/flowchart）。信息不完整时也要直接生成默认图，不要反问用户。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filename", Map.of(
                                                "type", "string",
                                                "description", "目标 Markdown 路径，例如 docs/sales-q1.md"
                                        ),
                                        "chartType", Map.of(
                                                "type", "string",
                                                "description", "图表类型：pie/bar/line/flowchart（可省略）"
                                        ),
                                        "title", Map.of(
                                                "type", "string",
                                                "description", "图表标题（可省略）"
                                        ),
                                        "data", Map.of(
                                                "type", "array",
                                                "description", "数值图表数据，每项为 {label, value}；关系图可省略",
                                                "items", Map.of(
                                                        "type", "object",
                                                        "properties", Map.of(
                                                                "label", Map.of("type", "string"),
                                                                "value", Map.of("type", "number")
                                                        ),
                                                        "required", List.of("label", "value")
                                                )
                                        )
                                ),
                                "required", List.of("filename")
                        ))
        );
    }

    private static Map<String, Object> buildTool(String name, String description,
                                                 Map<String, Object> parameters) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", parameters
                )
        );
    }
}