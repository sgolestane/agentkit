package dev.agentkit.core.memory;

import java.util.Objects;
import java.util.Optional;

/**
 * Why a {@link MemoryStore} refused a call, as something a program can compare (#257).
 *
 * <p>{@code MemoryStore} has two failure channels and says what each is for: an
 * {@link IllegalArgumentException} whose <em>message</em> is a reason the caller can act on,
 * and everything else, which reaches the model as "Memory operation failed." The message is
 * the payload there — {@code MemoryTools} hands it through verbatim — and the exception is
 * only the channel.
 *
 * <p>That leaves a gap between what the contract promises and what anything can check.
 * {@code MemoryStoreDifferentialTest} exists to stop the two stores in this repository
 * drifting apart, and it classified a call by which channel it used. A <strong>channel</strong>
 * divergence it catches: reverting {@code InMemoryMemoryStore} alone to the medium channel
 * fails {@code theTwoStoresAgree} with "The two MemoryStore implementations disagree." A
 * <strong>same-channel, different-reason</strong> divergence it could not see at all, and
 * that is the divergence that reaches the model: a store telling it to fix its value when
 * what is wrong is its key has sent it the wrong way, in the one channel it is given.
 *
 * <h2>Why an enumeration rather than the message</h2>
 *
 * <p>Because the message is prose and prose is meant to change. It carries escaped code
 * points, the key the caller wrote, the limit it broke and the segment that broke it —
 * asserting equality on it would pin the wording of every refusal in the package to the
 * first store that happened to write one, and would fail on improvements rather than on
 * regressions. What does not rot is <em>which</em> refusal it is, and that is a short list:
 * the seven reasons {@code MemoryStore} enumerates for {@code write} and {@code append},
 * less the medium (which is the other channel), plus the one that comes before all of them —
 * the argument was not a key.
 *
 * <p>The alternative weighed was to assert the validation <em>order</em> as contract, which
 * is cheaper and turns the current agreement into a rule rather than a coincidence. It was
 * not taken. It constrains a third-party store more than the contract does anywhere else:
 * every other clause says what an implementation must <em>answer</em>, and this one would
 * say in what sequence it must think. #245 already added one constraint of that kind — the
 * substring {@code "unpaired surrogate"} — so there is precedent, and the precedent is also
 * the cost: a rule about wording or ordering is one an implementor cannot discover from the
 * behaviour they are trying to match. A reason code is discoverable, and it lets the two
 * stores keep validating in whatever order suits them as long as they refuse the same call
 * for the same reason.
 *
 * <p>It also settles the tension this repository's single-hazard rule has with a fuzzer: a
 * probe hostile in several ways at once tests none of them individually, which argues for
 * keeping probes single-hazard — <em>unless</em> the oracle can say which hazard answered.
 * With a reason code it can, so {@code write(badKey, badValue)} is worth generating.
 *
 * <h2>What an implementation outside this repository owes</h2>
 *
 * <p>Nothing, for now, and that is deliberate. {@link #behind} answers
 * {@link Optional#empty()} for a plain {@link IllegalArgumentException}, so a store that
 * raises one is conforming exactly as it was before this type existed — a differential
 * oracle comparing it against another store simply falls back to comparing channels, which
 * is what it did for every store until now. A store that wants the stronger check raises
 * its refusals through {@link #of(String)}. Both stores in this repository do.
 */
public enum MemoryRefusal {

    /**
     * The argument was not a key at all — blank, absolute, carrying a line terminator or an
     * unpaired surrogate, escaping the store lexically, or too long to be written down.
     * {@link MemoryKeys} decides this for every store, so every store refuses alike.
     */
    NOT_A_KEY,

    /**
     * The key would be both a document and a folder. {@code MemoryNamespace} decides it for
     * every store: a filesystem enforces it for free and a map does not.
     */
    HIERARCHY_COLLISION,

