package dev.agentkit.core.tool;

/**
 * Who wrote the content a {@link Tool} returns.
 *
 * <p>The companion question to {@link SideEffects}, which declares what a tool
 * <em>changes</em>. This declares what it <em>brings back</em>, and the two are independent:
 * a tool that fetches a web page changes nothing and returns somebody else's words, while
 * one that appends a row to your own table changes something and returns yours.
 *
 * <p>Declared by the tool's author, because nobody else can know. The framework cannot tell
 * your database rows from a page someone else wrote — {@code ToolResult}'s javadoc has said
 * so for as long as it has existed, and this is the declaration that lets a caller act on
 * the difference instead of being told it is unknowable.
 *
 * <h2>What this is not</h2>
 *
 * <p><strong>Not taint tracking.</strong> Once one third-party result is in the context, the
 * model is opaque and every later token is potentially influenced by it, so a gate keyed on
 * "was this argument derived from untrusted input" allows the first step and denies
 * everything after — it degenerates into deny-all. That is the honest reason this framework
 * spends its enforcement on capability rather than provenance.
 *
 * <p>What a declaration buys is the thing taint tracking cannot: an <em>audit trail</em> that
 * says where content came from, a key a developer's own filter can act on, and the input a
 * run-scoped decision would need — "once this run has read the web, tighten the gate for the
 * rest of it" — which is coarse enough not to degenerate. That decision is #122; this is the
 * prerequisite it named.
 *
 * <p><strong>Not a fence.</strong> Marking content {@link #THIRD_PARTY} does not delimit it
 * in the prompt. Fencing is {@code Spotlight}'s job and several framework tools already do
 * it from inside their handlers; this says <em>whether</em> something needs that treatment,
 * which is the piece a general {@code ToolResult} was missing.
 *
 * <h2>One enum, two questions, and why the second one is not yours to answer (#161)</h2>
 *
 * <p>This was calibrated for <em>is this worth fencing?</em>, which is a question about
 * authorship. {@code TrustFloor} then keyed an authorization decision on the same labels,
 * which asks <em>is this exposed to an adversary?</em> — a question about control. The two
 * answers come apart, and #161 proposed a second per-tool declaration to separate them.
 * There is none, deliberately, and the reason is that <strong>exposure is not a property of
 * a tool</strong>. It is a property of a tool <em>in a deployment</em>, and the tool's
 * author is the one party who cannot see it.
 *
 * <p>Measured in this repository, on one class:
 *
 * <pre>
 * tool          declares       library it reads              exposed to an attacker?
 * search_tools  THIRD_PARTY    an MCP-backed registry        yes
 * search_tools  THIRD_PARTY    agentkit-examples-itops's     no — every name and
 *                              ToolCatalog                   description is a string
 *                                                            literal in that module
 * </pre>
 *
 * <p>Same class, same declaration, opposite answers, decided entirely by wiring that
 * happens after {@code DisclosingToolRegistry} was compiled. {@code read_skill} is the same
 * shape: a vendored bundle the deployment ships and a directory a user can drop files into
 * are one tool. A third field on {@link Tool} would be set once, in library code, by the
 * author who has no way to know — which is the mistake {@code Source} documents from the
 * other side, and the reason #229 is right that {@code Source} and this are not one type.
 * {@code Source} is a value chosen per call because a per-tool label could not carry it;
 * exposure is a value chosen per <em>deployment</em>, for the same kind of reason.
 *
 * <p>So the resolution lives at the policy site, which is where {@link #UNKNOWN} already
 * says the collapsing belongs, and it has two levers that both predate #161:
 *
 * <ul>
 *   <li>{@code TrustFloor.lowersOn} — which of these labels count as somebody else's
 *       words for this deployment's floor.</li>
 *   <li>{@link Tools#withProvenance} — what this deployment says about a tool it did not
 *       author. Wrapping a vendored {@code read_skill} as {@link #FIRST_PARTY} is how a
 *       deployment states that its own bundle is inside its trust boundary. That lever was
 *       inert until #161 — see {@code ToolResult}'s {@code narrower} for the measurement —
 *       which is most of why the question looked like it needed a new type.</li>
 * </ul>
 *
 * <p><strong>Why keying a floor on these labels is safe in the meantime.</strong> Every
 * label that is wrong for the exposure question is wrong in one direction: {@code recall}
 * and {@code read_skill} declare {@link #THIRD_PARTY} because their content is not the
 * deployment's prose, and a floor reading that lowers when it need not have. The cost is a
 * capability the run could have kept. There is no label that makes a floor <em>miss</em>
 * exposure it should have caught, because every route by which an adversary's bytes reach a
 * result runs through a tool that is not returning the deployment's own words. A floor is
 * therefore over-inclusive by construction, which is the same bias an audit oracle wants
 * and the opposite of what a screen wants — see {@code ToolGates.screeningAgainst}, whose
 * residual (#231) is the mirror image of this one and does not resolve the same way.
 */
public enum Provenance {

    /**
     * The deployment's own words: its database, its files, a value it computed.
     *
     * <p>"Ours" is about who <em>authored</em> the bytes, not where they were stored or how
     * far they travelled. A row your application wrote to your own table is first-party
     * after a round trip through a database on another continent. A comment field in that
     * same row, typed by a customer, is not.
     */
    FIRST_PARTY,

    /**
     * Somebody else's words: a fetched page, an MCP server's response, a shared volume, a
     * document a user uploaded, another agent's answer.
     *
     * <p>The test is not distance and not the process boundary — it is whether the
     * deployment chose every byte. Content that merely <em>passed through</em> a party you
     * do not control is third-party even if it started as yours, because you cannot tell
     * afterwards.
     */
    THIRD_PARTY,

    /**
     * Undeclared, which is the default. Which of the two a policy treats it as is the
     * policy's choice — {@code TrustFloor} is the first thing that actually has to make it,
     * and offers both readings by name.
     *
     * <p>Distinct from {@code THIRD_PARTY} rather than folded into it, because "the author
     * said this is somebody else's" and "nobody said" are different facts about the same
     * bytes, and only the second one is worth fixing. An audit trail that cannot tell them
     * apart cannot tell you which tools still need declaring.
     *
     * <p>Which of the two a <em>policy</em> should treat it as is deliberately not decided
     * here. This enum had a {@code mayBeSomebodyElses()} that answered "third-party", and
     * that is one of the open questions in #122: treating undeclared as third-party fires
     * for every run using any undeclared tool, which today is most of them, and treating it
     * as first-party makes the thing useless. {@link SideEffects} keeps the same discipline
     * — it has no such method, and {@code ToolGates.readOnly} does the collapsing at the
     * policy site, where a caller can choose differently.
     */
    UNKNOWN
}
