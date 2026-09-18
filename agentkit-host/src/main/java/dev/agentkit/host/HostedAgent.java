package dev.agentkit.host;

import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatTools;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.deferred.SubjectResolver;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.reliability.Approver;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.util.OneLine;
import dev.agentkit.host.repo.AgentDefinition;
import dev.agentkit.host.repo.AgentDefinition.ToolRef;
import dev.agentkit.host.repo.AgentDefinition.ToolSelector;
import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.DefinitionException.Problem;
import dev.agentkit.host.repo.OrgRepo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * One agent definition, assembled against its organization's connected tools: what a turn with it is given, and how.
 *
 * <p>{@link #assemble} checks what could not be checked before the connectors were reached, and refuses the
 * definition with every problem at once:
 * <ul>
 *   <li>each tool selector selects something, and every tool it names exists;</li>
 *   <li>no two selected tools share a name, since the model calls tools by name;</li>
 *   <li>every confirmed tool exists and is selected;</li>
 *   <li>every bound argument is an argument of the tool it is bound on;</li>
 *   <li><strong>nothing grants without a person in the loop</strong>: a selected tool that declares
 *       {@link ToolEffect#GRANT} must be confirmed, unless its connector is marked {@code authoritative} — it
 *       enforces its own rules for what it grants, as Access Desk's ledger does.</li>
 * </ul>
 * A connector that is not reachable is not a problem with the definition. The agent is assembled as unavailable, and
 * says why when asked.
 *
 * <p>Per turn, {@link #tools(Principal)} binds arguments to the person asking, {@link #systemPrompt} gives the model
 * the prompt, the policy, who it is talking to and the time, and {@link #gate} stops confirmed tools for the person.
 */
public final class HostedAgent {

    /** One tool the agent is given: where it comes from, and the arguments bound on it. */
    private record Selected(String connector, DeclaredTools.Entry entry, Map<String, String> bindings) {
    }

    /** The most loop turns a deferred action may take, and the most a model call in one may produce. */
    static final int DEFERRED_MAX_STEPS = 12;
    static final int DEFERRED_MAX_TOKENS = 1024;

    private final OrgRepo repo;
    private final AgentDefinition definition;
    private final List<Selected> selected;
    private final Set<String> confirmed;
    private final Optional<String> unavailable;
    private final Optional<SubjectResolver> subjects;
    private final OrgConnectors connectors;
    private final boolean rehearsal;

    private HostedAgent(OrgRepo repo, AgentDefinition definition, List<Selected> selected, Set<String> confirmed,
                        Optional<String> unavailable, Optional<SubjectResolver> subjects, OrgConnectors connectors,
                        boolean rehearsal) {
        this.repo = repo;
        this.definition = definition;
        this.selected = List.copyOf(selected);
        this.confirmed = Set.copyOf(confirmed);
        this.unavailable = unavailable;
        this.subjects = subjects;
        this.connectors = connectors;
        this.rehearsal = rehearsal;
    }

    /**
     * Assembles {@code definition} against {@code connectors}.
     *
     * @throws DefinitionException listing everything wrong with it
     */
    public static HostedAgent assemble(OrgRepo repo, AgentDefinition definition, OrgConnectors connectors) {
        String file = "agents/" + definition.id() + "/agent.yaml";
        List<Problem> problems = new ArrayList<>();

        List<String> needed = new ArrayList<>(definition.tools().stream().map(ToolSelector::connector).toList());
        if (definition.deferred() != null) {
            definition.deferred().subjects().values().forEach(s -> needed.add(s.tool().connector()));
        }
        List<String> down = needed.stream().distinct().filter(c -> connectors.catalog(c).isEmpty()).toList();
        if (!down.isEmpty()) {
            String why = down.stream().map(c -> connectors.failure(c).orElse("The " + c + " connector is not connected."))
                    .collect(Collectors.joining(" "));
            return new HostedAgent(repo, definition, List.of(), Set.of(), Optional.of(why), Optional.empty(), connectors,
                    false);
        }

        // Which tools, in the order the selectors and each connector list them.
        Map<String, Selected> byName = new LinkedHashMap<>();
        for (int i = 0; i < definition.tools().size(); i++) {
            ToolSelector selector = definition.tools().get(i);
            DeclaredTools catalog = connectors.catalog(selector.connector()).orElseThrow();
            for (String named : selector.tools()) {
                if (catalog.entry(named).isEmpty()) {
                    problems.add(new Problem(file, "tools[" + i + "].tools",
                            selector.connector() + " has no tool " + named + " that declares what it does"));
                }
            }
            int count = 0;
            for (DeclaredTools.Entry entry : catalog.entries()) {
                if (!selector.selects(selector.connector(), entry.tool().name(), entry.declaration().effect())) {
                    continue;
                }
                count++;
                Selected already = byName.get(entry.tool().name());
                if (already == null) {
                    byName.put(entry.tool().name(), new Selected(selector.connector(), entry, Map.of()));
                } else if (!already.connector().equals(selector.connector())) {
                    problems.add(new Problem(file, "tools[" + i + "]", "both " + already.connector() + " and "
                            + selector.connector() + " have a tool named " + entry.tool().name()
                            + "; select only one of them"));
                }
            }
            if (count == 0) {
                problems.add(new Problem(file, "tools[" + i + "]", "selects no tool of " + selector.connector()));
            }
        }

        // Confirmations.
        Set<String> confirmed = new java.util.LinkedHashSet<>();
        for (int i = 0; i < definition.confirm().size(); i++) {
            ToolRef ref = definition.confirm().get(i);
            Selected tool = byName.get(ref.tool());
            if (tool == null || !tool.connector().equals(ref.connector())) {
                problems.add(new Problem(file, "confirm[" + i + "]", ref + " is not one of this agent's tools"));
            } else {
                confirmed.add(ref.tool());
            }
        }

        // Bindings: exact ones must fit their tool; a wildcard binds every tool of the connector that has the argument.
        Map<String, Map<String, String>> bindings = new LinkedHashMap<>();
        for (Map.Entry<ToolRef, Map<String, String>> bind : definition.bind().entrySet()) {
            ToolRef ref = bind.getKey();
            List<Selected> targets = byName.values().stream()
                    .filter(s -> ref.matches(s.connector(), s.entry().tool().name())).toList();
            if (targets.isEmpty()) {
                problems.add(new Problem(file, "bind." + ref, ref.isWildcard()
                        ? "this agent has no tools of " + ref.connector() : ref + " is not one of this agent's tools"));
                continue;
            }
            for (Map.Entry<String, String> argument : bind.getValue().entrySet()) {
                List<Selected> fitting = targets.stream()
                        .filter(s -> BoundTool.hasArgument(s.entry().tool().inputSchema(), argument.getKey())).toList();
                if (fitting.isEmpty() || !ref.isWildcard() && fitting.size() != targets.size()) {
                    problems.add(new Problem(file, "bind." + ref + "." + argument.getKey(), (ref.isWildcard()
                            ? "no tool of " + ref.connector() + " has an argument " : ref + " has no argument ")
                            + argument.getKey()));
                    continue;
                }
                fitting.forEach(s -> bindings.computeIfAbsent(s.entry().tool().name(), k -> new LinkedHashMap<>())
                        .put(argument.getKey(), argument.getValue()));
            }
        }

        // Nothing grants without a person, unless the connector holds the rules itself.
        for (Selected tool : byName.values()) {
            if (tool.entry().declaration().effect() == ToolEffect.GRANT && !confirmed.contains(tool.entry().tool().name())
                    && !connectors.isAuthoritative(tool.connector())) {
                problems.add(new Problem(file, "confirm", tool.connector() + "/" + tool.entry().tool().name()
                        + " grants something, so it must be confirmed, or " + tool.connector()
                        + " marked authoritative because it enforces its own rules"));
            }
        }

        // Tools offered to MCP callers directly: the agent's own, and only ones that read.
        for (int i = 0; i < definition.mcpDirect().size(); i++) {
            ToolRef ref = definition.mcpDirect().get(i);
            Selected tool = byName.get(ref.tool());
            if (tool == null || !tool.connector().equals(ref.connector())) {
                problems.add(new Problem(file, "mcp.direct[" + i + "]", ref + " is not one of this agent's tools"));
            } else if (tool.entry().declaration().effect() != ToolEffect.READ) {
                problems.add(new Problem(file, "mcp.direct[" + i + "]", ref + " is declared "
                        + tool.entry().declaration().effect().wire() + "; only a tool that reads is offered outside a "
                        + "conversation, where nothing would stop it for the person"));
            }
        }

        // Deferred work: every subject is looked up by a tool that exists and takes the argument named.
        Optional<SubjectResolver> subjects = Optional.empty();
        if (definition.deferred() != null) {
            definition.deferred().subjects().forEach((kind, subject) -> {
                Optional<DeclaredTools.Entry> lookup = connectors.catalog(subject.tool().connector())
                        .flatMap(c -> c.entry(subject.tool().tool()));
                if (lookup.isEmpty()) {
                    problems.add(new Problem(file, "deferred.subjects." + kind + ".tool",
                            subject.tool().connector() + " has no tool " + subject.tool().tool() + " that declares what it does"));
                } else if (!BoundTool.hasArgument(lookup.get().tool().inputSchema(), subject.argument())) {
                    problems.add(new Problem(file, "deferred.subjects." + kind + ".argument",
                            subject.tool() + " has no argument " + subject.argument()));
                }
            });
            subjects = Optional.of(new ConnectorSubjects(definition.deferred().subjects(), connectors));
        }

        // Eval cases: every tool an expectation names is one this agent is given.
        String evals = "agents/" + definition.id() + "/evals.yaml";
        for (int i = 0; i < definition.evals().size(); i++) {
            List<dev.agentkit.host.repo.EvalCase.Expectation> expect = definition.evals().get(i).expect();
            for (int j = 0; j < expect.size(); j++) {
                String tool = expect.get(j).tool();
                boolean scheduler = definition.deferred() != null
                        && dev.agentkit.core.deferred.DeferredActionScheduler.TOOL_NAME.equals(tool);
                if (tool != null && !byName.containsKey(tool) && !scheduler) {
                    problems.add(new Problem(evals, "cases[" + i + "].expect[" + j + "]",
                            tool + " is not one of this agent's tools"));
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new DefinitionException(problems);
        }
        List<Selected> tools = byName.values().stream()
                .map(s -> new Selected(s.connector(), s.entry(), bindings.getOrDefault(s.entry().tool().name(), Map.of())))
                .toList();
        return new HostedAgent(repo, definition, tools, confirmed, Optional.empty(), subjects, connectors, false);
    }

    public AgentDefinition definition() {
        return definition;
    }

    /**
     * This agent for a rehearsal: the same prompt, policy, tools and bindings, reads run as usual, and every tool that
     * could change something refused before it runs, with the call recorded in the turn's trace — any not declared
     * {@link ToolEffect#READ}; a read that may leave something behind (it asks a person, or records what it found, or
     * its connector does not say it leaves nothing); and the tools the host adds for a turn, such as the scheduler.
     * What the agent set out to do is visible; nothing is done.
     */
    public HostedAgent rehearsing() {
        return new HostedAgent(repo, definition, selected, confirmed, unavailable, subjects, connectors, true);
    }

    /** Whether this is the agent {@link #rehearsing()}. */
    public boolean isRehearsal() {
        return rehearsal;
    }

    /** The tools a rehearsal refuses, by name, with what each would have done. */
    public Map<String, String> changing(List<Tool> alsoGiven) {
        Map<String, String> changing = new LinkedHashMap<>();
        for (Selected s : selected) {
            ToolEffect effect = s.entry().declaration().effect();
            if (effect != ToolEffect.READ) {
                changing.put(s.entry().tool().name(), effect.wire());
            } else if (s.entry().tool().sideEffects() != dev.agentkit.core.tool.SideEffects.NONE) {
                changing.put(s.entry().tool().name(), "leave something behind");
            }
        }
        alsoGiven.forEach(tool -> changing.putIfAbsent(tool.name(),
                dev.agentkit.core.deferred.DeferredActionScheduler.TOOL_NAME.equals(tool.name())
                        ? ToolEffect.SCHEDULE.wire() : "act"));
        return changing;
    }

    /** Refuses the {@code changing} tools, saying so in a way that lets the model carry on. */
    static ToolGate rehearsalGate(Map<String, String> changing) {
        return (tool, invocation) -> {
            String effect = changing.get(invocation.name());
            return effect == null ? dev.agentkit.core.reliability.GateResult.allow()
                    : dev.agentkit.core.reliability.GateResult.deny("Not run: this is a rehearsal, and "
                    + invocation.name() + " would " + effect + ". Nothing was changed. Carry on as you would if it had "
                    + "worked, without trying another way to do it, and say in your answer what you did not do.");
        };
    }

    /** {@code id@version}: which definition, at which commit, a turn ran. */
    public String qualifiedName() {
        return definition.id() + "@" + repo.version();
    }

    public String model() {
        return definition.model() != null ? definition.model() : repo.defaultModel();
    }

    /** Why the agent cannot run right now, if it cannot. */
    public Optional<String> unavailable() {
        return unavailable;
    }

    /** Whether {@code principal} is in the agent's audience. */
    public boolean admits(Principal principal) {
        return principal.org().equals(repo.org()) && (definition.audience().contains(AgentDefinition.EVERYONE)
                || definition.audience().stream().anyMatch(principal.groups()::contains));
    }

    /** The agent's tools for one person: each with its declaration, and bound arguments filled from them. */
    public DeclaredTools tools(Principal principal) {
        DeclaredTools tools = new DeclaredTools();
        for (Selected s : selected) {
            Tool tool = s.bindings().isEmpty() ? s.entry().tool() : new BoundTool(s.entry().tool(), s.bindings(), principal);
            tools.add(tool, s.entry().declaration());
        }
        return tools;
    }

    /**
     * The agent's tools offered directly to an MCP caller, bound to them as in a conversation. They answer with the
     * connector's own result: the fence a result gets on its way to this host's model is this host's, and is not
     * passed on to a caller that has its own.
     */
    public DeclaredTools directTools(Principal principal) {
        DeclaredTools direct = new DeclaredTools();
        for (ToolRef ref : definition.mcpDirect()) {
            selected.stream().filter(s -> s.connector().equals(ref.connector()) && s.entry().tool().name().equals(ref.tool()))
                    .findFirst()
                    .ifPresent(s -> connectors.client(s.connector()).ifPresent(client -> {
                        Tool raw = new ConnectorResult(s.entry().tool(), client);
                        direct.add(s.bindings().isEmpty() ? raw : new BoundTool(raw, s.bindings(), principal),
                                s.entry().declaration());
                    }));
        }
        return direct;
    }

    /** A connector's tool as it describes itself, answering with the connector's result as it came. */
    private static final class ConnectorResult extends dev.agentkit.core.tool.ForwardingTool {
        private final Tool described;
        private final dev.agentkit.mcp.McpConnection client;

        ConnectorResult(Tool described, dev.agentkit.mcp.McpConnection client) {
            this.described = described;
            this.client = client;
        }

        @Override
        protected Tool delegate() {
            return described;
        }

        @Override
        public dev.agentkit.core.tool.ToolResult execute(dev.agentkit.core.tool.ToolInvocation invocation) {
            dev.agentkit.mcp.McpCallResult result = client.callTool(invocation.name(), invocation.arguments());
            return result.isError() ? dev.agentkit.core.tool.ToolResult.error(result.text())
                    : dev.agentkit.core.tool.ToolResult.ok(result.text());
        }
    }

    /**
     * One of the agent's tools, as an operator reviews it: where it comes from, what it declares, and what the agent
     * definition adds — a confirmation, arguments bound to the person.
     */
    public record ToolInfo(String connector, String name, String description, String effect, String system,
                           boolean confirmed, Map<String, String> bound, String sideEffects) {
    }

    /** The agent's tools, in the order it is given them. */
    public List<ToolInfo> toolInfo() {
        return selected.stream().map(s -> new ToolInfo(s.connector(), s.entry().tool().name(),
                s.entry().tool().description(), s.entry().declaration().effect().wire(),
                s.entry().declaration().system(), confirmed.contains(s.entry().tool().name()), s.bindings(),
                s.entry().tool().sideEffects().name().toLowerCase(java.util.Locale.ROOT))).toList();
    }

    /** The names of tools that stop for the person's confirmation. */
    public Set<String> confirmed() {
        return confirmed;
    }

    /** Stops the confirmed tools for {@code approver}'s decision. */
    public ToolGate gate(Approver approver) {
        return ToolGates.requireApproval(invocation -> confirmed.contains(invocation.name()), approver);
    }

    /**
     * The system prompt for a turn: the operator's prompt and policy, then who the agent is talking to — their
     * directory record, fenced, since people type what is in it — and the time.
     */
    public String systemPrompt(Principal principal, Instant now) {
        boolean chat = definition.pattern() == AgentDefinition.Pattern.CHAT;
        return prompt(definition.systemPrompt(), chat, chat, principal, now);
    }

    /**
     * For {@link AgentDefinition.Pattern#PLAN_EXECUTE}: the prompt the plan is made with — the planner's prompt, the
     * policy, who the person is and the time. The policy is settled here, while planning; each step then runs with
     * {@link #systemPrompt}, the executor's prompt, the person and the time.
     */
    public String plannerPrompt(Principal principal, Instant now) {
        return prompt(Objects.requireNonNull(definition.plannerPrompt(), "plannerPrompt"), true, true, principal, now);
    }

    /**
     * @param withInput whether to say what input the agent works from, so a request in plain words that lacks some of it
     *                  can be completed by asking — for the prompt that reads the request, not for a step of a plan
     */
    private String prompt(String base, boolean withPolicy, boolean withInput, Principal principal, Instant now) {
        StringBuilder record = new StringBuilder("- email: ").append(principal.email()).append('\n');
        new TreeMap<>(principal.facts()).forEach((field, value) -> {
            if (!field.equals("email")) {
                record.append("- ").append(OneLine.of(field)).append(": ")
                        .append(value.isBlank() ? "(none)" : OneLine.of(value)).append('\n');
            }
        });
        StringBuilder prompt = new StringBuilder(base);
        if (withPolicy && !definition.policy().isBlank()) {
            prompt.append("\n\n").append(definition.policy());
        }
        if (withInput && definition.input() != null) {
            prompt.append("\n\nA request to you is for a task that works from these fields. It may come as the fields "
                    + "themselves, or in plain words; when a request in plain words does not say a required one, "
                    + "ask the person for it rather than guess:\n").append(definition.input().describe());
        }
        prompt.append("\n\nYou are talking to one person, and you act only as them. Their record in the directory:\n")
                .append(Spotlight.wrap(Spotlight.Kind.EVIDENCE, Source.of("directory", principal.email()),
                        record.toString()))
                .append("\n\nThe time now is ").append(now).append(" (UTC).");
        return prompt.toString();
    }

    /** Where this agent's deferred work looks its subjects up, if it schedules any. */
    public Optional<SubjectResolver> subjects() {
        return subjects;
    }

    /**
     * Who a deferred action runs as: the agent itself, under its {@code deferred.actor} name, with no groups and no
     * record. Its bindings fill {@code principal.email} with that name, which the connector recognises as the agent.
     */
    public Optional<Principal> actor() {
        return Optional.ofNullable(definition.deferred())
                .map(d -> new Principal(repo.org(), d.actor(), d.actor(), Set.of(), Map.of()));
    }

    /** How a deferred action's model is configured: the deferred prompt, and a shorter leash than a conversation. */
    public Optional<AgentConfig> deferredConfig() {
        return Optional.ofNullable(definition.deferred()).map(d -> AgentConfig.builder(model()).systemPrompt(d.prompt())
                .maxSteps(DEFERRED_MAX_STEPS).maxTokens(DEFERRED_MAX_TOKENS).build());
    }

    public AgentConfig config(String systemPrompt) {
        return AgentConfig.builder(model()).systemPrompt(systemPrompt).maxSteps(definition.maxSteps())
                .maxTokens(definition.maxTokens()).build();
    }

    /**
     * The agent for one chat turn, acting as {@code principal}: its tools bound to them, confirmed tools stopping for
     * them through the session's approver, and a question for them through {@code runtime}.
     *
     * @throws ChatUnavailable with a sentence for the person, when the agent cannot run or is not for them
     */
    public Agent turn(ChatRuntime.Session session, LlmClient llm, Principal principal, Instant now, ChatRuntime runtime) {
        return turn(session, llm, principal, now, runtime, List.of());
    }

    /** {@link #turn}, with tools the host adds for this turn, such as the scheduler for deferred work. */
    public Agent turn(ChatRuntime.Session session, LlmClient llm, Principal principal, Instant now, ChatRuntime runtime,
                      List<Tool> alsoGiven) {
        return builder(session, llm, principal, now, runtime, alsoGiven).build();
    }

    /**
     * How a turn with this agent is carried out, as {@code principal}: its own loop for a chat agent, or for a
     * plan-and-execute agent a plan made once and then carried out step by step ({@link PlanExecuteTurn}), every step
     * with the same tools, bindings and confirmations a chat turn has.
     *
     * @throws ChatUnavailable with a sentence for the person, when the agent cannot run or is not for them
     */
    public ChatRuntime.Runner runner(ChatRuntime.Session session, LlmClient llm, Principal principal, Instant now,
                                     ChatRuntime runtime, List<Tool> alsoGiven) {
        if (definition.pattern() == AgentDefinition.Pattern.PLAN_EXECUTE) {
            check(principal);
            return new PlanExecuteTurn(this, llm, principal, now,
                    () -> builder(session, llm, principal, now, runtime, alsoGiven).streaming(false).build(), session);
        }
        return turn(session, llm, principal, now, runtime, alsoGiven)::run;
    }

    private Agent.Builder builder(ChatRuntime.Session session, LlmClient llm, Principal principal, Instant now,
                                  ChatRuntime runtime, List<Tool> alsoGiven) {
        check(principal);
        List<Tool> tools = new ArrayList<>(tools(principal).entries().stream().map(DeclaredTools.Entry::tool).toList());
        tools.addAll(alsoGiven);
        tools.add(ChatTools.askPerson(runtime, session));
        ToolGate gate = gate(session.approver());
        return session.agent(llm, new SimpleToolRegistry(tools), config(systemPrompt(principal, now)))
                .name(definition.id() + (rehearsal ? "-rehearsal" : ""))
                .toolGate(rehearsal ? ToolGates.allOf(rehearsalGate(changing(alsoGiven)), gate) : gate);
    }

    private void check(Principal principal) {
        if (unavailable.isPresent()) {
            throw new ChatUnavailable(definition.name() + " is unavailable. " + unavailable.get());
        }
        if (!admits(principal)) {
            throw new ChatUnavailable(definition.name() + " is not available to you.");
        }
    }

    /**
     * How a chat runtime that serves only this agent builds it for a turn.
     *
     * @param principals who a conversation's tenant is; empty for someone the organization does not know
     * @param runtime    the runtime being built around this, for asking the person a question
     */
    public ChatRuntime.Agents chatAgents(Optional<LlmClient> llm, Function<String, Optional<Principal>> principals,
                                         Supplier<Instant> clock, Supplier<ChatRuntime> runtime) {
        Objects.requireNonNull(llm, "llm");
        Objects.requireNonNull(principals, "principals");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(runtime, "runtime");
        return new ChatRuntime.Agents() {
            @Override
            public Agent agentFor(ChatRuntime.Session session) {
                return turn(session, llm(), principal(session), clock.get(), runtime.get());
            }

            @Override
            public ChatRuntime.Runner runnerFor(ChatRuntime.Session session) {
                return runner(session, llm(), principal(session), clock.get(), runtime.get(), List.of());
            }

            private LlmClient llm() {
                return llm.orElseThrow(() -> new ChatUnavailable("No model is configured for " + definition.name() + "."));
            }

            private Principal principal(ChatRuntime.Session session) {
                return principals.apply(session.tenantId())
                        .orElseThrow(() -> new ChatUnavailable("You are not in this organization's directory."));
            }
        };
    }
}
