package dev.agentkit.core.supervisor;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.Quoted;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An {@link AgentScope} exposed to a model as three tools, so a run can start work, carry
 * on, and collect it later (#328).
 *
 * <p>{@code SubagentTools.delegate} blocks: the supervisor's turn does not continue until
 * the subagent's whole run is finished. These do not, which is the only difference and the
 * entire point. Both read the same {@link SubagentRoster}, so a deployment can wire either
 * or both, and the subagents are the same subagents.
 *
 * <p><strong>Every tool here declares {@link Tool#boundToOneRun()}.</strong> They close over
 * one run's scope, so registering them on a shared worker would hand every run on the task
 * queue a stranger's outstanding work — and until #328 there was no declaration that said
 * so, because the two that existed are about a gate and these hold none.
 * {@code ToolActivitiesImpl} refuses them at registration now.
 */
public final class ScopeTools {

    /** Start work and get a handle back. */
    public static final String START_TASK = "start_task";

    /** Wait for a handle and read what it produced. */
    public static final String COLLECT_TASK = "collect_task";

    /** What is running and has not been collected. */
    public static final String RUNNING_TASKS = "running_tasks";

    /**
     * How much of a collected answer reaches the run that started it.
     *
     * <p>{@code SubagentTools.DEFAULT_MAX_OUTPUT_CHARS}' figure and reasoning: enough for a
     * substantial answer, small enough that one task cannot decide what the rest of the run
     * costs.
     */
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 4_000;

    private ScopeTools() {
    }

    /** All three, over one scope and one roster. */
    public static List<Tool> forScope(AgentScope scope, SubagentRoster roster) {
        return List.of(startTaskTool(scope, roster), collectTaskTool(scope, roster),
                runningTasksTool(scope));
    }

    /**
     * {@code start_task} — begins a subagent's run without waiting for it.
     *
     * <p>The {@code subagent} enum renders from the live roster on every advertised spec, so
     * a roster grown mid-run is callable on the next turn. That is #308's rule, and a
     * snapshot here would have reintroduced the divergence it closed.
     */
    public static Tool startTaskTool(AgentScope scope, SubagentRoster roster) {
        return startTaskTool(scope, roster, DEFAULT_MAX_OUTPUT_CHARS);
    }

    /** {@link #startTaskTool(AgentScope, SubagentRoster)} with an explicit subgoal slot. */
    public static Tool startTaskTool(AgentScope scope, SubagentRoster roster,
                                     int maxOutputChars) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(roster, "roster");
        if (maxOutputChars < 1) {
            throw new IllegalArgumentException("maxOutputChars must be positive");
        }
        return new RunBound(roster) {
            @Override
            public String name() {
                return START_TASK;
            }

            @Override
            public String description() {
                return "Start a subagent working on a subgoal WITHOUT waiting for it, and get"
                        + " back a handle. Use this when you have other work to do meanwhile;"
                        + " use delegate when you need the answer before you can continue."
                        + " Collect every handle you start. Available subagents:\n"
                        + roster.catalog();
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object",
                        "properties", Map.of(
                                "subagent", Map.of("type", "string",
                                        "description", "Name of the subagent to start.",
                                        "enum", roster.names()),
                                "goal", Map.of("type", "string",
                                        "description", "The subgoal for it to pursue.")),
                        "required", List.of("subagent", "goal"));
            }

            @Override
            public ToolResult execute(dev.agentkit.core.tool.ToolInvocation invocation) {
                String name = invocation.stringArgument("subagent");
                String goalText = invocation.stringArgument("goal");
                if (name == null || name.isBlank()) {
                    return ToolResult.error("Missing required argument 'subagent'.");
                }
                if (goalText == null || goalText.isBlank()) {
                    return ToolResult.error("Missing required argument 'goal'.");
                }
                Subagent subagent = roster.find(name).orElse(null);
                if (subagent == null) {
                    return ToolResult.error("Unknown subagent "
                            + Quoted.distinguishably(name, 120) + ". Available: "
                            + Quoted.each(roster.names()) + ".");
                }
                // Fenced, bounded and refused-if-too-long through SubagentTools' own
                // helpers rather than a second spelling of them: start_task is delegate
                // without the wait, so a subgoal that reaches a child unfenced here would
                // be #106 and #207 re-opened through the tool that does not block.
                java.util.Optional<String> tooLong =
                        SubagentTools.slotRefusal(goalText, maxOutputChars);
                if (tooLong.isPresent()) {
                    return ToolResult.error(tooLong.get());
                }
                String handle;
                try {
                    handle = scope.start(subagent, Goal.of(
                            SubagentTools.delegatedSubgoal(goalText, maxOutputChars)));
                } catch (IllegalStateException closed) {
                    // A result, not a throw: the loop reads a throw as THREW -- "entered and
                    // may have landed half a side effect" -- and a refused start landed
                    // nothing at all.
                    return ToolResult.error("No task was started: " + closed.getMessage());
                }
                return ToolResult.ok("Started " + Quoted.of(handle) + " on "
                        + Quoted.of(subagent.name())
                        + ". It is running; collect_task " + Quoted.of(handle)
                        + " when you need the answer.");
            }
        };
    }

    /**
     * {@code collect_task} — waits for one handle and returns what it produced.
     *
     * <p>Without a roster it cannot answer the two gate questions from the subagents it may
     * surface, so it answers them {@code false}. Prefer
     * {@link #collectTaskTool(AgentScope, SubagentRoster)}.
     */
    public static Tool collectTaskTool(AgentScope scope) {
        return collectTaskTool(scope, null, DEFAULT_MAX_OUTPUT_CHARS);
    }

    /** {@link #collectTaskTool(AgentScope)} reading {@code roster} for its gate declarations. */
    public static Tool collectTaskTool(AgentScope scope, SubagentRoster roster) {
        return collectTaskTool(scope, roster, DEFAULT_MAX_OUTPUT_CHARS);
    }

    /** {@link #collectTaskTool(AgentScope)} with an explicit ceiling on what comes back. */
    public static Tool collectTaskTool(AgentScope scope, int maxOutputChars) {
        return collectTaskTool(scope, null, maxOutputChars);
    }

    /** {@link #collectTaskTool(AgentScope, SubagentRoster)} with an explicit ceiling. */
    public static Tool collectTaskTool(AgentScope scope, SubagentRoster roster,
                                       int maxOutputChars) {
        Objects.requireNonNull(scope, "scope");
        if (maxOutputChars < 1) {
            throw new IllegalArgumentException("maxOutputChars must be positive");
        }
        return new RunBound(roster) {
            @Override
            public String name() {
                return COLLECT_TASK;
            }

            @Override
            public String description() {
                return "Wait for a task you started with start_task and read its result."
                        + " Blocks until it is done.";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object",
                        "properties", Map.of("handle", Map.of("type", "string",
                                "description", "The handle start_task gave you.")),
                        "required", List.of("handle"));
            }

            @Override
            public Provenance provenance() {
                // A subagent's answer: a separate model that read whatever it read, which is
                // the reason #107 fenced a delegation's result.
                return Provenance.THIRD_PARTY;
            }

            @Override
            public ToolResult execute(dev.agentkit.core.tool.ToolInvocation invocation) {
                String handle = invocation.stringArgument("handle");
                if (handle == null || handle.isBlank()) {
                    return ToolResult.error("Missing required argument 'handle'.");
                }
                return scope.collect(handle)
                        .map(outcome -> rendered(outcome, maxOutputChars))
                        // Not a throw: the handle came from a model argument, and collecting
                        // twice is choosing badly rather than breaking an invariant. It is
                        // told which handles are actually outstanding.
                        .orElseGet(() -> ToolResult.error("No task " + Quoted.of(handle)
                                + " is outstanding; it was never started, or you already"
                                + " collected it. Running: "
                                + Quoted.each(scope.outstanding()) + "."));
            }
        };
    }

    private static ToolResult rendered(SubagentOutcome outcome, int maxOutputChars) {
        if (outcome.awaitsAPerson()) {
            // error rather than ok, and SubagentTools' own words rather than shorter ones:
            // #159 measured that a supervisor told a delegation merely "did not finish"
            // retries or routes around it, and each of those is the effect happening while
            // the question is outstanding. An earlier draft here said "its approval travels
            // with this run", which was not true -- the scope accumulates it in
            // AgentScope.awaiting() now, so it is, and the sentence is no longer needed.
            return ToolResult.error(SubagentTools.parked(outcome.subagentName(),
                    outcome.result().awaiting(), maxOutputChars));
        }
        if (!outcome.result().isSuccess()) {
            // SubagentTools' own renderer, not a thinner one: the stop reason stays outside
            // the fence as the framework's word and the detail goes inside (#113), and a
            // failure whose output() is empty falls back to error() so the model is told why
            // (#126). Rendering output() alone here re-opened both.
            return SubagentTools.didNotComplete("That task did not complete ("
                    + outcome.result().stopReason() + ").",
                    outcome.subagentName(), outcome.result(), maxOutputChars);
        }
        // Source.of("subagent", name), not Source.of(name): the one-argument form is the
        // framework's own literal and THROWS on anything that is not a lowercase kind, while
        // a subagent name is [A-Za-z0-9._-]{1,40}. A roster holding "Researcher" or
        // "web_search" made every collect_task throw -- and collect() had already taken the
        // outcome out of the scope, so the child's answer went with it. The two-argument
        // form coerces an unusable qualifier to "unknown" instead, which is why
        // SubagentTools.fenced uses it.
        return ToolResult.ok(Spotlight.fenceBounded(Spotlight.Kind.EVIDENCE,
                Source.of("subagent", outcome.subagentName()), outcome.result().output(),
                maxOutputChars).fence());
    }

    /** {@code running_tasks} — what has been started and not collected. */
    public static Tool runningTasksTool(AgentScope scope) {
        Objects.requireNonNull(scope, "scope");
        // No roster: listing handles runs no subagent, so neither gate question is this
        // tool's to answer yes to.
        return new RunBound(null) {
            @Override
            public String name() {
                return RUNNING_TASKS;
            }

            @Override
            public String description() {
                return "List the tasks you started that you have not collected yet.";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public ToolResult execute(dev.agentkit.core.tool.ToolInvocation invocation) {
                Map<String, String> work = scope.outstandingWork();
                if (work.isEmpty()) {
                    return ToolResult.ok("Nothing is running.");
                }
                StringBuilder sb = new StringBuilder();
                work.forEach((handle, subagent) -> sb.append(sb.isEmpty() ? "" : "\n")
                        .append("- ").append(Quoted.of(handle))
                        .append(" running ").append(Quoted.of(subagent)));
                return ToolResult.ok(sb.toString());
            }
        };
    }

    /**
     * A tool that says it belongs to one run, and passes on what its roster says.
     *
     * <p>{@link Tool#boundToOneRun()} is here rather than repeated three times because
     * forgetting it on one of them is the whole hazard, and a base class makes the answer
     * structural instead of remembered — the argument {@code ForwardingTool} makes for
     * forwarding. It is {@code final} for the same reason.
     *
     * <p>The two gate questions are {@code DelegateTool}'s, asked the same way and over the
     * same live snapshot: a tool that can run a subagent whose gate parks, or whose gate is
     * built for one run, holds that hazard whether it waits for the answer or not. Shipped
     * once answering {@code false} for every roster, which was only masked by
     * {@code boundToOneRun()} refusing these on the one path that asks.
     */
    private abstract static class RunBound implements Tool {

        private final SubagentRoster roster;

        RunBound(SubagentRoster roster) {
            this.roster = roster;
        }

        @Override
        public final boolean boundToOneRun() {
            return true;
        }

        @Override
        public boolean holdsGateWaitingForAHuman() {
            return roster != null && roster.published().values().stream()
                    .anyMatch(Subagent::holdsGateWaitingForAHuman);
        }

        @Override
        public boolean holdsGateBoundToOneRun() {
            return roster != null && roster.published().values().stream()
                    .anyMatch(Subagent::holdsGateBoundToOneRun);
        }
    }
}
