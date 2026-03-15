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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 工具执行器：负责执行 AI 发起的 tool_calls。
 * 支持的工具：web_search、create_text_file、read_file、list_files
 */
@Slf4j
@Service
public class ToolExecutor {

    private static final long MAX_SUPPORTED_FILE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_RETURN_CHARS = 100_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${relic.workspace.path:#{systemProperties['user.home'] + '/.openclaw/workspace'}}")
    private String workspacePath;

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
                case "web_search" -> webSearch((String) args.get("query"));
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

    // Web 搜索
    @SuppressWarnings("unchecked")
    private String webSearch(String query) {
        try {
            log.info("【Web搜索】关键词: {}", query);
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.duckduckgo.com/?q=" + encoded
                            + "&format=json&no_html=1&skip_disambig=1"))
                    .header("User-Agent", "RelicBot/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> data = objectMapper.readValue(response.body(), Map.class);

            StringBuilder sb = new StringBuilder();
            sb.append("搜索关键词: ").append(query).append("\n\n");

            String heading = (String) data.get("Heading");
            if (heading != null && !heading.isEmpty()) {
                sb.append("## ").append(heading).append("\n\n");
            }

            String abstractText = (String) data.get("Abstract");
            if (abstractText != null && !abstractText.isEmpty()) {
                sb.append(abstractText).append("\n");
                String abstractUrl = (String) data.get("AbstractURL");
                if (abstractUrl != null && !abstractUrl.isEmpty()) {
                    sb.append("来源: ").append(abstractUrl).append("\n");
                }
                sb.append("\n");
            }

            String answer = (String) data.get("Answer");
            if (answer != null && !answer.isEmpty()) {
                sb.append("回答: ").append(answer).append("\n\n");
            }

            String definition = (String) data.get("Definition");
            if (definition != null && !definition.isEmpty()) {
                sb.append("定义: ").append(definition).append("\n\n");
            }

            List<?> relatedTopics = (List<?>) data.get("RelatedTopics");
            if (relatedTopics != null && !relatedTopics.isEmpty()) {
                sb.append("相关信息:\n");
                int count = 0;
                for (Object item : relatedTopics) {
                    if (item instanceof Map<?, ?> topic && count < 8) {
                        String text = (String) ((Map<String, Object>) topic).get("Text");
                        String firstUrl = (String) ((Map<String, Object>) topic).get("FirstURL");
                        if (text != null && !text.isEmpty()) {
                            sb.append("- ").append(text);
                            if (firstUrl != null) sb.append(" (").append(firstUrl).append(")");
                            sb.append("\n");
                            count++;
                        }
                    }
                }
            }

            String resultText = sb.toString().trim();
            if (resultText.equals("搜索关键词: " + query)) {
                resultText = "未找到关于「" + query + "」的直接搜索结果。请根据你的已有知识来回答用户的问题。";
            }

            log.info("【Web搜索完成】结果长度: {}", resultText.length());
            return resultText;
        } catch (Exception e) {
            log.error("Web搜索失败", e);
            return "搜索失败: " + e.getMessage() + "。请根据你的已有知识来回答。";
        }
    }

    // 文件操作
    private String createTextFile(String filename, String content) {
        try {
            if (content != null && content.length() > 1_000_000) {
                return "文件内容过大，最大支持 1MB";
            }

            Path filePath = resolveAndValidatePath(filename);
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
            Path filePath = resolveAndValidatePath(filename);

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
                dirPath = resolveAndValidatePath(subPath);
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

    // 路径安全校验， 防止路径遍历攻击，确保路径不超出工作区范围
    private Path resolveAndValidatePath(String filename) {
        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path resolved = workspace.resolve(filename).normalize();

        if (!resolved.startsWith(workspace)) {
            throw new SecurityException("路径不允许超出工作区范围: " + filename);
        }

        return resolved;
    }
}
