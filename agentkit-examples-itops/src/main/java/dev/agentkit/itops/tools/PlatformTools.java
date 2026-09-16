package dev.agentkit.itops.tools;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.itops.domain.Artifact;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.runtime.OpsContext;
import dev.agentkit.itops.store.OpsStore;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tools that act on the platform itself rather than on an external system.
 *
 * <p>Two of them, and both exist to make something the agent would otherwise merely
 * <em>say</em> into something the platform can <em>record</em> and act on. A capability
 * verdict written in prose is not queryable and cannot stop a run; the same verdict
 * delivered as a tool call becomes an audit event, a ticket-processing outcome and a
 * branch in the runtime. The general principle: when a model's conclusion has consequences,
 * make it arrive through the tool boundary like everything else.
 */
public final class PlatformTools {

    /**
     * The tool the platform's own runtime branches on, named once.
     *
     * <p>It was a string literal here, in {@code ToolCatalog}'s always-available list, and in
     * the system prompt. Two of those three are code, and {@code RunRules} made a fourth that
     * has to agree with them exactly: a rule keyed on a misspelt name would allow every write
     * while reporting a control, which is the silent direction. The prompt's copy stays prose
     * and is the one a reader can see is prose.
     */
    public static final String REPORT_CAPABILITY = "report_capability";

    private PlatformTools() {
    }

    public static List<ToolPolicy> policies() {
        return List.of(
                ToolPolicy.read(REPORT_CAPABILITY, "platform.control", "platform"),
                ToolPolicy.read("create_artifact", "platform.artifacts", "platform"),
                // The framework's own discovery tool. Declared here rather than left to the
                // supervisor's fallback, which treats an unknown tool as HIGH and would park
                // the run on its first search — the fallback is right, and this is why an
                // undeclared tool must be an omission somebody notices rather than a default
                // that quietly works.
                ToolPolicy.read(dev.agentkit.core.tool.DisclosingToolRegistry
                        .DEFAULT_SEARCH_TOOL_NAME, "platform.tool_discovery", "platform"));
    }

    public static List<Tool> of(OpsContext context, OpsStore store) {
        return List.of(
                FunctionTool.builder(REPORT_CAPABILITY,
                                "capability: platform.control. State whether you can handle this "
                                        + "work with the tools and context available, before "
                                        + "changing anything. One of SUPPORTED, UNSUPPORTED, "
                                        + "NEEDS_MORE_CONTEXT, NEEDS_HUMAN.")
                        .schema(Map.of("type", "object", "properties", Map.of(
                                        "verdict", Map.of("type", "string",
                                                "description", "SUPPORTED, UNSUPPORTED, "
                                                        + "NEEDS_MORE_CONTEXT or NEEDS_HUMAN."),
                                        "reason", Map.of("type", "string",
                                                "description", "Why, in one or two sentences.")),
                                "required", List.of("verdict", "reason")))
                        // No external effect, but not idle either: the runtime reads this and
                        // an UNSUPPORTED verdict ends the run without touching the ticket.
                        .readOnly()
                        // This platform's own control surface: the verdict and the artifact
                        // id are written here, and nothing read from a ticket comes back out.
                        .provenance(Provenance.FIRST_PARTY)
                        .handler(invocation -> {
                            String raw = invocation.stringArgument("verdict");
                            String verdict = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
                            if (!List.of("SUPPORTED", "UNSUPPORTED", "NEEDS_MORE_CONTEXT",
                                    "NEEDS_HUMAN").contains(verdict)) {
                                return ToolResult.error("The 'verdict' must be one of SUPPORTED, "
                                        + "UNSUPPORTED, NEEDS_MORE_CONTEXT, NEEDS_HUMAN.");
                            }
                            String reason = invocation.stringArgument("reason");
                            context.fact("capability", verdict);
                            context.fact("capabilityReason", reason);
                            context.event(Execution.Event.Type.CAPABILITY_EVALUATED,
                                    Map.of("verdict", verdict, "reason", reason == null ? "" : reason));
                            return ToolResult.ok("Recorded capability verdict " + verdict + ".");
                        })
                        .build(),

                FunctionTool.builder("create_artifact",
                                "capability: platform.artifacts. Save a durable document — an "
                                        + "investigation report, remediation summary, runbook or "
                                        + "ticket analysis — that outlives this conversation.")
                        .schema(Map.of("type", "object", "properties", Map.of(
                                        "kind", Map.of("type", "string",
                                                "description", "INVESTIGATION_REPORT, "
                                                        + "REMEDIATION_SUMMARY, PROPOSED_WORKFLOW, "
                                                        + "RUNBOOK or TICKET_ANALYSIS."),
                                        "title", Map.of("type", "string", "description", "A short title."),
                                        "body", Map.of("type", "string",
                                                "description", "The content, in Markdown.")),
                                "required", List.of("kind", "title", "body")))
                        // IDEMPOTENT rather than EXTERNAL: it writes, but only inside the
                        // platform's own store, and writing the same report twice leaves the
                        // same world behind. A read-only run is still refused it, which is
                        // the conservative reading and the one the framework enforces.
                        .sideEffects(SideEffects.IDEMPOTENT)
                        // This platform's own control surface: the verdict and the artifact
                        // id are written here, and nothing read from a ticket comes back out.
                        .provenance(Provenance.FIRST_PARTY)
                        .handler(invocation -> {
                            Artifact.Kind kind;
                            try {
                                kind = Artifact.Kind.valueOf(invocation.stringArgument("kind")
                                        .strip().toUpperCase(Locale.ROOT));
                            } catch (IllegalArgumentException | NullPointerException bad) {
                                return ToolResult.error("Unknown artifact 'kind'. Use one of "
                                        + List.of(Artifact.Kind.values()) + ".");
                            }
                            Artifact artifact = new Artifact(OpsStore.Ids.next("art"),
                                    context.tenantId(), context.executionId(), kind,
                                    invocation.stringArgument("title"),
                                    invocation.stringArgument("body"), Instant.now());
                            store.save(artifact);
                            context.event(Execution.Event.Type.ARTIFACT_CREATED,
                                    Map.of("artifactId", artifact.id(), "kind", kind.name(),
                                            "title", artifact.title()));
                            return ToolResult.ok("Saved artifact " + artifact.id() + ".");
                        })
                        .build());
    }
}
