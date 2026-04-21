package com.relic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
public class SkillService {

    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("(?m)^name\\s*:\\s*([a-zA-Z0-9._-]+)\\s*$");
    private static final Pattern CLAWHUB_SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object configLock = new Object();

    @Value("${relic.openclaw.gateway-path:}")
    private String configuredGatewayPath;

    @Value("${relic.openclaw.config-path:#{systemProperties['user.home'] + '/.openclaw/openclaw.json'}}")
    private String openclawConfigPath;

    @Value("${relic.workspace.path:#{systemProperties['user.home'] + '/.openclaw/workspace'}}")
    private String defaultWorkspacePath;

    public Map<String, Object> listSkills() {
        try {
            return readSkillsSnapshotStrict();
        } catch (Exception e) {
            log.warn("读取 Skills 状态失败: {}", e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("workspaceDir", getDefaultWorkspacePath().toString());
            fallback.put("managedSkillsDir", getManagedSkillsPath().toString());
            fallback.put("skills", List.of());
            fallback.put("error", e.getMessage());
            return fallback;
        }
    }

    public Map<String, Object> setSkillEnabled(String skillKey, boolean enabled) {
        String normalizedSkillKey = normalizeSkillKey(skillKey);
        if (!StringUtils.hasText(normalizedSkillKey)) {
            throw new IllegalArgumentException("skillKey 不能为空");
        }

        synchronized (configLock) {
            ObjectNode root = readConfigRoot();
            ObjectNode skills = ensureObjectNode(root, "skills");
            ObjectNode entries = ensureObjectNode(skills, "entries");
            ObjectNode entry = ensureObjectNode(entries, normalizedSkillKey);
            entry.put("enabled", enabled);
            writeConfigRoot(root);
        }

        Map<String, Object> snapshot = readSkillsSnapshotStrict();
        return Map.of(
                "ok", true,
                "message", enabled ? "Skill 已启用" : "Skill 已禁用",
                "snapshot", snapshot
        );
    }

    public Map<String, Object> importSkill(String source) {
        String normalizedSource = source == null ? "" : source.trim();
        if (!StringUtils.hasText(normalizedSource)) {
            throw new IllegalArgumentException("导入链接不能为空");
        }

        Map<String, Object> currentSnapshot = readSkillsSnapshotStrict();
        Path workspaceDir = resolveWorkspaceDir(currentSnapshot);

        List<String> importedSkills;
        if (looksLikeClawhubSource(normalizedSource)) {
            importedSkills = importFromClawhub(normalizedSource, workspaceDir);
        } else if (looksLikeGithubUrl(normalizedSource)) {
            importedSkills = importFromGithub(normalizedSource, workspaceDir);
        } else {
            throw new IllegalArgumentException("仅支持 ClawHub 链接/slug 或 GitHub 仓库链接");
        }

        if (!importedSkills.isEmpty()) {
            enableSkills(importedSkills);
        }

        Map<String, Object> snapshot = readSkillsSnapshotStrict();
        return Map.of(
                "ok", true,
                "message", "导入完成",
                "importedSkills", importedSkills,
                "snapshot", snapshot
        );
    }

    private Map<String, Object> readSkillsSnapshotStrict() {
        Path gatewayDir = resolveGatewayDir();
        CommandResult result = runCommand(
                List.of(resolveNpxExecutable(), "openclaw", "skills", "list", "--json"),
                gatewayDir,
                Duration.ofSeconds(90)
        );

        String jsonText = extractJsonPayload(result.output());
        try {
            Map<String, Object> payload = objectMapper.readValue(jsonText, new TypeReference<>() {
            });
            payload.putIfAbsent("skills", List.of());
            payload.putIfAbsent("workspaceDir", getDefaultWorkspacePath().toString());
            payload.putIfAbsent("managedSkillsDir", getManagedSkillsPath().toString());
            return payload;
        } catch (Exception e) {
            throw new IllegalStateException("解析 Skills 数据失败: " + e.getMessage(), e);
        }
    }

    private List<String> importFromClawhub(String source, Path workspaceDir) {
        String slug = resolveClawhubSlug(source);
        if (!StringUtils.hasText(slug)) {
            throw new IllegalArgumentException("无法从链接中解析 ClawHub skill slug");
        }

        Path gatewayDir = resolveGatewayDir();
        runCommand(
                List.of(
                        resolveNpxExecutable(),
                        "clawhub",
                        "--workdir",
                        workspaceDir.toString(),
                        "--no-input",
                        "install",
                        slug,
                        "--force"
                ),
                gatewayDir,
                Duration.ofMinutes(3)
        );

        return List.of(slug);
    }

    private List<String> importFromGithub(String source, Path workspaceDir) {
        GithubRef githubRef = parseGithubRef(source);
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("relic-skill-import-");

            runCommand(
                    List.of("git", "clone", "--depth", "1", githubRef.repoCloneUrl(), tempDir.toString()),
                    resolveGatewayDir(),
                    Duration.ofMinutes(3)
            );

            Path scanRoot = tempDir;
            if (StringUtils.hasText(githubRef.requestedSubPath())) {
                Path subPath = tempDir.resolve(githubRef.requestedSubPath()).normalize();
                if (subPath.startsWith(tempDir) && Files.isDirectory(subPath)) {
                    scanRoot = subPath;
                }
            }

            List<Path> skillDirs = discoverSkillDirectories(scanRoot);
            if (skillDirs.isEmpty()) {
                throw new IllegalArgumentException("仓库中未找到有效的 SKILL.md");
            }

            Path workspaceSkillsDir = workspaceDir.resolve("skills").normalize();
            if (!workspaceSkillsDir.startsWith(workspaceDir)) {
                throw new SecurityException("非法 skills 目录");
            }
            Files.createDirectories(workspaceSkillsDir);

            List<String> importedNames = new ArrayList<>();
            for (Path skillDir : skillDirs) {
                String skillName = resolveSkillName(skillDir);
                Path targetDir = workspaceSkillsDir.resolve(skillName).normalize();
                if (!targetDir.startsWith(workspaceSkillsDir)) {
                    throw new SecurityException("非法 skill 目标目录");
                }

                deleteDirectory(targetDir);
                copyDirectory(skillDir, targetDir);
                importedNames.add(skillName);
            }

            return importedNames;
        } catch (IOException e) {
            throw new IllegalStateException("导入 GitHub skill 失败: " + e.getMessage(), e);
        } finally {
            if (tempDir != null) {
                try {
                    deleteDirectory(tempDir);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private List<Path> discoverSkillDirectories(Path rootDir) throws IOException {
        Set<Path> result = new LinkedHashSet<>();

        if (Files.isRegularFile(rootDir.resolve("SKILL.md"))) {
            result.add(rootDir);
        }

        Path nestedSkills = rootDir.resolve("skills");
        if (Files.isDirectory(nestedSkills)) {
            try (Stream<Path> stream = Files.list(nestedSkills)) {
                stream.filter(Files::isDirectory)
                        .filter(p -> Files.isRegularFile(p.resolve("SKILL.md")))
                        .forEach(result::add);
            }
        }

        try (Stream<Path> stream = Files.list(rootDir)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("SKILL.md")))
                    .forEach(result::add);
        }

        return new ArrayList<>(result);
    }

    private String resolveSkillName(Path skillDir) throws IOException {
        Path skillFile = skillDir.resolve("SKILL.md");
        String content = Files.readString(skillFile, StandardCharsets.UTF_8);
        String frontmatter = extractFrontmatter(content);
        Matcher matcher = SKILL_NAME_PATTERN.matcher(frontmatter);
        if (matcher.find()) {
            return sanitizeSkillName(matcher.group(1));
        }
        return sanitizeSkillName(skillDir.getFileName().toString());
    }

    private String extractFrontmatter(String content) {
        String text = content == null ? "" : content;
        if (!text.startsWith("---")) {
            return "";
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return "";
        }
        return text.substring(3, end);
    }

    private void enableSkills(List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) {
            return;
        }

        synchronized (configLock) {
            ObjectNode root = readConfigRoot();
            ObjectNode skills = ensureObjectNode(root, "skills");
            ObjectNode entries = ensureObjectNode(skills, "entries");

            for (String name : skillNames) {
                String normalized = sanitizeSkillName(name);
                if (!StringUtils.hasText(normalized)) {
                    continue;
                }
                ObjectNode entry = ensureObjectNode(entries, normalized);
                entry.put("enabled", true);
            }

            writeConfigRoot(root);
        }
    }

    private ObjectNode readConfigRoot() {
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            throw new IllegalStateException("未找到 openclaw.json，请先在网关目录执行 npm run setup");
        }
        try {
            JsonNode root = objectMapper.readTree(configPath.toFile());
            if (!(root instanceof ObjectNode objectNode)) {
                throw new IllegalStateException("openclaw.json 格式错误");
            }
            return objectNode;
        } catch (IOException e) {
            throw new IllegalStateException("读取 openclaw.json 失败: " + e.getMessage(), e);
        }
    }

