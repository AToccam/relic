package com.relic.resource;

import com.relic.rag.ingest.DocumentIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceResourceService {

    private final FileResourceRegistryService registryService;
    private final ObjectProvider<DocumentIngestionService> documentIngestionServiceProvider;

    @Value("${relic.workspace.path:#{systemProperties['user.home'] + '/.openclaw/workspace'}}")
    private String workspacePath;

    @Value("${relic.rag.ingest.auto-index-on-upload:false}")
    private boolean autoIndexOnUpload;

    @Value("${relic.web-search.auto-index-on-import:true}")
    private boolean autoIndexOnWebImport;

    public SavedResource saveUploadedFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }

        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        if (originalFilename.isBlank()) {
            originalFilename = "upload.bin";
        }

        Path workspace = getWorkspacePath();
        Path uploadDir = workspace.resolve("uploads").resolve(LocalDate.now().toString()).normalize();
        ensureInsideWorkspace(uploadDir, workspace, "非法上传路径");
        Files.createDirectories(uploadDir);

        String storedName = buildStoredName(uploadDir, originalFilename);
        Path storedPath = uploadDir.resolve(storedName).normalize();
        ensureInsideWorkspace(storedPath, workspace, "非法文件路径");

        try (var in = file.getInputStream()) {
            Files.copy(in, storedPath, StandardCopyOption.REPLACE_EXISTING);
        }

        String relativePath = toRelativePath(workspace, storedPath);
        String mimeType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        long size = Files.size(storedPath);
        String now = Instant.now().toString();

        FileResourceMetadata metadata = FileResourceMetadata.builder()
                .filename(originalFilename)
                .relativePath(relativePath)
                .mimeType(mimeType)
                .size(size)
                .sourceType("upload")
                .title(originalFilename)
                .createdAt(now)
                .updatedAt(now)
                .build();
        registryService.register(metadata);

        boolean indexTriggered = false;
        if (autoIndexOnUpload) {
            indexTriggered = triggerAutoIndex(relativePath);
        }

        log.info("上传文件成功: {} -> {}", originalFilename, relativePath);
        return new SavedResource(metadata, indexTriggered);
    }

    public SavedResource saveWebResource(String title,
                                         String url,
                                         String snippet,
                                         String keyword,
                                         String content) throws IOException {
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("url 不能为空");
        }
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("网页正文为空，无法加入文件区");
        }

        Path workspace = getWorkspacePath();
        Path uploadDir = workspace.resolve("uploads").resolve(LocalDate.now().toString()).resolve("web").normalize();
        ensureInsideWorkspace(uploadDir, workspace, "非法网页资源目录");
        Files.createDirectories(uploadDir);

        String filename = buildWebFilename(title, url);
        String storedName = buildStoredName(uploadDir, filename);
        Path storedPath = uploadDir.resolve(storedName).normalize();
        ensureInsideWorkspace(storedPath, workspace, "非法网页资源路径");

        String markdown = buildWebMarkdown(title, url, snippet, keyword, content);
        Files.writeString(storedPath, markdown, StandardCharsets.UTF_8);

        String relativePath = toRelativePath(workspace, storedPath);
        long size = Files.size(storedPath);
        String now = Instant.now().toString();
        String displayTitle = StringUtils.hasText(title) ? title.trim() : storedName;

        FileResourceMetadata metadata = FileResourceMetadata.builder()
                .filename(storedName)
                .relativePath(relativePath)
                .mimeType("text/markdown")
                .size(size)
                .sourceType("web_search")
                .originUrl(url.trim())
                .title(displayTitle)
                .snippet(trimToEmpty(snippet))
                .keyword(trimToEmpty(keyword))
                .createdAt(now)
                .updatedAt(now)
                .build();
        registryService.register(metadata);

        boolean indexTriggered = false;
        if (autoIndexOnWebImport) {
            indexTriggered = triggerAutoIndex(relativePath);
        }
        log.info("网页资源已加入文件区: {} -> {}", url, relativePath);
        return new SavedResource(metadata, indexTriggered);
    }

    public List<Map<String, Object>> listUploadedResources() throws IOException {
        Path workspace = getWorkspacePath();
        Path uploadRoot = workspace.resolve("uploads").normalize();
        ensureInsideWorkspace(uploadRoot, workspace, "非法文件目录");

        if (!Files.exists(uploadRoot)) {
            return List.of();
        }

        List<Map<String, Object>> files = new ArrayList<>();
        try (var stream = Files.walk(uploadRoot)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String relativePath = toRelativePath(workspace, path);
                    FileResourceMetadata metadata = registryService.find(relativePath).orElse(null);
                    files.add(toListItem(path, relativePath, metadata));
                } catch (Exception ignored) {
                }
            });
        }

        files.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.getOrDefault("updatedAt", ""))).reversed());
        return files;
    }

    public Map<String, Object> deleteUploadedResource(String relativePath) throws IOException {
        if (!StringUtils.hasText(relativePath)) {
            throw new IllegalArgumentException("relativePath 不能为空");
        }

        Path workspace = getWorkspacePath();
        Path uploadRoot = workspace.resolve("uploads").normalize();
        Path target = workspace.resolve(relativePath).normalize();
        ensureInsideWorkspace(uploadRoot, workspace, "非法文件目录");

        if (!target.startsWith(uploadRoot)) {
            throw new SecurityException("非法删除路径");
        }

        if (!Files.exists(target)) {
            registryService.remove(relativePath);
            return Map.of("ok", true, "deleted", false, "message", "文件不存在");
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("仅支持删除文件");
        }

        Files.delete(target);
        registryService.remove(relativePath);
        cleanupEmptyDirectories(target.getParent(), uploadRoot);

        log.info("删除文件区资源成功: {}", relativePath);
        return Map.of("ok", true, "deleted", true);
    }

    public Map<String, Object> toResponseMap(SavedResource savedResource) {
        FileResourceMetadata metadata = savedResource.metadata();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filename", metadata.getFilename());
        response.put("storedName", Path.of(metadata.getRelativePath()).getFileName().toString());
        response.put("relativePath", metadata.getRelativePath());
        response.put("mimeType", metadata.getMimeType());
        response.put("size", metadata.getSize());
        response.put("sourceType", metadata.getSourceType());
        response.put("indexTriggered", savedResource.indexTriggered());
        putIfPresent(response, "originUrl", metadata.getOriginUrl());
        putIfPresent(response, "title", metadata.getTitle());
        putIfPresent(response, "snippet", metadata.getSnippet());
        putIfPresent(response, "keyword", metadata.getKeyword());
        return response;
    }

    private Map<String, Object> toListItem(Path path, String relativePath, FileResourceMetadata metadata) throws IOException {
        String mimeType = metadata != null && StringUtils.hasText(metadata.getMimeType())
                ? metadata.getMimeType()
                : Files.probeContentType(path);
        if (!StringUtils.hasText(mimeType)) {
            mimeType = "application/octet-stream";
        }

        String updatedAt = metadata != null && StringUtils.hasText(metadata.getUpdatedAt())
                ? metadata.getUpdatedAt()
                : Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()).toString();

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("filename", metadata != null && StringUtils.hasText(metadata.getFilename())
                ? metadata.getFilename()
                : path.getFileName().toString());
        item.put("relativePath", relativePath);
        item.put("mimeType", mimeType);
        item.put("size", Files.size(path));
        item.put("updatedAt", updatedAt);
        item.put("sourceType", metadata != null && StringUtils.hasText(metadata.getSourceType())
                ? metadata.getSourceType()
                : "upload");
        if (metadata != null) {
            putIfPresent(item, "originUrl", metadata.getOriginUrl());
            putIfPresent(item, "title", metadata.getTitle());
            putIfPresent(item, "snippet", metadata.getSnippet());
            putIfPresent(item, "keyword", metadata.getKeyword());
        }
        return item;
    }

    private boolean triggerAutoIndex(String relativePath) {
        DocumentIngestionService ingestionService = documentIngestionServiceProvider.getIfAvailable();
        if (ingestionService == null) {
            return false;
        }
        ingestionService.triggerAutoIndexAsync(relativePath);
        return true;
    }

    private Path getWorkspacePath() {
        return Path.of(workspacePath).toAbsolutePath().normalize();
    }

    private void ensureInsideWorkspace(Path target, Path workspace, String message) {
        if (!target.startsWith(workspace)) {
            throw new SecurityException(message);
        }
    }

    private String toRelativePath(Path workspace, Path path) {
        return workspace.relativize(path).toString().replace('\\', '/');
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

    private String buildWebFilename(String title, String url) {
        String base = sanitizeFilename(title);
        if (!StringUtils.hasText(base)) {
            try {
                String host = URI.create(url).getHost();
                base = sanitizeFilename(host);
            } catch (Exception ignored) {
                base = "web-resource";
            }
        }
        if (!StringUtils.hasText(base)) {
            base = "web-resource";
        }
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        return base.endsWith(".md") ? base : base + ".md";
    }

    private String buildWebMarkdown(String title, String url, String snippet, String keyword, String content) {
        String displayTitle = StringUtils.hasText(title) ? title.trim() : url.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(displayTitle).append("\n\n");
        sb.append("> 来源: ").append(url.trim()).append("\n");
        if (StringUtils.hasText(keyword)) {
            sb.append("> 搜索关键词: ").append(keyword.trim()).append("\n");
        }
        if (StringUtils.hasText(snippet)) {
            sb.append("> 搜索摘要: ").append(snippet.trim()).append("\n");
        }
        sb.append("> 抓取时间: ").append(Instant.now()).append("\n\n");
        sb.append("---\n\n");
        // 保存成 Markdown 文本，便于 read_file 与 RAG 索引统一读取。
        sb.append(content.trim()).append("\n");
        return sb.toString();
    }

    private String sanitizeFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        String normalized = Normalizer.normalize(filename, Normalizer.Form.NFKC);
        String justName = Path.of(normalized).getFileName().toString();
        return justName.replaceAll("[^a-zA-Z0-9._\\-()\u4e00-\u9fa5]", "_");
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

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
