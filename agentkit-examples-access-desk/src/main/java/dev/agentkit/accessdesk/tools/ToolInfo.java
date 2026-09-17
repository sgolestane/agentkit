package dev.agentkit.accessdesk.tools;

import java.util.Objects;

/**
 * What a tool declares about itself.
 *
 * @param system       the system it acts on, as a person would name it ("okta", "slack", "access-desk")
 * @param effect       what it does
 * @param subjectParam the argument naming who or what it acts on (an email, a grant id), or null when it
 *                     acts on nobody in particular
 */
public record ToolInfo(String system, Effect effect, String subjectParam) {

    public ToolInfo {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(effect, "effect");
        subjectParam = subjectParam == null || subjectParam.isBlank() ? null : subjectParam.strip();
    }
}
