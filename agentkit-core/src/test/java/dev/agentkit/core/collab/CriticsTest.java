package dev.agentkit.core.collab;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.tool.SimpleToolRegistry;
import org.junit.jupiter.api.Test;

class CriticsTest {

    private static final Goal GOAL = Goal.of("Write a clear summary.");

    private static Agent agentReturning(String text) {
        return new Agent(new FakeLlmClient(FakeLlmClient.text(text)),
                new SimpleToolRegistry(), AgentConfig.builder("m").maxSteps(3).build());
    }

    @Test
    void parseAcceptsApproveAndToleratesPunctuation() {
        assertThat(Critics.parse("APPROVE").approved()).isTrue();
        assertThat(Critics.parse("  approve.  ").approved()).isTrue();
    }

    @Test
    void parseTreatsReviseAsNotApprovedAndKeepsFeedback() {
        Critique c = Critics.parse("REVISE\nAdd a concrete example.");
        assertThat(c.approved()).isFalse();
        assertThat(c.feedback()).isEqualTo("Add a concrete example.");
    }

    @Test
    void parseFailsClosedOnUnrecognizedOutput() {
        // Not clearly APPROVE -> revise, so the loop keeps improving rather than
        // accepting a draft the critic never actually approved.
        Critique c = Critics.parse("I think it looks fine to me");
        assertThat(c.approved()).isFalse();
        assertThat(c.feedback()).isNotBlank();
    }

    @Test
    void llmCriticParsesTheModelReply() {
        Critic approve = Critics.llm(new FakeLlmClient(FakeLlmClient.text("APPROVE")), "m");
        assertThat(approve.review(GOAL, "a fine draft").approved()).isTrue();

        Critic revise = Critics.llm(new FakeLlmClient(FakeLlmClient.text("REVISE\nToo vague.")), "m");
        Critique c = revise.review(GOAL, "meh");
        assertThat(c.approved()).isFalse();
        assertThat(c.feedback()).contains("Too vague");
    }

    @Test
    void agentCriticRunsAPeerAndParsesItsVerdict() {
        Critic critic = Critics.agent(
                Peer.of("reviewer", "Reviews drafts", () -> agentReturning("APPROVE")));
        assertThat(critic.review(GOAL, "draft").approved()).isTrue();
    }

    @Test
    void agentCriticThatDoesNotCompleteRequestsRevision() {
        Agent refusing = new Agent(new FakeLlmClient(FakeLlmClient.refusal("no")),
                new SimpleToolRegistry(), AgentConfig.builder("m").maxSteps(3).build());
        Critic critic = Critics.agent(Peer.of("reviewer", "Reviews", () -> refusing));

        Critique c = critic.review(GOAL, "draft");
        assertThat(c.approved()).isFalse();
        assertThat(c.feedback()).contains("did not complete");
    }
}
