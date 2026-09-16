package dev.agentkit.itops.domain;

import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.util.Frozen;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A proposed action stopped at the door, waiting for a person.
 *
 * <h2>It carries the invocation, not the parts of one (#170)</h2>
 *
 * <p>This held {@code String toolName} plus {@code Map arguments} — a {@link ToolInvocation}
 * taken apart, minus the call id — while its only production caller,
 * {@code Supervisor.park}, had a real {@code ToolInvocation} in hand and passed the pieces.
 * Three things followed from that, and all three are gone now that the whole value travels:
 *
 * <ul>
 *   <li><strong>A second full deep copy.</strong> {@code ToolInvocation} freezes its
 *       arguments in its own constructor; this record then froze the already-frozen map
 *       again. Measured as bytes allocated per call rather than as elapsed time — wall
 *       clock on a shared machine put the redundant share anywhere between 29% and 76% run
 *       to run, which is noise reported as a finding, while allocation is exact and
 *       repeated identically across three JVMs:
 *       <pre>
 * expanded nodes   ToolInvocation's freeze   this record's freeze   redundant
 *             26                    2,816 B                2,867 B         50%
 *          1,550                  149,968 B              151,168 B         50%
 *         73,000                6,758,992 B            6,696,592 B         50%
 *       </pre>
 *       Exactly half the deep-copy work on the path from a proposed call to a parked one
 *       was copying a snapshot that was already a snapshot, and it has to be half: the
 *       second walk visits the same expanded nodes as the first, so the two passes are the
 *       same work by construction. The reason to delete it is not the bytes but that it was
 *       a second statement of a rule {@code ToolInvocation} already states — #134's
 *       double-freeze, in this record.</li>
 *   <li><strong>A degree of freedom nothing needed.</strong> Nothing linked the recorded
 *       arguments to the invocation they came from, so this record held whatever it was
 *       handed. For a type whose whole job is "what a human is deciding about", the
 *       arguments and the tool name must be two views of one call rather than two
 *       parameters that happen to agree. They now cannot disagree.</li>
 *   <li><strong>The call id was dropped.</strong> Measured before this: the model's
 *       {@code tool_use} id appeared nowhere in the approval row and nowhere in the events
 *       either, so an approval could not be joined back to the specific call that raised it.
 *       {@link #callId()} recovers it.</li>
 * </ul>
 *
 * <p><strong>Which spelling of the tool name this now holds.</strong> It was
 * {@code tool.name()} — the name the <em>registry</em> resolved — and it is now
 * {@code invocation.name()}, the name the model asked for. Measured, they are the same
 * string on every path that reaches here: {@code Agent.runTool} resolves the tool with
 * {@code tools.find(invocation.name())}, both registries in the repository key that lookup
 * on {@code tool.name()} itself, and {@code GateResult.effectiveFor} refuses a replacement
 * that renames — so a gate cannot be handed a tool and an invocation naming different
 * things. That equality is now pinned by a test rather than left to reading, because it is
 * the whole of the argument that this substitution changes no value.
 *
 * <h2>The arguments are frozen, not {@code Map.copyOf}-ed (#132)</h2>
 *
 * <p>Now inherited rather than restated, which is the point of the change above.
 * {@link Map#copyOf} rejects a null <em>value</em> with a {@link NullPointerException}, and
 * {@code ToolInvocation} explicitly permits one — JSON {@code null} is a legal argument and
 * both it and {@code ToolUseBlock} say so. A model emitting <code>{"email": null}</code> on
 * an above-threshold tool therefore made this constructor throw from inside
 * {@code Supervisor.park}, {@code Agent.runTool} caught it as an ordinary
 * {@code RuntimeException}, and <strong>no approval was raised at all</strong> — the model
 * was told the tool had failed, which is something it can route around.
 *
 * <p>A control that silently does not engage on an input the framework calls legal is worse
 * than one that refuses loudly. {@link Frozen#deeply}, which {@code ToolInvocation} calls,
 * passes nulls through.
 *
 * <p>It also makes the recorded arguments a real snapshot, and that half is worth stating
 * accurately rather than dramatically. The substitution it would prevent — a gate chain
 * mutating a nested list after a reviewer has read it — is not reachable through the
 * production caller, because that caller's map is frozen before it ever gets here. It is a
 * type-level invariant, and it is now a stronger one: a caller cannot construct this record
 * from a mutable map at all, because the only way in is through a {@code ToolInvocation}
 * that has frozen it.
 *
 * <p>Everything a human needs in order to answer is captured at the moment the action was
 * proposed — the arguments, the supervisor's reasoning, the evidence the agent had, what
 * the effect will be and whether it can be undone. An approval card that says only
 * "run delete_user?" asks the reviewer to reconstruct the agent's reasoning from nothing,
 * which is how rubber-stamping starts.
 *
 * @param id           stable identifier
 * @param tenantId     tenant scope
 * @param executionId  the parked execution this will resume
 * @param invocation   the call as proposed, whole — its id, its tool name and its arguments
 *                     verbatim. See {@link #toolName()}, {@link #arguments()} and
 *                     {@link #callId()} for the views the web layer and the supervisor read
 * @param risk         the supervisor's graded risk, after any escalation
 * @param reason       why approval is required
 * @param effect       what will change if this is approved
 * @param reversible   whether it can be undone afterwards
 * @param evidence     what the agent had established before proposing this
 * @param state        pending until someone decides
 * @param requestedAt  when it was parked
 * @param decidedBy    who decided, once decided
 * @param decidedAt    when
 * @param decisionNote what they said
 */
public record ApprovalRequest(String id, String tenantId, String executionId,
                              ToolInvocation invocation, Risk risk, String reason,
                              String effect, boolean reversible, List<String> evidence,
                              State state, Instant requestedAt, String decidedBy,
                              Instant decidedAt, String decisionNote) {

    public enum State { PENDING, APPROVED, REJECTED }

    public ApprovalRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        // One null-check where there were two, and it covers the arguments as well as the
        // name: a ToolInvocation cannot exist with either missing.
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(effect, "effect");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }

    /**
     * The tool as it would be invoked.
     *
     * <p>Kept as an accessor rather than renaming every reader, because the web layer
     * renders this record through hand-built maps rather than field reflection — see
     * {@code WebServer.approvalJson} — so the wire format is a list of {@code json.put}
     * calls and changing the record's shape need not change a byte of it.
     */
    public String toolName() {
        return invocation.name();
    }

    /** The arguments as proposed, verbatim. */
    public Map<String, Object> arguments() {
        return invocation.arguments();
    }

    /**
     * The model's own id for the call this approval is about.
     *
     * <p>Recovered by #170 and dropped before it. It is what joins an approval to the
     * {@code TOOL_STARTED} event and to the transcript entry that proposed it; without it a
     * reviewer looking at two approvals for the same tool with the same arguments has
     * nothing that tells them apart but the timestamp.
     */
    public String callId() {
        return invocation.id();
    }

    public ApprovalRequest decided(State outcome, String who, String note) {
        return new ApprovalRequest(id, tenantId, executionId, invocation, risk, reason,
                effect, reversible, evidence, outcome, requestedAt, who, Instant.now(), note);
    }
}
