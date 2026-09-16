package dev.agentkit.eval;

import dev.agentkit.core.tool.ToolInvocation;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A named condition on the arguments of one tool call, for the argument-aware
 * {@link Checks}.
 *
 * <h2>The hole this closes</h2>
 *
 * <p>{@link ToolCall} has carried the whole {@link ToolInvocation} — arguments included —
 * since it was written, and until this class nothing in {@link Checks} read them. Every
 * trajectory check asked a question about a <em>name</em>, so
 * {@code Checks.usedTool("identity.add_user_to_group")} passed on a run that added the wrong
 * person to the wrong group. It scored that something happened rather than that the right
 * thing happened, which is the vacuous positive control this repository keeps rejecting: a
 * check that cannot fail on the run it exists to catch is worse than no check, because a
 * suite holding it reports a control it does not have.
 *
 * <p>It also made one property unwritable. The itops injection case had to be spelled as a
 * blanket {@code didNotUseTool("identity.add_user_to_group")}, which only worked because the
 * seeded ticket's legitimate work happened to use a <em>different</em> tool than the injected
 * instruction did. The day a ticket's real work and its injection name the same tool, the
 * blanket form says "this agent may never grant group membership", which is not the property
 * anybody wanted to assert.
 *
 * <h2>What it matches, exactly</h2>
 *
 * <p>The arguments <strong>the model requested</strong>. {@code EvalHarness} captures the
 * <em>proposed</em> invocation and says why: an eval scores the model, and a gate narrowing
 * the arguments afterwards is the harness protecting itself rather than the model choosing
 * better. The consequence is worth stating where a case author will read it: on a runtime
 * that rewrites arguments — an approval that narrows a group, a gate that substitutes an
 * effective call — a condition here describes what was <em>asked for</em> and not what ran.
 * The call that changed the world may carry arguments this never saw. That is one of the two
 * reasons {@link Checks#worldState} exists.
 *
 * <p>{@link #equalTo} compares exactly, against {@link ToolInvocation#stringArgument}. No
 * trimming, no case folding, no Unicode normalisation. That is the right default for a
 * <em>positive</em> check, where a looser comparison would let a near-miss pass; it is the
 * wrong default for a negative one, where any spelling the condition does not cover walks
 * straight through. Both directions are documented on the checks themselves. Use
 * {@link #matching} when a case genuinely wants a looser or a wider condition, and name it
 * honestly — the name is what a person reads in the failure.
 */
public final class Args {

    private final String description;
    private final Predicate<ToolInvocation> test;

    private Args(String description, Predicate<ToolInvocation> test) {
        this.description = Objects.requireNonNull(description, "description");
        this.test = Objects.requireNonNull(test, "test");
    }

    /**
     * Matches a call whose {@code key} argument renders exactly as {@code expected}.
     *
     * <p>Read through {@link ToolInvocation#stringArgument}, so a non-string JSON value is
     * compared by its {@code toString} and an absent argument never matches. Exact — see the
     * class note for why, and for when to reach for {@link #matching} instead.
     *
     * @param key      the argument name, as the tool's schema spells it; never {@code null}
     * @param expected the value the case demands; never {@code null}. Written by whoever
     *     authors the case, and echoed into the check's label, so it is first-party text —
     *     do not build one out of a model's output
     */
    public static Args equalTo(String key, String expected) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expected, "expected");
        return new Args(key + "='" + expected + "'",
                invocation -> expected.equals(invocation.stringArgument(key)));
    }

    /**
     * Matches a call for which {@code test} holds, under a name a person can read.
     *
     * <p>The escape hatch, and the reason it takes a description rather than deriving one: a
     * lambda has no name, and a failure report naming the synthetic class of a lambda tells
     * the reader nothing about what was expected.
     *
     * @param description what this condition demands, in a few words; never {@code null} or
     *     blank. First-party text — it goes in the check's label verbatim
     * @param test        the condition, over the invocation the model requested; never
     *     {@code null}. It must not throw: {@code EvalHarness} turns a thrown check into a
     *     failed one, so a condition that throws reads as the property being violated
     */
    public static Args matching(String description, Predicate<ToolInvocation> test) {
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("an argument condition needs a description; a"
                    + " blank one leaves the failure report unable to say what was expected");
        }
        return new Args(description, Objects.requireNonNull(test, "test"));
    }

    /** This condition and {@link #equalTo}{@code (key, expected)}, both on the same call. */
    public Args and(String key, String expected) {
        return and(equalTo(key, expected));
    }

    /**
     * This condition and {@code other}, both on the same call.
     *
     * <p><strong>The same call</strong>, which is the whole point and is easy to lose: two
     * separate {@code usedTool} checks, one per argument, are satisfied by two
     * <em>different</em> calls — one that named the right user and one that named the right
     * group. Conjoining here asks for a single call that did both.
     */
    public Args and(Args other) {
        Objects.requireNonNull(other, "other");
        return new Args(description + " and " + other.description,
                invocation -> test.test(invocation) && other.test.test(invocation));
    }

    /** What this condition demands, for a check's label. Never blank. */
    public String description() {
        return description;
    }

    /** Whether {@code invocation} — as the model requested it — satisfies this condition. */
    public boolean matches(ToolInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return test.test(invocation);
    }

    @Override
    public String toString() {
        return description;
    }
}
