package dev.agentkit.accessdesk.deferred;

import dev.agentkit.accessdesk.tools.Effect;
import dev.agentkit.accessdesk.tools.ToolCatalog;
import dev.agentkit.accessdesk.tools.ToolInfo;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
 *       is in {@link #ALLOWED_EFFECTS}: nothing that grants, and nothing that schedules more work.</li>
 *   <li><b>It acts on its subject.</b> {@link #gateFor} refuses a call whose declared subject argument does
 *       not refer to the subject — except a notification to one of the subject's contacts — and any call to
 *       a tool that declares no subject.</li>
 * </ul>
 */
public final class DeferredActions {

    /** What a deferred action may do. */
    public static final Set<Effect> ALLOWED_EFFECTS = Set.of(Effect.READ, Effect.REVOKE, Effect.NOTIFY, Effect.REQUEST);

    private DeferredActions() {
    }

    /** The goal a deferred action runs with: the objective, the fenced goal, now, and the subject's current record. */
    public static Goal goalFor(DeferredAction action, SubjectRecord current, Instant now) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(current, "current");
        StringBuilder record = new StringBuilder();
        new TreeMap<>(current.facts()).forEach((k, v) -> record.append("- ").append(k).append(": ").append(v).append('\n'));
        record.append("- may be notified: ").append(current.contacts().isEmpty() ? "no one besides the subject"
                : String.join(", ", new TreeSet<>(current.contacts())));
        String subject = action.subjectKind() + " " + action.subjectId();
        return Goal.of("Carry out the deferred action below for " + subject + ". It was scheduled at "
                + action.scheduledAt() + " by " + action.scheduledBy() + " to run at " + action.runAt() + ". Before acting, "
                + "compare it with the current record below: if the facts it depends on have changed (for example the "
                + "grant was already revoked or now expires later), do not carry it out; instead tell the people it would "
                + "have notified that it no longer applies, if it would have notified anyone.\n\n"
                + "Deferred action:\n"
                + Spotlight.wrap(Spotlight.Kind.PROCEDURE, Source.of("deferred-action", action.id()), action.goal())
                + "\n\nNow: " + now
                + "\n\nCurrent record for " + subject + ":\n" + record);
    }

    /** Only the tools a deferred action may use, judged by what each declares. */
    public static ToolCatalog restrict(ToolCatalog tools) {
        return tools.where(info -> ALLOWED_EFFECTS.contains(info.effect()));
    }

    /**
     * Keeps a deferred action to its subject, using {@code tools} to learn what each tool declares.
     */
    public static ToolGate gateFor(DeferredAction action, SubjectRecord current, ToolCatalog tools) {
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
                Optional<ToolInfo> declared = tools.info(invocation.name());
                if (declared.isEmpty()) {
                    return GateResult.deny("A deferred action can only use tools that declare what they do.");
                }
                ToolInfo info = declared.get();
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
                        || (info.effect() == Effect.NOTIFY && current.isContact(target));
                if (!allowed) {
                    return GateResult.deny("A deferred action for " + action.subjectKind() + " " + action.subjectId()
                            + " can only act on that " + action.subjectKind()
                            + (info.effect() == Effect.NOTIFY ? " or notify its contacts" : "") + ", not "
                            + String.valueOf(target).toLowerCase(Locale.ROOT) + ".");
                }
                return GateResult.allow();
            }
        };
    }
}
