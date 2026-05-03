package com.relic.rag.ingest;

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
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 文档文本提取服务。
 *
 * <p>复用现有 read_file 的能力边界，支持 txt/md/pdf/doc/docx，并限制最大文件大小。
 */
@Slf4j
@Service
public class DocumentTextExtractor {

    @Value("${relic.workspace.path:#{systemProperties['user.home'] + '/.openclaw/workspace'}}")
    private String workspacePath;

    @Value("${relic.rag.ingest.max-supported-file-bytes:10485760}")
    private long maxSupportedFileBytes;

    /**
     * 基于工作区相对路径读取并提取文档文本。
     */
    public String extractBySourceId(String sourceId) throws IOException {
        Path filePath = resolveWorkspacePath(sourceId);
        if (!Files.exists(filePath)) {
            throw new IOException("文件不存在: " + sourceId);
        }
        if (!Files.isRegularFile(filePath)) {
            throw new IOException("文件不是普通文件: " + sourceId);
        }

        long size = Files.size(filePath);
        if (size > maxSupportedFileBytes) {
            throw new IOException("文件过大，超过限制: " + size + " bytes");
        }

        String text = extractContentByType(filePath, sourceId);
        if (text == null || text.isBlank()) {
            throw new IOException("文件未提取到有效文本: " + sourceId);
        }
        return text;
    }

    private Path resolveWorkspacePath(String sourceId) {
        if (!StringUtils.hasText(sourceId)) {
            throw new IllegalArgumentException("sourceId 不能为空");
        }
        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path resolved = workspace.resolve(sourceId).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new SecurityException("非法路径，超出工作区范围: " + sourceId);
        }
        return resolved;
    }

    private String extractContentByType(Path filePath, String sourceId) throws IOException {
        String lower = sourceId == null ? "" : sourceId.toLowerCase(Locale.ROOT);
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
            return new PDFTextStripper().getText(document);
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
}
