package dev.agentkit.core.collab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.tool.SimpleToolRegistry;
import org.junit.jupiter.api.Test;

class PeerGroupTest {

    private static Agent agentReturning(String text) {
        return new Agent(new FakeLlmClient(FakeLlmClient.text(text)),
                new SimpleToolRegistry(), AgentConfig.builder("m").maxSteps(3).build());
    }

    private static Peer peer(String name) {
        return Peer.of(name, name + " does things", () -> agentReturning("done"));
    }

    @Test
    void preservesRegistrationOrderAndLooksUpByName() {
        PeerGroup group = PeerGroup.of(peer("alice"), peer("bob"));

        assertThat(group.names()).containsExactly("alice", "bob");
        assertThat(group.find("bob")).isPresent();
        assertThat(group.find("absent")).isEmpty();
        assertThat(group.isEmpty()).isFalse();
    }

    @Test
    void emptyGroupReportsEmptyAndAnEmptyCatalog() {
        PeerGroup group = new PeerGroup();
        assertThat(group.isEmpty()).isTrue();
        assertThat(group.names()).isEmpty();
        assertThat(group.catalog()).isEmpty();
    }

    @Test
    void singleAgentConvenienceFormReusesTheInstance() {
        // The (name, description, Agent) overload reuses one stateless agent across
        // interactions, rather than building a fresh one per call.
        Agent shared = agentReturning("shared reply");
        Peer p = Peer.of("worker", "works", shared);

        PeerGroup group = PeerGroup.of(p);
        assertThat(group.find("worker")).containsSame(p);
        assertThat(p.handle(Goal.of("go")).output()).isEqualTo("shared reply");
    }

    @Test
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> PeerGroup.of(peer("alice"), peer("alice")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate peer name");
    }

    @Test
    void catalogListsNameAndDescriptionOnePerLine() {
        PeerGroup group = PeerGroup.of(
                Peer.of("researcher", "Finds facts", () -> agentReturning("x")),
                Peer.of("editor", "Reviews drafts", () -> agentReturning("y")));

        assertThat(group.catalog())
                .isEqualTo("- researcher: Finds facts\n- editor: Reviews drafts");
    }

    @Test
    void peerRejectsABlankName() {
        assertThatThrownBy(() -> Peer.of("  ", "desc", () -> agentReturning("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    /**
     * Every group has exactly one budget and no way to be built without one (#299) — the
     * property that makes "build every peer's tool from the same budget" unnecessary to say.
     */
    @Test
    void everyGroupOwnsOneMessageBudget() {
        PeerGroup defaulted = PeerGroup.of(peer("alice"));
        assertThat(defaulted.messageBudget().maxMessages())
                .isEqualTo(PeerGroup.DEFAULT_MAX_MESSAGES);
        // The literal too, not only the constant: comparing a default against the constant
        // that names it passes whatever the constant becomes, and a silent change to how
        // many messages an unconfigured group gets is a change to when it stops working.
        assertThat(PeerGroup.DEFAULT_MAX_MESSAGES).isEqualTo(8);
        assertThat(new PeerGroup().messageBudget().remaining())
                .isEqualTo(PeerGroup.DEFAULT_MAX_MESSAGES);

        MessageBudget given = MessageBudget.of(3);
        PeerGroup group = PeerGroup.of(given, peer("alice"), peer("bob"));
        assertThat(group.messageBudget()).isSameAs(given);
        // Same instance on every read, so two tools built at different times cannot end up
        // holding different counters.
        assertThat(group.messageBudget()).isSameAs(group.messageBudget());
    }

    @Test
    void aBudgetReservesDownToZeroAndNoFurther() {
        MessageBudget budget = MessageBudget.of(2);
        assertThat(budget.tryReserve()).isTrue();
        assertThat(budget.tryReserve()).isTrue();
        assertThat(budget.tryReserve()).isFalse();
        assertThat(budget.remaining())
                .as("a refused reservation must not take the count below zero, even"
                        + " transiently, now that remaining() is something a caller reads")
                .isZero();

        budget.reset();
        assertThat(budget.remaining()).isEqualTo(2);
        assertThat(budget.tryReserve()).isTrue();
    }

    @Test
    void aBudgetOfNoMessagesIsRejected() {
        assertThatThrownBy(() -> MessageBudget.of(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxMessages must be > 0");
        assertThatThrownBy(() -> MessageBudget.of(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxMessages must be > 0");
    }

    @Test
    void peerHandleRunsAFreshAgentPerCall() {
        // The single-Agent convenience form reuses the instance; the supplier form
        // builds fresh. Here we verify handle() actually drives the agent.
        Peer p = Peer.of("worker", "works", () -> agentReturning("the result"));
        assertThat(p.handle(Goal.of("go")).output()).isEqualTo("the result");
    }
}
