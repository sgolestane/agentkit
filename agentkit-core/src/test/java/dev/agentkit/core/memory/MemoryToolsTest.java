package dev.agentkit.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryToolsTest {

    private static ToolResult run(Tool tool, Map<String, Object> args) {
        return tool.execute(new ToolInvocation("1", tool.name(), args));
    }

    @Test
    void memoryToolWriteThenRead() {
        Tool memory = MemoryTools.memoryTool(new InMemoryMemoryStore());
        assertThat(run(memory, Map.of("command", "write", "path", "k.md", "content", "v")).isError()).isFalse();
        assertThat(run(memory, Map.of("command", "read", "path", "k.md")).content()).isEqualTo("v");
    }

    @Test
    void memoryToolReadMissingIsError() {
        Tool memory = MemoryTools.memoryTool(new InMemoryMemoryStore());
        assertThat(run(memory, Map.of("command", "read", "path", "nope")).isError()).isTrue();
    }

    @Test
    void memoryToolListAndDelete() {
        MemoryStore store = new InMemoryMemoryStore();
        Tool memory = MemoryTools.memoryTool(store);
        run(memory, Map.of("command", "write", "path", "a.md", "content", "x"));
        assertThat(run(memory, Map.of("command", "list")).content()).contains("a.md");
        assertThat(run(memory, Map.of("command", "delete", "path", "a.md")).content()).contains("Deleted");
    }

    @Test
    void memoryToolRejectsMissingPath() {
        Tool memory = MemoryTools.memoryTool(new InMemoryMemoryStore());
        assertThat(run(memory, Map.of("command", "read")).isError()).isTrue();
        assertThat(run(memory, Map.of("command", "write", "path", "a")).isError()).isTrue(); // no content
    }

    @Test
    void memoryToolUnknownCommand() {
        Tool memory = MemoryTools.memoryTool(new InMemoryMemoryStore());
        assertThat(run(memory, Map.of("command", "frobnicate")).isError()).isTrue();
    }

    @Test
    void memoryToolTranslatesTraversalToError() {
        Tool memory = MemoryTools.memoryTool(new FileMemoryStore(java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "agentkit-mem-test-" + System.nanoTime()), Containment.BEST_EFFORT));
        ToolResult result = run(memory, Map.of("command", "write", "path", "../x", "content", "y"));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void rememberAndRecall() {
        WorkingMemory wm = new WorkingMemory();
        Tool remember = MemoryTools.rememberTool(wm);
        Tool recall = MemoryTools.recallTool(wm);

        assertThat(run(recall, Map.of()).content()).contains("No notes");
        run(remember, Map.of("note", "user is named Alice"));
        assertThat(run(recall, Map.of()).content()).contains("user is named Alice");
    }

    @Test
    void recallAnswersForTheNotesTheSystemPromptCouldNotAfford() {
        // render()'s header tells the model it is seeing only the tail and to call recall
        // for the rest. Bounding recall the same way would leave that a dead pointer — the
        // model told it has amnesia and given no remedy. A tool result is paid once.
        WorkingMemory wm = new WorkingMemory(60);
        for (int i = 1; i <= 10; i++) {
            wm.note("decision " + i);
        }

        assertThat(wm.render()).doesNotContain("- decision 1\n");
        assertThat(run(MemoryTools.recallTool(wm), Map.of()).content())
                .contains("- decision 1\n").contains("- decision 10");
    }

    @Test
    void memoryToolAppendAndListWithPrefix() {
        Tool memory = MemoryTools.memoryTool(new InMemoryMemoryStore());
        run(memory, Map.of("command", "append", "path", "log.txt", "content", "one\n"));
        run(memory, Map.of("command", "append", "path", "log.txt", "content", "two\n"));
        assertThat(run(memory, Map.of("command", "read", "path", "log.txt")).content()).isEqualTo("one\ntwo\n");
        run(memory, Map.of("command", "write", "path", "notes/a.md", "content", "x"));
        // Fenced as a catalog: these are names a previous run chose, arriving as something
        // to pick from. Nothing of the listing sits outside the fence but the label.
        String listed = run(memory, Map.of("command", "list", "path", "notes/")).content();
        assertThat(listed).isEqualTo(dev.agentkit.core.prompt.Spotlight.wrap(
                dev.agentkit.core.prompt.Spotlight.Kind.CATALOG, Source.of("memory-keys"), "notes/a.md"));
        assertThat(dev.agentkit.core.prompt.Spotlight.outsideFences(listed))
                .isEqualTo("memory-keys");
    }

    @Test
    void memoryToolDeleteMissingReportsNothing() {
        Tool memory = MemoryTools.memoryTool(new InMemoryMemoryStore());
        assertThat(run(memory, Map.of("command", "delete", "path", "gone")).content()).contains("Nothing to delete");
    }

    @Test
    void agentPersistsAndRecallsAcrossRuns(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        // Two independently-constructed durable stores over the same directory,
        // simulating two separate sessions with a process boundary in between.
        // Run 1: the model writes a fact to durable memory.
        SimpleToolRegistry reg1 = new SimpleToolRegistry().register(MemoryTools.memoryTool(new FileMemoryStore(root, Containment.BEST_EFFORT)));
        FakeLlmClient llm1 = new FakeLlmClient(
                FakeLlmClient.toolUse("m1", "memory",
                        Map.of("command", "write", "path", "facts/color.md", "content", "blue")),
                FakeLlmClient.text("Saved."));
        new Agent(llm1, reg1, AgentConfig.builder("m").maxSteps(5).build())
                .run(Goal.of("remember my favorite color is blue"));

        // Run 2 (new agent, freshly-constructed store over the same dir): reads it back.
        SimpleToolRegistry reg2 = new SimpleToolRegistry().register(MemoryTools.memoryTool(new FileMemoryStore(root, Containment.BEST_EFFORT)));
        FakeLlmClient llm2 = new FakeLlmClient(
                FakeLlmClient.toolUse("m2", "memory", Map.of("command", "read", "path", "facts/color.md")),
                FakeLlmClient.text("Your favorite color is blue."));
        AgentResult r2 = new Agent(llm2, reg2, AgentConfig.builder("m").maxSteps(5).build())
                .run(Goal.of("what is my favorite color?"));

        assertThat(r2.output()).contains("blue");
        var toolMsg = llm2.received().get(1).messages().get(2);
        assertThat(((dev.agentkit.core.message.ToolResultBlock) toolMsg.content().get(0)).content())
                .isEqualTo("blue");
    }

    @Test
    void aFailingStoreCannotForgeALineThroughTheExceptionItThrows() {
        // The trailing Throwable is not a '{}' argument — the logging framework renders it
        // itself — so escaping the other arguments never touched it. Asserted at the site
        // rather than on the helper, because the defect is never in the escaping: it is in
        // a site not calling it, and a helper test passes just as happily when every call
        // to it has been deleted.
        MemoryStore throwing = new MemoryStore() {
            @Override
            public java.util.Optional<String> read(String path) {
                throw new IllegalStateException(
                        "backend gone: a.md\n2026-08-21 WARN  d.a.c.Ops - disk healthy\u001B[1;32m");
            }

            @Override
            public void write(String path, String content) {
            }

            @Override
            public void append(String path, String content) {
            }

            @Override
            public boolean delete(String path) {
                return false;
            }

            @Override
            public boolean exists(String path) {
                return false;
            }

            @Override
            public java.util.List<String> list(String prefix) {
                return java.util.List.of();
            }
        };
        Tool memory = MemoryTools.memoryTool(throwing);

        String sentinel = "operator-stream-probe-" + System.nanoTime();
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.err;
        String logged;
        try {
            System.setErr(new java.io.PrintStream(captured, true,
                    java.nio.charset.StandardCharsets.UTF_8));
            org.slf4j.LoggerFactory.getLogger(MemoryToolsTest.class).warn(sentinel);
            assertThat(run(memory, Map.of("command", "read", "path", "k.md")).isError()).isTrue();
        } finally {
            System.setErr(original);
            logged = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        // Assumed on the PLUMBING and not on the line under test (#225). This was
        // "assumeTrue(logged.contains(\"Memory command\"))", under which deleting the warn
        // outright made this test SKIP rather than fail. A sentinel written through the
        // same backend separates "capture does not work here" from "the code said nothing",
        // which is the distinction the assumption exists to make and the one it lost.
        org.junit.jupiter.api.Assumptions.assumeTrue(logged.contains(sentinel),
                "the test logger did not write to System.err, so this cannot observe the "
                        + "line the operator would read");
        assertThat(logged)
                .as("the store threw, the model got an error result, and nothing outside"
                        + " the run named the command that failed")
                .contains("Memory command 'read' failed");
        assertThat(logged.lines().filter(line -> line.startsWith("2026-08-21 WARN")))
                .as("the exception message wrote a log entry of its own")
                .isEmpty();
        assertThat(logged).as("the exception message reached the terminal")
                .doesNotContain("\u001B[1;32m");
        // And the type an operator triages on is still there, which is the half a wrapper
        // is most likely to lose.
        assertThat(logged).contains("java.lang.IllegalStateException");
    }
}
