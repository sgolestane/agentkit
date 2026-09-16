package dev.agentkit.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Implemented by an {@link LlmClient} that wraps another one — a meter, a tracer, a
 * budget, a retry policy.
 *
 * <p>Decorators are how AgentKit adds cross-cutting behaviour, but they also hide what
 * they wrap, and some checks need to see through them: a durable Temporal worker must
 * reject a {@code BudgetLlmClient} whose tally is instance state, and it must still
 * reject one that arrives wrapped in a meter or a tracer. Declaring this interface makes
 * a decorator transparent to {@link #unwrap} rather than something later checks have to
 * learn about one type at a time.
 *
 * <p>Implement it on any decorator you write, <em>except</em> one that is itself the
 * thing a check looks for — declaring it makes a decorator transparent, so a
 * {@code BudgetLlmClient} that implemented this would disappear from the very check
 * that exists to find it. The cost of not implementing it is silent in the other
 * direction: a check meant to catch a misconfiguration simply stops catching it.
 */
public interface DelegatingLlmClient extends LlmClient {

    /**
     * How many layers {@link #chain} and {@link #unwrap} will walk before giving up.
     * Generous for real stacks, and bounded because a cycle — a decorator that ends up
     * wrapping itself — is constructible by accident and would otherwise hang.
     */
    int MAX_DEPTH = 64;

    /** The client this one wraps. */
    LlmClient delegate();

    /**
     * The decorator chain starting at {@code client}, outermost first, ending with the
     * first client that is not a declared decorator.
     *
     * <p>Prefer this to {@link #unwrap} when you are asking whether something is
     * <em>anywhere</em> in the stack. Those two questions look alike and are not: a
     * budget client sitting between two decorators is in the chain but is not what
     * {@code unwrap} returns.
     *
     * <p>Stops early at a decorator whose {@code delegate()} is null, at a cycle, and
     * after {@link #MAX_DEPTH} layers — so on a pathological chain the list is a prefix
     * rather than the whole stack.
     */
    static List<LlmClient> chain(LlmClient client) {
        List<LlmClient> chain = new ArrayList<>();
        LlmClient current = client;
        for (int depth = 0; depth < MAX_DEPTH && current != null; depth++) {
            chain.add(current);
            if (!(current instanceof DelegatingLlmClient delegating)) {
                break;
            }
            LlmClient next = delegating.delegate();
            if (next == current || chain.contains(next)) {
                break;
            }
            current = next;
        }
        return List.copyOf(chain);
    }

    /**
     * The first client in {@link #chain} that is an instance of {@code type}, if any.
     * This is the form a guard wants: "is there one of these in here at all?"
     */
    static <T> Optional<T> findInChain(LlmClient client, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return chain(client).stream().filter(type::isInstance).map(type::cast).findFirst();
    }

    /**
     * Peels off every decorator that declares itself delegating, returning the first
     * client underneath that does not.
     *
     * <p>Returns {@code client} itself if it is not a decorator. On a chain that hits a
     * null delegate, a cycle, or {@link #MAX_DEPTH} layers it returns the decorator it
     * stopped at rather than null — so a {@code instanceof} test on the result is only
     * as trustworthy as the chain is sane. When the question is "is X in here", use
     * {@link #findInChain}, which does not have that corner.
     */
    static LlmClient unwrap(LlmClient client) {
        List<LlmClient> chain = chain(client);
        return chain.isEmpty() ? client : chain.get(chain.size() - 1);
    }
}
