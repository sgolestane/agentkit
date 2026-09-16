package dev.agentkit.workbench.tools;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.workbench.domain.CapabilityGap;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.runtime.AnswerBox;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.RunContext;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Tools that act on the workbench itself rather than on the ALM.
 *
 * <p>Three of them, each turning something the agent would otherwise merely <em>say</em>
 * into something the platform records and acts on: a question becomes a parked item a
 * person answers, a missing capability becomes a product-discovery row, and a reusable fact
 * becomes a durable lesson. When a model's conclusion has consequences, it arrives through
 * the tool boundary like everything else.
 */
public final class WorkbenchTools {

    /** Named once; the supervisor's gate and the resume path both key on it. */
    public static final String ASK_HUMAN = "ask_human";
    public static final String REPORT_GAP = "report_capability_gap";
    public static final String SAVE_LEARNING = "save_learning";

    private WorkbenchTools() {
    }

    public static List<ToolPolicy> policies() {
        return List.of(
                // A question reaches a person and pauses the run; the gate is what enforces
                // that, the policy row is what grades it for the audit trail.
                ToolPolicy.write(ASK_HUMAN, "workbench.escalation", "workbench",
                        dev.agentkit.workbench.domain.Risk.LOW, true, true),
                ToolPolicy.read(REPORT_GAP, "workbench.discovery", "workbench"),
                ToolPolicy.read(SAVE_LEARNING, "workbench.learning", "workbench"),
                // The framework's own discovery tool, declared so the unknown-tool fallback
                // does not park the run on its first search.
                ToolPolicy.read(dev.agentkit.core.tool.DisclosingToolRegistry
                        .DEFAULT_SEARCH_TOOL_NAME, "workbench.tool_discovery", "workbench"));
    }

    public static List<Tool> of(RunContext context, WorkbenchStore store, Learnings learnings,
            AnswerBox answers) {
        return List.of(
                FunctionTool.builder(ASK_HUMAN,
                                "capability: workbench.escalation. Ask the supervising human a "
                                        + "question you cannot answer from the ticket, the "
                                        + "learnings or the systems you can reach — which system "
                                        + "to use, which of two people is meant, whether an "
                                        + "ambiguous request is intended. The run pauses until "
                                        + "they answer.")
                        .schema(Map.of("type", "object", "properties", Map.of(
                                        "question", Map.of("type", "string",
                                                "description", "The question, specific enough "
                                                        + "to be answered in one sentence.")),
                                "required", List.of("question")))
                        // The answer is a person's free text entering the model's context.
                        .provenance(Provenance.THIRD_PARTY)
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(invocation -> {
                            // Reached only when the gate let it through, which it does only
                            // while answers remain — see Supervisor. Each answer is handed
                            // back exactly once, fenced.
                            AnswerBox.Answer answer = answers.consume();
                            if (answer == null) {
                                return ToolResult.error("No answer is available; this question "
                                        + "should have been routed to a person.");
                            }
                            context.event(Run.Event.Type.HUMAN_ANSWERED,
                                    Map.of("question", answer.question()));
                            context.reading("operator answer: " + answer.answer());
                            return ToolResult.ok("The operator answered:\n"
                                    + Spotlight.wrap(Spotlight.Kind.EVIDENCE,
                                            Source.of("operator"), answer.answer()));
                        })
                        .build(),

                FunctionTool.builder(REPORT_GAP,
                                "capability: workbench.discovery. Report that resolving this "
                                        + "ticket needs a connection, tool or action the "
                                        + "workbench does not have. The request is recorded with "
                                        + "the ticket attached and reviewed by the product team.")
                        .schema(Map.of("type", "object", "properties", Map.of(
                                        "capability", Map.of("type", "string",
                                                "description", "Short name for what is missing, "
                                                        + "e.g. okta.group_membership."),
                                        "description", Map.of("type", "string",
                                                "description", "What the ticket needed it for.")),
                                "required", List.of("capability", "description")))
                        // Writes only inside the platform's own store; repeating it is harmless.
                        .sideEffects(SideEffects.IDEMPOTENT)
                        .provenance(Provenance.FIRST_PARTY)
                        .handler(invocation -> {
                            String capability = invocation.stringArgument("capability");
                            if (capability == null || capability.isBlank()) {
                                return ToolResult.error("A 'capability' name is required.");
                            }
                            String ticketKey = String.valueOf(context.fact("ticketKey"));
                            CapabilityGap gap = new CapabilityGap(
                                    WorkbenchStore.Ids.next("gap"), context.tenantId(),
                                    context.runId(), ticketKey,
                                    Spotlight.name(capability),
                                    invocation.stringArgument("description"), Instant.now());
                            store.save(gap);
                            context.event(Run.Event.Type.CAPABILITY_GAP_REPORTED,
                                    Map.of("capability", gap.capability(), "gapId", gap.id()));
                            return ToolResult.ok("Recorded. The missing capability has been "
                                    + "reported with this ticket attached; say so on the ticket "
                                    + "and leave it for a person.");
                        })
                        .build(),

                FunctionTool.builder(SAVE_LEARNING,
                                "capability: workbench.learning. Save one reusable fact about "
                                        + "this customer's environment — a system of record, a "
                                        + "naming convention, a process rule — so future runs "
                                        + "know it without asking. One sentence per call. Never "
                                        + "save secrets or personal data.")
                        .schema(Map.of("type", "object", "properties", Map.of(
                                        "lesson", Map.of("type", "string",
                                                "description", "The fact, as one sentence.")),
                                "required", List.of("lesson")))
                        .sideEffects(SideEffects.IDEMPOTENT)
                        .provenance(Provenance.FIRST_PARTY)
                        .handler(invocation -> {
                            String lesson = invocation.stringArgument("lesson");
                            if (lesson == null || lesson.isBlank()) {
                                return ToolResult.error("A 'lesson' is required.");
                            }
                            learnings.record(lesson);
                            context.event(Run.Event.Type.LEARNING_RECORDED, Map.of());
                            return ToolResult.ok("Saved.");
                        })
                        .build());
    }
}
