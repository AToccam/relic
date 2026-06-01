package com.relic.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class FileResourceRegistryService {

    private static final String REGISTRY_RELATIVE_PATH = ".relic/file-resources.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object lock = new Object();

    @Value("${relic.workspace.path:#{systemProperties['user.home'] + '/.openclaw/workspace'}}")
    private String workspacePath;

    public void register(FileResourceMetadata metadata) {
        if (metadata == null || !StringUtils.hasText(metadata.getRelativePath())) {
            return;
        }

        FileResourceMetadata normalized = normalize(metadata);
        synchronized (lock) {
            try {
                Map<String, FileResourceMetadata> registry = readRegistryUnsafe();
                registry.put(normalized.getRelativePath(), normalized);
                writeRegistryUnsafe(registry);
            } catch (Exception e) {
                log.warn("记录文件区资源元数据失败: {}", e.getMessage());
            }
        }
    }

    public Optional<FileResourceMetadata> find(String relativePath) {
        String normalizedPath = normalizeRelativePath(relativePath);
        if (!StringUtils.hasText(normalizedPath)) {
            return Optional.empty();
        }

        synchronized (lock) {
            try {
                return Optional.ofNullable(readRegistryUnsafe().get(normalizedPath));
            } catch (Exception e) {
                log.warn("读取文件区资源元数据失败: {}", e.getMessage());
                return Optional.empty();
            }
        }
    }

    public void remove(String relativePath) {
        String normalizedPath = normalizeRelativePath(relativePath);
        if (!StringUtils.hasText(normalizedPath)) {
            return;
        }

        synchronized (lock) {
            try {
                Map<String, FileResourceMetadata> registry = readRegistryUnsafe();
                if (registry.remove(normalizedPath) != null) {
                    writeRegistryUnsafe(registry);
                }
            } catch (Exception e) {
                log.warn("移除文件区资源元数据失败: {}", e.getMessage());
            }
        }
    }

    private FileResourceMetadata normalize(FileResourceMetadata metadata) {
        String now = Instant.now().toString();
        String relativePath = normalizeRelativePath(metadata.getRelativePath());
        String sourceType = StringUtils.hasText(metadata.getSourceType()) ? metadata.getSourceType() : "upload";

        metadata.setRelativePath(relativePath);
        metadata.setSourceType(sourceType);
        if (!StringUtils.hasText(metadata.getCreatedAt())) {
            metadata.setCreatedAt(now);
        }
        if (!StringUtils.hasText(metadata.getUpdatedAt())) {
            metadata.setUpdatedAt(now);
        }
        return metadata;
    }

    private Path getWorkspacePath() {
        return Path.of(workspacePath).toAbsolutePath().normalize();
    }

    private Path getRegistryPath() {
        Path workspace = getWorkspacePath();
        Path registryPath = workspace.resolve(REGISTRY_RELATIVE_PATH).normalize();
        if (!registryPath.startsWith(workspace)) {
            throw new SecurityException("非法资源注册表路径");
        }
        return registryPath;
    }

    @SuppressWarnings("unchecked")
    private Map<String, FileResourceMetadata> readRegistryUnsafe() throws IOException {
        Path registryPath = getRegistryPath();
        if (!Files.exists(registryPath)) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> root = objectMapper.readValue(registryPath.toFile(), Map.class);
        Object resourcesObj = root.get("resources");
        if (!(resourcesObj instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }

        Map<String, FileResourceMetadata> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = normalizeRelativePath(entry.getKey() == null ? "" : entry.getKey().toString());
            if (!StringUtils.hasText(key) || !(entry.getValue() instanceof Map<?, ?> value)) {
                continue;
            }

            FileResourceMetadata metadata = objectMapper.convertValue(value, FileResourceMetadata.class);
            metadata.setRelativePath(key);
            result.put(key, metadata);
        }
        return result;
    }

    private void writeRegistryUnsafe(Map<String, FileResourceMetadata> resources) throws IOException {
        Path registryPath = getRegistryPath();
        Files.createDirectories(registryPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(registryPath.toFile(), Map.of("resources", resources));
    }

    private String normalizeRelativePath(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            return "";
        }
        String normalized = relativePath.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
