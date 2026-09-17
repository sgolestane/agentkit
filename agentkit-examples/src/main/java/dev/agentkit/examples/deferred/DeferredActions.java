package dev.agentkit.examples.deferred;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * How a scheduled {@link DeferredAction} runs, and what it may do when it does — for any use case.
 *
 * <p>What an action <em>does</em> is its goal, which a model wrote when it was scheduled. Nothing
 * here knows what the subject is or which systems exist. What this class owns is what must hold
 * whatever that goal says, because it runs later on the operator's behalf and is text a model wrote:
 *
 * <ul>
 *   <li><b>The goal is a procedure, not the objective.</b> {@link #goalFor} states the objective in
 *       these words and fences the goal as a {@link Spotlight.Kind#PROCEDURE}: it directs the work, and
 *       cannot give the run a new objective or a tool it was not given.</li>
 *   <li><b>It can only take away, tell and ask.</b> {@link #restrict} keeps only tools whose declared
 *       {@link Effect} is in {@link #ALLOWED_EFFECTS}: nothing that grants, reads on its own, or
 *       schedules more work.</li>
 *   <li><b>It acts on its subject.</b> {@link #gateFor} refuses a call whose declared subject argument
 *       does not refer to the subject — except a notification to one of the subject's contacts.</li>
 * </ul>
 *
 * <p>All three come from {@link ToolInfo} declarations and a {@link SubjectRecord}, so a new use case
 * brings a {@link SubjectResolver} and declared tools, and changes nothing here.
 */
public final class DeferredActions {

    /** What a deferred action may do: never grant, never schedule more work. */
    public static final Set<Effect> ALLOWED_EFFECTS = Set.of(Effect.REVOKE, Effect.NOTIFY, Effect.REQUEST);

    private DeferredActions() {
    }

    /**
     * The goal a deferred action runs with: the objective, the scheduled goal fenced as a procedure,
     * today's date, and the subject's record as it stands today.
     */
    public static Goal goalFor(DeferredAction action, SubjectRecord current, LocalDate today) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(current, "current");
        StringBuilder record = new StringBuilder();
        new TreeMap<>(current.facts()).forEach((k, v) -> record.append("- ").append(k).append(": ").append(v).append('\n'));
        record.append("- may be notified: ").append(current.contacts().isEmpty() ? "no one besides the subject"
                : String.join(", ", new java.util.TreeSet<>(current.contacts())));
        String subject = action.subjectKind() + " " + action.subjectId();
        return Goal.of("Carry out the deferred action below for " + subject + ". It was scheduled on "
                + action.scheduledOn() + " to run on " + action.runOn() + ". Before acting, compare it with the "
                + "current record below: if the facts it depends on have changed, do not carry it out; instead "
                + "tell the people it would have notified that it no longer applies.\n\n"
                + "Deferred action:\n"
                + Spotlight.wrap(Spotlight.Kind.PROCEDURE, Source.of("deferred-action", action.id()), action.goal())
                + "\n\nToday: " + today
                + "\n\nCurrent record for " + subject + ":\n" + record);
    }

    /** Only the tools a deferred action may use, judged by what each declares. */
    public static ToolRegistry restrict(Collection<? extends Tool> tools, Function<String, Optional<ToolInfo>> catalog) {
        return new SimpleToolRegistry(tools.stream()
                .filter(t -> catalog.apply(t.name()).map(info -> ALLOWED_EFFECTS.contains(info.effect())).orElse(false))
                .toList());
    }

    /**
     * Keeps a deferred action to its subject. A call is allowed only when the tool declares an allowed
     * effect and a subject argument, and that argument refers to the subject — or, for a notification,
     * names one of the subject's contacts. A tool that declares nothing, or no subject, is refused.
     */
    public static ToolGate gateFor(DeferredAction action, SubjectRecord current,
                                   Function<String, Optional<ToolInfo>> catalog) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(catalog, "catalog");
        return new ToolGate() {
            @Override
            public boolean waitsForAHuman() {
                return false;
            }

            @Override
            public GateResult evaluate(Tool tool, ToolInvocation invocation) {
                Optional<ToolInfo> declared = catalog.apply(invocation.name());
                if (declared.isEmpty()) {
                    return GateResult.deny("A deferred action can only use tools that declare what they do.");
                }
                ToolInfo info = declared.get();
                if (!ALLOWED_EFFECTS.contains(info.effect())) {
                    return GateResult.deny("A deferred action cannot use " + invocation.name() + " ("
                            + info.effect().name().toLowerCase(Locale.ROOT) + "); it may only revoke, notify or request.");
                }
                if (info.subjectParam() == null) {
                    return GateResult.deny("A deferred action cannot use " + invocation.name()
                            + ", which does not declare whom it acts on.");
                }
                String target = invocation.stringArgument(info.subjectParam());
                boolean allowed = current.refersTo(target) || (info.effect() == Effect.NOTIFY && current.isContact(target));
                if (!allowed) {
                    return GateResult.deny("A deferred action for " + action.subjectKind() + " " + action.subjectId()
                            + " can only act on that " + action.subjectKind()
                            + (info.effect() == Effect.NOTIFY ? " or notify its contacts" : "") + ", not " + target + ".");
                }
                return GateResult.allow();
            }
        };
    }
}
