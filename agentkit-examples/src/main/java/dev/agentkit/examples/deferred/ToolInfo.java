package dev.agentkit.examples.deferred;

import java.util.Objects;

/**
 * What a tool declares about itself, once, by whoever builds the connector.
 *
 * @param system       the system it acts on, as a person would name it in a goal ("okta", "crm")
 * @param effect       what it does
 * @param subjectParam the argument naming the subject it acts on (an email, an employee id, an
 *                     account id), or null when it acts on no subject in particular
 */
public record ToolInfo(String system, Effect effect, String subjectParam) {

    public ToolInfo {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(effect, "effect");
    }
}
