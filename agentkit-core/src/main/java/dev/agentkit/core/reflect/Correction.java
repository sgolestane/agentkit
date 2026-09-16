package dev.agentkit.core.reflect;

import java.util.Objects;

/**
 * A person's refusal, and what they said about it (#329).
 *
 * <p>The highest-quality correction signal this system produces, and until #329 it was
 * write-only: recorded for audit, read by nobody, gone by the next run. A gate makes the
 * mistake safe; nothing made it rarer, and every repeat costs a person's attention.
 *
 * @param area       the scope this generalises to, chosen by the deployment — see
 *                   {@link CorrectionBook} for why it is not the tool name
 * @param decidedBy  who refused, already coerced to something renderable as a
 *                   {@link dev.agentkit.core.prompt.Source} qualifier
 * @param note       what they said, in one line
 * @param standing   whether the person meant this to hold for later runs, so a gate
 *                   enforces it, or only to explain this one refusal. See
 *                   {@link CorrectionBook#record} for why that is a choice and not a
 *                   consequence of refusing
 */
public record Correction(String area, String decidedBy, String note, boolean standing) {

    public Correction {
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(note, "note");
    }
}
