package dev.agentkit.core.supervisor;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.prompt.Spotlight;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A named, specialised worker a {@link Supervisor} can delegate a subgoal to.
 *
 * <p>A subagent pairs an {@link Agent} with an identity: a stable {@code name}
 * the supervisor routes by, and a one-line {@code description} of what it is good
 * at (shown to an LLM supervisor so it can choose). Each delegation runs a
 * <em>fresh</em> agent obtained from the {@link Supplier}, so per-run stateful
 * collaborators (a {@code DisclosingToolRegistry} that accumulates revealed
 * tools, a {@code WorkingMemory} scratchpad) never leak between delegations or
 * across parallel fan-out — the same reason {@code SelfVerifyingAgent} takes a
 * supplier.
 *
 * <h2>The name reaches the trace, not only the routing (#311)</h2>
 *
 * <p>Every factory here that has an {@link Agent} to call runs it under
 * {@code AgentRun.of(name)}, so the identity each of that child's observer callbacks carries
 * is the roster name — the same string {@code SubagentTools.delegateTool} puts in
 * {@code delegate}'s arguments, so the two join up in a shared trace. This is the reason the
 * name is stamped here rather than left to {@code Agent.Builder.name}: the child's agent is
 * built inside a {@code Supplier} that has never heard of the roster, so a deployment naming
 * it would be writing the same string twice and a deployment forgetting would leave the case
 * #311 is about — a call the policy stopped inside a child — attributed to nobody.
 *
 * <h2>And the parent, when the delegation came through a dispatch that knew one (#317)</h2>
 *
 * <p>{@link #handle(Goal, AgentRun)} takes the delegating run and mints the child under it
 * through {@link AgentRun#child(String)}, so the child's rows say whose behalf they are on
 * and not only whose they are. {@code SubagentTools}' {@code delegate} is what calls it: it
 * learns the executing run from {@link dev.agentkit.core.tool.Tool#boundTo(AgentRun)} and
 * passes it through. {@link #handle(Goal)} is the same delegation with no parent known —
 * what {@code Supervisor.fanOut} and a caller holding a {@link Subagent} directly get — and
 * it produces a child with an empty {@link AgentRun#parent()} rather than a guessed one.
 *
 * <p>{@link #handling(String, String, Function)} is the one shape that cannot be stamped at
 * all: the handler owns the run and this class never sees an {@code Agent}.
 * {@link #handlingUnder(String, String, BiFunction)} is the answer for a handler that
 * <em>is</em> running one — the framework mints the identity, parent and all, and hands it
 * over to be passed to {@link Agent#run(Goal, AgentRun)}.
 *
 * <h2>What it holds that a supervisor's tool has to declare for it (#313)</h2>
 *
 * <p>{@link #holdsGateWaitingForAHuman()} and {@link #holdsGateBoundToOneRun()} are the two
 * hazards {@link dev.agentkit.core.tool.Tool} declares, asked of a subagent so that
 * {@code delegate} can OR them across the roster. Both default to {@code false} and both are
 * set by the deployment rather than derived, because the recommended factory builds its agent
 * inside a {@code Supplier} nothing here may call: asking a {@code Supplier<Agent>} what its
 * gate does means building an agent, which is the per-delegation freshness this class exists
 * to preserve. Deriving it in {@link #of(String, String, Agent)} alone — where an
 * {@code Agent} is in hand — was rejected for a worse reason than cost: an answer that comes
 * from the wiring in one overload and from a declaration in the other is one a deployment
 * learns is automatic and then omits on the factory where it is not.
 */
public final class Subagent {

    private final String name;
    private final String description;
    /**
     * The delegation, and the parent it is being made on behalf of.
     *
     * <p>A {@link BiFunction} rather than the {@link Function} this held before #317,
     * because the parent has to reach the {@code AgentRun.of} call inside the factories and
     * there is nowhere else for it to travel. {@link #handling(String, String, Function)}
     * still takes a {@code Function} and still exists: it adapts to this by dropping the
     * parent, which is what its own contract already says about the identity.
     */
    private final BiFunction<Goal, Optional<AgentRun>, AgentResult> handler;
    private final boolean holdsGateWaitingForAHuman;
    private final boolean holdsGateBoundToOneRun;

    /**
     * An identifier, because the name reaches a line the framework writes.
     *
     * <p>The same rule {@code Peer} holds its name to, and for the same reason. A subagent
     * name is echoed in a header naming who answered — outside every fence, since that is
     * what a fence's attribution is for — so a name carrying a colon and a space writes a
     * sentence there. {@code Spotlight.name} reduces one that arrives as a tool argument,
     * because a refusal has to echo something and there is nobody to throw at; this is
     * wiring, where a bad name is a programming error and the place it was written is still
     * available to fix. {@link #named(String, String, Supplier)} is the other side of that
     * split, for a name that arrived as a tool argument.
     */
    private Subagent(String name, String description,
                     BiFunction<Goal, Optional<AgentRun>, AgentResult> handler,
                     boolean holdsGateWaitingForAHuman, boolean holdsGateBoundToOneRun) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.holdsGateWaitingForAHuman = holdsGateWaitingForAHuman;
        this.holdsGateBoundToOneRun = holdsGateBoundToOneRun;
        Spotlight.requireName(name, "Subagent");
    }

    /**
     * A subagent that builds a fresh {@link Agent} for each delegation (preferred).
     *
     * <p>A fresh {@link AgentRun} per delegation as well as a fresh agent, and for a related
     * reason: two delegations to this subagent are two runs, and one identity across both
     * would merge their rows in every trace that reads them. Under the delegating run when
     * the delegation came through one — see {@link #handle(Goal, AgentRun)}.
     */
    public static Subagent of(String name, String description, Supplier<Agent> agentFactory) {
        Objects.requireNonNull(agentFactory, "agentFactory");
        return new Subagent(name, description,
                (subgoal, parent) -> agentFactory.get().run(subgoal, runFor(name, parent)),
                false, false);
    }

    /**
     * {@link #of(String, String, Supplier)} for a name that arrived as a tool argument:
     * empty rather than thrown when the name is not one (#313).
     *
     * <p>The constructor calls {@code Spotlight.requireName}, which <strong>throws</strong>,
     * and that is right for wiring — a bad name there is a programming error and the line
     * that wrote it is available to fix. It is the wrong response to a name a model chose:
     * the model is the only party that can choose a different one, so it is the party to
     * tell, in an error {@code ToolResult}. That is the shape #166, #196 and #241 were each
     * closed with, and this is the factory that makes it available without a deployment
     * re-deriving the predicate:
     *
     * <pre>{@code
     * return Subagent.named(name, role, () -> worker(...))
     *         .map(built -> { roster.add(built); return ToolResult.ok("Built " + name); })
     *         .orElseGet(() -> ToolResult.error("Argument 'name' must be 1-40 characters of "
     *                 + "letters, digits, '.', '_' or '-' ..."));
     * }</pre>
     *
     * <p>Beside the throwing factory rather than replacing it, the way {@link Spotlight}
     * already ships {@code requireName} and {@code name} side by side for exactly this split.
     * A deployment that tested {@code Spotlight.isName} itself got the same answer; what it
     * did not get was any guarantee that its predicate and the constructor's stay the same
     * predicate, which is the defect {@code isName} was extracted to close in the first
     * place.
     *
     * <p><strong>Only the name is judged.</strong> Everything else the constructor rejects —
     * a null description, a null factory — stays a throw, because none of it can arrive from
     * a model: a tool handler supplies those itself. This returns empty for one reason, so a
     * caller mapping empty to a refusal cannot be wrong about what it is refusing.
     *
     * @return the subagent, or empty when {@code name} is not
     *     {@linkplain Spotlight#isName a name}
     */
    public static Optional<Subagent> named(String name, String description,
                                           Supplier<Agent> agentFactory) {
        if (!Spotlight.isName(name)) {
            return Optional.empty();
        }
        return Optional.of(of(name, description, agentFactory));
    }

    /**
     * A subagent that runs {@code handler} for each delegation, for when something has
     * to happen around the run rather than only inside the agent.
     *
     * <p>{@link Agent} is final and the other factories call {@code run} themselves, so
     * without this there is no way to get between the supervisor and a subagent's run —
     * which is what wrapping a delegation in a telemetry span, a timeout, or a per-
     * subagent budget all need:
     *
     * <pre>{@code
     * Subagent.handling("researcher", "searches the web",
     *         subgoal -> telemetry.invokeAgent("researcher", () -> agent.run(subgoal)));
     * }</pre>
     *
     * <p>The same freshness caution as {@link #of(String, String, Agent)} applies: build
     * or obtain the agent inside {@code handler} if it carries per-run state.
     *
     * <p><strong>This one does not stamp the run's identity</strong>, because the handler
     * owns the run and may not be running an {@code Agent} at all. A handler that is should
     * use {@link #handlingUnder(String, String, BiFunction)}, which is this factory with the
     * identity handed in — otherwise its rows in a shared trace carry whatever name that
     * agent was built with, which for an unnamed one is {@link AgentRun#ANONYMOUS}, and no
     * parent at all. See the class javadoc (#311, #317).
     *
     * <p><strong>Nor does it carry a declaration.</strong> A wrapper built here is a new
     * subagent and answers {@link #holdsGateWaitingForAHuman()} and
     * {@link #holdsGateBoundToOneRun()} {@code false} however loudly the subagent it wraps
     * says otherwise — the laundering {@code ForwardingTool} exists to stop, one level up.
     * {@link #holdingAGateWaitingForAHuman()} and {@link #holdingAGateBoundToOneRun()} are
     * how a wrapper re-states them; {@code SubagentTools.recordingParks} does not need to,
     * because it does not go through this factory.
     */
    public static Subagent handling(String name, String description, Function<Goal, AgentResult> handler) {
        Objects.requireNonNull(handler, "handler");
        return new Subagent(name, description, (subgoal, parent) -> handler.apply(subgoal),
                false, false);
    }

    /**
     * {@link #handling(String, String, Function)} for a handler that <em>is</em> running an
     * {@link Agent}: the identity to run it under is handed in (#317).
     *
     * <p>The one shape #317 called unfixable, fixed by moving one thing rather than by
     * taking the run away from the handler. The handler still owns the run — it can wrap it
     * in a span, a timeout, a retry — and is told which {@link AgentRun} that run should
     * carry, already stamped with this subagent's roster name and with the delegating run as
     * its {@link AgentRun#parent()} when one was known:
     *
     * <pre>{@code
     * Subagent.handlingUnder("researcher", "searches the web",
     *         (subgoal, run) -> telemetry.invokeAgent("researcher",
     *                 () -> agent.run(subgoal, run)));
     * }</pre>
     *
     * <p><strong>It is still a request, not a guarantee.</strong> Nothing here can make a
     * handler pass the run on, and a handler that ignores it — or that runs something which
     * is not an {@code Agent} at all — produces exactly what {@link #handling} produces.
     * What changes is that a handler which wants to attribute its rows correctly no longer
     * has to reconstruct the name and cannot reach the parent at all.
     *
     * <p>A separate name rather than an overload of {@code handling}: an implicitly typed
     * lambda disambiguates on arity, but a method reference does not, and a repository that
     * added the overload would be one {@code this::run} away from an ambiguity error in
     * somebody else's build.
     */
    public static Subagent handlingUnder(String name, String description,
                                         BiFunction<Goal, AgentRun, AgentResult> handler) {
        Objects.requireNonNull(handler, "handler");
        return new Subagent(name, description,
                (subgoal, parent) -> handler.apply(subgoal, runFor(name, parent)), false, false);
    }

    /**
     * Convenience for a stateless {@link Agent} that is safe to reuse across
     * delegations. Prefer {@link #of(String, String, Supplier)} when any
     * collaborator carries per-run state or the subagent may run in parallel with
     * itself — a single {@code Agent} shared across threads is not guaranteed safe.
     *
     * <p>The agent is shared and the identity is not: each delegation is still its own
     * {@link AgentRun} under this subagent's name, and under the delegating run when there
     * is one.
     */
    public static Subagent of(String name, String description, Agent agent) {
        Objects.requireNonNull(agent, "agent");
        return new Subagent(name, description,
                (subgoal, parent) -> agent.run(subgoal, runFor(name, parent)), false, false);
    }

    /**
     * This subagent, declaring that a gate somewhere inside it may block waiting for a
     * person (#283/#296/#313).
     *
     * <p>The subagent-shaped counterpart of {@link dev.agentkit.core.tool.Tool#holdsGateWaitingForAHuman()},
     * and it exists because the declaration could not be made at all: a supervisor's
     * {@code delegate} tool answered {@code false} for every roster, so a supervisor whose
     * subagent parks on an approver registered on a durable worker as holding nothing and the
     * approver was paged once per activity retry — the exact defect the tool-level
     * declaration exists to prevent, laundered by one layer of indirection.
     *
     * <p>A wither returning a copy, so the declaration reads at the wiring site next to the
     * factory that needed it:
     *
     * <pre>{@code
     * SubagentRoster.of(
     *         Subagent.of("publisher", "publishes drafts", () -> publisher(approver))
     *                 .holdingAGateWaitingForAHuman());
     * }</pre>
     *
     * <p>Over-declaring is safe and under-declaring is the hazard, which is why this is a
     * one-way switch: there is no {@code notHoldingAGate...} to undo it with, and a wrapper
     * that carries a subagent forward carries the declaration with it.
     */
    public Subagent holdingAGateWaitingForAHuman() {
        return new Subagent(name, description, handler, true, holdsGateBoundToOneRun);
    }

    /**
     * This subagent, declaring that a gate somewhere inside it was built for one run.
     *
     * <p>The sibling of {@link #holdingAGateWaitingForAHuman()} and the subagent-shaped
     * counterpart of {@link dev.agentkit.core.tool.Tool#holdsGateBoundToOneRun()}. The case
     * it is for is a subagent whose agent is gated by
     * {@code ToolGates.screeningAgainst(objective, screen)}: one run's objective, judging
     * every run's calls the day the supervisor holding it is registered on a shared worker.
     */
    public Subagent holdingAGateBoundToOneRun() {
        return new Subagent(name, description, handler, holdsGateWaitingForAHuman, true);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /**
     * Whether a gate inside this subagent may block waiting for a person.
     *
     * <p>Declared rather than detected — see {@link #holdingAGateWaitingForAHuman()} — and
     * ORed across the roster by {@code SubagentTools.delegateTool}, live, on every call.
     */
    public boolean holdsGateWaitingForAHuman() {
        return holdsGateWaitingForAHuman;
    }

    /** Whether a gate inside this subagent was built for one run. The sibling of the above. */
    public boolean holdsGateBoundToOneRun() {
        return holdsGateBoundToOneRun;
    }

    /**
     * Runs a fresh agent toward {@code subgoal}, on nobody's behalf in particular.
     *
     * <p>What a caller holding a {@link Subagent} directly gets, and what
     * {@code Supervisor.fanOut} calls. The child's {@link AgentRun#parent()} is empty, which
     * says "no dispatch that knew a parent was involved" — not "this is the top".
     */
    public AgentResult handle(Goal subgoal) {
        return dispatch(subgoal, Optional.empty());
    }

    /**
     * Runs a fresh agent toward {@code subgoal} on behalf of {@code parent} (#317).
     *
     * <p>The child's identity is {@code parent.child(name)}: this subagent's roster name, a
     * fresh id, and the delegating run above it. Called by {@code SubagentTools}'
     * {@code delegate} when {@link dev.agentkit.core.tool.Tool#boundTo(AgentRun)} told it
     * which run was executing it, and by nothing else — every other path takes
     * {@link #handle(Goal)} and produces no parent rather than a wrong one.
     *
     * @param parent the delegating run; never null, because "no parent" is
     *     {@link #handle(Goal)} and having two spellings of it is how one of them comes to
     *     mean the other
     */
    public AgentResult handle(Goal subgoal, AgentRun parent) {
        Objects.requireNonNull(parent, "parent");
        return dispatch(subgoal, Optional.of(parent));
    }

    /**
     * The identity one delegation runs under: under the delegating run when there is one,
     * and a fresh top-level one when there is not.
     *
     * <p>In one place because four factories mint it and a fifth spelling is how
     * {@code handlingUnder} would come to disagree with {@code of} about whether a parent is
     * carried.
     */
    private static AgentRun runFor(String name, Optional<AgentRun> parent) {
        return parent.map(p -> p.child(name)).orElseGet(() -> AgentRun.of(name));
    }

    /**
     * {@code subagent} with {@code around} interposed, keeping everything a wrapper must not
     * drop: the name, the description, both hazard declarations, and the parent.
     *
     * <p>Package-private because it is the seam {@code SubagentTools.recordingParks} needs
     * and not a feature. What it buys over building the wrapper with
     * {@link #handling(String, String, Function)} is exactly the four things that factory
     * loses, two of which fail open at a durable worker's registration check — a wrapper
     * that reported a parking subagent as holding no gate would be the laundering this
     * change was made to stop, committed by the class that added the declaration.
     *
     * @param around receives the subgoal and the parent, and is expected to reach
     *     {@code subagent.handle} with both
     */
    static Subagent wrapping(Subagent subagent,
                             BiFunction<Goal, Optional<AgentRun>, AgentResult> around) {
        Objects.requireNonNull(subagent, "subagent");
        Objects.requireNonNull(around, "around");
        return new Subagent(subagent.name(), subagent.description(), around,
                subagent.holdsGateWaitingForAHuman(), subagent.holdsGateBoundToOneRun());
    }

    /**
     * One delegation, with the parent already resolved to "this run" or "none".
     *
     * <p>The single entry both public {@code handle} methods and every wrapper go through,
     * so there is one place that decides what a missing parent means. Not an overload of
     * {@code handle}: {@code handle(goal, parent)} would then resolve by the static type of
     * a variable, which is how "no parent" comes to be spelled two ways and one of them
     * comes to mean the other.
     */
    AgentResult dispatch(Goal subgoal, Optional<AgentRun> parent) {
        Objects.requireNonNull(subgoal, "subgoal");
        Objects.requireNonNull(parent, "parent");
        return handler.apply(subgoal, parent);
    }
}
