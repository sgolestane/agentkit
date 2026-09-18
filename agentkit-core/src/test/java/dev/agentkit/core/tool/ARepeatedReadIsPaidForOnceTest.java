package dev.agentkit.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** What {@link ToolMemo} will and will not remember, and for how long. */
class ARepeatedReadIsPaidForOnceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");

    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
    private final ToolMemo memo = new ToolMemo(Duration.ofSeconds(30), 3, clock::get);

    private Tool read(String name) {
        return FunctionTool.builder(name, name)
                .sideEffects(SideEffects.NONE)
                .handler(invocation -> ToolResult.ok(name + " #" + reads.incrementAndGet()))
                .build();
    }

    private static ToolResult call(Tool tool, Map<String, Object> arguments) {
        return tool.execute(new ToolInvocation("t", tool.name(), new HashMap<>(arguments)));
    }

    @Test
    void theSameReadTwiceCallsTheToolOnce() {
        Tool lookup = memo.wrap(read("directory_lookup"));

        ToolResult first = call(lookup, Map.of("email", "ana@acme.example"));
        ToolResult second = call(lookup, Map.of("email", "ana@acme.example"));
        ToolResult other = call(lookup, Map.of("email", "ben@acme.example"));

        assertThat(first.content()).isEqualTo("directory_lookup #1");
        assertThat(second.content()).isEqualTo("directory_lookup #1");
        assertThat(other.content()).isEqualTo("directory_lookup #2");
        assertThat(reads.get()).isEqualTo(2);
        assertThat(memo.hits()).isEqualTo(1);
        assertThat(memo.misses()).isEqualTo(2);
    }

    @Test
    void anAnswerIsForgottenWhenItsTimeIsUp() {
        Tool lookup = memo.wrap(read("directory_lookup"));
        call(lookup, Map.of("email", "ana@acme.example"));

        clock.set(NOW.plusSeconds(29));
        assertThat(call(lookup, Map.of("email", "ana@acme.example")).content()).isEqualTo("directory_lookup #1");
        clock.set(NOW.plusSeconds(31));
        assertThat(call(lookup, Map.of("email", "ana@acme.example")).content()).isEqualTo("directory_lookup #2");
    }

    @Test
    void onlyAReadIsRemembered() {
        for (SideEffects effects : List.of(SideEffects.IDEMPOTENT, SideEffects.EXTERNAL, SideEffects.UNKNOWN)) {
            Tool write = FunctionTool.builder("grant_" + effects, "grant")
                    .sideEffects(effects)
                    .handler(invocation -> ToolResult.ok("granted #" + reads.incrementAndGet()))
                    .build();

            Tool wrapped = memo.wrap(write);

            assertThat(wrapped).as("%s is returned unwrapped", effects).isSameAs(write);
            assertThat(call(wrapped, Map.of()).content()).isNotEqualTo(call(wrapped, Map.of()).content());
        }
    }

    @Test
    void anErrorIsNeverRemembered() {
        AtomicInteger calls = new AtomicInteger();
        Tool flaky = memo.wrap(FunctionTool.builder("list_access", "list")
                .sideEffects(SideEffects.NONE)
                .handler(invocation -> calls.incrementAndGet() == 1
                        ? ToolResult.error("the directory timed out")
                        : ToolResult.ok("two entries"))
                .build());

        assertThat(call(flaky, Map.of()).isError()).isTrue();
        assertThat(call(flaky, Map.of()).content()).isEqualTo("two entries");
        assertThat(call(flaky, Map.of()).content()).isEqualTo("two entries");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void twoCallsThatAskTheSameThingAgreeHoweverTheirArgumentsWereBuilt() {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("filter", new LinkedHashMap<>(Map.of("level", "read")));
        one.put("email", "ana@acme.example");
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("email", "ana@acme.example");
        other.put("filter", new LinkedHashMap<>(Map.of("level", "read")));

        assertThat(ToolMemo.keyFor(new ToolInvocation("a", "list_access", one)))
                .isEqualTo(ToolMemo.keyFor(new ToolInvocation("b", "list_access", other)));
        assertThat(ToolMemo.keyFor(new ToolInvocation("a", "list_access", one)))
                .isNotEqualTo(ToolMemo.keyFor(new ToolInvocation("b", "list_messages", one)));
    }

    @Test
    void theOldestAnswerGoesWhenTheMemoIsFull() {
        Tool lookup = memo.wrap(read("directory_lookup"));
        call(lookup, Map.of("email", "one@acme.example"));     // #1
        call(lookup, Map.of("email", "two@acme.example"));     // #2
        call(lookup, Map.of("email", "three@acme.example"));   // #3
        call(lookup, Map.of("email", "four@acme.example"));    // #4, evicting one@

        assertThat(call(lookup, Map.of("email", "three@acme.example")).content()).isEqualTo("directory_lookup #3");
        assertThat(call(lookup, Map.of("email", "one@acme.example")).content()).isEqualTo("directory_lookup #5");
    }

    @Test
    void wrappingARegistryLeavesEverythingElseAboutAToolAlone() {
        Tool original = FunctionTool.builder("directory_lookup", "look someone up")
                .schema(Map.of("type", "object", "properties", Map.of("email", Map.of("type", "string"))))
                .sideEffects(SideEffects.NONE)
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> ToolResult.ok("ok"))
                .build();

        Tool wrapped = memo.wrapAll(List.of(original)).getFirst();

        assertThat(wrapped.name()).isEqualTo("directory_lookup");
        assertThat(wrapped.description()).isEqualTo("look someone up");
        assertThat(wrapped.inputSchema()).isEqualTo(original.inputSchema());
        assertThat(wrapped.sideEffects()).isEqualTo(SideEffects.NONE);
        assertThat(wrapped.provenance()).isEqualTo(Provenance.THIRD_PARTY);
        assertThat(wrapped.spec()).isEqualTo(original.spec());
    }

    @Test
    void aMemoWithoutATimeToLiveIsRefused() {
        assertThatThrownBy(() -> new ToolMemo(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolMemo(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearingForgetsWhatTheCallerKnowsIsStale() {
        Tool lookup = memo.wrap(read("directory_lookup"));
        call(lookup, Map.of("email", "ana@acme.example"));

        memo.clear();

        assertThat(call(lookup, Map.of("email", "ana@acme.example")).content()).isEqualTo("directory_lookup #2");
    }

    @Test
    void callsThatAskDifferentThingsNeverShareAKey() {
        assertThat(key(Map.of("a", "1,b=2"))).isNotEqualTo(key(Map.of("a", "1", "b", "2")));
        assertThat(key(Map.of("ids", List.of("x,y")))).isNotEqualTo(key(Map.of("ids", List.of("x", "y"))));
        assertThat(key(Map.of("id", 7))).isNotEqualTo(key(Map.of("id", "7")));
        assertThat(key(Map.of("q", "a\"b"))).isNotEqualTo(key(Map.of("q", "a\\\"b")));
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("id", null);
        assertThat(key(withNull)).isNotEqualTo(key(Map.of("id", "null")));
    }

    @Test
    void aReadAfterAWriteIsAnsweredByTheTool() {
        AtomicInteger created = new AtomicInteger();
        Tool lookup = FunctionTool.builder("directory_lookup", "look up")
                .sideEffects(SideEffects.NONE)
                .handler(invocation -> ToolResult.ok(created.get() == 0 ? "not found" : "found"))
                .build();
        Tool create = FunctionTool.builder("create_user", "create")
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    created.incrementAndGet();
                    return ToolResult.ok("created");
                })
                .build();
        List<Tool> tools = memo.wrapAll(List.of(lookup, create));

        assertThat(call(tools.get(0), Map.of("email", "ana@acme.example")).content()).isEqualTo("not found");
        call(tools.get(1), Map.of("email", "ana@acme.example"));

        assertThat(call(tools.get(0), Map.of("email", "ana@acme.example")).content()).isEqualTo("found");
    }

    @Test
    void aReadUnderWayWhenTheMemoWasClearedDoesNotPutItsAnswerBack() {
        AtomicInteger reads = new AtomicInteger();
        Tool slow = memo.wrap(FunctionTool.builder("list_access", "list")
                .sideEffects(SideEffects.NONE)
                .handler(invocation -> {
                    int n = reads.incrementAndGet();
                    if (n == 1) {
                        memo.clear();   // a write lands while this read is in flight
                    }
                    return ToolResult.ok("read #" + n);
                })
                .build());

        call(slow, Map.of());

        assertThat(call(slow, Map.of()).content()).isEqualTo("read #2");
    }

    @Test
    void aClockThatWentBackwardsDoesNotMakeAnAnswerYounger() {
        Tool lookup = memo.wrap(read("directory_lookup"));
        call(lookup, Map.of("email", "ana@acme.example"));

        clock.set(NOW.minusSeconds(3_600));

        assertThat(call(lookup, Map.of("email", "ana@acme.example")).content()).isEqualTo("directory_lookup #2");
    }

    @Test
    void aReadBoundToOneRunIsNotShared() {
        Tool perRun = new Tool() {
            @Override
            public String name() {
                return "my_inbox";
            }

            @Override
            public String description() {
                return "this run's inbox";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public SideEffects sideEffects() {
                return SideEffects.NONE;
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                return ToolResult.ok("inbox #" + reads.incrementAndGet());
            }

            @Override
            public Tool boundTo(dev.agentkit.core.agent.AgentRun run) {
                Tool outer = this;
                return new ForwardingTool() {
                    @Override
                    protected Tool delegate() {
                        return outer;
                    }
                };
            }
        };

        Tool bound = memo.wrap(perRun).boundTo(dev.agentkit.core.agent.AgentRun.of("one"));

        assertThat(call(bound, Map.of()).content()).isEqualTo("inbox #1");
        assertThat(call(bound, Map.of()).content()).isEqualTo("inbox #2");
        assertThat(memo.hits()).isZero();
    }

    private static String key(Map<String, Object> arguments) {
        return ToolMemo.keyFor(new ToolInvocation("t", "list_access", new HashMap<>(arguments)));
    }
}
