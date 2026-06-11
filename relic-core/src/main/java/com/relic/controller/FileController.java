package com.relic.controller;

import com.relic.resource.WorkspaceResourceService;
import com.relic.service.GeneratedFileRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/files")
public class FileController {

    @Autowired
    private GeneratedFileRegistryService generatedFileRegistryService;

    @Autowired
    private WorkspaceResourceService workspaceResourceService;

    @Value("${relic.workspace.path:#{systemProperties['user.home'] + '/.openclaw/workspace'}}")
    private String workspacePath;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        return workspaceResourceService.toResponseMap(workspaceResourceService.saveUploadedFile(file));
    }

    @GetMapping("/list")
    public Map<String, Object> listFiles() throws IOException {
        return Map.of("items", workspaceResourceService.listUploadedResources());
    }

    @GetMapping("/generated/list")
    public Map<String, Object> listGeneratedFiles() {
        return Map.of("items", generatedFileRegistryService.listGeneratedFiles());
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> downloadFile(@RequestParam("relativePath") String relativePath) throws IOException {
        if (!StringUtils.hasText(relativePath)) {
            throw new IllegalArgumentException("relativePath 不能为空");
        }

        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path candidate = Path.of(relativePath.replace('/', java.io.File.separatorChar));
        Path target = candidate.isAbsolute() ? candidate.normalize() : workspace.resolve(relativePath).normalize();

        if (!target.isAbsolute()) {
            throw new SecurityException("非法下载路径");
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在: " + relativePath);
        }

        Resource resource = new UrlResource(target.toUri());
        String filename = target.getFileName().toString();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        String asciiFallback = filename.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "");
        if (asciiFallback.isBlank()) {
            asciiFallback = "download";
        }
        String contentDisposition = "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encodedFilename;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header("Content-Transfer-Encoding", "binary")
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }

    @DeleteMapping
    public Map<String, Object> deleteFile(@RequestParam("relativePath") String relativePath) throws IOException {
        return workspaceResourceService.deleteUploadedResource(relativePath);
    }

    @PostMapping("/delete")
    public Map<String, Object> deleteFileByBody(@RequestBody Map<String, String> request) throws IOException {
        return workspaceResourceService.deleteUploadedResource(request.get("relativePath"));
    }

    @PostMapping("/generated/delete")
    public Map<String, Object> deleteGeneratedFileByBody(@RequestBody Map<String, String> request) throws IOException {
        return deleteWorkspaceFileByRelativePath(request.get("relativePath"));
    }

    private Map<String, Object> deleteWorkspaceFileByRelativePath(String relativePath) throws IOException {
        if (!StringUtils.hasText(relativePath)) {
            throw new IllegalArgumentException("relativePath 不能为空");
        }

        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path target = workspace.resolve(relativePath).normalize();

        if (!target.startsWith(workspace)) {
            throw new SecurityException("非法删除路径");
        }

        if (!Files.exists(target)) {
            generatedFileRegistryService.removeGeneratedFile(relativePath);
            return Map.of("ok", true, "deleted", false, "message", "文件不存在");
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("仅支持删除文件");
        }

        Files.delete(target);
        generatedFileRegistryService.removeGeneratedFile(relativePath);
        cleanupEmptyDirectories(target.getParent(), workspace);

        log.info("删除 AI 生成文件成功: {}", relativePath);
        return Map.of("ok", true, "deleted", true);
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

}
