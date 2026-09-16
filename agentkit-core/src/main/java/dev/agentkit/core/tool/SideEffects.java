package dev.agentkit.core.tool;

/**
 * What a {@link Tool} changes that outlives the call.
 *
 * <p>"Outlives the call" rather than "outside the process", because the process boundary is
 * the wrong line: a tool that appends to an in-memory board other steps then read has
 * changed something, and one that reads a file off disk has not. Reads are free at any
 * distance. Writes, sends and deletes are not, wherever they land.
 *
 * <p>One deliberate carve-out: the model's own view of the tool catalog does not count.
 * {@code search_tools} reveals a deferred tool, which the rest of the run can see — but
 * refusing it would mean a rehearsal could not discover the tools it is meant to rehearse
 * choosing, which defeats the purpose.
 *
 * <p>Two questions turn on this: whether a run can be <em>rehearsed</em> without
 * consequences, and whether a failed step is safe to <em>run again</em>. They are close but
 * not the same, which is why there is a value between "changes nothing" and "changes
 * something": a write to a fixed key is unsafe to rehearse and perfectly safe to repeat.
 *
 * <p><strong>Only the first question has a consumer today.</strong> {@code ToolGates.readOnly}
 * reads this; nothing yet distinguishes {@link #IDEMPOTENT} from {@link #EXTERNAL} — the
 * durable runner's retry count is set per run on {@code DurableAgentOptions}, not per tool.
 * The value is here anyway because a declaration is written once, by the author, at the
 * moment they know the answer. Adding it later would silently change the meaning of every
 * {@code EXTERNAL} already written.
 *
 * <p>Only the tool's author knows which applies, so an undeclared tool is treated as the
 * most dangerous option wherever the distinction matters.
 */
public enum SideEffects {

    /**
     * Reads and derives, and changes nothing that outlives the call — a search, a lookup, a
     * calculation. Safe to rehearse.
     *
     * <p>Reading is not restricted: opening a file, querying a database or calling a search
     * API are all {@code NONE}, because none of them leaves anything behind.
     */
    NONE,

    /**
     * Changes something that outlives the call, but running it twice leaves the world as
     * running it once would — a write to a fixed key, an upsert, setting a value rather
     * than incrementing one. Not safe to rehearse; safe to repeat.
     *
     * <p>Nothing reads this yet; see the note on the enum itself for why it exists anyway.
     */
    IDEMPOTENT,

    /**
     * Changes something that outlives the call, and running it twice is not the same as
     * running it once: sends, appends, deletes, pays.
     */
    EXTERNAL,

    /**
     * Not declared, and therefore treated as {@link #EXTERNAL} wherever the distinction
     * matters. The default, because the alternative is that every tool written before this
     * existed silently becomes eligible for a rehearsal that then sends real email. It also
     * stays distinct from {@code EXTERNAL} on purpose: "nobody said" and "someone said it
     * is dangerous" are different facts.
     *
     * <p>Tools that arrive from elsewhere — an MCP server's, say — are all {@code UNKNOWN},
     * since only a builder can set this. Vouch for them with
     * {@link Tools#withSideEffects(Tool, SideEffects)} if you want them rehearsable.
     */
    UNKNOWN
}
