package dev.agentkit.workbench.domain;

/**
 * One ticket's triage answer, as the model returns it — the shape behind "The agent can handle
 * 23 of your 47 open tickets".
 *
 * <p>Produced by a single-shot structured call ({@code agentkit-json} derives the schema
 * from this record and parses the reply back into it), never by the agent loop: a verdict
 * is a classification, not a run, and constraining it to a schema is what makes the inbox
 * badge trustworthy enough to bulk-select on.
 *
 * @param canHandle  whether the agent believes it can resolve this ticket with the tools it has
 * @param category   a short kebab-case family, e.g. {@code access-request} — what an
 *                   automation rule matches on
 * @param confidence 0..1
 * @param plan       one or two sentences: what the agent would do
 * @param missing    what is missing when it cannot handle it — a capability, a connection,
 *                   information; empty when nothing is
 */
public record TriageVerdict(boolean canHandle, String category, double confidence,
                            String plan, String missing) {
}
