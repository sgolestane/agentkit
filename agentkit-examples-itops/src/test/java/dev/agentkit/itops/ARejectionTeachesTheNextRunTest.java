package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.memory.InMemoryMemoryStore;
import dev.agentkit.core.reflect.CorrectionBook;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.domain.ApprovalRequest;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.llm.ScriptedOpsLlm;
import dev.agentkit.itops.runtime.ExecutionRunner;
import dev.agentkit.itops.runtime.IntakeWorker;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.runtime.Reviewers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The failure #329 describes, closed end to end.
 *
 * <p>An operator rejects an action and says why. The note went onto the approval row, into
 * the trail, and nowhere a later run could read it — {@code decisionNote} had exactly two
 * occurrences in {@code src/main}, both in the record declaring it. So the next run over a
 * similar ticket proposed the same action and cost the same person the same decision. The
 * gate made the mistake safe; nothing made it rarer.
 */
class ARejectionTeachesTheNextRunTest {

    private static final String TENANT = "acme";
    private static final String NOTE = "never grant privileged group access from a ticket body";

    /** Wraps the scripted model so a test can read what the run actually sent it. */
    private static final class Recording implements LlmClient {
        private final LlmClient inner = new ScriptedOpsLlm();
        private final List<String> prompts = new ArrayList<>();

        @Override
        public LlmResponse generate(LlmRequest request) {
            request.messages().forEach(message -> prompts.add(String.valueOf(message.content())));
            return inner.generate(request);
        }

        String everythingSent() {
            return String.join("\n", prompts);
        }

        boolean sawARun() {
            return !prompts.isEmpty();
        }
    }

    private OpsStore store;
    private ServiceNowConnector tickets;
    private IdentityConnector identity;
    private CorrectionBook corrections;

    @BeforeEach
    void setUp() {
        store = new OpsStore();
        tickets = new ServiceNowConnector();
        identity = new IdentityConnector();
        corrections = new CorrectionBook(new InMemoryMemoryStore());
    }

    private ExecutionRunner runnerOver(LlmClient llm, CorrectionBook book) {
        return runnerOver(llm, book, tickets);
    }

