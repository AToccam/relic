package com.relic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class ChatHistoryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Value("${relic.workspace.path:#{systemProperties['user.home'] + '/.openclaw/workspace'}}")
    private String workspacePath;

    @Value("${relic.chat.auto-title.enabled:true}")
    private boolean autoTitleEnabled;

    @Value("${relic.chat.auto-title.fallback-max-length:18}")
    private int fallbackTitleMaxLength;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OllamaLocalService ollamaLocalService;

    public ChatHistoryService(ObjectProvider<OllamaLocalService> ollamaLocalProvider) {
        this.ollamaLocalService = ollamaLocalProvider.getIfAvailable();
    }

    public String normalizeConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        String normalized = conversationId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return normalized.isBlank() ? UUID.randomUUID().toString().replace("-", "") : normalized;
    }

    public synchronized void appendMessage(String conversationId, String role, Object content) {
        String safeId = normalizeConversationId(conversationId);
        try {
            Map<String, Object> doc = readConversationDoc(safeId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) doc.computeIfAbsent("messages", k -> new ArrayList<>());

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", UUID.randomUUID().toString().replace("-", ""));
            message.put("role", role);
            message.put("content", content == null ? "" : content);
            message.put("createdAt", Instant.now().toString());
            messages.add(message);

            if (shouldAutoGenerateTitle(role, doc)) {
                String firstParagraph = extractFirstUserParagraph(messages);
                if (StringUtils.hasText(firstParagraph)) {
                    String generatedTitle = generateTitle(firstParagraph);
                    if (StringUtils.hasText(generatedTitle)) {
                        doc.put("title", generatedTitle);
                    }
                }
            }

            doc.put("conversationId", safeId);
            doc.put("updatedAt", Instant.now().toString());
            writeConversationDoc(safeId, doc);
        } catch (Exception e) {
            log.warn("保存聊天记录失败: conversationId={}, error={}", safeId, e.getMessage());
        }
    }

    public synchronized List<Map<String, Object>> getHistory(String conversationId) {
        String safeId = normalizeConversationId(conversationId);
        try {
            Map<String, Object> doc = readConversationDoc(safeId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) doc.getOrDefault("messages", List.of());
            return new ArrayList<>(messages);
        } catch (Exception e) {
            log.warn("读取聊天记录失败: conversationId={}, error={}", safeId, e.getMessage());
            return List.of();
        }
    }

    public synchronized List<Map<String, Object>> listConversations() {
        Path dir = getConversationDir();
        if (!Files.exists(dir)) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            Map<String, Object> doc = objectMapper.readValue(Files.readString(path), MAP_TYPE);
                            String conversationId = String.valueOf(doc.getOrDefault("conversationId", stripJsonSuffix(path.getFileName().toString())));
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> messages = (List<Map<String, Object>>) doc.getOrDefault("messages", List.of());

                            String lastPreview = "";
                            for (int i = messages.size() - 1; i >= 0; i--) {
                                Object content = messages.get(i).get("content");
                                String text = toPreviewText(content);
                                if (StringUtils.hasText(text)) {
                                    lastPreview = text.length() > 80 ? text.substring(0, 80) + "..." : text;
                                    break;
                                }
                            }

                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("conversationId", conversationId);
                            item.put("title", String.valueOf(doc.getOrDefault("title", "")));
                            item.put("updatedAt", String.valueOf(doc.getOrDefault("updatedAt", "")));
                            item.put("messageCount", messages.size());
                            item.put("lastPreview", lastPreview);
                            result.add(item);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (IOException e) {
            log.warn("读取会话列表失败: {}", e.getMessage());
        }

        result.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.getOrDefault("updatedAt", ""))).reversed());
        return result;
    }

    public synchronized boolean renameConversation(String conversationId, String newName) {
        String safeId = normalizeConversationId(conversationId);
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        try {
            Map<String, Object> doc = readConversationDoc(safeId);
            doc.put("title", trimmed);
            doc.put("updatedAt", Instant.now().toString());
            writeConversationDoc(safeId, doc);
            return true;
        } catch (Exception e) {
            log.warn("重命名会话失败: conversationId={}, error={}", safeId, e.getMessage());
            return false;
        }
    }

    public synchronized boolean deleteConversation(String conversationId) {
        String safeId = normalizeConversationId(conversationId);
        Path dir = getConversationDir();
        Path file = dir.resolve(safeId + ".json").normalize();
        if (!file.startsWith(dir)) {
            return false;
        }

        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除会话失败: conversationId={}, error={}", safeId, e.getMessage());
            return false;
        }
    }

    private String toPreviewText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String str) {
            return str;
        }
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> rawPart)) {
                    continue;
                }
                Object type = rawPart.get("type");
                if ("text".equals(String.valueOf(type))) {
                    Object text = rawPart.get("text");
                    if (text != null) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(String.valueOf(text));
                    }
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    private boolean shouldAutoGenerateTitle(String role, Map<String, Object> doc) {
        if (!autoTitleEnabled) {
            return false;
        }
        // 在首轮 assistant 输出后命名，避免阻塞首个 token 返回
        if (!"assistant".equals(role)) {
            return false;
        }
        String currentTitle = Objects.toString(doc.getOrDefault("title", ""), "").trim();
        return currentTitle.isEmpty();
    }

    private String extractFirstUserParagraph(List<Map<String, Object>> messages) {
        for (Map<String, Object> message : messages) {
            if (!"user".equals(message.get("role"))) {
                continue;
            }
            String text = toPreviewText(message.get("content"));
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
            if (!StringUtils.hasText(normalized)) {
                continue;
            }

            String[] paragraphs = normalized.split("\\n\\s*\\n");
            String first = paragraphs.length > 0 ? paragraphs[0] : normalized;
            first = first.replaceAll("\\s+", " ").trim();
            if (StringUtils.hasText(first)) {
                return first;
            }
        }
        return "";
    }

    private String generateTitle(String firstParagraph) {
        String generated = "";
        if (ollamaLocalService != null) {
            generated = ollamaLocalService.summarizeConversationTitle(firstParagraph);
        }
        if (StringUtils.hasText(generated)) {
            return generated.trim();
        }
        return buildFallbackTitle(firstParagraph);
    }

    private String buildFallbackTitle(String firstParagraph) {
        String fallback = firstParagraph == null ? "" : firstParagraph.replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(fallback)) {
            return "";
        }
        int maxLen = Math.max(8, fallbackTitleMaxLength);
        if (fallback.length() <= maxLen) {
            return fallback;
        }
        return fallback.substring(0, maxLen).trim();
    }

    private Path getConversationDir() {
        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        return workspace.resolve("chat-history").normalize();
    }

    private Map<String, Object> readConversationDoc(String conversationId) throws IOException {
        Path dir = getConversationDir();
        Files.createDirectories(dir);
        Path file = dir.resolve(conversationId + ".json").normalize();
        if (!file.startsWith(dir)) {
            throw new SecurityException("非法会话路径");
        }

        if (!Files.exists(file)) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("conversationId", conversationId);
            doc.put("title", "");
            doc.put("updatedAt", Instant.now().toString());
            doc.put("messages", new ArrayList<>());
            return doc;
        }

        Map<String, Object> doc = objectMapper.readValue(Files.readString(file), MAP_TYPE);
        if (!doc.containsKey("title") || doc.get("title") == null) {
            doc.put("title", "");
        } else {
            doc.put("title", Objects.toString(doc.get("title"), ""));
        }
        return doc;
    }

    private void writeConversationDoc(String conversationId, Map<String, Object> doc) throws IOException {
        Path dir = getConversationDir();
        Files.createDirectories(dir);
        Path file = dir.resolve(conversationId + ".json").normalize();
        if (!file.startsWith(dir)) {
            throw new SecurityException("非法会话路径");
        }

        String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
        Files.writeString(file, prettyJson, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private String stripJsonSuffix(String filename) {
        return filename.endsWith(".json") ? filename.substring(0, filename.length() - 5) : filename;
    }
}