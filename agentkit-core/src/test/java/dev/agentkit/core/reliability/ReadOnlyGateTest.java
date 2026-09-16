package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.DisclosingToolRegistry;
import dev.agentkit.core.tool.ForwardingTool;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReadOnlyGateTest {

    private static Tool tool(String name, SideEffects effects) {
        return FunctionTool.builder(name, "does " + name)
                .sideEffects(effects)
                .handler(invocation -> ToolResult.ok("ran"))
                .build();
    }

    /** The tool the agent loop would have resolved before gating. */
    private static Tool resolved(String name) {
        return REGISTRY.find(name).orElseThrow();
    }

    private static ToolInvocation call(String name) {
        return new ToolInvocation("c1", name, Map.of());
    }

    private static final ToolRegistry REGISTRY = new SimpleToolRegistry(List.of(
            tool("search", SideEffects.NONE),
            tool("send_email", SideEffects.EXTERNAL),
            FunctionTool.builder("legacy", "written before side effects existed")
                    .handler(invocation -> ToolResult.ok("ran"))
                    .build()));

    @Test
    void aReadOnlyToolRuns() {
        assertThat(ToolGates.readOnly().evaluate(resolved("search"), call("search")).allowed()).isTrue();
    }

    @Test
    void aToolWithExternalEffectsIsRefusedWithAReasonTheModelCanActOn() {
        GateResult result = ToolGates.readOnly().evaluate(resolved("send_email"), call("send_email"));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason())
                .contains("read-only")
                .contains("send_email")
                // The model gets told what to do instead, not just that it was blocked.
                .contains("report what you would have done");
    }

    @Test
    void anUndeclaredToolIsRefusedRatherThanAssumedHarmless() {
        // The whole point of the default: every tool written before SideEffects existed
        // would otherwise be silently eligible, and a rehearsal that sends real email is
        // worse than no rehearsal at all.
        GateResult result = ToolGates.readOnly().evaluate(resolved("legacy"), call("legacy"));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("declares none either way");
    }

    @Test
    void anIdempotentWriteIsStillAWriteAndIsRefused() {
        // The value exists for the other question — is this safe to run again — not for
        // this one. A rehearsal that leaves rows behind is not a rehearsal.
        assertThat(ToolGates.readOnly()
                .evaluate(tool("upsert", SideEffects.IDEMPOTENT), call("upsert")).allowed()).isFalse();
    }

    @Test
    void theFrameworksOwnReadOnlyToolsRunInARehearsal() {
        // Without this, a rehearsal denies literally every tool — including the
        // search_tools call a disclosing agent must make first, so it could never reach a
        // deferred tool at all, and the feature would look broken on first contact.
        DisclosingToolRegistry disclosing = DisclosingToolRegistry.builder()
                .alwaysAvailable(tool("always", SideEffects.NONE))
                .deferred(tool("weather", SideEffects.NONE))
                .build();
        Tool search = disclosing.find(DisclosingToolRegistry.DEFAULT_SEARCH_TOOL_NAME).orElseThrow();

        assertThat(ToolGates.readOnly()
                .evaluate(search, call(DisclosingToolRegistry.DEFAULT_SEARCH_TOOL_NAME)).allowed())
                .describedAs("search_tools is how disclosure works at all")
                .isTrue();
    }


    @Test
    void undeclaredIsTheDefaultSoNothingBecomesEligibleByAccident() {
        assertThat(FunctionTool.builder("t", "d").handler(i -> ToolResult.ok("")).build()
                .sideEffects()).isEqualTo(SideEffects.UNKNOWN);
        assertThat(new Tool() {
            @Override public String name() {
                return "t";
            }

            @Override public String description() {
                return "d";
            }

            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override public ToolResult execute(ToolInvocation invocation) {
                return ToolResult.ok("");
            }
        }.sideEffects()).isEqualTo(SideEffects.UNKNOWN);
    }

    @Test
    void readOnlyShorthandMatchesTheExplicitDeclaration() {
        assertThat(FunctionTool.builder("t", "d").readOnly().handler(i -> ToolResult.ok("")).build()
                .sideEffects()).isEqualTo(SideEffects.NONE);
    }



    @Test
    void itComposesWithTheOtherGates() {
        // allOf is how a rehearsal narrows an existing policy rather than replacing it.
        // Asserting only that "search" is denied would pass with readOnly stubbed out to
        // allow everything, since denyTools alone denies it — so this checks both halves
        // still bite and that neither is what the other was already doing.
        Tool search = tool("search", SideEffects.NONE);
        Tool sendEmail = tool("send_email", SideEffects.EXTERNAL);
        ToolGate gate = ToolGates.allOf(
                ToolGates.readOnly(),
                ToolGates.denyTools(java.util.Set.of("search")));

        // Denied by the policy the rehearsal narrowed, though readOnly would allow it.
        assertThat(ToolGates.readOnly().evaluate(search, call("search")).allowed()).isTrue();
        assertThat(gate.evaluate(search, call("search")).allowed()).isFalse();
        // Denied by the rehearsal, though the policy has nothing to say about it.
        assertThat(ToolGates.denyTools(java.util.Set.of("search"))
                .evaluate(sendEmail, call("send_email")).allowed()).isTrue();
        GateResult rehearsed = gate.evaluate(sendEmail, call("send_email"));
        assertThat(rehearsed.allowed()).isFalse();
        // allOf must forward the tool, or readOnly falls back to its no-tool branch and
        // this passes for the wrong reason — it would refuse everything, search included.
        assertThat(rehearsed.reason()).contains("read-only");
    }

    @Test
    void anInstrumentedToolKeepsItsDeclarationSoRehearsalStillWorks() {
        // agentkit-otel wraps every tool in the registry, and a wrapper that let the
        // interface default answer would report UNKNOWN for all of them — turning a
        // rehearsal into deny-everything for anyone who wired up tracing. The core cannot
        // depend on that module, so this pins the contract the wrapper has to keep.
        Tool declared = tool("search", SideEffects.NONE);
        Tool wrapper = new Instrumented(declared);

        assertThat(wrapper.sideEffects()).isEqualTo(SideEffects.NONE);
        assertThat(ToolGates.readOnly().evaluate(wrapper, call("search")).allowed()).isTrue();
    }

    /**
     * A decorator of the shape the instrumenting wrappers actually have, since #296.
     *
     * <p>This was a hand-written six-method forwarder, and the class that taught the
     * pattern to anyone reading the file — it forwarded {@code sideEffects} and
     * {@code provenance} and, exactly as the trap predicts, neither of the two gate
     * declarations, nor {@code spec()}, nor {@code inputExamples()}. So the file that
     * documented the pattern shipped three of the omissions it was documenting.
     * {@link ForwardingTool} makes the whole tool the default; the only thing left to write
     * is what is being decorated, which here is nothing.
     */
    private static final class Instrumented extends ForwardingTool {
        private final Tool delegate;

        Instrumented(Tool delegate) {
            this.delegate = delegate;
        }

        @Override
        protected Tool delegate() {
            return delegate;
        }
    }
}
