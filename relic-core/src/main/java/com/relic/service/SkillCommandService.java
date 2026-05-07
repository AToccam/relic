package com.relic.service;

import com.relic.util.MessageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class SkillCommandService {

    private static final Pattern COMMAND_PATTERN = Pattern.compile("(?s)^/([a-zA-Z0-9._-]+)(?:\\s+(.*))?$");
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{1,80}$");
    private static final int MAX_SKILL_CHARS = 20_000;

    private final SkillService skillService;

    public SkillCommandService(SkillService skillService) {
        this.skillService = skillService;
    }

    public List<Map<String, Object>> rewriteForEnabledSkillCommand(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        int lastUserIndex = findLastUserMessageIndex(messages);
        if (lastUserIndex < 0) {
            return messages;
        }

        Map<String, Object> lastUserMessage = messages.get(lastUserIndex);
        String userText = MessageHelper.extractTextContent(lastUserMessage.get("content")).trim();
        Matcher matcher = COMMAND_PATTERN.matcher(userText);
        if (!matcher.matches()) {
            return messages;
        }

        String requestedSkill = matcher.group(1) == null ? "" : matcher.group(1).trim();
        if (!SKILL_NAME_PATTERN.matcher(requestedSkill).matches()) {
            return messages;
        }

        Map<String, Object> snapshot;
        try {
            snapshot = skillService.listSkills();
        } catch (Exception e) {
            log.warn("读取 Skills 快照失败，跳过命令解析: {}", e.getMessage());
            return messages;
        }

        String enabledSkillName = findEnabledSkillName(snapshot, requestedSkill);
        if (!StringUtils.hasText(enabledSkillName)) {
            return messages;
        }

        String skillMarkdown = readSkillMarkdown(snapshot, enabledSkillName);
        if (!StringUtils.hasText(skillMarkdown)) {
            log.warn("检测到 Skill 命令 /{}，但未找到 SKILL.md", enabledSkillName);
            return messages;
        }

        String commandArgs = matcher.group(2) == null ? "" : matcher.group(2).trim();
        String rewrittenText = buildRewrittenPrompt(enabledSkillName, skillMarkdown, commandArgs);

        List<Map<String, Object>> rewrittenMessages = new ArrayList<>(messages);
        rewrittenMessages.set(lastUserIndex, rewriteUserMessage(lastUserMessage, rewrittenText));
        log.info("Skill 命令已生效: /{}", enabledSkillName);
        return rewrittenMessages;
    }

    private int findLastUserMessageIndex(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object role = messages.get(i).get("role");
            if ("user".equals(role)) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private String findEnabledSkillName(Map<String, Object> snapshot, String requestedSkill) {
        Object rawSkills = snapshot.get("skills");
        if (!(rawSkills instanceof List<?> skills)) {
            return "";
        }

        for (Object skillObj : skills) {
            if (!(skillObj instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> skillMap = (Map<String, Object>) rawMap;
            String name = Objects.toString(skillMap.get("name"), "").trim();
            if (!name.equalsIgnoreCase(requestedSkill)) {
                continue;
            }

            boolean disabled = toBoolean(skillMap.get("disabled"));
            boolean eligible = !skillMap.containsKey("eligible") || toBoolean(skillMap.get("eligible"));
            if (!disabled && eligible) {
                return name;
            }
        }
        return "";
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private String readSkillMarkdown(Map<String, Object> snapshot, String skillName) {
        String safeName = skillName == null ? "" : skillName.trim();
        if (!SKILL_NAME_PATTERN.matcher(safeName).matches()) {
            return "";
        }

        List<Path> roots = new ArrayList<>();

        String workspaceDir = Objects.toString(snapshot.get("workspaceDir"), "").trim();
        if (StringUtils.hasText(workspaceDir)) {
            roots.add(Path.of(workspaceDir).resolve("skills"));
        }

        String managedSkillsDir = Objects.toString(snapshot.get("managedSkillsDir"), "").trim();
        if (StringUtils.hasText(managedSkillsDir)) {
            roots.add(Path.of(managedSkillsDir));
        }

        for (Path root : roots) {
            Path skillFile = root.resolve(safeName).resolve("SKILL.md").normalize();
            try {
                if (!skillFile.startsWith(root.toAbsolutePath().normalize())) {
                    continue;
                }
                if (!Files.isRegularFile(skillFile)) {
                    continue;
                }
                String content = Files.readString(skillFile, StandardCharsets.UTF_8);
                return truncateSkillContent(content);
            } catch (IOException e) {
                log.warn("读取 Skill 文件失败: {}", e.getMessage());
            } catch (Exception ignored) {
                // 路径异常时跳过该候选
            }
        }
        return "";
    }

    private String truncateSkillContent(String content) {
        String text = content == null ? "" : content.trim();
        if (text.length() <= MAX_SKILL_CHARS) {
            return text;
        }
        return text.substring(0, MAX_SKILL_CHARS) + "\n\n[Skill 内容过长，已截断]";
    }

    private String buildRewrittenPrompt(String skillName, String skillMarkdown, String commandArgs) {
        String request = StringUtils.hasText(commandArgs)
                ? commandArgs
                : "请按该 Skill 的流程开始执行；如果关键信息缺失，请先向我提问。";

        return "你正在执行一个已启用的 Skill 命令。\n"
                + "命令: /" + skillName.toLowerCase(Locale.ROOT) + "\n\n"
                + "=== SKILL.md 开始 ===\n"
                + skillMarkdown + "\n"
                + "=== SKILL.md 结束 ===\n\n"
                + "请严格依据上面的 Skill 内容执行下面请求，不要复述 SKILL.md 原文。\n"
                + "用户请求:\n"
                + request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rewriteUserMessage(Map<String, Object> original, String rewrittenText) {
        Map<String, Object> rewritten = new LinkedHashMap<>(original);
        Object content = original.get("content");

        if (content instanceof List<?> rawParts) {
            List<Map<String, Object>> parts = new ArrayList<>();
            boolean replacedText = false;

            for (Object partObj : rawParts) {
                if (!(partObj instanceof Map<?, ?> rawPart)) {
                    continue;
                }
                Map<String, Object> part = new LinkedHashMap<>((Map<String, Object>) rawPart);
                if (!replacedText && "text".equals(part.get("type"))) {
                    part.put("text", rewrittenText);
                    replacedText = true;
                }
                parts.add(part);
            }

            if (!replacedText) {
                Map<String, Object> textPart = new LinkedHashMap<>();
                textPart.put("type", "text");
                textPart.put("text", rewrittenText);
                parts.add(0, textPart);
            }

            rewritten.put("content", parts);
            return rewritten;
        }

        rewritten.put("content", rewrittenText);
        return rewritten;
    }
}