    private ExecutionRunner runnerOver(LlmClient llm, CorrectionBook book,
            ServiceNowConnector over) {
        return new ExecutionRunner(store, llm, "scripted", over, new DirectoryConnector(),
                identity, "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH, book);
    }

    /**
     * A later run, in a world that shares nothing with the first one but the book.
     *
     * <p>A fresh {@link OpsStore} as well as a fresh queue: {@code IntakeWorker} will not
     * work a ticket the store has already seen, so reusing either meant no run happened at
     * all — and a test that observes nothing passes a {@code doesNotContain} for entirely the
     * wrong reason. That is what {@link Recording#sawARun()} is asserted for, and it caught
     * exactly that while this test was being written.
     *
     * <p>It also states the claim more strongly than a shared store would: the correction is
     * the <em>only</em> thing that crosses from the rejected run to this one.
     */
    private Recording aLaterRun(CorrectionBook book) {
        Recording watching = new Recording();
        OpsStore freshStore = new OpsStore();
        ServiceNowConnector freshQueue = new ServiceNowConnector();
        ExecutionRunner later = new ExecutionRunner(freshStore, watching, "scripted",
                freshQueue, new DirectoryConnector(), new IdentityConnector(),
                "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH, book);
        new IntakeWorker(freshStore, freshQueue, later, TENANT, "it-ops-agent")
                .tick("AgentKit", Duration.ofHours(1), 10);
        assertThat(watching.sawARun()).as("the later run never happened").isTrue();
        return watching;
    }

    private ApprovalRequest pending(ExecutionRunner runner) {
        new IntakeWorker(store, tickets, runner, TENANT, "it-ops-agent")
                .tick("AgentKit", Duration.ofHours(1), 10);
        return store.approvals(TENANT).stream()
                .filter(approval -> approval.state() == ApprovalRequest.State.PENDING)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no pending approval; approvals were " + store.approvals(TENANT)));
    }

    @Test
    @DisplayName("what an operator said when they refused reaches the next run")
    void theNoteReachesALaterRun() {
        ApprovalRequest parked = pending(runnerOver(new ScriptedOpsLlm(), corrections));
        runnerOver(new ScriptedOpsLlm(), corrections)
                .resume(TENANT, parked.id(), "sam@example.com", false, NOTE);

        // A later run, with its own runner and its own ticket queue -- nothing of the
        // rejected run is still alive. Only the book crosses.
        Recording watching = aLaterRun(corrections);

        assertThat(watching.everythingSent())
                .as("the operator's reason did not reach the run that repeats the mistake")
                .contains(NOTE);
    }

    /**
     * The negative, and the reason this test file is not one test.
     *
     * <p>Without the book the note goes nowhere, which is both the behaviour before #329 and
     * the behaviour a deployment still gets by passing {@code null}. Without this, a
     * {@code contains(NOTE)} above would pass just as well if the note reached the prompt by
     * some route that has nothing to do with the correction book — the goal text, say, or
     * the ticket body.
     */
    @Test
    @DisplayName("with no book, the note still goes nowhere")
    void withoutTheBookNothingIsRemembered() {
        ApprovalRequest parked = pending(runnerOver(new ScriptedOpsLlm(), null));
        runnerOver(new ScriptedOpsLlm(), null)
                .resume(TENANT, parked.id(), "sam@example.com", false, NOTE);

        Recording watching = aLaterRun(null);

        assertThat(watching.everythingSent()).doesNotContain(NOTE);
    }

    /** It is filed under the capability, so a sibling tool in the same family is covered. */
    @Test
    @DisplayName("the correction is filed under the capability, not the tool name")
    void itIsFiledUnderTheCapability() {
        ApprovalRequest parked = pending(runnerOver(new ScriptedOpsLlm(), corrections));
        runnerOver(new ScriptedOpsLlm(), corrections)
                .resume(TENANT, parked.id(), "sam@example.com", false, NOTE);

        String capability = dev.agentkit.itops.tools.ToolCatalog
                .policyOrUnknown(parked.toolName()).capability();
        assertThat(corrections.recall(TENANT, List.of(capability)))
                .as("filed somewhere the next run over this capability will not look")
                .singleElement()
                .satisfies(correction -> {
                    assertThat(correction.note()).isEqualTo(NOTE);
                    assertThat(correction.decidedBy())
                            .as("an operator identifier is an email address, and coercing it"
                                    + " to 'unknown' would name nobody -- which is the whole"
                                    + " of what attribution buys")
                            .isEqualTo("sam.example.com");
                });
        assertThat(capability)
                .as("the tool name is not the key")
                .isNotEqualTo(parked.toolName());
    }

    /**
     * The resume path does not argue with the person who just approved.
     *
     * <p>The resumed goal already says "a human has approved this action, perform it". An
     * advisory from the same capability saying somebody once refused it is the stalest
     * possible advice to set against that — the operator's current answer for this exact
     * capability is the approval sitting in the same prompt.
     */
    @Test
    @DisplayName("a resumed run is not advised against the capability just approved")
    void aResumedRunIsNotAdvisedAgainstWhatWasApproved() {
        ApprovalRequest first = pending(runnerOver(new ScriptedOpsLlm(), corrections));
        String capability = dev.agentkit.itops.tools.ToolCatalog
                .policyOrUnknown(first.toolName()).capability();
        runnerOver(new ScriptedOpsLlm(), corrections)
                .resume(TENANT, first.id(), "sam@example.com", false, NOTE);
        assertThat(corrections.recall(TENANT, List.of(capability))).hasSize(1);

        // A second ticket parks on the same capability, and this time a person approves.
        OpsStore freshStore = new OpsStore();
        ServiceNowConnector freshQueue = new ServiceNowConnector();
        Recording watching = new Recording();
        ExecutionRunner later = new ExecutionRunner(freshStore, watching, "scripted",
                freshQueue, new DirectoryConnector(), new IdentityConnector(),
                "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH, corrections);
        new IntakeWorker(freshStore, freshQueue, later, TENANT, "it-ops-agent")
                .tick("AgentKit", Duration.ofHours(1), 10);
        ApprovalRequest parked = freshStore.approvals(TENANT).stream()
                .filter(a -> a.state() == ApprovalRequest.State.PENDING)
                .filter(a -> dev.agentkit.itops.tools.ToolCatalog
                        .policyOrUnknown(a.toolName()).capability().equals(capability))
                .findFirst().orElseThrow();

        Recording onResume = new Recording();
        new ExecutionRunner(freshStore, onResume, "scripted", freshQueue,
                new DirectoryConnector(), new IdentityConnector(), "agentkit-integration",
                Reviewers.goalAlignment(), Risk.HIGH, corrections)
                .resume(TENANT, parked.id(), "sam@example.com", true, "Confirmed with HR.");

        assertThat(onResume.sawARun()).as("the resume never ran").isTrue();
        assertThat(onResume.everythingSent())
                .as("the run was told to perform an action and advised against it in one"
                        + " prompt")
                .doesNotContain(NOTE);
    }

    /**
     * One tenant's operator notes do not reach another tenant's prompt.
     *
     * <p>Through a <strong>second tenant's run</strong>, not through {@code CorrectionBook}
     * directly. The first version asserted {@code recall("globex", ...)} at the book, which
     * {@code CorrectionBookTest} already covers verbatim — so it said nothing about the
     * wiring, and hardcoding the tenant at both call sites in {@code ExecutionRunner} left
     * the entire itops suite green. The tenant being threaded from the execution row is the
     * thing that was broken and the thing this has to pin.
     */
    @Test
    @DisplayName("a correction does not cross tenants")
    void aCorrectionDoesNotCrossTenants() {
        ApprovalRequest parked = pending(runnerOver(new ScriptedOpsLlm(), corrections));
        runnerOver(new ScriptedOpsLlm(), corrections)
                .resume(TENANT, parked.id(), "sam@example.com", false, NOTE);

        // A different tenant's world, working its own tickets, sharing only the book.
        Recording watching = new Recording();
        OpsStore otherStore = new OpsStore();
        ServiceNowConnector otherQueue = new ServiceNowConnector();
        ExecutionRunner other = new ExecutionRunner(otherStore, watching, "scripted",
                otherQueue, new DirectoryConnector(), new IdentityConnector(),
                "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH, corrections);
        new IntakeWorker(otherStore, otherQueue, other, "globex", "it-ops-agent")
                .tick("AgentKit", Duration.ofHours(1), 10);

        assertThat(watching.sawARun()).as("the other tenant's run never happened").isTrue();
        assertThat(watching.everythingSent())
                .as("one tenant's operator spoke into another tenant's prompt")
                .doesNotContain(NOTE);
        // And the control: the tenant that recorded it is still told.
        assertThat(aLaterRun(corrections).everythingSent()).contains(NOTE);
    }

    /**
     * The tenant a refusal is filed under is the one whose approval it was.
     *
     * <p>The other half of the threading, and it needs its own rejection under a second
     * tenant: every test here refuses as {@code acme} and reads back as {@code acme}, so
     * hardcoding the tenant at the <em>record</em> call site passed the whole suite even
     * after the recall side was pinned.
     */
    @Test
    @DisplayName("a refusal is filed under the tenant whose approval it was")
    void aRefusalIsFiledUnderItsOwnTenant() {
        OpsStore otherStore = new OpsStore();
        ServiceNowConnector otherQueue = new ServiceNowConnector();
        ExecutionRunner other = new ExecutionRunner(otherStore, new ScriptedOpsLlm(),
                "scripted", otherQueue, new DirectoryConnector(), new IdentityConnector(),
                "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH, corrections);
        new IntakeWorker(otherStore, otherQueue, other, "globex", "it-ops-agent")
                .tick("AgentKit", Duration.ofHours(1), 10);
        ApprovalRequest parked = otherStore.approvals("globex").stream()
                .filter(a -> a.state() == ApprovalRequest.State.PENDING)
                .findFirst().orElseThrow();

        other.resume("globex", parked.id(), "sam@example.com", false, NOTE);

        String capability = dev.agentkit.itops.tools.ToolCatalog
                .policyOrUnknown(parked.toolName()).capability();
        assertThat(corrections.recall("globex", List.of(capability)))
                .as("globex's refusal was not filed under globex")
                .hasSize(1);
        assertThat(corrections.recall(TENANT, List.of(capability)))
                .as("globex's operator was filed under acme")
                .isEmpty();
    }

    /** The read is on the row, not only the write. */
    @Test
    @DisplayName("being advised is recorded in the audit trail")
    void beingAdvisedIsRecorded() {
        ApprovalRequest parked = pending(runnerOver(new ScriptedOpsLlm(), corrections));
        runnerOver(new ScriptedOpsLlm(), corrections)
                .resume(TENANT, parked.id(), "sam@example.com", false, NOTE);

        OpsStore freshStore = new OpsStore();
        ServiceNowConnector freshQueue = new ServiceNowConnector();
        ExecutionRunner later = new ExecutionRunner(freshStore, new ScriptedOpsLlm(),
                "scripted", freshQueue, new DirectoryConnector(), new IdentityConnector(),
                "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH, corrections);
        new IntakeWorker(freshStore, freshQueue, later, TENANT, "it-ops-agent")
                .tick("AgentKit", Duration.ofHours(1), 10);

        assertThat(freshStore.executions(TENANT).stream()
                .flatMap(e -> freshStore.events(e.id()).stream())
                .filter(event -> event.type()
                        == dev.agentkit.itops.domain.Execution.Event.Type.CORRECTIONS_RECALLED)
                .toList())
                .as("nothing on the row says this run was advised, or by whom")
                .isNotEmpty()
                // All three fields. Asserting only `from` left `count` and `areas`
                // falsifiable without a test noticing -- and the "which correction" half is
                // exactly what this event's javadoc says it exists to answer.
                .allSatisfy(event -> {
                    assertThat(event.detail().get("from"))
                            .isEqualTo("operator:sam.example.com");
                    assertThat(event.detail().get("count")).isEqualTo("1");
                    assertThat(event.detail().get("areas")).isEqualTo(
                            dev.agentkit.itops.tools.ToolCatalog
                                    .policyOrUnknown(parked.toolName()).capability());
                });
    }

    /**
     * A refusal filed under the fallback capability is still recalled.
     *
     * <p>{@code policyOrUnknown} files an undeclared tool's refusal under
     * {@code UNKNOWN_CAPABILITY}, and {@code policies()} contains no policy with it — so
     * without adding it to the recall list a correction could be written and then never
     * read, which is the state #329 exists to end. Not reachable through a run today,
     * because every registered tool has a declared policy; recorded directly, because the
     * gap is in which areas the run asks for.
     */
    @Test
    @DisplayName("a refusal under the fallback capability is not written-only")
    void aRefusalUnderTheFallbackCapabilityIsRecalled() {
        corrections.record(TENANT, dev.agentkit.itops.tools.ToolCatalog.UNKNOWN_CAPABILITY,
                "sam@example.com", NOTE);

        assertThat(aLaterRun(corrections).everythingSent())
                .as("a refusal on an undeclared tool is recorded and never read")
                .contains(NOTE);
    }

    /**
     * A standing refusal actually stops the repeat, which advice alone could not.
     *
     * <p>This is why the gate exists. {@code ExecutionRunner.SYSTEM_PROMPT} tells the model
     * that a privileged change needing approval "is not your cue to stop: propose the change
     * anyway", and closes off NEEDS_HUMAN unless it cannot even say what the change should
     * be. A recorded objection folded into the user turn as advice loses to that — the run
     * proposes, parks, and costs the same person the same decision, which is the cost #329
     * exists to reduce. A gate does not lose to a system prompt.
     */
    @Test
    @DisplayName("a standing refusal stops the next run rather than parking it again")
    void aStandingRefusalStopsTheRepeat() {
        ApprovalRequest parked = pending(runnerOver(new ScriptedOpsLlm(), corrections));
        String capability = dev.agentkit.itops.tools.ToolCatalog
                .policyOrUnknown(parked.toolName()).capability();
        runnerOver(new ScriptedOpsLlm(), corrections)
                .resume(TENANT, parked.id(), "sam@example.com", false, NOTE, true);

        assertThat(pendingApprovalsOfALaterRun())
                .as("the run parked again on the capability a person refused and meant it")
                .noneMatch(a -> dev.agentkit.itops.tools.ToolCatalog
                        .policyOrUnknown(a.toolName()).capability().equals(capability));
    }

    /**
     * The negative that makes the one above mean something.
     *
     * <p>Identical but for the flag. Without it the later run still parks on that
     * capability, so the test above is measuring the gate rather than some other reason the
     * work did not happen.
     */
    @Test
    @DisplayName("an ordinary rejection leaves the next run free to ask again")
    void anOrdinaryRejectionStillParks() {
        ApprovalRequest parked = pending(runnerOver(new ScriptedOpsLlm(), corrections));
        String capability = dev.agentkit.itops.tools.ToolCatalog
                .policyOrUnknown(parked.toolName()).capability();
        runnerOver(new ScriptedOpsLlm(), corrections)
                .resume(TENANT, parked.id(), "sam@example.com", false, NOTE, false);

        assertThat(pendingApprovalsOfALaterRun())
                .as("a routine rejection silently became policy")
                .anyMatch(a -> dev.agentkit.itops.tools.ToolCatalog
                        .policyOrUnknown(a.toolName()).capability().equals(capability));
    }

    /** And lifting it puts the capability back. */
    @Test
    @DisplayName("lifting a standing refusal lets the work happen again")
    void liftingLetsTheWorkHappenAgain() {
        ApprovalRequest parked = pending(runnerOver(new ScriptedOpsLlm(), corrections));
        String capability = dev.agentkit.itops.tools.ToolCatalog
                .policyOrUnknown(parked.toolName()).capability();
        ExecutionRunner runner = runnerOver(new ScriptedOpsLlm(), corrections);
        runner.resume(TENANT, parked.id(), "sam@example.com", false, NOTE, true);

        assertThat(runner.liftStandingRefusal(TENANT, capability)).contains(1);

        assertThat(pendingApprovalsOfALaterRun())
                .as("the capability stayed refused after an operator lifted it")
                .anyMatch(a -> dev.agentkit.itops.tools.ToolCatalog
                        .policyOrUnknown(a.toolName()).capability().equals(capability));
    }

    /** A later run in its own world, and what it asked a person for. */
    private List<ApprovalRequest> pendingApprovalsOfALaterRun() {
        OpsStore freshStore = new OpsStore();
        ServiceNowConnector freshQueue = new ServiceNowConnector();
        ExecutionRunner later = new ExecutionRunner(freshStore, new ScriptedOpsLlm(),
                "scripted", freshQueue, new DirectoryConnector(), new IdentityConnector(),
                "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH, corrections);
        new IntakeWorker(freshStore, freshQueue, later, TENANT, "it-ops-agent")
                .tick("AgentKit", Duration.ofHours(1), 10);
        return freshStore.approvals(TENANT).stream()
                .filter(a -> a.state() == ApprovalRequest.State.PENDING)
                .toList();
    }

    /**
     * The gate's tenant comes from the run, not from a constant.
     *
     * <p>Replacing {@code running.tenantId()} at the gate with {@code "acme"} left all 187
     * itops tests green — one tenant's standing refusal would gate every tenant's runs. The
     * advisory path had this pinned; the enforcement path did not, which is the same defect
     * one layer down from the one this file already records.
     */
    @Test
    @DisplayName("one tenant's standing refusal does not gate another tenant's run")
    void aStandingRefusalDoesNotCrossTenants() {
        ApprovalRequest parked = pending(runnerOver(new ScriptedOpsLlm(), corrections));
        String capability = dev.agentkit.itops.tools.ToolCatalog
                .policyOrUnknown(parked.toolName()).capability();
        runnerOver(new ScriptedOpsLlm(), corrections)
                .resume(TENANT, parked.id(), "sam@example.com", false, NOTE, true);

        // globex refused nothing, so globex's run must still be able to ask.
        OpsStore otherStore = new OpsStore();
        ServiceNowConnector otherQueue = new ServiceNowConnector();
        ExecutionRunner other = new ExecutionRunner(otherStore, new ScriptedOpsLlm(),
                "scripted", otherQueue, new DirectoryConnector(), new IdentityConnector(),
                "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH, corrections);
        new IntakeWorker(otherStore, otherQueue, other, "globex", "it-ops-agent")
                .tick("AgentKit", Duration.ofHours(1), 10);

        assertThat(otherStore.approvals("globex").stream()
                .filter(a -> a.state() == ApprovalRequest.State.PENDING)
                .map(a -> dev.agentkit.itops.tools.ToolCatalog
                        .policyOrUnknown(a.toolName()).capability())
                .toList())
                .as("acme's standing refusal gated globex")
                .contains(capability);
    }

    /**
     * The denial carries the operator's words, their name, and the remedy.
     *
     * <p>Replacing the whole denial with {@code deny("no")} left all 187 tests green, so
     * none of what the commit calls load-bearing was actually asserted: the words reaching
     * the model, the attribution, "do not look for another route", and "an operator can lift
     * this" — which is the sentence the argument for standing refusals being defensible at
     * all rests on.
     */
    @Test
    @DisplayName("a denial says what the person said, who said it, and how to undo it")
    void theDenialCarriesTheOperatorsWords() {
        ApprovalRequest parked = pending(runnerOver(new ScriptedOpsLlm(), corrections));
        runnerOver(new ScriptedOpsLlm(), corrections)
                .resume(TENANT, parked.id(), "sam@example.com", false, NOTE, true);

        Recording watching = new Recording();
        OpsStore freshStore = new OpsStore();
        ServiceNowConnector freshQueue = new ServiceNowConnector();
        ExecutionRunner later = new ExecutionRunner(freshStore, watching, "scripted",
                freshQueue, new DirectoryConnector(), new IdentityConnector(),
                "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH, corrections);
        new IntakeWorker(freshStore, freshQueue, later, TENANT, "it-ops-agent")
                .tick("AgentKit", Duration.ofHours(1), 10);

        String sent = watching.everythingSent();
        assertThat(sent).as("the run never saw a denial").contains("refused this capability");
        assertThat(sent).as("the operator's words did not travel").contains(NOTE);
        assertThat(sent).as("the denial named nobody")
                .contains("source=\"operator:sam.example.com\"");
        assertThat(sent).as("#101's sentence, which the itops gate has carried since")
                .contains("Do not look for another route to the same effect");
        assertThat(sent).as("nothing told anyone how to undo it")
                .contains("An operator can lift this");
    }

    /**
     * A person's approval outranks a standing refusal on the same capability.
     *
     * <p>The order matters and is the realistic one: an approval is already pending when the
     * refusal is set — parked before it, by a run that was allowed to ask — and a person then
     * decides that pending one. (Once a standing refusal is in force nothing new parks on
     * that capability at all, because the gate stops the call before the supervisor sees it.)
     *
     * <p>Without deferring here the gate refused the approved call: the approval was spent,
     * the ticket resolved and closed, and the row filed COMPLETED / "Done." with the action
     * undone. Their approval is the newer and more specific answer for that capability, and
     * the advisory recall already reasons that way.
     */
    @Test
    @DisplayName("an approval is honoured despite a standing refusal on its capability")
    void anApprovalOutranksAStandingRefusal() {
        ApprovalRequest parked = pending(runnerOver(new ScriptedOpsLlm(), corrections));
        String capability = dev.agentkit.itops.tools.ToolCatalog
                .policyOrUnknown(parked.toolName()).capability();

        // The refusal arrives after this one is already waiting, on the same capability.
        corrections.record(TENANT, capability, "alex@example.com", NOTE, true);
        assertThat(corrections.standing(TENANT, List.of(capability))).hasSize(1);

        Recording onResume = new Recording();
        new ExecutionRunner(store, onResume, "scripted", tickets, new DirectoryConnector(),
                identity, "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH,
                corrections)
                .resume(TENANT, parked.id(), "sam@example.com", true, "Confirmed with HR.");

        assertThat(onResume.sawARun()).as("the resume never ran").isTrue();
        assertThat(onResume.everythingSent())
                .as("the run was refused the action a person had just approved")
                .doesNotContain("refused this capability");
    }

    /** An approval is not a correction: only a refusal teaches anything. */
    @Test
    @DisplayName("approving records nothing")
    void approvingTeachesNothing() {
        ApprovalRequest parked = pending(runnerOver(new ScriptedOpsLlm(), corrections));
        String capability = dev.agentkit.itops.tools.ToolCatalog
                .policyOrUnknown(parked.toolName()).capability();

        runnerOver(new ScriptedOpsLlm(), corrections)
                .resume(TENANT, parked.id(), "sam@example.com", true, "Confirmed with HR.");

        assertThat(corrections.recall(TENANT, List.of(capability))).isEmpty();
    }
}
