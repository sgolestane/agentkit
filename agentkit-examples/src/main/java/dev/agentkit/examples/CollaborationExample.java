package dev.agentkit.examples;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.collab.Blackboard;
import dev.agentkit.core.collab.BlackboardTools;
import dev.agentkit.core.collab.Critics;
import dev.agentkit.core.collab.MessageBudget;
import dev.agentkit.core.collab.MessagingTools;
import dev.agentkit.core.collab.Peer;
import dev.agentkit.core.collab.PeerGroup;
import dev.agentkit.core.collab.RefineLoop;
import dev.agentkit.core.collab.RefineResult;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.RetryPolicy;
import dev.agentkit.core.reliability.RetryingLlmClient;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.util.OneLine;
import java.util.function.Supplier;

/**
 * Exercises all three collaboration primitives together to produce a short brief:
 *
 * <ul>
 *   <li><b>Agent-to-agent messaging</b> — the writer can {@code send_message} a
 *       {@code researcher} peer for facts (bounded by a shared message budget).</li>
 *   <li><b>Shared workspace</b> — the writer jots findings to a {@link Blackboard}
 *       with {@code post_note} and can {@code read_board} what's there.</li>
 *   <li><b>Generator&#8596;critic refine loop</b> — a {@link RefineLoop} wraps the
 *       writer as the generator and an {@code editor} peer as the {@link Critics#agent
 *       agent critic}, revising until the editor approves or the round cap is hit.</li>
 * </ul>
 *
 * <p>Everything runs against the backend chosen by {@link ExampleBackend}.
 */
public final class CollaborationExample {

    private CollaborationExample() {
    }

    /** Wires the writer + researcher + editor collaboration over a shared {@code board}. */
    public static RefineLoop build(LlmClient llm, String model, Blackboard board) {
        // A peer the writer can message for facts, and the one budget the whole exchange
        // spends from. It belongs to the group (#299): every send_message tool built over
        // these peers draws from this counter because there is nowhere else to put one.
        // Six messages for the group's whole lifetime, not per run — this example builds a
        // fresh group per build(...) call, and a deployment that wires one group and runs
        // many goals calls peers.messageBudget().reset() between them.
        PeerGroup peers = PeerGroup.of(MessageBudget.of(6),
                Peer.of("researcher", "Answers factual questions with brief, concrete facts.",
                        () -> new Agent(llm, new SimpleToolRegistry(),
                                AgentConfig.builder(model).maxSteps(6)
                                        .systemPrompt("You answer factual questions concisely and concretely.")
                                        .build())));
        // The generator: a writer that can message peers and use the shared workspace.
        Supplier<Agent> writer = () -> {
            SimpleToolRegistry tools = new SimpleToolRegistry();
            tools.register(MessagingTools.sendMessageTool(peers));
            tools.register(BlackboardTools.postNoteTool(board, "writer"));
            tools.register(BlackboardTools.readBoardTool(board));
            return new Agent(llm, tools, AgentConfig.builder(model).maxSteps(8)
                    .systemPrompt("You are a writer. Ask the researcher (via send_message) for any facts "
                            + "you need, jot findings to the shared workspace with post_note, then write "
                            + "the final brief in exactly three sentences.")
                    .build());
        };

        // The critic: an editor peer that reviews each draft (agent-to-agent critique).
        Peer editor = Peer.of("editor", "Reviews drafts for clarity and accuracy",
                () -> new Agent(llm, new SimpleToolRegistry(),
                        AgentConfig.builder(model).maxSteps(4)
                                .systemPrompt("You are a meticulous editor.").build()));

        return new RefineLoop(writer, Critics.agent(editor), /* maxRounds */ 2);
    }

    /** Runs the example against the configured backend (see {@link ExampleBackend}). */
    public static void main(String[] args) {
        ExampleBackend backend = ExampleBackend.fromEnv();
        LlmClient reliable = new RetryingLlmClient(backend.llm(), RetryPolicy.defaults());

        Blackboard board = new Blackboard();
        RefineLoop loop = build(reliable, backend.model(), board);
        RefineResult result = loop.run(Goal.of(
                "Write a three-sentence brief on the benefits of durable execution for AI agents."));

        System.out.println("approved=" + result.approved() + " rounds=" + result.rounds());
        System.out.println(result.output());
        System.out.println("\n--- shared workspace ---");
        for (Blackboard.Entry entry : board.entries()) {
            // The content is model-written and this listing is structure an operator reads
            // row by row, so a newline in it forges rows of its own — the same defect as
            // #98, in an example rather than a log. Collapsed rather than escaped: this is
            // prose, and only its shape is dangerous here.
            System.out.println("#" + entry.id() + " [" + entry.topic() + "] by "
                    + entry.author() + ": " + OneLine.of(entry.content()));
        }
    }
}
