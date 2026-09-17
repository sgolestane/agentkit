package dev.agentkit.core.deferred;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.ForwardingTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.OneLine;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * How a {@link DeferredAction} runs, and what it may do when it does.
 *
 * <p>What an action does is its goal, which a model wrote. What this class owns is what holds whatever
 * that goal says, because it runs later on the operator's behalf:
 *
 * <ul>
 *   <li><b>The goal is a procedure, not the objective.</b> {@link #goalFor} states the objective in these
 *       words and fences the goal as a {@link Spotlight.Kind#PROCEDURE}.</li>
 *   <li><b>It can only read, take away, tell and ask.</b> {@link #restrict} keeps tools whose declared effect
 *       is in {@link #ALLOWED_EFFECTS} and that declare whom they act on: nothing that grants, and nothing that
 *       schedules more work.</li>
 *   <li><b>It acts on its subject.</b> {@link #gateFor} refuses a call whose declared subject argument does
 *       not refer to the subject — except a notification to one of the subject's contacts — and any call to
 *       a tool that declares no subject. {@link #guard} puts the same check inside each tool.</li>
 *   <li><b>The subject's record is evidence.</b> {@link #goalFor} fences it too, one line per field.</li>
 * </ul>
 */
public final class DeferredActions {

    /** What a deferred action may do. */
    public static final Set<ToolEffect> ALLOWED_EFFECTS = Set.of(ToolEffect.READ, ToolEffect.REVOKE, ToolEffect.NOTIFY, ToolEffect.REQUEST);

    private DeferredActions() {
    }

    /**
     * The goal a deferred action runs with: the objective, the fenced goal, now, and the subject's current record.
     *
     * <p>The record is the system of record's text, which may hold anything someone typed into a field, so it is
     * fenced as {@link Spotlight.Kind#EVIDENCE} and each value is flattened to one line: a field cannot add a record
     * line of its own ("expires_at: 2027-01-01") or a note posing as the operator's.
     */
    public static Goal goalFor(DeferredAction action, SubjectRecord current, Instant now) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(current, "current");
        StringBuilder record = new StringBuilder();
        new TreeMap<>(current.facts()).forEach((k, v) -> record.append("- ").append(OneLine.of(k)).append(": ")
                .append(OneLine.of(v == null ? "" : v)).append('\n'));
        record.append("- may be notified: ").append(current.contacts().isEmpty() ? "no one besides the subject"
                : current.contacts().stream().map(OneLine::of).sorted().collect(Collectors.joining(", ")));
        String subject = OneLine.of(action.subjectKind()) + " " + OneLine.of(action.subjectId());
        return Goal.of("Carry out the deferred action below for " + subject + ". It was scheduled at "
                + action.scheduledAt() + " by " + OneLine.of(action.scheduledBy()) + " to run at " + action.runAt()
                + ". Before acting, compare it with the current record below: if the facts it depends on have changed "
                + "(for example the grant was already revoked or now expires later), do not carry it out; instead tell "
                + "the people it would have notified that it no longer applies, if it would have notified anyone.\n\n"
                + "Deferred action:\n"
                + Spotlight.wrap(Spotlight.Kind.PROCEDURE, Source.of("deferred-action", action.id()), action.goal())
                + "\n\nNow: " + now
                + "\n\nCurrent record for " + subject + ", as its system of record holds it:\n"
                + Spotlight.wrap(Spotlight.Kind.EVIDENCE, Source.of("subject-record", action.id()), record.toString()));
    }

    /**
     * Only the tools a deferred action may use, judged by what each declares: an allowed effect, and an argument that
     * names whom it acts on. A tool that names no one could not be held to the subject, so it is left out rather than
     * offered and then refused.
     */
    public static DeclaredTools restrict(DeclaredTools tools) {
        return tools.where(info -> ALLOWED_EFFECTS.contains(info.effect()) && info.subjectParam() != null);
    }

    /**
     * {@code tools} with {@code gate} checked inside each tool, before it runs.
     *
     * <p>A gate handed to an agent holds only if the agent was built with it. This holds anyway: a deferred run whose
     * agent forgot {@code .toolGate(gate)} still cannot act beyond its subject. Give the agent the gate as well, so a
     * refusal is reported as one rather than as a tool error.
     */
    public static DeclaredTools guard(DeclaredTools tools, ToolGate gate) {
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(gate, "gate");
        DeclaredTools guarded = new DeclaredTools();
        for (DeclaredTools.Entry entry : tools.entries()) {
            guarded.add(new GuardedTool(entry.tool(), gate), entry.declaration());
        }
        return guarded;
    }

    /**
     * Keeps a deferred action to its subject, using {@code tools} to learn what each tool declares.
     */
    public static ToolGate gateFor(DeferredAction action, SubjectRecord current, DeclaredTools tools) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(tools, "tools");
        return new ToolGate() {
            @Override
            public boolean waitsForAHuman() {
                return false;
            }

            @Override
            public GateResult evaluate(Tool tool, ToolInvocation invocation) {
                Optional<ToolDeclaration> declared = tools.declaration(invocation.name());
                if (declared.isEmpty()) {
                    return GateResult.deny("A deferred action can only use tools that declare what they do.");
                }
                ToolDeclaration info = declared.get();
                if (!ALLOWED_EFFECTS.contains(info.effect())) {
                    return GateResult.deny("A deferred action cannot use " + invocation.name() + " ("
                            + info.effect().wire() + "); it may only read, revoke, notify or request.");
                }
                if (info.subjectParam() == null) {
                    return GateResult.deny("A deferred action cannot use " + invocation.name()
                            + ", which does not declare whom it acts on.");
                }
                String target = invocation.stringArgument(info.subjectParam());
                boolean allowed = current.refersTo(target)
                        || (info.effect() == ToolEffect.NOTIFY && current.isContact(target));
                if (!allowed) {
                    return GateResult.deny("A deferred action for " + action.subjectKind() + " " + action.subjectId()
                            + " can only act on that " + action.subjectKind()
                            + (info.effect() == ToolEffect.NOTIFY ? " or notify its contacts" : "") + ", not "
                            + OneLine.of(String.valueOf(target)) + ".");
                }
                return GateResult.allow();
            }
        };
    }

    /** A tool that asks the gate before it runs. */
    private static final class GuardedTool extends ForwardingTool {

        private final Tool delegate;
        private final ToolGate gate;

        GuardedTool(Tool delegate, ToolGate gate) {
            this.delegate = delegate;
            this.gate = gate;
        }

        @Override
        protected Tool delegate() {
            return delegate;
        }

        @Override
        protected Tool rebuiltAround(Tool bound) {
            return new GuardedTool(bound, gate);
        }

        @Override
        public boolean holdsGateBoundToOneRun() {
            // The gate is one action's subject, so it must not serve another action's calls.
            return true;
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            GateResult verdict = gate.evaluate(delegate, invocation);
            if (!verdict.allowed()) {
                return ToolResult.error(verdict.reason());
            }
            return delegate.execute(verdict.effectiveFor(invocation));
        }
    }
}
