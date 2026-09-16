package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.domain.ApprovalRequest;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.domain.ToolInvocationRecord;
import dev.agentkit.itops.runtime.AuditObserver;
import dev.agentkit.itops.runtime.OpsContext;
import dev.agentkit.itops.runtime.Supervisor;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.tools.ToolCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The two audit records carry the {@link ToolInvocation}, not three loose fields cut out of
 * one (#170).
 *
 * <h2>What this file is, and is not</h2>
 *
 * <p>It is not a bug report. Measured before the change, on {@code main}: both
 * {@code ApprovalRequest} and {@code ToolInvocationRecord} were built by a caller that held
 * a real {@code ToolInvocation} and passed {@code name()} and {@code arguments()} out of it,
 * so the two halves already described the same call on every path that existed. Nothing a
 * user could do made them disagree. This is the shape change that makes disagreement
 * unrepresentable, plus two things that <em>were</em> measurable — a second deep copy of a
 * map that was already frozen, and a call id that was thrown away — and one latent
 * misreading of a JSON {@code null} that the shape change takes with it.
 *
 * <p>So the tests below split into three kinds, and each says which it is:
 *
 * <ul>
 *   <li><strong>Fails before, passes after.</strong> {@code isSameAs} on the arguments —
 *       the deleted copy is observable as object identity, which is the cheapest honest
 *       measurement of "no second snapshot was taken". And the JSON-{@code null} approval,
 *       which changes a destructive call's verdict.</li>
 *   <li><strong>Passes before and after, on purpose.</strong> The tool name the approval
 *       records, and which of a call's two invocations the audit row holds. Both are values
 *       this change moves the <em>source</em> of. A test that only passed afterwards would
 *       mean the change had altered what gets recorded, which is exactly what it must not
 *       do (#131, #180). These pin that it did not.</li>
 *   <li><strong>Does not compile before.</strong> {@link ApprovalRequest#callId()} and
 *       {@link ApprovalRequest#invocation()} did not exist. Said plainly rather than dressed
 *       up as a regression.</li>
 * </ul>
 *
 * <h2>Why identity rather than a call counter</h2>
 *
 * <p>Counting {@code Frozen.deeply} calls was tried first and does not work: {@code deeply}
 * is static and its output is a plain {@code LinkedHashMap}, so a counting {@code Map}
 * subclass passed in as the arguments is visited exactly once whether one copy is taken or
 * five — the second freeze walks the first freeze's output, not the instrumented input.
 * Object identity measures the same fact directly. Before the change
 * {@code approval.arguments() != invocation.arguments()}, because a copy stood between them;
 * after it they are one map.
 */
class RecordsCarryTheInvocationTest {

    private static final String TENANT = "acme";

    // ---------------------------------------------------------------- fixtures

    private record Fixture(OpsStore store, OpsContext context, Execution execution,
                           ToolRegistry registry, IdentityConnector identity) {}

    private static Fixture fixture(IdentityConnector identity) {
        OpsStore store = new OpsStore();
        Execution execution = store.createExecution(TENANT, "it-ops-agent",
                Execution.Trigger.CHAT, "manual", "Clean up the leaver.");
        OpsContext context = new OpsContext(TENANT, execution.id(), store);
        ToolRegistry registry = ToolCatalog.forExecution(new ServiceNowConnector(),
                "agentkit-integration", new DirectoryConnector(), identity, context, store);
        return new Fixture(store, context, execution, registry, identity);
    }

    private static Supervisor supervisor(Fixture fixture, ApprovalRequest preApproved) {
        return new Supervisor(Risk.HIGH, fixture.identity(), fixture.context(), fixture.store(),
                "Clean up the leaver.", null, preApproved);
    }

    // ------------------------------------------------------- the parked record

    /**
     * The approval a human decides from holds the call, and holds it once.
     *
     * <p><strong>Fails before the change.</strong> {@code Supervisor.park} passed
     * {@code invocation.arguments()} into a constructor that ran {@code Frozen.deeply} over
     * it again, so the approval's map was a distinct object with equal content. The
     * {@code isSameAs} below is that second copy, measured.
     */
    @Test
    void theApprovalHoldsTheInvocationItWasHandedRatherThanACopyOfItsParts() {
        Fixture fixture = fixture(new IdentityConnector());
        Tool tool = fixture.registry().find("identity.delete_user").orElseThrow();
        ToolInvocation invocation = new ToolInvocation("toolu_01", "identity.delete_user",
                Map.of("email", "alice@example.com"));

        GateResult verdict = supervisor(fixture, null).evaluate(tool, invocation);

        assertThat(verdict.allowed())
                .as("a DESTRUCTIVE call was not parked, so this test measured nothing")
                .isFalse();
        ApprovalRequest approval = fixture.store().approvals(TENANT).get(0);

        assertThat(approval.invocation())
                .as("the approval rebuilt a call instead of keeping the one it was handed")
                .isSameAs(invocation);
        assertThat(approval.arguments())
                .as("the approval's arguments were deep-copied a second time over a map "
                        + "ToolInvocation had already frozen")
                .isSameAs(invocation.arguments());
    }

    /**
     * The recorded tool name did not change when its source did.
     *
     * <p><strong>Passes before and after, and that is the point.</strong> {@code park} used
     * to record {@code tool.name()} — the name the registry resolved — and now records
     * {@code invocation.name()}, the name the model asked for. They are the same string on
     * every path that reaches a gate, and this pins it instead of leaving it to a paragraph:
     * a name only reaches a gate by having resolved a tool, and both registries key that
     * lookup on {@code tool.name()} itself.
     */
    @Test
    void theApprovalNamesTheSameToolItNamedBefore() {
        Fixture fixture = fixture(new IdentityConnector());
        Tool tool = fixture.registry().find("identity.delete_user").orElseThrow();
        ToolInvocation invocation = new ToolInvocation("toolu_01", "identity.delete_user",
                Map.of("email", "alice@example.com"));

        supervisor(fixture, null).evaluate(tool, invocation);
        ApprovalRequest approval = fixture.store().approvals(TENANT).get(0);

        assertThat(approval.toolName())
                .as("the approval no longer names the tool the registry resolved")
                .isEqualTo(tool.name())
                .isEqualTo(invocation.name());
        // Recovered by #170, and absent before it: an approval could not be joined back to
        // the call that raised it, nor to the TOOL_STARTED event filed under the same id.
        assertThat(approval.callId()).isEqualTo("toolu_01");
    }

    /**
     * The approval row and the event that announces it cannot name different calls.
     *
     * <p><strong>A mutation survivor, and the reason it is here.</strong> {@code park} used
     * to fill the {@code HUMAN_APPROVAL_REQUESTED} detail from its own local
     * {@code toolName} and from {@code invocation.arguments()}; it now reads both off the
     * {@code request} it has just built, so the event and the row a reviewer opens are two
     * views of one object. Nothing tested the detail map at all — replacing the tool with
     * the call id, and the arguments with an empty map, both left the suite green — so the
     * change had no guard and neither did the behaviour it preserves.
     */
    @Test
    void theEventAnnouncingAnApprovalCarriesTheSameCallTheApprovalDoes() {
        Fixture fixture = fixture(new IdentityConnector());
        Tool tool = fixture.registry().find("identity.delete_user").orElseThrow();
        ToolInvocation invocation = new ToolInvocation("toolu_01", "identity.delete_user",
                Map.of("email", "alice@example.com"));

        supervisor(fixture, null).evaluate(tool, invocation);
        ApprovalRequest approval = fixture.store().approvals(TENANT).get(0);
        Map<String, Object> detail = fixture.store().events(fixture.execution().id()).stream()
                .filter(event -> event.type() == Execution.Event.Type.HUMAN_APPROVAL_REQUESTED)
                .findFirst()
                .orElseThrow()
                .detail();

        assertThat(detail)
                .as("the event names a different tool, or different arguments, from the "
                        + "approval row a reviewer will open")
                .containsEntry("approvalId", approval.id())
                .containsEntry("tool", approval.toolName())
                .containsEntry("arguments", approval.arguments());
    }

    /**
     * What a reviewer is told the call will do, in the words the catalogue chose for it.
     *
     * <p><strong>A mutation survivor.</strong> {@code describeEffect} took the tool name as
     * a separate parameter and now asks the invocation for it, and its {@code switch} is the
     * one place the approval card's sentence comes from — yet switching it on
     * {@code invocation.id()}, which sends every branch to the {@code default} arm, left the
     * suite green. The effect is the whole of what distinguishes an approval card from
     * "run delete_user?", which the record's own javadoc calls the start of rubber-stamping.
     */
    @Test
    void theApprovalCardSaysWhatTheCallWillDoAndNotMerelyThatItRuns() {
        Fixture fixture = fixture(new IdentityConnector());
        Tool tool = fixture.registry().find("identity.delete_user").orElseThrow();

        supervisor(fixture, null).evaluate(tool, new ToolInvocation("toolu_01",
                "identity.delete_user", Map.of("email", "alice@example.com")));

        assertThat(fixture.store().approvals(TENANT).get(0).effect())
                .as("the approval card fell through to the generic arm and told the reviewer "
                        + "only that a tool would run")
                .isEqualTo("The account alice@example.com and its credentials are permanently "
                        + "removed. Group memberships are lost and cannot be reconstructed "
                        + "from the account.");
    }

    /**
     * An approval for one tool does not authorise another.
     *
     * <p><strong>A mutation survivor.</strong> {@code matchesApproved} compares the approved
     * tool name against the proposed one, and this change moved where the proposed name is
     * read from — {@code tool.name()} to {@code invocation.name()}. Making that comparison a
     * tautology left the suite green, which means the once-only pre-approval path had no
     * test that a <em>different</em> tool is refused. It is the check that stops a resumed
     * run spending a human's "yes" on something else.
     */
    @Test
    void aPreApprovalForOneToolDoesNotAuthoriseAnother() {
        Fixture fixture = fixture(new IdentityConnector());
        ToolInvocation approvedCall = new ToolInvocation("toolu_01", "identity.suspend_user",
                Map.of("email", "alice@example.com"));
        ApprovalRequest approved = new ApprovalRequest("apr-1", TENANT, fixture.execution().id(),
                approvedCall, Risk.HIGH, "why", "suspends", true, List.of(),
                ApprovalRequest.State.APPROVED, Instant.now(), "alice", Instant.now(), "ok");

        Tool other = fixture.registry().find("identity.delete_user").orElseThrow();
        GateResult verdict = supervisor(fixture, approved).evaluate(other,
                new ToolInvocation("toolu_02", "identity.delete_user",
                        Map.of("email", "alice@example.com")));

        assertThat(verdict.allowed())
                .as("an approval to suspend an account authorised deleting it")
                .isFalse();
        assertThat(verdict.reason())
                .as("the call was refused, but as an already-approved repeat rather than as "
                        + "a call nobody approved — which would mean the names did match")
                .contains("submitted for review");
    }

    /**
     * A name that resolved a tool is that tool's own name — for every tool in the module.
     *
     * <p>The premise the test above rests on, asserted over the whole catalogue rather than
     * the one tool it happens to use, and asserted again against a live gate call so that a
     * registry which someday resolved names loosely — case-insensitively, by alias — would
     * fail here rather than silently changing what an approval records.
     */
    @Test
    void aGateIsNeverHandedAToolWhoseNameIsNotTheInvocationsName() {
        Fixture fixture = fixture(new IdentityConnector());
        for (Tool tool : fixture.registry().tools()) {
            assertThat(fixture.registry().find(tool.name()).orElseThrow().name())
                    .as("the registry resolved '%s' to a tool with another name", tool.name())
                    .isEqualTo(tool.name());
        }

        List<String> pairs = new ArrayList<>();
        ToolGate recording = (tool, invocation) -> {
            pairs.add(tool.name() + '|' + invocation.name());
            return GateResult.allow();
        };
        runAgent(recording, new Reader(new ArrayList<>()));

        assertThat(pairs)
                .as("the gate saw a tool and an invocation naming different things")
                .containsExactly("identity.get_group_members|identity.get_group_members");
    }

    // -------------------------------------------------------- the audit record

    /**
     * The row still reports what ran, not what was asked for.
     *
     * <p><strong>Passes before and after, deliberately.</strong> #131 and #180 settled that
     * {@code onToolResult} reports the call the gate settled on while
     * {@code onToolProposed} reports the proposal, and handing the record a whole
     * {@code ToolInvocation} is a chance to re-answer that by accident. It does not: the row
     * is the narrowed call, and it is that call's own object rather than a rebuild of it.
     * The {@code isSameAs} half fails before the change; the {@code containsEntry} half is
     * what {@code AuditRowShowsWhatRanTest} already guards and is repeated here because it
     * is this diff's risk, not that one's.
     */
    @Test
    void theAuditRowStillHoldsTheCallTheGateSettledOn() {
        AtomicReference<ToolInvocation> settled = new AtomicReference<>();
        ToolGate narrowing = (tool, invocation) -> {
            ToolInvocation replacement = new ToolInvocation(invocation.id(), invocation.name(),
                    Map.of("group", "Employees-All"));
            settled.set(replacement);
            return GateResult.allowWith(replacement);
        };
        List<String> received = new ArrayList<>();

        List<ToolInvocationRecord> rows = runAgent(narrowing, new Reader(received));

        assertThat(received)
                .as("the gate did not narrow anything, so this test measured nothing")
                .containsExactly("Employees-All");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).arguments())
                .as("the row named the group the model proposed rather than the one that ran")
                .containsEntry("group", "Employees-All");
        assertThat(rows.get(0).invocation())
                .as("the row rebuilt the settled call instead of holding it")
                .isSameAs(settled.get());
        assertThat(rows.get(0).arguments())
                .as("the row's arguments were frozen a second time over an already-frozen map")
                .isSameAs(settled.get().arguments());
        assertThat(rows.get(0).callId()).isEqualTo("c1");
    }

    // ------------------------------------------- the fourth spelling of a read

    /**
     * An approved call whose {@code email} is JSON {@code null} is no longer read as the
     * four-character string {@code "null"}.
     *
     * <p><strong>Fails before the change, and it is a verdict that flips — but it was
     * latent, not live.</strong> {@code Supervisor.conditionsChanged} spelled the read
     * {@code String.valueOf(preApproved.arguments().get("email"))}, a fourth spelling of
     * {@code ToolInvocation.stringArgument} that cannot tell a JSON {@code null} from the
     * string {@code "null"}. With an account whose email is literally {@code "null"} in the
     * directory, the re-check answered "the account still exists", the approval stood, and
     * {@code identity.delete_user} — the one irreversible operation in the catalogue — ran
     * with no email at all. It needs that account to exist: the demo tenant seeds none and
     * no tool in the catalogue creates one, which is why this is filed as latent.
     *
     * <p><strong>What the repair does and does not do.</strong> It removes the second
     * spelling. It does not close the class: {@code IdentityConnector.findUser} normalises a
     * null email to {@code ""}, so an account keyed {@code ""} reproduces the same wrong
     * answer through the new spelling. Closing it properly means refusing an approved
     * destructive call that names no account, which is a new control rather than #170's
     * shape change, and is left for its own issue.
     */
    @Test
    void anApprovalWhoseEmailIsJsonNullNoLongerMatchesAnAccountNamedNull() {
        IdentityConnector identity = new IdentityConnector(
                List.of(new IdentityConnector.User("null", "Not A Leaver", "ACTIVE", false)),
                List.of(new IdentityConnector.GroupSeed("Employees-All", false, Set.of())));
        Fixture fixture = fixture(identity);
        Tool tool = fixture.registry().find("identity.delete_user").orElseThrow();

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("email", null);
        ToolInvocation invocation = new ToolInvocation("toolu_01", "identity.delete_user", arguments);
        ApprovalRequest approved = new ApprovalRequest("apr-1", TENANT, fixture.execution().id(),
                invocation, Risk.DESTRUCTIVE, "why", "deletes", false, List.of(),
                ApprovalRequest.State.APPROVED, Instant.now(), "alice", Instant.now(), "ok");

        GateResult verdict = supervisor(fixture, approved).evaluate(tool, invocation);

        assertThat(verdict.allowed())
                .as("an irreversible call with no email was allowed because String.valueOf "
                        + "turned the absent argument into the name of a real account")
                .isFalse();
        assertThat(verdict.reason()).contains("Conditions changed since approval");
    }

    /**
     * The one null-check that replaced two still runs.
     *
     * <p><strong>A mutation survivor, and one this change is directly responsible for.</strong>
     * Each record used to check {@code toolName} and {@code arguments} separately; each now
     * checks {@code invocation} once, because a {@code ToolInvocation} cannot exist with
     * either missing. That is a fair trade only if the remaining check is real, and deleting
     * it from either record left the suite green. Without it a null travels as far as the
     * first {@code toolName()} call — which for {@code ApprovalRequest} is the operator
     * console rendering a row, a long way from the constructor that accepted it.
     */
    @Test
    void neitherRecordCanBeBuiltWithoutACall() {
        assertThatThrownBy(() -> new ApprovalRequest("apr-1", TENANT, "exec-1", null,
                Risk.HIGH, "why", "effect", true, List.of(), ApprovalRequest.State.PENDING,
                Instant.now(), null, null, null))
                .as("an approval was built around no call at all")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("invocation");

        assertThatThrownBy(() -> new ToolInvocationRecord("call-1", "exec-1", null,
                Risk.HIGH, Risk.HIGH, "OK", null, "idem-1", Instant.now(), Instant.now(),
                false, "done"))
                .as("an audit row was built around no call at all")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("invocation");
    }

    // -------------------------------------------------------------- the runner

    /** Records what it was handed, so "did the narrowing reach the tool" is answerable. */
    private record Reader(List<String> received) implements Tool {
        public String name() {
            return "identity.get_group_members";
        }

        public String description() {
            return "lists a group's members";
        }

        public Map<String, Object> inputSchema() {
            return Map.of("type", "object");
        }

        public ToolResult execute(ToolInvocation invocation) {
            received.add(String.valueOf(invocation.arguments().get("group")));
            return ToolResult.ok("bob@example.com");
        }
    }

    /** Asks for one group, then stops. Core's FakeLlmClient is test-scoped to core. */
    private static final class AsksForOneGroup implements LlmClient {
        private boolean asked;

        @Override
        public LlmResponse generate(LlmRequest request) {
            if (asked) {
                return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of("done")),
                        LlmStopReason.END_TURN, TokenUsage.ZERO);
            }
            asked = true;
            return LlmResponse.of(Message.of(Role.ASSISTANT,
                            ProposedCall.of("c1", "identity.get_group_members",
                                    Map.of("group", "Production-Administrators"))),
                    LlmStopReason.TOOL_USE, TokenUsage.ZERO);
        }
    }

    private static List<ToolInvocationRecord> runAgent(ToolGate gate, Reader tool) {
        OpsStore store = new OpsStore();
        Execution execution = store.createExecution(TENANT, "it-ops-agent",
                Execution.Trigger.CHAT, "manual", "List the administrators.");
        OpsContext context = new OpsContext(TENANT, execution.id(), store);

        Agent.builder(new AsksForOneGroup(),
                        new SimpleToolRegistry().register(tool),
                        AgentConfig.builder("m").maxSteps(3).build())
                .toolGate(gate)
                .observer(new AuditObserver(context, store))
                .build()
                .run(Goal.of("list the administrators"));

        return store.invocations(execution.id());
    }
}
