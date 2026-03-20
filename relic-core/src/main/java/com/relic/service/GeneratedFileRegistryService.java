package com.relic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GeneratedFileRegistryService {

    private static final String REGISTRY_RELATIVE_PATH = ".relic/generated-files.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object lock = new Object();

    @Value("${relic.workspace.path:#{systemProperties['user.home'] + '/.openclaw/workspace'}}")
    private String workspacePath;

    public void registerGeneratedFile(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        if (!StringUtils.hasText(normalized)) {
            return;
        }

        synchronized (lock) {
            try {
                Map<String, String> registry = readRegistryUnsafe();
                registry.put(normalized, Instant.now().toString());
                writeRegistryUnsafe(registry);
            } catch (Exception e) {
                log.warn("记录 AI 生成文件失败: {}", e.getMessage());
            }
        }
    }

    public List<Map<String, Object>> listGeneratedFiles() {
        synchronized (lock) {
            Map<String, String> registry;
            try {
                registry = readRegistryUnsafe();
            } catch (Exception e) {
                log.warn("读取 AI 生成文件注册表失败: {}", e.getMessage());
                return List.of();
            }

            Path workspace = getWorkspacePath();
            List<Map<String, Object>> items = new ArrayList<>();
            List<String> staleKeys = new ArrayList<>();

            for (Map.Entry<String, String> entry : registry.entrySet()) {
                String relativePath = normalizeRelativePath(entry.getKey());
                if (!StringUtils.hasText(relativePath)) {
                    staleKeys.add(entry.getKey());
                    continue;
                }

                Path file = workspace.resolve(relativePath).normalize();
                if (!file.startsWith(workspace) || !Files.exists(file) || !Files.isRegularFile(file)) {
                    staleKeys.add(entry.getKey());
                    continue;
                }

                try {
                    String mimeType = Files.probeContentType(file);
                    if (!StringUtils.hasText(mimeType)) {
                        mimeType = "application/octet-stream";
                    }

                    String updatedAt = entry.getValue();
                    if (!StringUtils.hasText(updatedAt)) {
                        updatedAt = Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()).toString();
                    }

                    items.add(Map.of(
                            "filename", file.getFileName().toString(),
                            "relativePath", relativePath,
                            "mimeType", mimeType,
                            "size", Files.size(file),
                            "updatedAt", updatedAt
                    ));
                } catch (Exception ignored) {
                    staleKeys.add(entry.getKey());
                }
            }

            if (!staleKeys.isEmpty()) {
                for (String key : staleKeys) {
                    registry.remove(key);
                }
                try {
                    writeRegistryUnsafe(registry);
                } catch (Exception e) {
                    log.warn("清理 AI 生成文件注册表失败: {}", e.getMessage());
                }
            }

            items.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.getOrDefault("updatedAt", ""))).reversed());
            return items;
        }
    }

    public void removeGeneratedFile(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        if (!StringUtils.hasText(normalized)) {
            return;
        }

        synchronized (lock) {
            try {
                Map<String, String> registry = readRegistryUnsafe();
                if (registry.remove(normalized) != null) {
                    writeRegistryUnsafe(registry);
                }
            } catch (Exception e) {
                log.warn("移除 AI 生成文件注册记录失败: {}", e.getMessage());
            }
        }
    }

    private Path getWorkspacePath() {
        return Path.of(workspacePath).toAbsolutePath().normalize();
    }

    private Path getRegistryPath() {
        Path workspace = getWorkspacePath();
        Path registryPath = workspace.resolve(REGISTRY_RELATIVE_PATH).normalize();
        if (!registryPath.startsWith(workspace)) {
            throw new SecurityException("非法注册表路径");
        }
        return registryPath;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readRegistryUnsafe() throws IOException {
        Path registryPath = getRegistryPath();
        if (!Files.exists(registryPath)) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> root = objectMapper.readValue(registryPath.toFile(), Map.class);
        Object filesObj = root.get("files");
        if (!(filesObj instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toString();
            String value = entry.getValue() == null ? "" : entry.getValue().toString();
            if (StringUtils.hasText(key)) {
                result.put(normalizeRelativePath(key), value);
            }
        }
        return result;
    }

    private void writeRegistryUnsafe(Map<String, String> files) throws IOException {
        Path registryPath = getRegistryPath();
        Files.createDirectories(registryPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(registryPath.toFile(), Map.of("files", files));
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