package com.relic.tool;

import java.util.List;
import java.util.Map;

/**
 * Tool definitions exposed to AI providers that support function calling.
 */
public final class ToolDefinitions {

    private ToolDefinitions() {}

    public static List<Map<String, Object>> getAll() {
        return List.of(
                buildTool("create_text_file",
                        "Create or overwrite a text file in the user's workspace.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filename", Map.of(
                                                "type", "string",
                                                "description", "Workspace-relative file path, for example docs/notes.md"
                                        ),
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "Text content to write into the file"
                                        )
                                ),
                                "required", List.of("filename", "content")
                        )),
                buildTool("read_file",
                        "Read a file from the workspace. Supports text, PDF, DOC and DOCX.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filename", Map.of(
                                                "type", "string",
                                                "description", "File path to read"
                                        )
                                ),
                                "required", List.of("filename")
                        )),
                buildTool("list_files",
                        "List files and directories in the workspace.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path", Map.of(
                                                "type", "string",
                                                "description", "Optional subdirectory path. Empty means workspace root."
                                        )
                                ),
                                "required", List.of()
                        )),
                buildTool("render_mermaid_chart",
                        "Render an inline Mermaid chart directly in chat without creating or saving any file. Use this for normal chart, diagram, flowchart, mind map, timeline, class diagram, sequence diagram, relationship diagram and visualization requests.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "chartType", Map.of(
                                                "type", "string",
                                                "description", "Optional chart type hint, such as pie, bar, line, flowchart, mindmap, timeline, sequenceDiagram, classDiagram or erDiagram"
                                        ),
                                        "title", Map.of(
                                                "type", "string",
                                                "description", "Chart title"
                                        ),
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "Complete Mermaid source or Markdown containing a mermaid code block. Prefer this for all non-trivial diagrams. Every visible node must have a meaningful user-facing label. Do not expose raw internal IDs such as P1, D1 or E1 as node text."
                                        ),
                                        "mermaidSource", Map.of(
                                                "type", "string",
                                                "description", "Alias of content. Use raw Mermaid syntax such as flowchart LR, graph TD, mindmap, timeline, sequenceDiagram, classDiagram, erDiagram, etc."
                                        ),
                                        "data", Map.of(
                                                "type", "array",
                                                "description", "Optional numeric chart data for simple pie/bar/line charts. Each item is {label, value}.",
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
                                "required", List.of()
                        )),
                buildTool("create_mermaid_chart_file",
                        "Create a Mermaid chart Markdown file. Use this only when the user explicitly asks to save, export, download, or create a file.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filename", Map.of(
                                                "type", "string",
                                                "description", "Target Markdown path, for example docs/sales-q1.md"
                                        ),
                                        "chartType", Map.of(
                                                "type", "string",
                                                "description", "Optional chart type, such as pie, bar, line or flowchart"
                                        ),
                                        "title", Map.of(
                                                "type", "string",
                                                "description", "Optional chart title"
                                        ),
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "Complete Mermaid source or Markdown containing a mermaid code block. Every visible node must have a meaningful user-facing label. Do not expose raw internal IDs such as P1, D1 or E1 as node text."
                                        ),
                                        "mermaidSource", Map.of(
                                                "type", "string",
                                                "description", "Alias of content. Use raw Mermaid syntax."
                                        ),
                                        "data", Map.of(
                                                "type", "array",
                                                "description", "Optional numeric chart data. Each item is {label, value}.",
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