    /**
     * The value is not text UTF-8 can carry — half a surrogate pair. {@code MemoryValues}
     * decides it for every store, for the reason a key carrying one is refused: a store
     * that wrote it down would substitute a replacement character and lose it.
     */
    VALUE_IS_NOT_TEXT,

    /**
     * The key resolves outside the store's root. The first of the reasons a store with no
     * medium under it cannot raise: there is nothing for a map to be outside of.
     */
    OUTSIDE_THE_STORE,

    /** The key is reached through a symbolic link, at its last name or above it (#99). */
    REACHED_THROUGH_A_LINK,

    /**
     * Something that is not a document stands at the key, or at a component on the way to
     * it — a FIFO, a socket, a device node, or a folder holding only such things (#115,
     * #145, #188). Distinct from {@link #HIERARCHY_COLLISION}: none of those holds a
     * <em>key</em>, so the hierarchy is not in collision and the thing still cannot be got
     * out of the way.
     */
    NOT_A_DOCUMENT,

    /**
     * Whether the key is free could not be decided within a bound — too many entries under
     * it, or folders nested too deep (#146). The store declines to keep walking a tree the
     * model chose the size of.
     */
    NOT_DECIDABLE_WITHIN_A_BOUND;

    /** This reason, carrying {@code message} — the string {@code MemoryTools} hands on. */
    public IllegalArgumentException of(String message) {
        return of(message, null);
    }

    /** This reason, carrying {@code message} and the failure it was diagnosed from. */
    public IllegalArgumentException of(String message, Throwable cause) {
        Objects.requireNonNull(message, "message");
        return new Refused(this, message, cause);
    }

    /**
     * The reason behind {@code refusal}, or empty if it does not carry one.
     *
     * <p>Empty is an answer rather than a failure, for the reason the class note gives: a
     * store outside this repository raising a plain {@link IllegalArgumentException} is
     * conforming, and a caller comparing two stores falls back to comparing channels.
     * {@code null} answers empty too, so a caller need not check twice — as does a
     * {@link Refused} that arrived without one, which is only reachable through
     * deserialisation and degrades to the same answer a store outside this package gets
     * rather than to a {@link NullPointerException} thrown out of a comparison.
     */
    public static Optional<MemoryRefusal> behind(Throwable refusal) {
        return refusal instanceof Refused carried
                ? Optional.ofNullable(carried.reason())
                : Optional.empty();
    }

    /**
     * An {@link IllegalArgumentException} that knows which refusal it is.
     *
     * <p>Package-private, and the type is not the point: {@link MemoryRefusal#behind} is the
     * whole of the public reading surface, so nothing outside this package can come to
     * depend on the class, catch it by name, or be broken by it changing. What is public is
     * the reason and the two verbs — raise one, read one back — which is the smallest
     * surface that closes #257.
     *
     * <p><strong>One thing does change for a caller, and it is worth knowing.</strong> A
     * refusal's concrete class is no longer {@code java.lang.IllegalArgumentException}, so
     * anything rendering {@code getClass().getSimpleName()} sees {@code Refused} instead.
     * Catching, {@code instanceof} and {@code getMessage()} are unaffected — it is a
     * subclass and the message is untouched — but a log line built from the class name
     * reads differently. It caught one test in this repository,
     * {@code NonDocumentAtAKeyTest}, which was asking for the class name where it meant the
     * channel; that is the same confusion {@code MemoryStoreDifferentialTest}'s
     * {@code Outcome} records having made about {@code SafePaths}' own subclass, and it now
     * asks for the channel.
     *
     * <p>Not {@code final}, for one subclass: {@code FileMemoryStore.OutsideTheRoot}, which
     * exists so the read side can tell a well-formed key that lands outside the root from a
     * malformed one by catching rather than by re-deriving. Extending is what keeps it a
     * refusal with a reason as well as a type that can be caught.
     */
    static class Refused extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private final MemoryRefusal reason;

        Refused(MemoryRefusal reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        MemoryRefusal reason() {
            return reason;
        }
    }
}
