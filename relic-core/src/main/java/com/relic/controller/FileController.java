package com.relic.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/files")
public class FileController {

    @Value("${relic.workspace.path:#{systemProperties['user.home'] + '/.openclaw/workspace'}}")
    private String workspacePath;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }

        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        if (originalFilename.isBlank()) {
            originalFilename = "upload.bin";
        }

        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path uploadDir = workspace.resolve("uploads").resolve(LocalDate.now().toString()).normalize();
        if (!uploadDir.startsWith(workspace)) {
            throw new SecurityException("非法上传路径");
        }
        Files.createDirectories(uploadDir);

        String storedName = buildStoredName(originalFilename);
        Path storedPath = uploadDir.resolve(storedName).normalize();
        if (!storedPath.startsWith(workspace)) {
            throw new SecurityException("非法文件路径");
        }

        try (var in = file.getInputStream()) {
            Files.copy(in, storedPath, StandardCopyOption.REPLACE_EXISTING);
        }

        String relativePath = workspace.relativize(storedPath).toString().replace('\\', '/');
        String mimeType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();

        log.info("上传文件成功: {} -> {}", originalFilename, relativePath);

        return Map.of(
                "filename", originalFilename,
                "storedName", storedName,
                "relativePath", relativePath,
                "mimeType", mimeType,
                "size", file.getSize()
        );
    }

    private String buildStoredName(String originalFilename) {
        int dot = originalFilename.lastIndexOf('.');
        String ext = dot > 0 && dot < originalFilename.length() - 1
                ? originalFilename.substring(dot)
                : "";
        return UUID.randomUUID().toString().replace("-", "") + ext;
    }

    private String sanitizeFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        String normalized = Normalizer.normalize(filename, Normalizer.Form.NFKC);
        String justName = Path.of(normalized).getFileName().toString();
        return justName.replaceAll("[^a-zA-Z0-9._\\-()\u4e00-\u9fa5]", "_");
    }
}