    private void writeConfigRoot(ObjectNode root) {
        Path configPath = getConfigPath();
        try {
            Files.createDirectories(configPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), root);
        } catch (IOException e) {
            throw new IllegalStateException("写入 openclaw.json 失败: " + e.getMessage(), e);
        }
    }

    private ObjectNode ensureObjectNode(ObjectNode parent, String key) {
        JsonNode existing = parent.get(key);
        if (existing instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode child = objectMapper.createObjectNode();
        parent.set(key, child);
        return child;
    }

    private Path resolveGatewayDir() {
        List<Path> candidates = new ArrayList<>();
        if (StringUtils.hasText(configuredGatewayPath)) {
            candidates.add(Path.of(configuredGatewayPath.trim()));
        }

        Path cwd = Path.of("").toAbsolutePath().normalize();
        candidates.add(cwd.resolve("relic-gateway"));
        candidates.add(cwd);

        if (cwd.getParent() != null) {
            candidates.add(cwd.getParent().resolve("relic-gateway"));
        }

        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized.resolve("package.json"))) {
                return normalized;
            }
        }

        throw new IllegalStateException("未找到 relic-gateway 目录，请检查 relic.openclaw.gateway-path 配置");
    }

    private Path resolveWorkspaceDir(Map<String, Object> snapshot) {
        Object workspaceObj = snapshot.get("workspaceDir");
        if (workspaceObj instanceof String workspaceDir && StringUtils.hasText(workspaceDir)) {
            Path path = Path.of(workspaceDir).toAbsolutePath().normalize();
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new IllegalStateException("无法创建 workspace 目录: " + e.getMessage(), e);
            }
            return path;
        }

        Path fallback = getDefaultWorkspacePath();
        try {
            Files.createDirectories(fallback);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 workspace 目录: " + e.getMessage(), e);
        }
        return fallback;
    }

    private Path getDefaultWorkspacePath() {
        return Path.of(defaultWorkspacePath).toAbsolutePath().normalize();
    }

    private Path getManagedSkillsPath() {
        return Path.of(System.getProperty("user.home"), ".openclaw", "skills").toAbsolutePath().normalize();
    }

    private Path getConfigPath() {
        return Path.of(openclawConfigPath).toAbsolutePath().normalize();
    }

    private String resolveNpxExecutable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "npx.cmd" : "npx";
    }

    private CommandResult runCommand(List<String> command, Path workingDir, Duration timeout) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDir.toFile());
        processBuilder.redirectErrorStream(true);

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new IllegalStateException("无法启动命令: " + String.join(" ", command), e);
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = Thread.startVirtualThread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(buffer);
            } catch (IOException ignored) {
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("命令执行超时: " + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("命令执行被中断", e);
        }

        try {
            reader.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IllegalStateException("命令执行失败: " + summarizeOutput(output));
        }

        return new CommandResult(exitCode, output);
    }

    private String summarizeOutput(String output) {
        String text = output == null ? "" : output.trim();
        if (text.length() <= 600) {
            return text;
        }
        return text.substring(text.length() - 600);
    }

    private String extractJsonPayload(String rawOutput) {
        String text = rawOutput == null ? "" : rawOutput.trim();
        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first < 0 || last <= first) {
            throw new IllegalStateException("命令输出中未找到有效 JSON");
        }
        return text.substring(first, last + 1);
    }

    private boolean looksLikeClawhubSource(String source) {
        if (CLAWHUB_SLUG_PATTERN.matcher(source).matches()) {
            return true;
        }
        try {
            URI uri = URI.create(source);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return normalizedHost.contains("clawhub.com") || normalizedHost.contains("clawhub.ai");
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveClawhubSlug(String source) {
        if (CLAWHUB_SLUG_PATTERN.matcher(source).matches()) {
            return source;
        }

        URI uri = URI.create(source);
        List<String> segments = Arrays.stream(uri.getPath().split("/"))
                .filter(StringUtils::hasText)
                .toList();

        if (segments.size() >= 2 && "skills".equalsIgnoreCase(segments.get(0))) {
            String slug = segments.get(1).trim().toLowerCase(Locale.ROOT);
            return CLAWHUB_SLUG_PATTERN.matcher(slug).matches() ? slug : "";
        }

        if (segments.size() == 1) {
            String slug = segments.get(0).trim().toLowerCase(Locale.ROOT);
            return CLAWHUB_SLUG_PATTERN.matcher(slug).matches() ? slug : "";
        }

        return "";
    }

    private boolean looksLikeGithubUrl(String source) {
        try {
            URI uri = URI.create(source);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            return host.toLowerCase(Locale.ROOT).contains("github.com");
        } catch (Exception e) {
            return false;
        }
    }

    private GithubRef parseGithubRef(String source) {
        URI uri;
        try {
            uri = URI.create(source.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("无效的 GitHub 链接");
        }

        List<String> segments = Arrays.stream(uri.getPath().split("/"))
                .filter(StringUtils::hasText)
                .toList();
        if (segments.size() < 2) {
            throw new IllegalArgumentException("GitHub 链接至少需要 owner/repo");
        }

        String owner = segments.get(0);
        String repo = segments.get(1).replaceAll("\\.git$", "");
        String repoCloneUrl = "https://github.com/" + owner + "/" + repo + ".git";

        String requestedSubPath = "";
        if (segments.size() >= 5 && "tree".equalsIgnoreCase(segments.get(2))) {
            requestedSubPath = String.join("/", segments.subList(4, segments.size()));
        }

        return new GithubRef(repoCloneUrl, requestedSubPath);
    }

    private String normalizeSkillKey(String skillKey) {
        return sanitizeSkillName(skillKey);
    }

    private String sanitizeSkillName(String input) {
        if (!StringUtils.hasText(input)) {
            return "";
        }
        return input.trim().replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private void copyDirectory(Path sourceDir, Path targetDir) throws IOException {
        Files.walk(sourceDir).forEach(source -> {
            try {
                Path relative = sourceDir.relativize(source);
                if (isGitMetadataPath(relative)) {
                    return;
                }

                Path target = targetDir.resolve(relative.toString()).normalize();
                if (!target.startsWith(targetDir)) {
                    throw new SecurityException("非法文件路径");
                }

                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private boolean isGitMetadataPath(Path relativePath) {
        if (relativePath.getNameCount() == 0) {
            return false;
        }
        return ".git".equalsIgnoreCase(relativePath.getName(0).toString());
    }

    private void deleteDirectory(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private record CommandResult(int exitCode, String output) {
    }

    private record GithubRef(String repoCloneUrl, String requestedSubPath) {
    }
}
