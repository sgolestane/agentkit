package dev.agentkit.workbench.tools;

import dev.agentkit.core.tool.DisclosingToolRegistry;
import dev.agentkit.core.tool.ForwardingTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.workbench.connector.Alm;
import dev.agentkit.workbench.domain.Risk;
import dev.agentkit.workbench.runtime.AnswerBox;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.RunContext;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles the tools a run may reach, and decides which of them it starts out knowing
 * about.
 *
 * <p>Always available: how to read tickets, how to ask a person, how to report a gap and
 * save a learning, and how to find everything else. Deferred: the writers. Starting with
 * the writers hidden is context economy, not a security boundary — a deferred tool is
 * registered and callable; what stops a call is the gate. That is why the supervisor reads
 * {@link #policyOrUnknown} for every invocation rather than only for the disclosed ones.
 *
 * <p>Built per run, because the tools close over the run's {@link RunContext}: the tenant
 * never appears in a schema, so the model has no argument through which to reach another
 * tenant's data.
 */
public final class ToolCatalog {

    private static final Map<String, ToolPolicy> POLICIES =
            index(AlmTools.policies(), WorkbenchTools.policies());

    /** The capability a tool no policy declares is filed under. */
    public static final String UNKNOWN_CAPABILITY = "unknown";

    private ToolCatalog() {
    }

    @SafeVarargs
    private static Map<String, ToolPolicy> index(List<ToolPolicy>... groups) {
        Map<String, ToolPolicy> byName = new LinkedHashMap<>();
        for (List<ToolPolicy> group : groups) {
            for (ToolPolicy policy : group) {
                byName.put(policy.name(), policy);
            }
        }
        return Map.copyOf(byName);
    }

    /** What the supervisor knows about a tool before it runs, if anything. */
    public static Optional<ToolPolicy> policy(String toolName) {
        return Optional.ofNullable(POLICIES.get(toolName));
    }

    /**
     * The same, with the answer this platform gives when nobody wrote a policy: the most
     * dangerous grading it has. "Nobody wrote a policy" and "somebody decided it was
     * harmless" must not resolve the same way.
     */
    public static ToolPolicy policyOrUnknown(String toolName) {
        return policy(toolName).orElseGet(() ->
                ToolPolicy.write(toolName, UNKNOWN_CAPABILITY, "unknown", Risk.HIGH,
                        false, false));
    }

    /** Every declared policy, for the console's tool inventory. */
    public static List<ToolPolicy> policies() {
        return List.copyOf(POLICIES.values());
    }

    /**
     * The registry for one run — every tool advertised from the first turn.
     *
     * <p>The writers were deferred behind {@code search_tools} at first, itops-style. With
     * a catalogue this small the economy bought nothing and cost correctness: a resumed
     * run that did not think to search concluded "I cannot add comments or transition
     * tickets (no such tools were provided)" and finished without doing the work a human
     * had just approved — measured in the browser. Deferral pays at dozens of tools;
     * at seven it is a way for the model to be wrong about what exists.
     */
    public static DisclosingToolRegistry forRun(Alm alm, RunContext context,
            WorkbenchStore store, Learnings learnings, AnswerBox answers) {
        List<Tool> everything = new ArrayList<>();
        everything.addAll(AlmTools.of(alm, context));
        everything.addAll(WorkbenchTools.of(context, store, learnings, answers));

        DisclosingToolRegistry.Builder builder = DisclosingToolRegistry.builder();
        for (Tool tool : everything) {
            builder.alwaysAvailable(new BoundToThisRun(tool));
        }
        return builder.build();
    }

    /**
     * Every tool this catalog builds, saying what has always been true of it (#330).
     *
     * <p>The tools close over the run's {@link RunContext} and {@link AnswerBox} — the
     * audit trail, the evidence, the single-use answers — which is run-bound state with no
     * gate attached: exactly the shape {@code Tool.boundToOneRun()} was added for, because
     * a durable worker's registration check cannot see it any other way. This module runs
     * in-process today, so the declaration changes nothing here; what it buys is that a
     * future durable registration of these tools is refused with a reason instead of
     * quietly applying one run's context to every run on the queue.
     */
    private static final class BoundToThisRun extends ForwardingTool {
        private final Tool tool;

        private BoundToThisRun(Tool tool) {
            this.tool = tool;
        }

        @Override
        protected Tool delegate() {
            return tool;
        }

        @Override
        public boolean boundToOneRun() {
            return true;
        }

        @Override
        protected Tool rebuiltAround(Tool bound) {
            return new BoundToThisRun(bound);
        }
    }
}
