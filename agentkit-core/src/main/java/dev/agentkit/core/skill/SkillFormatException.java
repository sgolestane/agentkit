package dev.agentkit.core.skill;

/** Thrown when a {@code SKILL.md} document is malformed. */
public class SkillFormatException extends RuntimeException {

    public SkillFormatException(String message) {
        super(message);
    }

    public SkillFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
