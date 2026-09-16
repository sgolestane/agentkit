package dev.agentkit.eval;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.Quoted;
import dev.agentkit.core.verify.Verdict;
import dev.agentkit.core.verify.Verifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Built-in {@link Check}s: outcome, output, tool-use, budget, and LLM-as-judge.
 *
 * <p>Tool-use checks ({@link #usedTool}, {@link #didNotUseTool}) score the
 * trajectory, and {@link #judge} scores it with a rubric — the evaluation
 * dimensions a plain output verifier cannot reach. {@link #verifiedBy} reuses the
 * core verification package's critics (including {@code LlmVerifier}) for
 * output-versus-goal judging.
 *
 * <p>Two dimensions arrived later than the rest and are worth finding on purpose, because
 * nothing else here can express them:
 *
 * <ul>
 *   <li><strong>Order</strong> — {@link #inOrder}. Every other tool-use check asks a set
 *       question, and most of what an agent is actually asked for is a sequence: read before
 *       you act, take ownership before you change anything, read the state back
 *       <em>afterwards</em>.</li>
 *   <li><strong>How far a call got</strong> — {@link #refused} and {@link #parkedOn}. A call
 *       a policy refused, a call parked for a person, and a tool that ran and returned an
 *       error are one bit to {@link #didNotUseTool} and three different things to a
 *       reviewer.</li>
 *   <li><strong>What the call said</strong> — the {@link Args} overloads of
 *       {@link #usedTool(String, Args) usedTool},
 *       {@link #didNotUseTool(String, Args) didNotUseTool},
 *       {@link #attemptedTool(String, Args) attemptedTool} and
 *       {@link #didNotAttemptTool(String, Args) didNotAttemptTool}. {@link ToolCall} has
 *       always carried the arguments and nothing here read them, so
 *       {@code usedTool("identity.add_user_to_group")} passed on a run that added the wrong
 *       person to the wrong group.</li>
 *   <li><strong>What the world ended up holding</strong> — {@link #worldState}. Everything
 *       above scores the trajectory, which is a record of what the agent <em>asked for</em>
 *       and what the runtime reported back. It is not the same question as what actually
 *       changed, and neither answer implies the other; {@link #worldState} states the
 *       difference in full.</li>
 * </ul>
 */
public final class Checks {

    private Checks() {
    }

    /** Passes if the run finished with a {@code COMPLETED} stop reason. */
    public static Check completed() {
        return run -> run.result().isSuccess()
                ? CheckOutcome.pass("completed")
                : CheckOutcome.fail("completed", "stopReason was " + run.result().stopReason());
    }

    /** Passes if the final output contains {@code substring}. */
    public static Check outputContains(String substring) {
        Objects.requireNonNull(substring, "substring");
        return run -> run.result().output().contains(substring)
                ? CheckOutcome.pass("outputContains")
                : CheckOutcome.fail("outputContains", "output did not contain: " + substring);
    }

    /** Passes if the final output matches {@code regex} (a find, not a full match). */
    public static Check outputMatches(String regex) {
        Pattern pattern = Pattern.compile(regex);
        return run -> pattern.matcher(run.result().output()).find()
                ? CheckOutcome.pass("outputMatches")
                : CheckOutcome.fail("outputMatches", "output did not match /" + regex + "/");
    }

    /**
     * Passes if a tool named {@code name} actually ran and returned a non-error
     * result at least once. A call that was blocked by a gate, hit an unknown tool,
     * or errored does <em>not</em> count — use {@link #attemptedTool} for that.
     */
    public static Check usedTool(String name) {
        Objects.requireNonNull(name, "name");
        return run -> run.succeededToolNames().contains(name)
                ? CheckOutcome.pass("usedTool:" + name)
                : CheckOutcome.fail("usedTool:" + name,
                        "tool did not succeed; succeeded: " + run.succeededToolNames());
    }

    /**
     * Passes if a call to {@code name} that satisfied {@code arguments} actually ran and
     * returned a non-error result.
     *
     * <p>{@link #usedTool(String)} with the arguments read. That check scores that
     * <em>something</em> happened; this one scores that the right thing happened, which is
     * the difference between a control and a control-shaped statement — see {@link Args} for
     * the defect it closes.
     *
     * <p><strong>One call has to do both.</strong> The condition is evaluated per call, so
     * {@code Args.equalTo("user", …).and("group", …)} demands a single successful call that
     * named both. Two separate checks, one per argument, are satisfied by two different
     * calls.
     *
     * <p><strong>The distinctions {@link #usedTool(String)} draws are untouched.</strong> A
     * matching call that a gate refused, parked, or that ran and returned an error does not
     * satisfy this, exactly as before — {@link #attemptedTool(String, Args)} is how a case
     * scores the request itself. What the condition narrows is <em>which</em> calls are
     * looked at, never how far a call had to get.
     *
     * <p>The residual, which no argument-aware check can close: the condition sees the
     * arguments the model <strong>proposed</strong>, and on a runtime that narrows or
     * substitutes them the call that ran is not the call that matched. {@link #worldState} is
     * the check that reads the other end.
     */
    public static Check usedTool(String name, Args arguments) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        String label = "usedTool:" + name + "[" + arguments.description() + "]";
        return run -> matched(run, name, arguments, ToolCall::succeeded)
                ? CheckOutcome.pass(label)
                : CheckOutcome.fail(label, "no successful call to it matched; the calls to it"
                        + " carried " + callsTo(run, name) + " with dispositions "
                        + dispositions(run, name));
    }

    /**
     * Passes if a tool named {@code name} never <em>successfully</em> ran — so a call
     * a gate blocked counts as "not used" (the safe outcome). Use
     * {@link #didNotAttemptTool} to also forbid the model from requesting it.
     */
    public static Check didNotUseTool(String name) {
        Objects.requireNonNull(name, "name");
        return run -> run.succeededToolNames().contains(name)
                ? CheckOutcome.fail("didNotUseTool:" + name, "tool ran successfully")
                : CheckOutcome.pass("didNotUseTool:" + name);
    }

    /**
     * Passes if no call to {@code name} satisfying {@code arguments} ever successfully ran.
     *
     * <p>{@link #didNotUseTool(String)} narrowed to the calls a case actually objects to. The
     * blanket form is the stronger statement and should be preferred wherever it is true; use
     * this one where it is <em>not</em> — where the tool has legitimate work to do in the same
     * run as the thing being forbidden, and a blanket prohibition would forbid the legitimate
     * work too. The itops injection case is precisely that shape.
     *
     * <p><strong>Read the name literally: it forbids a matching call from succeeding, and
     * nothing else.</strong> A call to {@code name} that succeeded with arguments the
     * condition does not cover satisfies this check. That is what the check says and it is
     * not a weakening of {@link #didNotUseTool(String)}, which is still there and still means
     * what it meant — but a reader who skims this as "it did not use the tool" will be
     * wrong.
     *
     * <p><strong>A negative check over model-written arguments is evadable and it is worth
     * knowing exactly how.</strong> {@link Args#equalTo} compares exactly, so a trailing
     * space, a different capitalisation, a Unicode-confusable spelling, or an argument passed
     * under a second name the schema also accepts all walk through — and a runtime that
     * strips or normalises before it acts may still perform the very change the case forbids.
     * Widening the condition with {@link Args#matching} narrows the gap and cannot close it,
     * because the set of spellings that reach the same effect is a property of the tool
     * rather than of the check. <strong>Where the property that matters is that the world did
     * not change, assert that</strong>, with {@link #worldState}; this check is worth keeping
     * alongside it to say which trajectory produced the clean world, not instead of it.
     */
    public static Check didNotUseTool(String name, Args arguments) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        String label = "didNotUseTool:" + name + "[" + arguments.description() + "]";
        return run -> matched(run, name, arguments, ToolCall::succeeded)
                ? CheckOutcome.fail(label, "a matching call ran successfully; the calls to it"
                        + " carried " + callsTo(run, name))
                : CheckOutcome.pass(label);
    }

    /** Passes if the model requested a tool named {@code name}, whatever the result. */
    public static Check attemptedTool(String name) {
        Objects.requireNonNull(name, "name");
        return run -> run.toolNames().contains(name)
                ? CheckOutcome.pass("attemptedTool:" + name)
                : CheckOutcome.fail("attemptedTool:" + name, "tool not requested; requested: " + run.toolNames());
    }

    /**
     * Passes if the model requested {@code name} with arguments satisfying
     * {@code arguments}, whatever the result.
     *
     * <p>{@link #attemptedTool(String)} with the arguments read, and the check for "it asked
     * for exactly the dangerous thing" on a run where a gate stopped it. Keeping the
     * attempted/used pair separate at the argument level matters for the same reason it
     * matters without arguments: a run that <em>asked</em> to add mallory to the
     * administrators and was refused, and a run that never had the idea, are two different
     * agents and one bit apart under {@link #usedTool(String, Args)}.
     */
    public static Check attemptedTool(String name, Args arguments) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        String label = "attemptedTool:" + name + "[" + arguments.description() + "]";
        return run -> matched(run, name, arguments, call -> true)
                ? CheckOutcome.pass(label)
                : CheckOutcome.fail(label, "no call to it matched; the calls to it carried "
                        + callsTo(run, name));
    }

    /**
     * Passes if the model never even requested a tool named {@code name} — stricter
     * than {@link #didNotUseTool}, useful for flagging a dangerous <em>attempt</em>
     * that a gate happened to block.
     */
    public static Check didNotAttemptTool(String name) {
        Objects.requireNonNull(name, "name");
        return run -> run.toolNames().contains(name)
                ? CheckOutcome.fail("didNotAttemptTool:" + name, "tool was requested")
                : CheckOutcome.pass("didNotAttemptTool:" + name);
    }

    /**
     * Passes if the model never requested {@code name} with arguments satisfying
     * {@code arguments} — stricter than {@link #didNotUseTool(String, Args)}, because a
     * matching call a gate refused still fails it.
     *
     * <p>The evadability note on {@link #didNotUseTool(String, Args)} applies here in full,
     * and one degree worse: this check scores the request, so it can only ever see the
     * spellings the condition anticipated. It says something about the model's intent that no
     * reading of the world can say, and it says nothing at all about the world.
     */
    public static Check didNotAttemptTool(String name, Args arguments) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        String label = "didNotAttemptTool:" + name + "[" + arguments.description() + "]";
        return run -> matched(run, name, arguments, call -> true)
                ? CheckOutcome.fail(label, "a matching call was requested; the calls to it"
                        + " carried " + callsTo(run, name))
                : CheckOutcome.pass(label);
    }

    /** Whether any call to {@code name} passing {@code far} satisfied {@code arguments}. */
    private static boolean matched(EvalRun run, String name, Args arguments,
            Predicate<ToolCall> far) {
        return run.toolCalls().stream()
                .filter(call -> call.name().equals(name))
                .filter(far)
                .anyMatch(call -> arguments.matches(call.invocation()));
    }

    /**
     * Passes if the named tools all ran successfully, in this relative order.
     *
     * <p>The check a trajectory could not previously express. Every other tool-use check here
     * asks a set question — was this tool used, was it attempted — and the demands agents are
     * actually given are usually sequences: read the ticket before you act on it, take
     * ownership before you change anything downstream, read the state back <em>after</em>
     * changing it.
     *
     * <p><strong>A subsequence, not a prefix and not adjacency.</strong> Other calls may come
     * before, between and after. That is what makes the "read it back afterwards" case work
     * at all: a run that reads a group, changes it, and reads it again satisfies
     * {@code inOrder("identity.add_user_to_group", "identity.get_group_members")} on the
     * second read, and a run that never reads it back fails — where an index-of comparison
     * would be satisfied by the read that came <em>first</em>, which is the check passing on
     * the run it exists to catch.
     *
     * <p><strong>Scored on the calls that succeeded</strong>, which is the whole difference
     * between this and a check on {@link EvalRun#toolNames()}. A {@code get_ticket} a gate
     * refused did not read the ticket, and letting it satisfy "read before you act" would let
     * a refused call discharge the promise to make it. Use {@link #attemptedTool} alongside
     * if the attempt is separately interesting.
     *
     * <p>Repeats are matched greedily from the left, so naming the same tool twice means "at
     * least two successful calls to it".
     *
     * @param toolNames at least two names, in the order they must occur
     * @throws IllegalArgumentException if fewer than two names are given. One name is
     *     {@link #usedTool} wearing a costume and none passes vacuously — and a check that
     *     cannot fail is worse than no check, because a suite containing it reports a
     *     control
     */
    public static Check inOrder(String... toolNames) {
        Objects.requireNonNull(toolNames, "toolNames");
        List<String> wanted = List.of(toolNames);
        if (wanted.size() < 2) {
            throw new IllegalArgumentException("an ordering check needs at least two tool"
                    + " names; " + wanted.size() + " states no order and would pass on any"
                    + " run");
        }
        String label = "inOrder:" + String.join(">", wanted);
        return run -> {
            List<String> ran = run.succeededToolNames();
            int at = 0;
            for (String name : ran) {
                if (at < wanted.size() && wanted.get(at).equals(name)) {
                    at++;
                }
            }
            return at == wanted.size()
                    ? CheckOutcome.pass(label)
                    : CheckOutcome.fail(label, "reached " + at + " of " + wanted.size()
                            + " in order; the tools that succeeded were " + ran);
        };
    }

    /**
     * Passes if a gate refused a call to {@code name} outright.
     *
     * <p>{@link Disposition#REFUSED} and nothing else. Not {@link #didNotUseTool}, which is
     * satisfied by a tool that ran and returned an error, by an unknown tool, and by a call
     * that was never made at all; and not {@link #parkedOn}, because a park is a question
     * somebody can still answer yes to. The distinction is the point — a policy that stopped
     * a call and a tool that reported a failure are the same {@code isError()} bit and mean
     * opposite things about the run.
     */
    public static Check refused(String name) {
        Objects.requireNonNull(name, "name");
        return run -> run.toolCalls().stream()
                .anyMatch(call -> call.name().equals(name) && call.refused())
                ? CheckOutcome.pass("refused:" + name)
                : CheckOutcome.fail("refused:" + name,
                        "no call to it was refused; dispositions were " + dispositions(run, name));
    }

    /**
     * Passes if a gate stopped a call to {@code name} pending somebody's decision.
     *
     * <p>The outcome a human-in-the-loop agent is usually evaluated <em>for</em>, and one no
     * other check can name: to {@link #didNotUseTool} a parked call and a refused one are the
     * same answer, and the difference is whether anybody was asked.
     */
    public static Check parkedOn(String name) {
        Objects.requireNonNull(name, "name");
        return run -> run.toolCalls().stream()
                .anyMatch(call -> call.name().equals(name) && call.parked())
                ? CheckOutcome.pass("parkedOn:" + name)
                : CheckOutcome.fail("parkedOn:" + name,
                        "no call to it was parked; dispositions were " + dispositions(run, name));
    }

    /**
     * Passes if no call in the run was refused by a gate.
     *
     * <p>The check for the other direction, and the one a suite needs more often than it
     * looks: a policy that fires on a run doing exactly what it was asked is worse than no
     * policy, because it trains whoever reads the report to skim. Put it on the happy-path
     * case, where its job is to fail the day a new rule starts costing correct behaviour.
     *
     * <p>A parked call does not fail this. A park is a person being asked, which is an
     * outcome a human-in-the-loop agent is <em>supposed</em> to reach — {@link #parkedOn} is
     * how a case says it expected one.
     */
    public static Check nothingWasRefused() {
        return run -> {
            List<String> refused = run.toolCalls().stream()
                    .filter(ToolCall::refused)
                    .map(ToolCall::name)
                    .toList();
            return refused.isEmpty()
                    ? CheckOutcome.pass("nothingWasRefused")
                    : CheckOutcome.fail("nothingWasRefused", "policy refused " + refused);
        };
    }

    /**
     * Passes if {@code expected} holds of what {@code reading} reports once the run is over.
     *
     * <p>The check that asks the system under test what actually changed, rather than asking
     * the transcript what the agent said it was doing. Every other check here scores the
     * trajectory; this one scores the world.
     *
     * <h4>The seam, and why it is a caller's lambda</h4>
     *
     * <p>{@code agentkit-eval} cannot know what "the world" is. It has no identity provider,
     * no ticket system and no database, and the dependency runs the other way — an example
     * module depends on this one, so shipping an identity-aware check here would invert it.
     * What is shippable is the <em>shape</em>: the caller closes over whatever holds the
     * state, this reads it after the run, and the reading is kept separate from the predicate
     * so a failure can print what was actually there. A {@code BooleanSupplier} would have
     * been half the API and would have produced failure reports that say only "false".
     *
     * <h4>What a pass guarantees</h4>
     *
     * <p>Exactly one thing: <strong>at the moment this check ran, the caller's reading of the
     * world satisfied the caller's predicate.</strong> Everything else a reader might take
     * from it has to be established some other way.
     *
     * <ul>
     *   <li><strong>It does not say the agent caused it.</strong> The reading is taken
     *       afterwards, so anything else holding the same objects — an earlier case in the
     *       same dataset sharing a connector, a fixture, a background thread — is
     *       indistinguishable from the agent. Give each case a world nothing else has
     *       touched, or compare against a baseline captured before the run; this check cannot
     *       do either for you.</li>
     *   <li><strong>It does not say the trajectory was sane.</strong> A run that granted
     *       access, revoked it, granted it again, leaked the group's membership into a public
     *       comment and called ten tools it had no business calling ends in the same final
     *       state as a clean run. Only the trajectory checks can see that, and a suite that
     *       replaces them with this one has stopped scoring the agent's conduct.</li>
     *   <li><strong>It cannot see what the reading does not cover.</strong> It reads what the
     *       caller reads. A grant into a group nobody looked at, a row written to a table
     *       nobody queried, is invisible — which is the failure mode a suite of narrow
     *       readings quietly accumulates.</li>
     *   <li><strong>It cannot see anything transient.</strong> It runs once, at the end. A
     *       change the agent made and undid reads clean, and for a destructive action undone
     *       badly that is not the same thing as never having happened.</li>
     * </ul>
     *
     * <h4>And the converse, because neither of these subsumes the other</h4>
     *
     * <p>A trajectory check does not say the world changed either.
     * {@link #usedTool(String, Args)} scores that a call returned a non-error result, which is
     * the <em>tool's</em> report about itself. An idempotent add that answers "already a
     * member" is a success that changed nothing; a tool that reports success and drops the
     * write is a success that changed nothing; a gate that narrowed the arguments between the
     * proposal and the execution is a success that changed something else. Only a reading of
     * the world separates those, and only the trajectory says which calls got the world
     * there. A case that cares about both has to write both — that is two checks, on purpose,
     * and the pair is not redundant.
     *
     * <h4>Failure text</h4>
     *
     * <p>The reading is rendered through {@link Quoted#distinguishably}, bounded at
     * {@link #MAX_READING_CHARS}. That is not decoration: what a world-state reading contains
     * is, very often, an argument the model wrote — the email address it chose to add is the
     * string that comes back in the group's membership — and a failure message is read by a
     * person, in a terminal, which is the shape #278 named. Escaping to ASCII costs the
     * common case nothing, since an ASCII reading is unchanged.
     *
     * @param description what the world is expected to hold, in a few words; never
     *     {@code null} or blank. First-party text: it becomes the check's label verbatim, so
     *     do not build one out of a model's output
     * @param reading     how to read the state, run once when the check runs; never
     *     {@code null}. A reading that throws fails the check with this description attached,
     *     rather than being left to {@code EvalHarness}'s catch-all, which would report a
     *     stack message with no indication of which world-state check produced it
     * @param expected    the condition on the reading; never {@code null}
     * @param <T>         whatever the reading returns — a list of members, a row, a count
     */
    public static <T> Check worldState(String description, Supplier<T> reading,
            Predicate<T> expected) {
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("a world-state check needs a description; a"
                    + " blank one leaves the report unable to say what was expected of the"
                    + " world");
        }
        Objects.requireNonNull(reading, "reading");
        Objects.requireNonNull(expected, "expected");
        String label = "worldState:" + description;
        return run -> {
            T actual;
            try {
                actual = reading.get();
            } catch (RuntimeException failed) {
                return CheckOutcome.fail(label, "the world could not be read: "
                        + Quoted.distinguishably(String.valueOf(failed), MAX_READING_CHARS));
            }
            return expected.test(actual)
                    ? CheckOutcome.pass(label)
                    : CheckOutcome.fail(label, "the world held '"
                            + Quoted.distinguishably(String.valueOf(actual), MAX_READING_CHARS)
                            + "'");
        };
    }

    /**
     * How much of a world-state reading a failure message shows.
     *
     * <p>Two thousand, which is {@code ArgumentText.MAX_CHARS} — the number this repository
     * already uses for "a block of call arguments a person has to reason about". A reading is
     * the same kind of thing one reader over, and a second number for it would be one more
     * constant to misremember.
     *
     * <p>Measured on the readings the itops cases actually take: the seeded groups render to
     * 2, 17, 19 and 55 characters, and the one the routine case reads is 38 after the grant.
     * So the bound is a factor of thirty-six clear of the largest reading that occurs, and it
     * is here for the reading nobody predicted — a group whose membership is the whole tenant,
     * a query that answers with a table. It is applied through {@link Quoted#distinguishably},
     * which cuts before the escaping and again after, so a reading of 200,000 newlines — the
     * shape that expands sixfold — comes out at 2,032 characters including the sentence around
     * it rather than at 1.2 MB.
     */
    private static final int MAX_READING_CHARS = 2_000;

    /**
     * How many calls to one tool a failure message enumerates.
     *
     * <p>An agent that called the same tool two hundred times has a problem the first few
     * calls already show, and a check's {@code detail} is a line in a report rather than a
     * transcript. What follows the shown calls is a count, so the reader is never left
     * thinking they saw all of them.
     */
    private static final int MAX_CALLS_SHOWN = 5;

    /** How many arguments of one call a failure message enumerates. */
    private static final int MAX_ARGUMENTS_SHOWN = 20;

    /**
     * How much of one {@code key=value} entry a failure message shows.
     *
     * <p>These are model-written and unbounded in practice — a tool that takes a document
     * body takes as much of one as the model cares to write. Cut here so that no single
     * argument can take the whole message.
     *
     * <p>The residual, stated rather than glossed: a value that is a nested container is
     * rendered by {@code String.valueOf} and cut <em>afterwards</em>, so the intermediate is
     * as large as the container. {@code ToolInvocation} bounds that at construction —
     * {@code Frozen.MAX_NODES}, 100,000 expanded values — and this is a failure path in a
     * test harness building one message, not a log line on a hot path, so the cut-after is
     * the cheaper trade. Cutting before would mean rendering the container ourselves and
     * losing the fidelity the entry exists to provide.
     */
    private static final int MAX_ARGUMENT_CHARS = 200;

    /**
     * How much of the <em>escaped</em> listing reaches the message.
     *
     * <p>A second ceiling because the escaping runs after the per-entry cut and never
     * shrinks, which is the half a single bound gets wrong — the {@code cut -> expand -> cut}
     * seam {@link Quoted#distinguishably} runs, in a place that has to run it for itself
     * because {@link Quoted#each} bounds a count and not a length.
     *
     * <p>Measured on forty calls of sixty 10,000-character arguments, which is what the three
     * count bounds already reduce to five calls of twenty 200-character entries:
     *
     * <pre>
     * argument content        whole detail without this bound   with it
     * plain ASCII                                      22,335     8,222
     * newlines (6x escaped)                           119,085     8,222
     * </pre>
     *
     * <p>Eight thousand rather than a number derived for this site, for
     * {@code Quoted.MAX_ESCAPED_MESSAGE_CHARS}' reason and to the same figure: it is twice the
     * window this repository already gives a model for a failure message, and a person reading
     * a check's {@code detail} in a report is not helped by the twenty-second kilobyte. What
     * it costs is the tail of a genuinely wide listing, which says {@code Cut.MARKER} and can
     * be read in full in the trajectory the report carries alongside.
     */
    private static final int MAX_DETAIL_CHARS = 8_000;

    /**
     * Every call to {@code name} and what it carried, quoted and bounded, for a failure
     * message.
     *
     * <p>Through {@link Quoted#each}, which escapes what could end the line or drive the
     * terminal and escapes the delimiter it uses. The count bounds are applied here rather
     * than left to {@code Quoted.each}'s own, so the tally a reader is shown is the tally of
     * what was dropped rather than of what one layer happened to see.
     *
     * <p>Calls are numbered so a reader can tell one call's arguments from the next: a flat
     * list of {@code key=value} would show a run that named the right user in one call and
     * the right group in another as though a single call had done both, which is exactly the
     * confusion {@link Args#and(Args)} exists to prevent at the other end.
     */
    private static String callsTo(EvalRun run, String name) {
        List<ToolCall> calls = run.toolCalls().stream()
                .filter(call -> call.name().equals(name))
                .toList();
        int shownCalls = Math.min(calls.size(), MAX_CALLS_SHOWN);
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < shownCalls; i++) {
            String at = "#" + (i + 1) + " ";
            Map<String, Object> arguments = calls.get(i).invocation().arguments();
            int shown = 0;
            for (Map.Entry<String, Object> argument : arguments.entrySet()) {
                if (shown == MAX_ARGUMENTS_SHOWN) {
                    entries.add(at + "and " + (arguments.size() - MAX_ARGUMENTS_SHOWN)
                            + " more arguments");
                    break;
                }
                shown++;
                entries.add(Cut.to(at + argument.getKey() + "=" + argument.getValue(),
                        MAX_ARGUMENT_CHARS));
            }
            if (arguments.isEmpty()) {
                entries.add(at + "no arguments");
            }
        }
        String rendered = Cut.to(Quoted.each(entries, entries.size()), MAX_DETAIL_CHARS);
        return calls.size() > shownCalls
                ? rendered + " and " + (calls.size() - shownCalls) + " more calls"
                : rendered;
    }

    /**
     * How many dispositions a failure message lists.
     *
     * <p>Bounded for the reason every other listing here is, and it was not: the number of
     * calls to one tool is the model's to choose, so a run that called the same tool ten
     * thousand times wrote a hundred kilobytes of enum names into one line. The values
     * themselves are a fixed six-constant alphabet and cannot be hostile — this is a bound on
     * volume alone, which is why it is a plain count rather than a trip through
     * {@link Quoted}.
     */
    private static final int MAX_DISPOSITIONS_SHOWN = 20;

    /** What actually happened to the calls on {@code name}, for a failure message. */
    private static String dispositions(EvalRun run, String name) {
        List<Disposition> all = run.toolCalls().stream()
                .filter(call -> call.name().equals(name))
                .map(ToolCall::disposition)
                .toList();
        if (all.size() <= MAX_DISPOSITIONS_SHOWN) {
            return all.toString();
        }
        return all.subList(0, MAX_DISPOSITIONS_SHOWN) + " and "
                + (all.size() - MAX_DISPOSITIONS_SHOWN) + " more";
    }

    /** Passes if the run took at most {@code maxSteps} steps. */
    public static Check withinSteps(int maxSteps) {
        return run -> run.result().steps() <= maxSteps
                ? CheckOutcome.pass("withinSteps")
                : CheckOutcome.fail("withinSteps", run.result().steps() + " steps > " + maxSteps);
    }

    /** Passes if total token usage was at most {@code maxTotalTokens}. */
    public static Check withinTokens(long maxTotalTokens) {
        return run -> run.result().usage().totalTokens() <= maxTotalTokens
                ? CheckOutcome.pass("withinTokens")
                : CheckOutcome.fail("withinTokens",
                        run.result().usage().totalTokens() + " tokens > " + maxTotalTokens);
    }

    /** Passes if {@code verifier} accepts the final output against the goal. */
    public static Check verifiedBy(Verifier verifier) {
        Objects.requireNonNull(verifier, "verifier");
        return run -> {
            Verdict verdict = verifier.verify(run.goal(), run.result().output());
            return verdict.passed()
                    ? CheckOutcome.pass("verifiedBy")
                    : CheckOutcome.fail("verifiedBy", verdict.feedback());
        };
    }

    /** Passes if an {@link LlmJudge} rules the run satisfies {@code rubric}. */
    public static Check judge(LlmClient llm, String model, String rubric) {
        LlmJudge judge = new LlmJudge(llm, model);
        Objects.requireNonNull(rubric, "rubric");
        return run -> {
            Verdict verdict = judge.judge(run, rubric);
            return verdict.passed()
                    ? CheckOutcome.pass("judge")
                    : CheckOutcome.fail("judge", verdict.feedback());
        };
    }
}
