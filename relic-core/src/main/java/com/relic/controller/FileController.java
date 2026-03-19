package com.relic.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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

        String storedName = buildStoredName(uploadDir, originalFilename);
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

    @GetMapping("/list")
    public Map<String, Object> listFiles() throws IOException {
        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path uploadRoot = workspace.resolve("uploads").normalize();

        if (!uploadRoot.startsWith(workspace)) {
            throw new SecurityException("非法文件目录");
        }

        if (!Files.exists(uploadRoot)) {
            return Map.of("items", List.of());
        }

        List<Map<String, Object>> files = new ArrayList<>();
        try (var stream = Files.walk(uploadRoot)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String relativePath = workspace.relativize(path).toString().replace('\\', '/');
                    String mimeType = Files.probeContentType(path);
                    if (!StringUtils.hasText(mimeType)) {
                        mimeType = "application/octet-stream";
                    }

                    Map<String, Object> item = Map.of(
                            "filename", path.getFileName().toString(),
                            "relativePath", relativePath,
                            "mimeType", mimeType,
                            "size", Files.size(path),
                            "updatedAt", Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()).toString()
                    );
                    files.add(item);
                } catch (Exception ignored) {
                }
            });
        }

        files.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.getOrDefault("updatedAt", ""))).reversed());
        return Map.of("items", files);
    }

    @DeleteMapping
    public Map<String, Object> deleteFile(@RequestParam("relativePath") String relativePath) throws IOException {
        return deleteUploadedFileByRelativePath(relativePath);
    }

    @PostMapping("/delete")
    public Map<String, Object> deleteFileByBody(@RequestBody Map<String, String> request) throws IOException {
        return deleteUploadedFileByRelativePath(request.get("relativePath"));
    }

    private Map<String, Object> deleteUploadedFileByRelativePath(String relativePath) throws IOException {
        if (!StringUtils.hasText(relativePath)) {
            throw new IllegalArgumentException("relativePath 不能为空");
        }

        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path uploadRoot = workspace.resolve("uploads").normalize();
        Path target = workspace.resolve(relativePath).normalize();

        if (!target.startsWith(uploadRoot)) {
            throw new SecurityException("非法删除路径");
        }

        if (!Files.exists(target)) {
            return Map.of("ok", true, "deleted", false, "message", "文件不存在");
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("仅支持删除文件");
        }

        Files.delete(target);
        cleanupEmptyDirectories(target.getParent(), uploadRoot);

        log.info("删除上传文件成功: {}", relativePath);
        return Map.of("ok", true, "deleted", true);
    }

    private String buildStoredName(Path uploadDir, String originalFilename) {
        String base = originalFilename;
        int dot = originalFilename.lastIndexOf('.');
        String ext = dot > 0 && dot < originalFilename.length() - 1
                ? originalFilename.substring(dot)
                : "";
        if (!ext.isEmpty()) {
            base = originalFilename.substring(0, dot);
        }

        String candidate = originalFilename;
        int index = 1;
        while (Files.exists(uploadDir.resolve(candidate))) {
            candidate = base + "(" + index + ")" + ext;
            index++;
        }
        return candidate;
    }

    private void cleanupEmptyDirectories(Path dir, Path stopAt) {
        Path current = dir;
        while (current != null && current.startsWith(stopAt) && !current.equals(stopAt)) {
            try (var stream = Files.list(current)) {
                if (stream.findAny().isPresent()) {
                    return;
                }
            } catch (IOException e) {
                return;
            }

            try {
                Files.deleteIfExists(current);
            } catch (IOException e) {
                return;
            }
            current = current.getParent();
        }
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
