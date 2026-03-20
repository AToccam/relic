package com.relic.tool;

import java.util.List;
import java.util.Map;

/**
 * 定义所有可用的工具（Function Calling），供 DeepSeek API 使用。
 */
public final class ToolDefinitions {

    private ToolDefinitions() {}

    public static List<Map<String, Object>> getAll() {
        return List.of(
                buildTool("create_text_file",
                        "在用户的工作区目录中创建或覆盖一个文本文件",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filename", Map.of(
                                                "type", "string",
                                                "description", "文件名（可包含子目录路径，如 docs/notes.txt）"
                                        ),
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "要写入文件的文本内容"
                                        )
                                ),
                                "required", List.of("filename", "content")
                        )),
                buildTool("read_file",
                        "读取用户工作区目录中指定文件的内容（支持文本、PDF、DOC、DOCX）",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filename", Map.of(
                                                "type", "string",
                                                "description", "要读取的文件名（可包含子目录路径）"
                                        )
                                ),
                                "required", List.of("filename")
                        )),
                buildTool("list_files",
                        "列出用户工作区目录中的文件和文件夹",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path", Map.of(
                                                "type", "string",
                                                "description", "要列出的子目录路径，留空则列出根目录"
                                        )
                                ),
                                "required", List.of()
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
