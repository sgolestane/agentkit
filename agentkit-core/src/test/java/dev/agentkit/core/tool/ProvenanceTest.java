package dev.agentkit.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.message.ToolResultBlock;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a tool result came from, once it is inside a run (#60).
 *
 * <p>{@code ToolResult}'s javadoc has said for as long as it has existed that the framework
 * "cannot tell your own database rows from a page someone else wrote". That was true and it
 * was also the end of the sentence: there was nowhere for an author who <em>did</em> know to
 * write it down, so an observer building an audit trail, a caller's own filter, and any
 * run-scoped decision all had nothing to key on.
 *
 * <p>These tests are about the declaration surviving the journey, not about anything acting
 * on it. Acting on it is #122, and it is a runtime-semantics change that needs designing
 * rather than a gap to close.
 */
class ProvenanceTest {

    private static Tool declaring(String name, Provenance provenance, String content) {
        return FunctionTool.builder(name, "d")
                .provenance(provenance)
                .handler(inv -> ToolResult.ok(content))
                .build();
    }

    @Test
    @DisplayName("a tool declares once and every result it returns is attributed")
    void aDeclarationReachesTheResult() {
        Tool fetch = declaring("fetch", Provenance.THIRD_PARTY, "a page someone else wrote");

        ToolResult raw = fetch.execute(new ToolInvocation("t", "fetch", Map.of()));

        // The handler said nothing, so the tool's declaration stands.
        assertThat(raw.provenance()).isEqualTo(Provenance.UNKNOWN);
        assertThat(raw.attributedTo(fetch).provenance()).isEqualTo(Provenance.THIRD_PARTY);
    }

    @Test
    @DisplayName("a per-call answer may narrow the tool's, and may not widen it")
    void aPerCallAnswerNarrowsOnly() {
        // Narrowing is the case the per-call answer exists for: a file reader serving both
        // your config and a user's upload knows which is which.
        Tool reader = FunctionTool.builder("read", "d")
                .provenance(Provenance.UNKNOWN)
                .handler(inv -> ToolResult.from(Provenance.THIRD_PARTY, "a user's upload"))
                .build();
        assertThat(reader.execute(new ToolInvocation("t", "read", Map.of()))
                .attributedTo(reader).provenance())
                .isEqualTo(Provenance.THIRD_PARTY);

        // Widening is not, and permitting it made the declaration worth nothing: a tool
        // could answer THIRD_PARTY to the interface — which is what a reviewer, a start-up
        // inventory and any linter read — and stamp FIRST_PARTY on every result it
        // actually returned. The transcript then said the deployment had written a page
        // the deployment had never seen.
        Tool liar = FunctionTool.builder("fetch", "d")
                .provenance(Provenance.THIRD_PARTY)
                .handler(inv -> ToolResult.from(Provenance.FIRST_PARTY, "a stranger's page"))
                .build();
        assertThat(liar.execute(new ToolInvocation("t", "fetch", Map.of()))
                .attributedTo(liar).provenance())
                .as("a tool talked its way out of its own declaration")
                .isEqualTo(Provenance.THIRD_PARTY);
        // And UNKNOWN cannot be narrowed away either.
        Tool alsoLiar = FunctionTool.builder("x", "d")
                .provenance(Provenance.UNKNOWN)
                .handler(inv -> ToolResult.from(Provenance.FIRST_PARTY, "x"))
                .build();
        assertThat(alsoLiar.execute(new ToolInvocation("t", "x", Map.of()))
                .attributedTo(alsoLiar).provenance())
                .isEqualTo(Provenance.UNKNOWN);
    }

    @Test
    @DisplayName("a tool you did not author can still be declared")
    void aForeignToolCanBeAttributed() {
        // The one thing UNKNOWN is for — telling you which tools still need declaring —
        // was unreachable for exactly the population that needs it, because you cannot
        // override a method on a tool that arrives from somewhere else. withSideEffects
        // existed for the same reason and had no sibling.
        Tool foreign = FunctionTool.builder("vendor", "d")
                .handler(inv -> ToolResult.ok("x")).build();

        Tool declared = Tools.withProvenance(foreign, Provenance.THIRD_PARTY);

        assertThat(declared.provenance()).isEqualTo(Provenance.THIRD_PARTY);
        assertThat(declared.sideEffects()).isEqualTo(foreign.sideEffects());
        assertThat(declared.name()).isEqualTo("vendor");
    }

    @Test
    @DisplayName("a script laundering a page through the sandbox says so")
    void codeExecutionCarriesWhatItsScriptRead() {
        // run_code returns whatever model-written code chose to print, mixed from the
        // sandbox's own computation and every bridged tool's bytes — so no tool-level
        // declaration could be true for it, and it is the one place a per-call answer is
        // cheaply available. The bridge sees each inner result; it used to compute the
        // attribution and throw it away, so a script that read a page reported UNKNOWN.
        var registry = new SimpleToolRegistry()
                .register(declaring("fetch", Provenance.THIRD_PARTY, "a stranger's page"));

        var readingScript = dev.agentkit.core.codeexec.CodeExecutionTool
                .builder((code, bridge) -> dev.agentkit.core.codeexec.SandboxExecution.ok(
                        bridge.invoke("fetch", Map.of()).content()), registry)
                .allowAllTools()
                .build();
        assertThat(readingScript.execute(new ToolInvocation("t", "run_code",
                Map.of("code", "print(fetch())"))).provenance())
                .isEqualTo(Provenance.THIRD_PARTY);

        // A script that calls nothing is still not the deployment's words — it is whatever
        // model-written code printed — so the floor is UNKNOWN rather than ours.
        var quietScript = dev.agentkit.core.codeexec.CodeExecutionTool
                .builder((code, bridge) -> dev.agentkit.core.codeexec.SandboxExecution.ok("2"),
                        registry)
                .allowAllTools()
                .build();
        assertThat(quietScript.execute(new ToolInvocation("t", "run_code",
                Map.of("code", "print(1+1)"))).provenance())
                .isEqualTo(Provenance.UNKNOWN);
    }

    @Test
    @DisplayName("the label survives the trip into the transcript")
    void theTranscriptCarriesIt() {
        // A label that does not survive being written down is not a label. The block is
        // what an observer, a compactor and an audit trail actually read.
        var registry = new SimpleToolRegistry()
                .register(declaring("fetch", Provenance.THIRD_PARTY, "a page"));
        var llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "fetch", Map.of()),
                FakeLlmClient.text("Done."));

        Agent.builder(llm, registry, AgentConfig.builder("m").maxSteps(3).build()).build()
                .run(Goal.of("go"));

        ToolResultBlock block = llm.received().get(1).messages().stream()
                .flatMap(m -> m.content().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .findFirst().orElseThrow();
        assertThat(block.provenance()).isEqualTo(Provenance.THIRD_PARTY);
        // And it does not reach the model, which has nowhere to put it and no use for it.
        assertThat(block.content()).isEqualTo("a page");
    }

    @Test
    @DisplayName("an undeclared tool is visibly undeclared, not quietly first-party")
    void undeclaredIsItsOwnAnswer() {
        // The whole value of the distinction is that an audit trail can say which tools
        // still need declaring. Folding UNKNOWN into THIRD_PARTY would lose exactly that,
        // and folding it into FIRST_PARTY would be a lie in the dangerous direction.
        Tool silent = FunctionTool.builder("silent", "d")
                .handler(inv -> ToolResult.ok("x")).build();

        assertThat(silent.provenance()).isEqualTo(Provenance.UNKNOWN);
        assertThat(ToolResult.ok("x").attributedTo(silent).provenance())
                .isEqualTo(Provenance.UNKNOWN);
        // Whether a *policy* should read UNKNOWN as third-party is not settled here, and
        // there used to be a mayBeSomebodyElses() on the enum that settled it. That is one
        // of #122's open questions, SideEffects keeps the same discipline by having no such
        // method, and the truth table of a one-line method nothing calls is not a test.
    }

    @Test
    @DisplayName("wrapping a tool does not un-declare it")
    void decoratorsForwardIt() {
        // The trap sideEffects() already has, and the reason its javadoc warns about it: a
        // default that means "undeclared" turns every decorator into a silent eraser. A
        // tool wrapped for observability or re-declared for its side effects would report
        // as undeclared, and the erasure is invisible.
        Tool fetch = declaring("fetch", Provenance.THIRD_PARTY, "a page");

        assertThat(Tools.withSideEffects(fetch, SideEffects.NONE).provenance())
                .as("declaring the side effects erased who wrote the content")
                .isEqualTo(Provenance.THIRD_PARTY);
    }

    @Test
    @DisplayName("the framework's own refusals are the framework's own words")
    void aRunnersOwnErrorIsFirstParty() {
        // These three used to be UNKNOWN, which the design treats as somebody else's — so a
        // run whose only failure was the loop refusing a name looked, to anything reading
        // the transcript, like a run that had read a stranger's text. A false positive is
        // expensive for #122, which already worries about firing on undeclared tools.
        var registry = new SimpleToolRegistry();
        var llm = new dev.agentkit.core.llm.FakeLlmClient(
                dev.agentkit.core.llm.FakeLlmClient.toolUse("t1", "nosuch", Map.of()),
                dev.agentkit.core.llm.FakeLlmClient.text("Done."));

        Agent.builder(llm, registry, AgentConfig.builder("m").maxSteps(3).build()).build()
                .run(Goal.of("go"));

        ToolResultBlock block = llm.received().get(1).messages().stream()
                .flatMap(m -> m.content().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .findFirst().orElseThrow();
        assertThat(block.isError()).isTrue();
        assertThat(block.provenance()).isEqualTo(Provenance.FIRST_PARTY);
    }

    @Test
    @DisplayName("an error from a declared tool inherits, conservatively")
    void anErrorInheritsTheToolsDeclaration() {
        // A tool's error message is usually its own words, so this overstates what the run
        // has read. The alternative understates it, and an error message is exactly where
        // remote text turns up — OpenRouterLlmClient putting an HTTP body into an exception
        // is the case in this repository. Written down because it is a decision, not a
        // consequence nobody looked at.
        Tool failing = FunctionTool.builder("fetch", "d")
                .provenance(Provenance.THIRD_PARTY)
                .handler(inv -> ToolResult.error("upstream said no"))
                .build();

        assertThat(failing.execute(new ToolInvocation("t", "fetch", Map.of()))
                .attributedTo(failing).provenance())
                .isEqualTo(Provenance.THIRD_PARTY);
    }

    @Test
    @DisplayName("a framework-authored refusal says so, and error() still does not")
    void aRefusalIsFirstPartyAndAnErrorIsNot() {
        // #272. The two live side by side because the distinction is the whole change: the
        // framework composing a sentence about a call that never happened, versus a result
        // that has not said who wrote its text.
        assertThat(ToolResult.refused("Unknown tool: 'nosuch'").provenance())
                .isEqualTo(Provenance.FIRST_PARTY);
        assertThat(ToolResult.refused("Unknown tool: 'nosuch'").isError()).isTrue();
        assertThat(ToolResult.error("upstream said no").provenance())
                .isEqualTo(Provenance.UNKNOWN);
    }

    @Test
    @DisplayName("why a blanket FIRST_PARTY default on error() would have bought nothing")
    void narrowerCannotTellAnAbstainingResultFromOneClaimingFirstParty() {
        // The measurement that chose a separate factory over changing error()'s default,
        // asserted rather than argued. narrower() collapses "the result abstained" and "the
        // result said FIRST_PARTY" to the same answer against every declaration, so the
        // default would leave every attributed result exactly where it is — and its whole
        // observable effect would be on results that bypass attributedTo, which is the set
        // refused() covers. What it would add beyond that set is mislabelling: failed() is
        // built on error(), and failed() is a framework frame around a detail somebody else
        // wrote.
        //
        // This is #161's table, read for a different question, and the property has to hold
        // for the argument to: a change here would make the default and the factory stop
        // being equivalent for attributed results, which is a thing whoever makes it should
        // have to notice.
        for (Provenance declared : Provenance.values()) {
            Tool tool = FunctionTool.builder("t", "d").provenance(declared)
                    .handler(inv -> ToolResult.ok("x")).build();
            ToolInvocation call = new ToolInvocation("t1", "t", Map.of());

            Provenance abstained = new ToolResult("x", true, Provenance.UNKNOWN)
                    .attributedTo(tool).provenance();
            Provenance claimedFirstParty = new ToolResult("x", true, Provenance.FIRST_PARTY)
                    .attributedTo(tool).provenance();

            assertThat(claimedFirstParty)
                    .as("a result claiming FIRST_PARTY is now distinguishable from one that"
                            + " abstained, under a tool declaring %s — so error()'s default"
                            + " is no longer a no-op for attributed results and the"
                            + " argument in ToolResult.refused's javadoc needs redoing",
                            declared)
                    .isEqualTo(abstained);
            assertThat(tool.execute(call)).isNotNull(); // the tool is real, not a stub
        }
    }

    @Test
    @DisplayName("clearing a tool result keeps what it says about the past")
    void clearingAResultKeepsItsAttribution() {
        // ClearToolResultsEditor replaces the run's own transcript in place, so dropping
        // the label there destroys the record of what the run has read — the one thing a
        // caller cannot reconstruct afterwards. It was dropping it.
        var history = java.util.List.of(
                dev.agentkit.core.message.Message.of(dev.agentkit.core.message.Role.USER,
                        java.util.List.of(new ToolResultBlock("t1",
                                "a vendor page, longer than the placeholder", false,
                                Provenance.THIRD_PARTY))),
                dev.agentkit.core.message.Message.user("later"));

        var edited = new dev.agentkit.core.context.ClearToolResultsEditor(1, "[cleared]")
                .edit(history);

        ToolResultBlock kept = edited.stream()
                .flatMap(m -> m.content().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .findFirst().orElseThrow();
        assertThat(kept.content()).isEqualTo("[cleared]");
        assertThat(kept.provenance())
                .as("the edit erased the record of what the run had read")
                .isEqualTo(Provenance.THIRD_PARTY);
    }
}
