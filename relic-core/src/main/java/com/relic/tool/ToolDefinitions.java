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
                buildTool("create_docx_file",
                        "Create or overwrite a Microsoft Word .docx document in the user's workspace and return a download link. Use this when the user asks for Word, docx, Word document, 文档, Word文档, 报告, 可下载文档, or a document/report without explicitly asking for Markdown or plain text.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filename", Map.of(
                                                "type", "string",
                                                "description", "Workspace-relative .docx path, for example reports/summary.docx. Add .docx if omitted."
                                        ),
                                        "title", Map.of(
                                                "type", "string",
                                                "description", "Document title"
                                        ),
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "Document body in Markdown-like text. Supports headings (#, ##, ###), paragraphs, bullet/numbered lists, simple Markdown tables, and Markdown image syntax for uploaded images. Do not include Mermaid charts or generated chart images in Word files."
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
                        "Render an inline Mermaid chart directly in chat without creating or saving any file. Use this for normal chart, diagram, flowchart, mind map, timeline, class diagram, sequence diagram, relationship diagram and visualization requests. Prefer concise summary diagrams by default: for relationship/comparison charts use one clear center topic, no more than 5 top-level dimensions, no more than 18 nodes and 24 edges, and short labels. Do not make encyclopedia-style sprawling graphs unless the user explicitly asks for a detailed chart. Generate one chart by default; if the user explicitly asks for multiple/separate charts, call this once per chart, up to 3 charts.",
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
                                                "description", "Complete Mermaid source or Markdown containing a mermaid code block. Prefer this for all non-trivial diagrams. For comparison/relationship charts, summarize the core point instead of listing every detail: use one center topic, up to 5 top-level dimensions, up to 18 nodes, up to 24 edges, and concise labels. Every visible node must have a meaningful user-facing label. Do not expose raw internal IDs such as P1, D1 or E1 as node text."
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
