package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.Provenance;
import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.codeexec.CodeExecutionTool;
import dev.agentkit.core.codeexec.CodeSandbox;
import dev.agentkit.core.codeexec.SandboxExecution;
import dev.agentkit.core.collab.Blackboard;
import dev.agentkit.core.collab.BlackboardTools;
import dev.agentkit.core.knowledge.InMemoryKnowledgeBase;
import dev.agentkit.core.knowledge.KnowledgeTools;
import dev.agentkit.core.memory.InMemoryMemoryStore;
import dev.agentkit.core.memory.MemoryTools;
import dev.agentkit.core.memory.WorkingMemory;
import dev.agentkit.core.skill.Skill;
import dev.agentkit.core.skill.SkillLibrary;
import dev.agentkit.core.skill.SkillTools;
import dev.agentkit.core.tool.DisclosingToolRegistry;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins what each of the framework's own tools declares.
 *
 * <p>The README promises a named list of tools that declare themselves, so progressive
 * disclosure, skills, memory recall and knowledge search all still work in a rehearsal.
 * Nothing else in the suite would notice if a {@code .readOnly()} were deleted — the whole
 * reactor stays green, and the feature quietly becomes deny-everything. That is the same
 * regression the tracing wrapper had, found by review rather than by a test.
 *
 * <p>The undeclared half is pinned too. A tool left {@link SideEffects#UNKNOWN} is a
 * decision — it is what makes a rehearsal refuse writes — and a later {@code .readOnly()}
 * added in the wrong place should fail here rather than in someone's dry run.
 */
class FrameworkToolDeclarationsTest {

    private static SkillLibrary library() {
        return new SkillLibrary(List.of(
                new Skill("demo", "a demo skill", "do the thing",
                        java.util.Optional.empty(), List.of(), List.of())));
    }

    private static CodeSandbox sandbox() {
        return (code, bridge) -> SandboxExecution.ok("");
    }

    static Stream<Arguments> declarations() {
        Blackboard board = new Blackboard();
        WorkingMemory memory = new WorkingMemory();
        DisclosingToolRegistry disclosing = DisclosingToolRegistry.builder()
                .deferred(FunctionTool.builder("deferred", "deferred")
                        .handler(i -> ToolResult.ok("")).build())
                .build();
        return Stream.of(
                // Read-only, so a rehearsal can still discover, read and recall.
                Arguments.of(SideEffects.NONE, Provenance.THIRD_PARTY,
                        disclosing.find(DisclosingToolRegistry.DEFAULT_SEARCH_TOOL_NAME).orElseThrow()),
                Arguments.of(SideEffects.NONE, Provenance.THIRD_PARTY, SkillTools.readSkillTool(library())),
                Arguments.of(SideEffects.NONE, Provenance.THIRD_PARTY,
                        SkillTools.readSkillResourceTool(library())),
                Arguments.of(SideEffects.NONE, Provenance.THIRD_PARTY, KnowledgeTools.knowledgeSearchTool(
                        InMemoryKnowledgeBase.bm25())),
                Arguments.of(SideEffects.NONE, Provenance.THIRD_PARTY, MemoryTools.recallTool(memory)),
                Arguments.of(SideEffects.NONE, Provenance.THIRD_PARTY, BlackboardTools.readBoardTool(board)),
                // Undeclared, and deliberately: each leaves something behind, or reaches
                // tools this declaration cannot see.
                Arguments.of(SideEffects.UNKNOWN, Provenance.FIRST_PARTY, MemoryTools.rememberTool(memory)),
                Arguments.of(SideEffects.UNKNOWN, Provenance.THIRD_PARTY,
                        MemoryTools.memoryTool(new InMemoryMemoryStore())),
                Arguments.of(SideEffects.UNKNOWN, Provenance.FIRST_PARTY,
                        BlackboardTools.postNoteTool(board, "alice")),
                Arguments.of(SideEffects.UNKNOWN, Provenance.UNKNOWN, CodeExecutionTool
                        .builder(sandbox(), new SimpleToolRegistry())
                        .allowAllTools()
                        .build()));
    }

    @ParameterizedTest(name = "{2} declares {0}")
    @MethodSource("declarations")
    void eachFrameworkToolDeclaresWhatTheDocumentationSaysItDoes(
            SideEffects expected, Provenance ignored, Tool tool) {
        assertThat(tool.sideEffects()).describedAs(tool.name()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{2} returns {1}")
    @MethodSource("declarations")
    void eachFrameworkToolSaysWhoWroteWhatItReturns(
            SideEffects ignored, Provenance expected, Tool tool) {
        // The same table, because a new framework tool that reads somebody else's words
        // must be a visible omission rather than an invisible one — and the way to get
        // that is one row per tool that will not compile until both columns are filled.
        // A hand-written list of four assertions is not that, however it is described.
        //
        // The deliberately-undeclared half is pinned too, for the reason this file's other
        // table pins it: "nobody said" is a decision when it is written down.
        assertThat(tool.provenance()).describedAs(tool.name()).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("declarations")
    void andTheGateAgreesWithTheDeclaration(SideEffects expected, Provenance ignored, Tool tool) {
        // The declaration is only worth pinning because a gate acts on it; this is the
        // half a caller actually observes.
        assertThat(ToolGates.readOnly()
                .evaluate(tool, new dev.agentkit.core.tool.ToolInvocation("c1", tool.name(), Map.of()))
                .allowed())
                .describedAs(tool.name())
                .isEqualTo(expected == SideEffects.NONE);
    }
}
