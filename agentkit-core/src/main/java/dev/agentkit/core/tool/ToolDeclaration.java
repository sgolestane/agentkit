package dev.agentkit.core.tool;

import java.util.Objects;

/**
 * What a tool declares about itself, once, by whoever builds or connects it.
 *
 * <p>Rules about tools are written against declarations rather than names, so a new tool is covered the
 * moment it is declared: a deferred action may use tools whose effect is a revocation, not
 * "{@code revoke_grant} and {@code slack_remove_account}".
 *
 * @param system       the system it acts on, as a person would name it ("okta", "crm")
 * @param effect       what it does
 * @param subjectParam the argument naming who or what it acts on (an email, an account id), or null when it
 *                     acts on nobody in particular
 */
public record ToolDeclaration(String system, ToolEffect effect, String subjectParam) {

    public ToolDeclaration {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(effect, "effect");
        subjectParam = subjectParam == null || subjectParam.isBlank() ? null : subjectParam.strip();
    }
}
