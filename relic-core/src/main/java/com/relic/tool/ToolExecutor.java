package com.relic.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.service.GeneratedFileRegistryService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 工具执行器：负责执行 AI 发起的 tool_calls。
 */
@Slf4j
@Service
public class ToolExecutor {

    private static final long MAX_SUPPORTED_FILE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_RETURN_CHARS = 100_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${relic.workspace.path:#{systemProperties['user.home'] + '/.openclaw/workspace'}}")
    private String workspacePath;

    @Value("${relic.workspace.allow-outside-read:true}")
    private boolean allowOutsideRead;

    private final GeneratedFileRegistryService generatedFileRegistryService;

    public ToolExecutor(GeneratedFileRegistryService generatedFileRegistryService) {
        this.generatedFileRegistryService = generatedFileRegistryService;
    }

    @PostConstruct
    public void init() {
        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        if (!Files.exists(workspace)) {
            try {
                Files.createDirectories(workspace);
                log.info("[ToolExecutor] 创建工作区目录: {}", workspace);
            } catch (IOException e) {
                log.warn("[ToolExecutor] 无法创建工作区目录: {}", e.getMessage());
            }
        }
        log.info("[ToolExecutor] 工作区路径: {}", workspace);
    }

    @SuppressWarnings("unchecked")
    public String execute(String toolName, String arguments) {
        try {
            Map<String, Object> args = objectMapper.readValue(arguments, Map.class);
            return switch (toolName) {
                case "create_text_file" -> createTextFile(
                        (String) args.get("filename"),
                        (String) args.get("content"));
                case "create_mermaid_chart_file" -> createMermaidChartFile(
                        (String) args.get("filename"),
                        (String) args.get("chartType"),
                        (String) args.get("title"),
                        args.get("data"));
                case "read_file" -> readFile((String) args.get("filename"));
                case "list_files" -> listFiles((String) args.getOrDefault("path", ""));
                default -> "未知工具: " + toolName;
            };
        } catch (Exception e) {
            log.error("工具执行失败: {} - {}", toolName, e.getMessage(), e);
            return "工具执行出错: " + e.getMessage();
        }
    }

    private String createTextFile(String filename, String content) {
        try {
            if (content != null && content.length() > 1_000_000) {
                return "文件内容过大，最大支持 1MB";
            }

            Path filePath = resolveAndValidateWritePath(filename);
            Files.createDirectories(filePath.getParent());

            boolean exists = Files.exists(filePath);
            Files.writeString(filePath, content == null ? "" : content, StandardCharsets.UTF_8);

            Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
            String relativePath = workspace.relativize(filePath).toString().replace('\\', '/');
            generatedFileRegistryService.registerGeneratedFile(relativePath);

            log.info("[创建文本文件] {} ({})", filePath, exists ? "已覆盖" : "新建");
            return (exists ? "文件已覆盖: " : "文件已创建: ") + filename
                    + "\nDOWNLOAD_URL: " + buildDownloadUrl(relativePath);
        } catch (SecurityException e) {
            return "安全错误: " + e.getMessage();
        } catch (IOException e) {
            return "创建文件失败: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String createMermaidChartFile(String filename, String chartType, String title, Object rawData) {
        try {
            String safeFilename = filename == null ? "" : filename.trim();
            if (safeFilename.isEmpty()) {
                return "filename 不能为空";
            }
            if (!safeFilename.toLowerCase(Locale.ROOT).endsWith(".md")) {
                safeFilename = safeFilename + ".md";
            }

            List<ChartPoint> points = parseChartData(rawData);
            String safeTitle = title == null || title.isBlank() ? inferTitleFromFilename(safeFilename) : title.trim();
            String normalizedType = normalizeChartType(chartType, points);

            String markdown;
            if ("flowchart".equals(normalizedType) || points.isEmpty()) {
                List<String> entities = inferEntities(safeTitle, safeFilename);
                markdown = buildDefaultFlowchartMarkdown(safeTitle, entities);
            } else {
                markdown = buildMermaidMarkdown(normalizedType, safeTitle, points);
            }

            Path filePath = resolveAndValidateWritePath(safeFilename);
            Files.createDirectories(filePath.getParent());

            boolean exists = Files.exists(filePath);
            Files.writeString(filePath, markdown, StandardCharsets.UTF_8);

            Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
            String relativePath = workspace.relativize(filePath).toString().replace('\\', '/');
            generatedFileRegistryService.registerGeneratedFile(relativePath);

            log.info("[创建图表文件] {} ({})", filePath, exists ? "已覆盖" : "新建");
            return (exists ? "图表文件已覆盖: " : "图表文件已创建: ") + safeFilename
                    + "\nDOWNLOAD_URL: " + buildDownloadUrl(relativePath);
        } catch (SecurityException e) {
            return "安全错误: " + e.getMessage();
        } catch (Exception e) {
            return "创建图表文件失败: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private List<ChartPoint> parseChartData(Object rawData) {
        List<ChartPoint> points = new ArrayList<>();

        if (rawData instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> row = (Map<String, Object>) raw;
                String label = valueAsString(row.get("label"));
                Double value = valueAsDouble(row.get("value"));
                if (!label.isBlank() && value != null) {
                    points.add(new ChartPoint(label, value));
                }
            }
            return points;
        }

        if (rawData instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String label = entry.getKey() == null ? "" : entry.getKey().toString();
                Double value = valueAsDouble(entry.getValue());
                if (!label.isBlank() && value != null) {
                    points.add(new ChartPoint(label, value));
                }
            }
        }

        return points;
    }

    private String valueAsString(Object obj) {
        return obj == null ? "" : obj.toString().trim();
    }

    private Double valueAsDouble(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(obj.toString().trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeChartType(String chartType, List<ChartPoint> points) {
        String t = chartType == null ? "" : chartType.trim().toLowerCase(Locale.ROOT);
        if (t.equals("bar") || t.equals("line") || t.equals("pie") || t.equals("flowchart")) {
            return t;
        }
        return points == null || points.isEmpty() ? "flowchart" : "pie";
    }

    private String buildMermaidMarkdown(String chartType, String title, List<ChartPoint> points) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("```mermaid\n");

        if ("pie".equals(chartType)) {
            sb.append("pie title ").append(escapeMermaid(title)).append("\n");
            for (ChartPoint point : points) {
                sb.append("    \"").append(escapeMermaid(point.label())).append("\" : ")
                        .append(formatNumber(point.value())).append("\n");
            }
        } else {
            double max = points.stream().mapToDouble(ChartPoint::value).max().orElse(1d);
            double upper = Math.max(1d, Math.ceil(max * 1.2));
            String labels = points.stream()
                    .map(p -> "\"" + escapeMermaid(p.label()) + "\"")
                    .collect(Collectors.joining(", "));
            String values = points.stream()
                    .map(p -> formatNumber(p.value()))
                    .collect(Collectors.joining(", "));

            sb.append("xychart-beta\n");
            sb.append("    title \"").append(escapeMermaid(title)).append("\"\n");
            sb.append("    x-axis [").append(labels).append("]\n");
            sb.append("    y-axis \"Value\" 0 --> ").append(formatNumber(upper)).append("\n");
            if ("line".equals(chartType)) {
                sb.append("    line [").append(values).append("]\n");
            } else {
                sb.append("    bar [").append(values).append("]\n");
            }
        }

        sb.append("```\n");
        return sb.toString();
    }

    private String inferTitleFromFilename(String filename) {
        String base = filename == null ? "" : filename.trim();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0 && slash < base.length() - 1) {
            base = base.substring(slash + 1);
        }
        if (base.toLowerCase(Locale.ROOT).endsWith(".md")) {
            base = base.substring(0, base.length() - 3);
        }
        return base.isBlank() ? "Relationship Diagram" : base;
    }

    private List<String> inferEntities(String title, String filename) {
        String source = (title == null ? "" : title) + " " + (filename == null ? "" : filename);
        String cleaned = source
                .replaceAll("(?i)flowchart|chart|diagram|mermaid|docs/|\\.md", " ")
                .replaceAll("[,，、/&-]", " ")
                .replaceAll("\\b(of|and|the|for|with|to)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();

        List<String> entities = Stream.of(cleaned.split("\\s+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(4)
                .toList();

        if (entities.size() >= 2) {
            return entities;
        }
        return List.of("TopicA", "TopicB");
    }

    private String buildDefaultFlowchartMarkdown(String title, List<String> entities) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title == null || title.isBlank() ? "Relationship Diagram" : title).append("\n\n");
        sb.append("```mermaid\n");
        sb.append("flowchart LR\n");

        String first = escapeMermaid(entities.get(0));
        for (int i = 1; i < entities.size(); i++) {
            String current = escapeMermaid(entities.get(i));
            sb.append("    ").append(first).append(" <--> ").append(current).append("\n");
        }
        if (entities.size() >= 3) {
            for (int i = 1; i < entities.size() - 1; i++) {
                String left = escapeMermaid(entities.get(i));
                String right = escapeMermaid(entities.get(i + 1));
                sb.append("    ").append(left).append(" --> ").append(right).append("\n");
            }
        }

        sb.append("```\n");
        return sb.toString();
    }

    private String escapeMermaid(String text) {
        return text == null ? "" : text.replace("\"", "\\\\\"").trim();
    }

    private String formatNumber(double value) {
        if (Math.rint(value) == value) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private String buildDownloadUrl(String relativePath) {
        return "/api/files/download?relativePath="
                + URLEncoder.encode(relativePath, StandardCharsets.UTF_8);
    }

    private String readFile(String filename) {
        try {
            Path filePath = resolveReadPath(filename);

            if (!Files.exists(filePath)) {
                return "文件不存在: " + filename;
            }
            if (!Files.isRegularFile(filePath)) {
                return filename + " 不是一个普通文件";
            }

            long size = Files.size(filePath);
            if (size > MAX_SUPPORTED_FILE_BYTES) {
                return "文件过大，当前仅支持读取不超过 10MB 的文件";
            }

            String extracted = extractContentByType(filePath, filename);
            if (extracted == null || extracted.isBlank()) {
                return "文件已读取，但未提取到可见文本内容";
            }

            return limitContent(extracted, size);
        } catch (SecurityException e) {
            return "安全错误: " + e.getMessage();
        } catch (java.nio.charset.MalformedInputException e) {
            return "该文件可能是二进制文件，无法以文本形式读取";
        } catch (IOException e) {
            return "读取文件失败: " + e.getMessage();
        }
    }

    private String extractContentByType(Path filePath, String filename) throws IOException {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".pdf")) {
            return readPdf(filePath);
        }
        if (lower.endsWith(".docx")) {
            return readDocx(filePath);
        }
        if (lower.endsWith(".doc")) {
            return readDoc(filePath);
        }

        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    private String readPdf(Path filePath) throws IOException {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String readDocx(Path filePath) throws IOException {
        try (InputStream in = Files.newInputStream(filePath);
             XWPFDocument document = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String readDoc(Path filePath) throws IOException {
        try (InputStream in = Files.newInputStream(filePath);
             HWPFDocument document = new HWPFDocument(in);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String limitContent(String content, long sizeInBytes) {
        if (content.length() <= MAX_RETURN_CHARS) {
            return content;
        }
        return "文件较大 (" + sizeInBytes + " 字节)，仅显示前 " + MAX_RETURN_CHARS + " 字符:\n"
                + content.substring(0, MAX_RETURN_CHARS);
    }

    private String listFiles(String subPath) {
        try {
            Path dirPath;
            if (subPath == null || subPath.isEmpty()) {
                dirPath = Path.of(workspacePath).toAbsolutePath().normalize();
            } else {
                dirPath = resolveAndValidateWritePath(subPath);
            }

            if (!Files.exists(dirPath)) {
                return "目录不存在: " + (subPath == null || subPath.isEmpty() ? "工作区根目录" : subPath);
            }
            if (!Files.isDirectory(dirPath)) {
                return subPath + " 不是一个目录";
            }

            try (Stream<Path> stream = Files.list(dirPath)) {
                String listing = stream
                        .map(p -> (Files.isDirectory(p) ? "[目录] " : "[文件] ") + p.getFileName().toString())
                        .sorted()
                        .collect(Collectors.joining("\n"));
                return listing.isEmpty() ? "目录为空" : listing;
            }
        } catch (SecurityException e) {
            return "安全错误: " + e.getMessage();
        } catch (IOException e) {
            return "列出文件失败: " + e.getMessage();
        }
    }

    private Path resolveAndValidateWritePath(String filename) {
        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path resolved = workspace.resolve(filename).normalize();

        if (!resolved.startsWith(workspace)) {
            throw new SecurityException("路径不允许超出工作区范围: " + filename);
        }

        return resolved;
    }

    private Path resolveReadPath(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new SecurityException("文件路径不能为空");
        }

        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path candidate;
        try {
            candidate = Path.of(filename);
        } catch (InvalidPathException e) {
            throw new SecurityException("非法路径: " + filename);
        }

        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : workspace.resolve(filename).normalize();

        if (!allowOutsideRead && !resolved.startsWith(workspace)) {
            throw new SecurityException("路径不允许超出工作区范围: " + filename);
        }

        return resolved;
    }

    private record ChartPoint(String label, double value) {}
}