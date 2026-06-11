package com.relic.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relic.service.GeneratedFileRegistryService;
import jakarta.annotation.PostConstruct;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.poi.util.Units;

/**
 * Executes tool calls requested by AI providers.
 */
@Service
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private static final long MAX_SUPPORTED_FILE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_RETURN_CHARS = 100_000;
    public static final String INLINE_CHART_MARKER = "INLINE_CHART_MARKDOWN:";
    public static final String STRUCTURED_CHART_MARKER = "RELIC_CHART_JSON:";
    public static final String CHART_VALIDATION_ERROR_MARKER = "CHART_VALIDATION_ERROR:";
    private static final Pattern MERMAID_BLOCK_PATTERN = Pattern.compile("```mermaid\\s*\\R([\\s\\S]*?)\\R?```");
    private static final Pattern STRUCTURED_CHART_LINE_PATTERN = Pattern.compile("^" + Pattern.quote(STRUCTURED_CHART_MARKER) + "(\\{.*})\\s*$");
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile("^!\\[([^\\]]*)]\\(([^)]+)\\)\\s*$");
    private static final Pattern LABELED_PLACEHOLDER_NODE_PATTERN = Pattern.compile("\\b([A-Za-z]{1,8}\\d{1,4})\\s*[\\[({]");
    private static final Pattern BARE_PLACEHOLDER_NODE_PATTERN = Pattern.compile("\\b([A-Za-z]{1,8}\\d{1,4})\\b(?!\\s*[\\[({])");
    private static final Pattern FLOWCHART_LABEL_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*\\s*[\\[({]([^\\]\\)}]+)[\\]\\)}]");
    private static final Pattern PIE_ROW_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final int DOCX_CHART_WIDTH = 900;
    private static final int DOCX_CHART_ROW_HEIGHT = 78;
    private static final int DOCX_CHART_MAX_WIDTH_EMU = Units.toEMU(500);
    private static final int DOCX_CHART_MAX_HEIGHT_EMU = Units.toEMU(520);
    private static final String[] DOCX_CHART_FONT_CANDIDATES = {
            "Microsoft YaHei UI", "Microsoft YaHei", "SimHei", "SimSun", "DengXian",
            "Noto Sans CJK SC", "Noto Sans SC", "Source Han Sans SC", "Arial Unicode MS", "Dialog"
    };

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 请求级别的工作目录上下文：相对路径写入时优先以此为基础。
    private static final ThreadLocal<String> WORKING_DIRECTORY_CONTEXT = new ThreadLocal<>();

    public static void setWorkingDirectoryContext(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            WORKING_DIRECTORY_CONTEXT.remove();
        } else {
            WORKING_DIRECTORY_CONTEXT.set(workingDirectory.trim());
        }
    }

    public static void clearWorkingDirectoryContext() {
        WORKING_DIRECTORY_CONTEXT.remove();
    }

    public static String currentWorkingDirectory() {
        return WORKING_DIRECTORY_CONTEXT.get();
    }

    public static boolean hasWorkingDirectoryOverride() {
        String value = WORKING_DIRECTORY_CONTEXT.get();
        return value != null && !value.isBlank();
    }

    @Value("${relic.workspace.path:#{systemProperties['user.home'] + '/.openclaw/workspace'}}")
    private String workspacePath;

    @Value("${relic.workspace.allow-outside-read:true}")
    private boolean allowOutsideRead;

    @Value("${relic.chart.mermaid-cli.path:}")
    private String mermaidCliPath;

    @Value("${relic.chart.mermaid-cli.timeout-ms:15000}")
    private long mermaidCliTimeoutMs = 15000L;

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

            String registryKey = backupToWorkspaceIfOutside(filePath);
            generatedFileRegistryService.registerGeneratedFile(registryKey);

            log.info("[create text file] {} ({})", filePath, exists ? "overwritten" : "created");
            return (exists ? "File overwritten: " : "File created: ") + filePath.toAbsolutePath()
                    + "\nDOWNLOAD_URL: " + buildDownloadUrl(registryKey);
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

            String registryKey = backupToWorkspaceIfOutside(filePath);
            generatedFileRegistryService.registerGeneratedFile(registryKey);

            log.info("[create docx file] {} ({})", filePath, exists ? "overwritten" : "created");
            return (exists ? "Word document overwritten: " : "Word document created: ") + filePath.toAbsolutePath()
                    + "\nDOWNLOAD_URL: " + buildDownloadUrl(registryKey);
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

            Matcher imageMatcher = MARKDOWN_IMAGE_PATTERN.matcher(trimmed);
            if (imageMatcher.matches()) {
                addDocumentImage(document, imageMatcher.group(2), imageMatcher.group(1));
                continue;
            }

            if (isMarkdownHorizontalRule(trimmed)) {
                continue;
            }

            Matcher structuredChartMatcher = STRUCTURED_CHART_LINE_PATTERN.matcher(trimmed);
            if (structuredChartMatcher.matches()) {
                continue;
            }

            if (trimmed.equalsIgnoreCase("```mermaid")) {
                i++;
                while (i < lines.size() && !lines.get(i).trim().startsWith("```")) {
                    i++;
                }
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

            MarkdownHeading heading = parseMarkdownHeading(trimmed);
            if (heading != null) {
                addParagraph(document, heading.text(), true, headingFontSize(heading.level()), "");
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                addParagraph(document, trimmed.substring(2), false, 11, "\u2022 ");
            } else if (trimmed.matches("\\d+[.)]\\s+.*")) {
                addParagraph(document, trimmed.replaceFirst("^\\d+[.)]\\s+", ""), false, 11, "1. ");
            } else {
                addParagraph(document, trimmed, false, 11, "");
            }
        }
    }

    private MarkdownHeading parseMarkdownHeading(String line) {
        Matcher matcher = Pattern.compile("^(#{1,6})\\s*(\\S.*)$").matcher(line == null ? "" : line.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new MarkdownHeading(matcher.group(1).length(), matcher.group(2).replaceAll("\\s+#{1,6}$", "").trim());
    }

    private int headingFontSize(int level) {
        return switch (level) {
            case 1 -> 17;
            case 2 -> 15;
            default -> 13;
        };
    }

    private boolean isMarkdownHorizontalRule(String line) {
        String compact = (line == null ? "" : line.trim()).replaceAll("\\s+", "");
        return compact.matches("([-*_])\\1{2,}");
    }

    private void addDocumentImage(XWPFDocument document, String source, String altText) {
        String cleanSource = cleanMarkdownImageSource(source);
        if (cleanSource.isBlank()) {
            return;
        }

        try {
            ImagePayload payload = loadDocumentImage(cleanSource);
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            try (InputStream in = new ByteArrayInputStream(payload.bytes())) {
                int[] size = scaledPictureSize(payload.bytes());
                run.addPicture(in, payload.pictureType(), payload.filename(), size[0], size[1]);
            }
            if (altText != null && !altText.isBlank()) {
                addParagraph(document, altText.trim(), false, 9, "");
            }
        } catch (Exception e) {
            log.warn("[create docx file] failed to embed image {}: {}", cleanSource, e.getMessage());
            String label = altText == null || altText.isBlank() ? "Image" : altText.trim();
            addParagraph(document, label + " (" + cleanSource + ")", false, 10, "");
        }
    }

    private ImagePayload loadDocumentImage(String source) throws IOException {
        if (source.startsWith("data:image/")) {
            return decodeDataUrlImage(source);
        }

        String pathSource = decodeWorkspaceImagePath(source);
        Path imagePath = resolveReadPath(pathSource);
        if (!Files.exists(imagePath) || !Files.isRegularFile(imagePath)) {
            throw new IOException("Image file does not exist: " + pathSource);
        }
        if (Files.size(imagePath) > MAX_SUPPORTED_FILE_BYTES) {
            throw new IOException("Image file is too large: " + pathSource);
        }

        byte[] bytes = Files.readAllBytes(imagePath);
        return normalizeImagePayload(bytes, imagePath.getFileName().toString(), pictureTypeForName(imagePath.getFileName().toString()));
    }

    private ImagePayload decodeDataUrlImage(String source) throws IOException {
        int comma = source.indexOf(',');
        if (comma < 0 || !source.substring(0, comma).contains(";base64")) {
            throw new IOException("Unsupported image data URL");
        }
        String header = source.substring(0, comma).toLowerCase(Locale.ROOT);
        byte[] bytes = Base64.getDecoder().decode(source.substring(comma + 1));
        String extension = header.contains("image/jpeg") || header.contains("image/jpg") ? ".jpg"
                : header.contains("image/gif") ? ".gif"
                : header.contains("image/bmp") ? ".bmp"
                : ".png";
        return normalizeImagePayload(bytes, "image" + extension, pictureTypeForName(extension));
    }

    private ImagePayload normalizeImagePayload(byte[] bytes, String filename, int pictureType) throws IOException {
        if (pictureType > 0 && ImageIO.read(new ByteArrayInputStream(bytes)) != null) {
            return new ImagePayload(bytes, pictureType, filename);
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("Unsupported image format");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new ImagePayload(output.toByteArray(), XWPFDocument.PICTURE_TYPE_PNG, replaceImageExtension(filename, ".png"));
    }

    private int pictureTypeForName(String name) {
        String lowered = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lowered.endsWith(".png")) {
            return XWPFDocument.PICTURE_TYPE_PNG;
        }
        if (lowered.endsWith(".jpg") || lowered.endsWith(".jpeg")) {
            return XWPFDocument.PICTURE_TYPE_JPEG;
        }
        if (lowered.endsWith(".gif")) {
            return XWPFDocument.PICTURE_TYPE_GIF;
        }
        if (lowered.endsWith(".bmp")) {
            return XWPFDocument.PICTURE_TYPE_BMP;
        }
        return 0;
    }

    private String cleanMarkdownImageSource(String source) {
        String clean = source == null ? "" : source.trim();
        if ((clean.startsWith("\"") && clean.endsWith("\"")) || (clean.startsWith("'") && clean.endsWith("'"))) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        if (clean.startsWith("<") && clean.endsWith(">")) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        return clean;
    }

    private String decodeWorkspaceImagePath(String source) {
        String clean = cleanMarkdownImageSource(source);
        int relativePathIndex = clean.indexOf("relativePath=");
        if (relativePathIndex >= 0) {
            String value = clean.substring(relativePathIndex + "relativePath=".length());
            int amp = value.indexOf('&');
            if (amp >= 0) {
                value = value.substring(0, amp);
            }
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return clean;
    }

    private String replaceImageExtension(String filename, String extension) {
        String clean = filename == null || filename.isBlank() ? "image" : filename;
        int dot = clean.lastIndexOf('.');
        return (dot > 0 ? clean.substring(0, dot) : clean) + extension;
    }

    private void addMermaidChartPreview(XWPFDocument document, String source) {
        if (source == null || source.isBlank()) {
            return;
        }

        try {
            Optional<byte[]> rendered = renderMermaidPng(source);
            byte[] png = rendered.orElseGet(() -> {
                try {
                    return buildMermaidPreviewPng(source);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            try (InputStream in = new ByteArrayInputStream(png)) {
                int[] size = scaledPictureSize(png);
                run.addPicture(in, XWPFDocument.PICTURE_TYPE_PNG, "chart.png", size[0], size[1]);
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

    private void addNativeChartFallback(XWPFDocument document, String source) {
        List<String> labels = extractMermaidPreviewLabels(source);
        if (labels.isEmpty()) {
            addParagraph(document, "图表结构", true, 13, "");
            for (String line : source.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank() && !trimmed.startsWith("```")) {
                    addParagraph(document, trimmed, false, 10, "");
                }
            }
            return;
        }

        addParagraph(document, detectMermaidPreviewType(source), true, 13, "");
        int rows = Math.max(1, labels.size() * 2 - 1);
        XWPFTable table = document.createTable(rows, 1);
        table.setWidth("100%");
        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            XWPFTableCell cell = table.getRow(rowIndex).getCell(0);
            cell.removeParagraph(0);
            XWPFParagraph paragraph = cell.addParagraph();
            paragraph.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun run = paragraph.createRun();
            if (rowIndex % 2 == 0) {
                run.setText(labels.get(rowIndex / 2));
                run.setFontSize(11);
                run.setBold(true);
            } else {
                run.setText("↓");
                run.setFontSize(12);
            }
        }
    }

    private Optional<byte[]> renderMermaidPng(String source) {
        Optional<Path> cli = findMermaidCli();
        if (cli.isEmpty()) {
            log.debug("[create docx file] Mermaid CLI not found; using chart preview fallback");
            return Optional.empty();
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("relic-mermaid-");
            Path input = tempDir.resolve("chart.mmd");
            Path output = tempDir.resolve("chart.png");
            Files.writeString(input, source, StandardCharsets.UTF_8);

            List<String> command = List.of(
                    cli.get().toString(),
                    "-i", input.toString(),
                    "-o", output.toString(),
                    "-b", "transparent",
                    "-t", "default"
            );
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            findBrowserExecutable().ifPresent(path ->
                    builder.environment().putIfAbsent("PUPPETEER_EXECUTABLE_PATH", path.toString()));
            Process process = builder.start();
            boolean finished = process.waitFor(Math.max(1000L, mermaidCliTimeoutMs), TimeUnit.MILLISECONDS);
            String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!finished) {
                process.destroyForcibly();
                log.warn("[create docx file] Mermaid CLI timed out after {}ms", mermaidCliTimeoutMs);
                return Optional.empty();
            }
            if (process.exitValue() != 0 || !Files.exists(output) || Files.size(output) == 0) {
                log.warn("[create docx file] Mermaid CLI failed with exit {}: {}", process.exitValue(), abbreviate(processOutput, 300));
                return Optional.empty();
            }

            byte[] png = Files.readAllBytes(output);
            if (ImageIO.read(new ByteArrayInputStream(png)) == null) {
                log.warn("[create docx file] Mermaid CLI output is not a readable PNG");
                return Optional.empty();
            }
            return Optional.of(png);
        } catch (Exception e) {
            log.warn("[create docx file] Mermaid CLI render failed: {}", e.getMessage());
            return Optional.empty();
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private Optional<Path> findMermaidCli() {
        if (mermaidCliPath != null && !mermaidCliPath.isBlank()) {
            Path configured = Path.of(mermaidCliPath).toAbsolutePath().normalize();
            if (Files.isRegularFile(configured)) {
                return Optional.of(configured);
            }
            log.warn("[create docx file] configured Mermaid CLI not found: {}", configured);
        }

        List<Path> candidates = new ArrayList<>();
        Path userDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        candidates.add(userDir.resolve("relic-face/node_modules/.bin/mmdc.cmd"));
        candidates.add(userDir.resolve("relic-face/node_modules/.bin/mmdc"));
        Path parent = userDir.getParent();
        if (parent != null) {
            candidates.add(parent.resolve("relic-face/node_modules/.bin/mmdc.cmd"));
            candidates.add(parent.resolve("relic-face/node_modules/.bin/mmdc"));
        }
        candidates.add(Path.of("mmdc.cmd"));
        candidates.add(Path.of("mmdc"));

        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst();
    }

    private Optional<Path> findBrowserExecutable() {
        List<Path> candidates = List.of(
                Path.of(System.getenv().getOrDefault("PUPPETEER_EXECUTABLE_PATH", "")),
                Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "Google/Chrome/Application/chrome.exe"),
                Path.of(System.getenv().getOrDefault("PROGRAMFILES", ""), "Google/Chrome/Application/chrome.exe"),
                Path.of(System.getenv().getOrDefault("PROGRAMFILES(X86)", ""), "Google/Chrome/Application/chrome.exe"),
                Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "Microsoft/Edge/Application/msedge.exe"),
                Path.of(System.getenv().getOrDefault("PROGRAMFILES", ""), "Microsoft/Edge/Application/msedge.exe"),
                Path.of(System.getenv().getOrDefault("PROGRAMFILES(X86)", ""), "Microsoft/Edge/Application/msedge.exe")
        );
        return candidates.stream()
                .filter(path -> !path.toString().isBlank())
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst();
    }

    private int[] scaledPictureSize(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return new int[]{DOCX_CHART_MAX_WIDTH_EMU, Units.toEMU(300)};
        }
        double scale = Math.min(
                (double) DOCX_CHART_MAX_WIDTH_EMU / Units.toEMU(image.getWidth()),
                (double) DOCX_CHART_MAX_HEIGHT_EMU / Units.toEMU(image.getHeight())
        );
        scale = Math.min(1.0d, Math.max(0.1d, scale));
        return new int[]{
                Math.max(Units.toEMU(120), (int) Math.round(Units.toEMU(image.getWidth()) * scale)),
                Math.max(Units.toEMU(80), (int) Math.round(Units.toEMU(image.getHeight()) * scale))
        };
    }

    private void cleanupTempDir(Path tempDir) {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup only.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup only.
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

            g.setFont(chartFontForText(type, Font.BOLD, 30));
            g.setColor(new Color(15, 23, 42));
            g.drawString(type, 44, 60);

            if (labels.isEmpty()) {
                String firstLine = source.lines().findFirst().orElse("Mermaid chart").trim();
                g.setFont(chartFontForText(firstLine, Font.PLAIN, 22));
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

            String label = abbreviate(visible.get(i), 30);
            g.setFont(chartFontForText(label, Font.PLAIN, 22));
            g.setColor(new Color(30, 41, 59));
            drawCenteredText(g, label, x, y, boxWidth, boxHeight);

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

        for (int i = 0; i < Math.min(labels.size(), 8); i++) {
            int rowY = 112 + i * 30;
            String label = abbreviate(labels.get(i), 34);
            g.setColor(palette[i % palette.length]);
            g.fillRoundRect(285, rowY - 16, 20, 20, 5, 5);
            g.setColor(new Color(30, 41, 59));
            g.setFont(chartFontForText(label, Font.PLAIN, 20));
            g.drawString(label, 316, rowY);
        }
    }

    private void drawCenteredText(Graphics2D g, String text, int x, int y, int width, int height) {
        FontMetrics metrics = g.getFontMetrics();
        int textX = x + Math.max(0, (width - metrics.stringWidth(text)) / 2);
        int textY = y + ((height - metrics.getHeight()) / 2) + metrics.getAscent();
        g.drawString(text, textX, textY);
    }

    private Font chartFont(int style, int size) {
        return chartFontForText("", style, size);
    }

    private Font chartFontForText(String text, int style, int size) {
        Set<String> families = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String candidate : DOCX_CHART_FONT_CANDIDATES) {
            if (families.contains(candidate)) {
                Font font = new Font(candidate, style, size);
                if (canDisplay(font, text)) {
                    return font;
                }
            }
        }
        String sample = text == null || text.isBlank() ? "中文English123" : text;
        for (String family : families) {
            Font font = new Font(family, style, size);
            if (canDisplay(font, sample)) {
                return font;
            }
        }
        Font fallback = new Font(Font.SANS_SERIF, style, size);
        return canDisplay(fallback, text) ? fallback : new Font(Font.DIALOG, style, size);
    }

    private boolean canDisplay(Font font, String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        return font.canDisplayUpTo(text) < 0;
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
        if (lower.startsWith("timeline")) {
            return extractTimelineLabels(source);
        }
        return source.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("%%"))
                .filter(line -> !line.equalsIgnoreCase("mindmap"))
                .filter(line -> !line.equalsIgnoreCase("timeline"))
                .filter(line -> !line.equalsIgnoreCase("gantt"))
                .filter(line -> !line.toLowerCase(Locale.ROOT).startsWith("title "))
                .filter(line -> !line.toLowerCase(Locale.ROOT).startsWith("section "))
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

    private List<String> extractTimelineLabels(String source) {
        List<String> labels = new ArrayList<>();
        for (String rawLine : source.split("\\R")) {
            String line = rawLine.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (line.isBlank()
                    || line.startsWith("%%")
                    || lower.equals("timeline")
                    || lower.startsWith("title ")) {
                continue;
            }
            if (lower.startsWith("section ")) {
                String section = line.substring("section".length()).trim();
                if (!section.isBlank() && !labels.contains(section)) {
                    labels.add(section);
                }
            } else if (line.contains(":")) {
                String[] parts = line.split(":", 2);
                String left = parts[0].trim();
                String right = parts.length > 1 ? parts[1].trim() : "";
                String label = right.isBlank() ? left : left + "：" + right;
                if (!label.isBlank() && !labels.contains(label)) {
                    labels.add(label);
                }
            } else if (!labels.contains(line)) {
                labels.add(line);
            }
            if (labels.size() >= 12) {
                break;
            }
        }
        return labels;
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
                .replaceAll("^#{1,6}\\s*", "")
                .replaceAll("\\s+#{1,6}$", "")
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
        String trimmed = sanitizeMermaidSource(source);
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
            if (!labeledIds.contains(id) && !isMermaidColorLiteral(source, bareMatcher.start(1), bareMatcher.end(1))) {
                bareIds.add(id);
            }
        }

        if (bareIds.isEmpty()) {
            return "";
        }

        return CHART_VALIDATION_ERROR_MARKER + " Mermaid nodes are missing display labels: " + String.join(", ", bareIds)
                + ". Call the chart tool again with complete Mermaid syntax, and give each placeholder id a clear user-facing label, for example P1[actual meaning]. Do not show raw ids such as P1, D1 or E1 to the user.";
    }

    private boolean isMermaidColorLiteral(String source, int start, int end) {
        if (source == null || start <= 0 || end > source.length()) {
            return false;
        }
        String token = source.substring(start, end);
        if (!token.matches("(?i)[0-9a-f]{6}|[0-9a-f]{3}")) {
            return false;
        }
        return source.charAt(start - 1) == '#';
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
        return sanitizeMermaidLabel(text).replace("\"", "\\\\\"").trim();
    }

    private String sanitizeMermaidSource(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        String normalized = source
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("(?i)<br\\s*/?>", " ");
        boolean indentationSensitive = normalized.stripLeading().toLowerCase(Locale.ROOT).startsWith("mindmap");
        StringBuilder cleaned = new StringBuilder();
        for (String line : normalized.split("\\R", -1)) {
            cleaned.append(indentationSensitive ? sanitizeIndentationSensitiveMermaidLine(line) : sanitizeMermaidLine(line)).append('\n');
        }
        return indentationSensitive ? cleaned.toString().stripTrailing() : cleaned.toString().trim();
    }

    private String sanitizeMermaidLine(String line) {
        if (line == null || line.isBlank()) {
            return line == null ? "" : line;
        }
        return line.replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String sanitizeIndentationSensitiveMermaidLine(String line) {
        if (line == null || line.isBlank()) {
            return line == null ? "" : line;
        }
        return line.replaceAll("(?i)<br\\s*/?>", " ").stripTrailing();
    }

    private String sanitizeMermaidLabel(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
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
            Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
            Path basePath = workspace;
            String overrideDir = currentWorkingDirectory();
            if (overrideDir != null && !overrideDir.isBlank()) {
                try {
                    basePath = Path.of(overrideDir).toAbsolutePath().normalize();
                } catch (InvalidPathException e) {
                    log.warn("[ToolExecutor] invalid workingDirectory '{}', falling back to workspace: {}", overrideDir, e.getMessage());
                    basePath = workspace;
                }
            }

            Path dirPath;
            if (subPath == null || subPath.isEmpty()) {
                dirPath = basePath;
            } else {
                Path candidate;
                try {
                    candidate = Path.of(subPath);
                } catch (InvalidPathException e) {
                    return "Invalid path: " + subPath;
                }
                dirPath = candidate.isAbsolute() ? candidate.normalize() : basePath.resolve(subPath).normalize();
            }

            if (!Files.exists(dirPath)) {
                return "Directory does not exist: " + (subPath == null || subPath.isEmpty() ? dirPath : subPath);
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
        if (filename == null || filename.isBlank()) {
            throw new SecurityException("File path must not be empty");
        }
        Path candidate;
        try {
            candidate = Path.of(filename);
        } catch (InvalidPathException e) {
            throw new SecurityException("Invalid path: " + filename);
        }

        String overrideDir = currentWorkingDirectory();
        boolean overrideActive = overrideDir != null && !overrideDir.isBlank();

        // 当用户已选工作目录时：所有写入一律落在工作目录内，绝对路径只保留 basename。
        if (overrideActive) {
            try {
                Path base = Path.of(overrideDir).toAbsolutePath().normalize();
                String effectiveName = candidate.isAbsolute()
                        ? candidate.getFileName().toString()
                        : filename;
                Path resolved = base.resolve(effectiveName).normalize();
                log.info("[ToolExecutor] write path resolved via workingDirectory: input='{}', base='{}', result='{}'",
                        filename, base, resolved);
                return resolved;
            } catch (InvalidPathException e) {
                log.warn("[ToolExecutor] invalid workingDirectory '{}', falling back to workspace: {}", overrideDir, e.getMessage());
            }
        }

        if (candidate.isAbsolute()) {
            Path normalized = candidate.normalize();
            log.info("[ToolExecutor] write path is absolute (no override): '{}'", normalized);
            return normalized;
        }
        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path resolved = workspace.resolve(filename).normalize();
        log.info("[ToolExecutor] write path resolved via workspace: input='{}', result='{}'", filename, resolved);
        return resolved;
    }

    /**
     * 将文件备份到默认 workspace，并返回 workspace 相对路径（用于注册表/下载链接）。
     * 若文件已经位于 workspace 内，则返回其原始相对路径，无需复制。
     */
    private String backupToWorkspaceIfOutside(Path filePath) {
        try {
            Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
            if (filePath.startsWith(workspace)) {
                return workspace.relativize(filePath).toString().replace('\\', '/');
            }
            Path backupDir = workspace.resolve("generated").normalize();
            if (!backupDir.startsWith(workspace)) {
                return filePath.toString().replace('\\', '/');
            }
            Files.createDirectories(backupDir);
            Path backupPath = backupDir.resolve(filePath.getFileName().toString());
            Files.copy(filePath, backupPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return workspace.relativize(backupPath).toString().replace('\\', '/');
        } catch (Exception e) {
            log.warn("[ToolExecutor] backup to workspace failed for {}: {}", filePath, e.getMessage());
            return filePath.toString().replace('\\', '/');
        }
    }

    private Path resolveReadPath(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new SecurityException("File path must not be empty");
        }

        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path basePath = workspace;
        String overrideDir = currentWorkingDirectory();
        if (overrideDir != null && !overrideDir.isBlank()) {
            try {
                basePath = Path.of(overrideDir).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                log.warn("[ToolExecutor] invalid workingDirectory '{}', falling back to workspace: {}", overrideDir, e.getMessage());
                basePath = workspace;
            }
        }
        Path candidate;
        try {
            candidate = Path.of(filename);
        } catch (InvalidPathException e) {
            throw new SecurityException("Invalid path: " + filename);
        }

        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : basePath.resolve(filename).normalize();

        if (!allowOutsideRead && !resolved.startsWith(workspace) && !resolved.startsWith(basePath)) {
            throw new SecurityException("Path is outside workspace: " + filename);
        }

        return resolved;
    }

    private record ChartPoint(String label, double value) {}
    private record MarkdownHeading(int level, String text) {}
    private record ImagePayload(byte[] bytes, int pictureType, String filename) {}
}
