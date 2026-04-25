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
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.poi.util.Units;

/**
 * Executes tool calls requested by AI providers.
 */
@Slf4j
@Service
public class ToolExecutor {

    private static final long MAX_SUPPORTED_FILE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_RETURN_CHARS = 100_000;
    public static final String INLINE_CHART_MARKER = "INLINE_CHART_MARKDOWN:";
    public static final String STRUCTURED_CHART_MARKER = "RELIC_CHART_JSON:";
    public static final String CHART_VALIDATION_ERROR_MARKER = "CHART_VALIDATION_ERROR:";
    private static final Pattern MERMAID_BLOCK_PATTERN = Pattern.compile("```mermaid\\s*\\R([\\s\\S]*?)\\R?```");
    private static final Pattern STRUCTURED_CHART_LINE_PATTERN = Pattern.compile("^" + Pattern.quote(STRUCTURED_CHART_MARKER) + "(\\{.*})\\s*$");
    private static final Pattern LABELED_PLACEHOLDER_NODE_PATTERN = Pattern.compile("\\b([A-Za-z]{1,8}\\d{1,4})\\s*[\\[({]");
    private static final Pattern BARE_PLACEHOLDER_NODE_PATTERN = Pattern.compile("\\b([A-Za-z]{1,8}\\d{1,4})\\b(?!\\s*[\\[({])");
    private static final Pattern FLOWCHART_LABEL_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*\\s*[\\[({]([^\\]\\)}]+)[\\]\\)}]");
    private static final Pattern PIE_ROW_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final int DOCX_CHART_WIDTH = 900;
    private static final int DOCX_CHART_ROW_HEIGHT = 78;

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
                log.info("[ToolExecutor] created workspace directory: {}", workspace);
            } catch (IOException e) {
                log.warn("[ToolExecutor] failed to create workspace directory: {}", e.getMessage());
            }
        }
        log.info("[ToolExecutor] workspace path: {}", workspace);
    }

    @SuppressWarnings("unchecked")
    public String execute(String toolName, String arguments) {
        try {
            Map<String, Object> args = objectMapper.readValue(arguments, Map.class);
            return switch (toolName) {
                case "create_text_file" -> createTextFile(
                        firstTextArg(args, "filename", "file_path", "filepath", "path"),
                        (String) args.get("content"));
                case "create_docx_file" -> createDocxFile(
                        firstTextArg(args, "filename", "file_path", "filepath", "path"),
                        (String) args.get("title"),
                        (String) args.get("content"));
                case "render_mermaid_chart" -> renderMermaidChart(
                        (String) args.get("title"),
                        firstTextArg(args, "content", "mermaidSource", "source"),
                        (String) args.get("chartType"),
                        args.get("data"));
                case "read_file" -> readFile(firstTextArg(args, "filename", "file_path", "filepath", "path"));
                case "list_files" -> listFiles((String) args.getOrDefault("path", ""));
                default -> "Unknown tool: " + toolName;
            };
        } catch (Exception e) {
            log.error("Tool execution failed: {} - {}", toolName, e.getMessage(), e);
            return "Tool execution error: " + e.getMessage();
        }
    }

    private String createTextFile(String filename, String content) {
        try {
            if (content != null && content.length() > 1_000_000) {
                return "File content is too large; max supported size is 1MB";
            }

            Path filePath = resolveAndValidateWritePath(filename);
            Files.createDirectories(filePath.getParent());

            boolean exists = Files.exists(filePath);
            Files.writeString(filePath, content == null ? "" : content, StandardCharsets.UTF_8);

            Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
            String relativePath = workspace.relativize(filePath).toString().replace('\\', '/');
            generatedFileRegistryService.registerGeneratedFile(relativePath);

            log.info("[create text file] {} ({})", filePath, exists ? "overwritten" : "created");
            return (exists ? "File overwritten: " : "File created: ") + filename
                    + "\nDOWNLOAD_URL: " + buildDownloadUrl(relativePath);
        } catch (SecurityException e) {
            return "Security error: " + e.getMessage();
        } catch (IOException e) {
            return "Failed to create file: " + e.getMessage();
        }
    }

    private String createDocxFile(String filename, String title, String content) {
        try {
            if (content != null && content.length() > 1_000_000) {
                return "Document content is too large; max supported size is 1MB";
            }

            String safeFilename = filename == null ? "" : filename.trim();
            if (safeFilename.isEmpty()) {
                safeFilename = "document.docx";
            }
            if (!safeFilename.toLowerCase(Locale.ROOT).endsWith(".docx")) {
                safeFilename = safeFilename + ".docx";
            }

            Path filePath = resolveAndValidateWritePath(safeFilename);
            Files.createDirectories(filePath.getParent());

            boolean exists = Files.exists(filePath);
            try (XWPFDocument document = new XWPFDocument();
                 OutputStream outputStream = Files.newOutputStream(filePath)) {
                String safeTitle = title == null || title.isBlank() ? inferTitleFromFilename(safeFilename) : title.trim();
                String safeContent = sanitizeDocxContent(content == null ? "" : content);
                addTitle(document, safeTitle);
                addMarkdownLikeContent(document, safeContent);
                document.write(outputStream);
            }

            Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
            String relativePath = workspace.relativize(filePath).toString().replace('\\', '/');
            generatedFileRegistryService.registerGeneratedFile(relativePath);

            log.info("[create docx file] {} ({})", filePath, exists ? "overwritten" : "created");
            return (exists ? "Word document overwritten: " : "Word document created: ") + safeFilename
                    + "\nDOWNLOAD_URL: " + buildDownloadUrl(relativePath);
        } catch (SecurityException e) {
            return "Security error: " + e.getMessage();
        } catch (Exception e) {
            return "Failed to create Word document: " + e.getMessage();
        }
    }

    private void addTitle(XWPFDocument document, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(stripMarkdown(title));
        run.setBold(true);
        run.setFontSize(20);
    }

    private void addMarkdownLikeContent(XWPFDocument document, String content) {
        List<String> lines = content.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            Matcher structuredChartMatcher = STRUCTURED_CHART_LINE_PATTERN.matcher(trimmed);
            if (structuredChartMatcher.matches()) {
                String chartSource = extractJsonTextField(structuredChartMatcher.group(1), "source");
                if (!chartSource.isBlank()) {
                    addMermaidChartPreview(document, chartSource);
                    continue;
                }
            }

            if (trimmed.equalsIgnoreCase("```mermaid")) {
                StringBuilder mermaidSource = new StringBuilder();
                i++;
                while (i < lines.size() && !lines.get(i).trim().startsWith("```")) {
                    mermaidSource.append(lines.get(i)).append('\n');
                    i++;
                }
                addMermaidChartPreview(document, mermaidSource.toString().trim());
                continue;
            }

            if (isMarkdownTableLine(trimmed)) {
                List<List<String>> tableRows = new ArrayList<>();
                while (i < lines.size() && isMarkdownTableLine(lines.get(i).trim())) {
                    String tableLine = lines.get(i).trim();
                    if (!isMarkdownTableDelimiter(tableLine)) {
                        tableRows.add(parseMarkdownTableRow(tableLine));
                    }
                    i++;
                }
                i--;
                addTable(document, tableRows);
                continue;
            }

            if (trimmed.startsWith("### ")) {
                addParagraph(document, trimmed.substring(4), true, 13, "");
            } else if (trimmed.startsWith("## ")) {
                addParagraph(document, trimmed.substring(3), true, 15, "");
            } else if (trimmed.startsWith("# ")) {
                addParagraph(document, trimmed.substring(2), true, 17, "");
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                addParagraph(document, trimmed.substring(2), false, 11, "\u2022 ");
            } else if (trimmed.matches("\\d+[.)]\\s+.*")) {
                addParagraph(document, trimmed.replaceFirst("^\\d+[.)]\\s+", ""), false, 11, "1. ");
            } else {
                addParagraph(document, trimmed, false, 11, "");
            }
        }
    }

    private void addMermaidChartPreview(XWPFDocument document, String source) {
        if (source == null || source.isBlank()) {
            return;
        }

        try {
            byte[] png = buildMermaidPreviewPng(source);
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            try (InputStream in = new ByteArrayInputStream(png)) {
                run.addPicture(in, XWPFDocument.PICTURE_TYPE_PNG, "chart.png", Units.toEMU(500), Units.toEMU(300));
            }
        } catch (Exception e) {
            log.warn("[create docx file] failed to embed chart preview: {}", e.getMessage());
            addParagraph(document, "Chart", true, 13, "");
            for (String line : source.split("\\R")) {
                if (!line.trim().isEmpty()) {
                    addParagraph(document, line.trim(), false, 10, "");
                }
            }
        }
    }

    private byte[] buildMermaidPreviewPng(String source) throws IOException {
        List<String> labels = extractMermaidPreviewLabels(source);
        String type = detectMermaidPreviewType(source);
        int visibleCount = Math.max(1, Math.min(labels.size(), 12));
        int height = Math.max(280, 120 + visibleCount * DOCX_CHART_ROW_HEIGHT);
        BufferedImage image = new BufferedImage(DOCX_CHART_WIDTH, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, DOCX_CHART_WIDTH, height);
            g.setColor(new Color(248, 250, 252));
            g.fillRoundRect(12, 12, DOCX_CHART_WIDTH - 24, height - 24, 26, 26);
            g.setColor(new Color(203, 213, 225));
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(12, 12, DOCX_CHART_WIDTH - 24, height - 24, 26, 26);

            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 30));
            g.setColor(new Color(15, 23, 42));
            g.drawString(type, 44, 60);

            if (labels.isEmpty()) {
                String firstLine = source.lines().findFirst().orElse("Mermaid chart").trim();
                g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 22));
                g.setColor(new Color(51, 65, 85));
                g.drawString(abbreviate(firstLine.isBlank() ? "Mermaid chart" : firstLine, 48), 44, 112);
            } else if (source.stripLeading().toLowerCase(Locale.ROOT).startsWith("pie")) {
                drawPiePreview(g, labels);
            } else {
                drawFlowPreview(g, labels);
            }
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private void drawFlowPreview(Graphics2D g, List<String> labels) {
        List<String> visible = labels.stream().limit(12).toList();
        int centerX = DOCX_CHART_WIDTH / 2;
        int y = 92;
        int boxWidth = 560;
        int boxHeight = 48;
        for (int i = 0; i < visible.size(); i++) {
            int x = centerX - boxWidth / 2;
            g.setColor(Color.WHITE);
            g.fillRoundRect(x, y, boxWidth, boxHeight, 14, 14);
            g.setColor(new Color(37, 99, 235));
            g.setStroke(new BasicStroke(2.2f));
            g.drawRoundRect(x, y, boxWidth, boxHeight, 14, 14);

            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 22));
            g.setColor(new Color(30, 41, 59));
            drawCenteredText(g, abbreviate(visible.get(i), 30), x, y, boxWidth, boxHeight);

            if (i < visible.size() - 1) {
                int lineX = centerX;
                int lineTop = y + boxHeight;
                int lineBottom = y + DOCX_CHART_ROW_HEIGHT;
                g.setColor(new Color(100, 116, 139));
                g.setStroke(new BasicStroke(2.4f));
                g.drawLine(lineX, lineTop, lineX, lineBottom);
                int arrowY = lineBottom;
                g.fillPolygon(
                        new int[] { lineX - 8, lineX + 8, lineX },
                        new int[] { arrowY - 10, arrowY - 10, arrowY },
                        3);
            }
            y += DOCX_CHART_ROW_HEIGHT;
        }
    }

    private void drawPiePreview(Graphics2D g, List<String> labels) {
        Color[] palette = {
                new Color(37, 99, 235),
                new Color(5, 150, 105),
                new Color(217, 119, 6),
                new Color(220, 38, 38),
                new Color(124, 58, 237),
                new Color(8, 145, 178)
        };
        int x = 68;
        int y = 108;
        int size = 150;
        int start = 90;
        int sweep = labels.isEmpty() ? 360 : 360 / Math.min(labels.size(), palette.length);
        for (int i = 0; i < Math.min(labels.size(), palette.length); i++) {
            g.setColor(palette[i % palette.length]);
            int arc = i == Math.min(labels.size(), palette.length) - 1 ? 90 - start : -sweep;
            g.fillArc(x, y, size, size, start, arc);
            start += arc;
        }
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f));
        g.drawOval(x, y, size, size);

        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 20));
        for (int i = 0; i < Math.min(labels.size(), 8); i++) {
            int rowY = 112 + i * 30;
            g.setColor(palette[i % palette.length]);
            g.fillRoundRect(285, rowY - 16, 20, 20, 5, 5);
            g.setColor(new Color(30, 41, 59));
            g.drawString(abbreviate(labels.get(i), 34), 316, rowY);
        }
    }

    private void drawCenteredText(Graphics2D g, String text, int x, int y, int width, int height) {
        FontMetrics metrics = g.getFontMetrics();
        int textX = x + Math.max(0, (width - metrics.stringWidth(text)) / 2);
        int textY = y + ((height - metrics.getHeight()) / 2) + metrics.getAscent();
        g.drawString(text, textX, textY);
    }

    private List<String> extractMermaidPreviewLabels(String source) {
        String lower = source == null ? "" : source.stripLeading().toLowerCase(Locale.ROOT);
        if (lower.startsWith("pie")) {
            return extractPieLabels(source);
        }
        if (lower.startsWith("xychart-beta")) {
            return extractXyLabels(source);
        }
        if (lower.startsWith("flowchart") || lower.startsWith("graph")) {
            return extractFlowchartLabels(source);
        }
        return source.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("%%"))
                .filter(line -> !line.equalsIgnoreCase("mindmap"))
                .filter(line -> !line.equalsIgnoreCase("timeline"))
                .filter(line -> !line.equalsIgnoreCase("gantt"))
                .map(line -> line.replaceFirst("^[-*:]+\\s*", ""))
                .limit(12)
                .collect(Collectors.toList());
    }

    private List<String> extractFlowchartLabels(String source) {
        List<String> labels = new ArrayList<>();
        Matcher matcher = FLOWCHART_LABEL_PATTERN.matcher(source);
        while (matcher.find()) {
            String label = matcher.group(1).trim();
            if (!label.isBlank() && !labels.contains(label)) {
                labels.add(label);
            }
        }
        return labels;
    }

    private List<String> extractPieLabels(String source) {
        List<String> labels = new ArrayList<>();
        Matcher matcher = PIE_ROW_PATTERN.matcher(source);
        while (matcher.find()) {
            labels.add(matcher.group(1) + ": " + matcher.group(2));
        }
        return labels;
    }

    private List<String> extractXyLabels(String source) {
        Matcher matcher = Pattern.compile("(?im)^\\s*x-axis\\s*\\[(.*)]\\s*$").matcher(source);
        if (!matcher.find()) {
            return List.of();
        }
        return Stream.of(matcher.group(1).split(","))
                .map(label -> label.replace("\"", "").trim())
                .filter(label -> !label.isBlank())
                .collect(Collectors.toList());
    }

    private String detectMermaidPreviewType(String source) {
        String lower = source == null ? "" : source.stripLeading().toLowerCase(Locale.ROOT);
        if (lower.startsWith("pie")) {
            return "Pie chart";
        }
        if (lower.startsWith("xychart-beta")) {
            return "XY chart";
        }
        if (lower.startsWith("flowchart") || lower.startsWith("graph")) {
            return "Flowchart";
        }
        if (lower.startsWith("mindmap")) {
            return "Mind map";
        }
        if (lower.startsWith("timeline")) {
            return "Timeline";
        }
        return "Mermaid chart";
    }

    private String extractJsonTextField(String json, String fieldName) {
        try {
            Object value = objectMapper.readValue(json, Map.class).get(fieldName);
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String abbreviate(String text, int maxLength) {
        String value = text == null ? "" : text.trim();
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)).trim() + "...";
    }

    private String sanitizeDocxContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String value = content.trim();
        if (!looksLikeHtmlContent(value)) {
            return value;
        }

        String cleaned = value
                .replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<style[^>]*>.*?</style>", "")
                .replaceAll("(?is)<!DOCTYPE[^>]*>", "")
                .replaceAll("(?is)<meta[^>]*>", "")
                .replaceAll("(?is)</?(html|head|body|xml|o:p|span|font)[^>]*>", "")
                .replaceAll("(?is)<h1[^>]*>", "# ")
                .replaceAll("(?is)<h2[^>]*>", "## ")
                .replaceAll("(?is)<h3[^>]*>", "### ")
                .replaceAll("(?is)<h4[^>]*>", "#### ")
                .replaceAll("(?is)</h[1-6]>", "\n\n")
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</p>", "\n\n")
                .replaceAll("(?is)<p[^>]*>", "")
                .replaceAll("(?is)</div>", "\n")
                .replaceAll("(?is)<div[^>]*>", "")
                .replaceAll("(?is)<li[^>]*>", "- ")
                .replaceAll("(?is)</li>", "\n")
                .replaceAll("(?is)</?(ul|ol)[^>]*>", "\n")
                .replaceAll("(?is)</tr>", "\n")
                .replaceAll("(?is)<tr[^>]*>", "")
                .replaceAll("(?is)</t[dh]>", " | ")
                .replaceAll("(?is)<t[dh][^>]*>", "| ")
                .replaceAll("(?is)</?table[^>]*>", "\n")
                .replaceAll("(?is)<[^>]+>", "");

        cleaned = decodeHtmlEntities(cleaned)
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        return cleaned.isBlank() ? stripMarkdown(value) : cleaned;
    }

    private boolean looksLikeHtmlContent(String content) {
        String lowered = content == null ? "" : content.stripLeading().toLowerCase(Locale.ROOT);
        return lowered.startsWith("<!doctype")
                || lowered.startsWith("<html")
                || lowered.startsWith("<?xml")
                || lowered.contains("<body")
                || lowered.contains("<style")
                || lowered.contains("xmlns:w=")
                || lowered.contains("<table");
    }

    private String decodeHtmlEntities(String text) {
        return (text == null ? "" : text)
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private void addParagraph(XWPFDocument document, String text, boolean bold, int fontSize, String prefix) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(prefix + stripMarkdown(text));
        run.setBold(bold);
        run.setFontSize(fontSize);
    }

    private void addTable(XWPFDocument document, List<List<String>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        int cols = rows.stream().mapToInt(List::size).max().orElse(1);
        XWPFTable table = document.createTable(rows.size(), cols);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            XWPFTableRow tableRow = table.getRow(rowIndex);
            List<String> sourceRow = rows.get(rowIndex);
            for (int colIndex = 0; colIndex < cols; colIndex++) {
                XWPFTableCell cell = tableRow.getCell(colIndex);
                cell.removeParagraph(0);
                XWPFParagraph paragraph = cell.addParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(colIndex < sourceRow.size() ? stripMarkdown(sourceRow.get(colIndex)) : "");
                run.setBold(rowIndex == 0);
            }
        }
    }

    private boolean isMarkdownTableLine(String line) {
        return line.startsWith("|") && line.endsWith("|") && line.indexOf('|', 1) > 0;
    }

    private boolean isMarkdownTableDelimiter(String line) {
        return line.replace("|", "")
                .replace(":", "")
                .replace("-", "")
                .trim()
                .isEmpty();
    }

    private List<String> parseMarkdownTableRow(String line) {
        String trimmed = line;
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return Stream.of(trimmed.split("\\|", -1))
                .map(String::trim)
                .collect(Collectors.toList());
    }

    private String stripMarkdown(String text) {
        return (text == null ? "" : text)
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("__([^_]+)__", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .trim();
    }

    private String renderMermaidChart(String title, String providedSource, String chartType, Object rawData) {
        try {
            String safeTitle = title == null || title.isBlank() ? "Mermaid Chart" : title.trim();
            List<ChartPoint> points = parseChartData(rawData);
            String markdown;

            if (providedSource != null && !providedSource.isBlank()) {
                markdown = buildProvidedMermaidMarkdown(safeTitle, providedSource);
            } else if (!points.isEmpty()) {
                markdown = buildMermaidMarkdown(normalizeChartType(chartType, points), safeTitle, points);
            } else {
                return CHART_VALIDATION_ERROR_MARKER
                        + " render_mermaid_chart requires content or mermaidSource with complete Mermaid syntax.";
            }

            String validationError = validateMermaidMarkdown(markdown);
            if (!validationError.isBlank()) {
                return validationError;
            }

            return buildStructuredChartMarker(safeTitle, markdown)
                    + "\n" + INLINE_CHART_MARKER + "\n" + markdown;
        } catch (Exception e) {
            return CHART_VALIDATION_ERROR_MARKER + " " + e.getMessage();
        }
    }

    private String buildStructuredChartMarker(String title, String markdown) throws IOException {
        String source = extractFirstMermaidSource(markdown);
        Map<String, Object> payload = Map.of(
                "kind", "mermaid",
                "title", title == null ? "" : title,
                "source", source
        );
        return STRUCTURED_CHART_MARKER + objectMapper.writeValueAsString(payload);
    }

    private String extractFirstMermaidSource(String markdown) {
        Matcher matcher = MERMAID_BLOCK_PATTERN.matcher(markdown == null ? "" : markdown);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private String firstTextArg(Map<String, Object> args, String... names) {
        for (String name : names) {
            Object value = args.get(name);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private String buildProvidedMermaidMarkdown(String title, String source) {
        String trimmed = source == null ? "" : source.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        if (trimmed.contains("```")) {
            String markdown = trimmed.startsWith("#") ? trimmed : "# " + title + "\n\n" + trimmed;
            return markdown.endsWith("\n") ? markdown : markdown + "\n";
        }
        return "# " + title + "\n\n```mermaid\n" + trimmed + "\n```\n";
    }

    private String validateMermaidMarkdown(String markdown) {
        Matcher matcher = MERMAID_BLOCK_PATTERN.matcher(markdown);
        while (matcher.find()) {
            String error = validateMermaidSource(matcher.group(1));
            if (!error.isBlank()) {
                return error;
            }
        }
        return "";
    }

    private String validateMermaidSource(String source) {
        String trimmed = source == null ? "" : source.stripLeading().toLowerCase(Locale.ROOT);
        if (!trimmed.startsWith("flowchart") && !trimmed.startsWith("graph")) {
            return "";
        }

        Set<String> labeledIds = new LinkedHashSet<>();
        Matcher labeledMatcher = LABELED_PLACEHOLDER_NODE_PATTERN.matcher(source);
        while (labeledMatcher.find()) {
            labeledIds.add(labeledMatcher.group(1));
        }

        Set<String> bareIds = new LinkedHashSet<>();
        Matcher bareMatcher = BARE_PLACEHOLDER_NODE_PATTERN.matcher(source);
        while (bareMatcher.find()) {
            String id = bareMatcher.group(1);
            if (!labeledIds.contains(id)) {
                bareIds.add(id);
            }
        }

        if (bareIds.isEmpty()) {
            return "";
        }

        return CHART_VALIDATION_ERROR_MARKER + " Mermaid nodes are missing display labels: " + String.join(", ", bareIds)
                + ". Call the chart tool again with complete Mermaid syntax, and give each placeholder id a clear user-facing label, for example P1[actual meaning]. Do not show raw ids such as P1, D1 or E1 to the user.";
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
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx")) {
            base = base.substring(0, base.length() - 5);
        } else if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            base = base.substring(0, base.length() - 3);
        }
        return base.isBlank() ? "Relationship Diagram" : base;
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
                return "File does not exist: " + filename;
            }
            if (!Files.isRegularFile(filePath)) {
                return filename + " is not a regular file";
            }

            long size = Files.size(filePath);
            if (size > MAX_SUPPORTED_FILE_BYTES) {
                return "File is too large; only files up to 10MB are supported";
            }

            String extracted = extractContentByType(filePath, filename);
            if (extracted == null || extracted.isBlank()) {
                return "File was read, but no visible text content was extracted";
            }

            return limitContent(extracted, size);
        } catch (SecurityException e) {
            return "Security error: " + e.getMessage();
        } catch (java.nio.charset.MalformedInputException e) {
            return "This file may be binary and cannot be read as text";
        } catch (IOException e) {
            return "Failed to read file: " + e.getMessage();
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
        return "File is large (" + sizeInBytes + " bytes); showing first " + MAX_RETURN_CHARS + " chars:\n"
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
                return "Directory does not exist: " + (subPath == null || subPath.isEmpty() ? "workspace root" : subPath);
            }
            if (!Files.isDirectory(dirPath)) {
                return subPath + " is not a directory";
            }

            try (Stream<Path> stream = Files.list(dirPath)) {
                String listing = stream
                        .map(p -> (Files.isDirectory(p) ? "[dir] " : "[file] ") + p.getFileName().toString())
                        .sorted()
                        .collect(Collectors.joining("\n"));
                return listing.isEmpty() ? "Directory is empty" : listing;
            }
        } catch (SecurityException e) {
            return "Security error: " + e.getMessage();
        } catch (IOException e) {
            return "Failed to list files: " + e.getMessage();
        }
    }

    private Path resolveAndValidateWritePath(String filename) {
        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path resolved = workspace.resolve(filename).normalize();

        if (!resolved.startsWith(workspace)) {
            throw new SecurityException("Path is outside workspace: " + filename);
        }

        return resolved;
    }

    private Path resolveReadPath(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new SecurityException("File path must not be empty");
        }

        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path candidate;
        try {
            candidate = Path.of(filename);
        } catch (InvalidPathException e) {
            throw new SecurityException("Invalid path: " + filename);
        }

        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : workspace.resolve(filename).normalize();

        if (!allowOutsideRead && !resolved.startsWith(workspace)) {
            throw new SecurityException("Path is outside workspace: " + filename);
        }

        return resolved;
    }

    private record ChartPoint(String label, double value) {}
}
