package com.relic.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 工具执行器：负责执行 AI 发起的 tool_calls。
 * 支持的工具：create_text_file、read_file、list_files
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

    @PostConstruct
    public void init() {
        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        if (!Files.exists(workspace)) {
            try {
                Files.createDirectories(workspace);
                log.info("【ToolExecutor】创建工作区目录: {}", workspace);
            } catch (IOException e) {
                log.warn("【ToolExecutor】无法创建工作区目录: {}", e.getMessage());
            }
        }
        log.info("【ToolExecutor】工作区路径: {}", workspace);
    }

    @SuppressWarnings("unchecked")
    public String execute(String toolName, String arguments) {
        try {
            Map<String, Object> args = objectMapper.readValue(arguments, Map.class);
            return switch (toolName) {
                case "create_text_file" -> createTextFile(
                        (String) args.get("filename"),
                        (String) args.get("content"));
                case "read_file" -> readFile((String) args.get("filename"));
                case "list_files" -> listFiles((String) args.getOrDefault("path", ""));
                default -> "未知工具: " + toolName;
            };
        } catch (Exception e) {
            log.error("工具执行失败: {} - {}", toolName, e.getMessage(), e);
            return "工具执行出错: " + e.getMessage();
        }
    }

    // 文件操作
    private String createTextFile(String filename, String content) {
        try {
            if (content != null && content.length() > 1_000_000) {
                return "文件内容过大，最大支持 1MB";
            }

            Path filePath = resolveAndValidateWritePath(filename);
            Files.createDirectories(filePath.getParent());

            boolean exists = Files.exists(filePath);
            Files.writeString(filePath, content == null ? "" : content, StandardCharsets.UTF_8);

            log.info("【创建文件】{} ({})", filePath, exists ? "已覆盖" : "新建");
            return (exists ? "文件已覆盖: " : "文件已创建: ") + filename;
        } catch (SecurityException e) {
            return "安全错误: " + e.getMessage();
        } catch (IOException e) {
            return "创建文件失败: " + e.getMessage();
        }
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
        String lower = filename == null ? "" : filename.toLowerCase();

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

    // 写入路径仍限制在工作区，防止越权写文件
    private Path resolveAndValidateWritePath(String filename) {
        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path resolved = workspace.resolve(filename).normalize();

        if (!resolved.startsWith(workspace)) {
            throw new SecurityException("路径不允许超出工作区范围: " + filename);
        }

        return resolved;
    }

    // 读取路径支持绝对路径；当 allowOutsideRead=true 时可读取工作区外文件
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
}
