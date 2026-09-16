package dev.agentkit.core.skill;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Parses the {@code SKILL.md} format: a YAML-frontmatter header delimited by
 * {@code ---} lines, followed by a markdown body.
 *
 * <p>Only the small subset of YAML needed for skill metadata is supported —
 * {@code key: value} pairs with optionally quoted scalar values. The required
 * keys are {@code name} and {@code description}. This avoids a YAML dependency in
 * the core; richer frontmatter can be added later without changing the format.
 */
public final class SkillParser {

    private static final String DELIMITER = "---";

    /** The metadata and body extracted from a {@code SKILL.md} document. */
    public record Parsed(String name, String description, Map<String, String> frontmatter, String body) {
    }

    private SkillParser() {
    }

    /** Parses {@code SKILL.md} content into an in-memory {@link Skill} (no resources). */
    public static Skill toSkill(String content) {
        Parsed parsed = parse(content);
        return Skill.of(parsed.name(), parsed.description(), parsed.body());
    }

    /** Parses {@code SKILL.md} content into its metadata and body. */
    public static Parsed parse(String content) {
        Objects.requireNonNull(content, "content");
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        if (normalized.startsWith("﻿")) {
            normalized = normalized.substring(1); // strip UTF-8 BOM
        }
        String[] lines = normalized.split("\n", -1);

        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i >= lines.length || !lines[i].strip().equals(DELIMITER)) {
            throw new SkillFormatException("SKILL.md must begin with a '---' frontmatter delimiter");
        }
        i++; // consume opening delimiter

        Map<String, String> frontmatter = new LinkedHashMap<>();
        boolean closed = false;
        for (; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().equals(DELIMITER)) {
                closed = true;
                i++;
                break;
            }
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new SkillFormatException("Invalid frontmatter line (expected 'key: value'): " + line);
            }
            String key = line.substring(0, colon).strip();
            String value = unquote(line.substring(colon + 1).strip());
            if (!key.isEmpty()) {
                if (frontmatter.containsKey(key)) {
                    throw new SkillFormatException("Duplicate frontmatter key: '" + key + "'");
                }
                frontmatter.put(key, value);
            }
        }
        if (!closed) {
            throw new SkillFormatException("SKILL.md frontmatter is not closed with a '---' delimiter");
        }

        String name = frontmatter.get("name");
        String description = frontmatter.get("description");
        if (name == null || name.isBlank()) {
            throw new SkillFormatException("SKILL.md frontmatter must define a non-blank 'name'");
        }
        if (description == null || description.isBlank()) {
            throw new SkillFormatException("SKILL.md frontmatter must define a non-blank 'description'");
        }

        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length)).strip();
        return new Parsed(name, description, frontmatter, body);
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
