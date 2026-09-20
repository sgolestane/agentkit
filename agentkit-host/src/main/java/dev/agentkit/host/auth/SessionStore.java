package dev.agentkit.host.auth;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a sign-in leaves behind, for as long as it lasts: a person's console session, and a sign-in waiting for the
 * identity provider to send them back. With several instances of the host it must be shared (Postgres), so a person
 * signed in on one is signed in on all, and a sign-in begun on one can finish on another.
 *
 * <p>Keys are what the browser holds — a session id, a sign-in's state — and a store may keep only a digest of them:
 * a copy of the store is not a way in.
 */
public interface SessionStore {

    /** Keeps {@code value} under {@code key}, of {@code kind}, until {@code expires}. */
    void put(String kind, String key, String value, Instant expires);

    /** The value under {@code key}, if there is one and it has not expired by {@code now}. */
    Optional<String> get(String kind, String key, Instant now);

    /** As {@link #get}, and removed: for what may be used once, such as a sign-in's state. */
    Optional<String> take(String kind, String key, Instant now);

    void remove(String kind, String key);

    /** Forgets everything expired by {@code now}. */
    void prune(Instant now);

    /** A store in this process: for one instance, and forgotten on restart. */
    static SessionStore inMemory() {
        record Kept(String value, Instant expires) {
        }
        Map<String, Kept> kept = new ConcurrentHashMap<>();
        return new SessionStore() {
            @Override
            public void put(String kind, String key, String value, Instant expires) {
                kept.put(kind + '\n' + key, new Kept(value, expires));
            }

            @Override
            public Optional<String> get(String kind, String key, Instant now) {
                return Optional.ofNullable(kept.get(kind + '\n' + key)).filter(k -> k.expires().isAfter(now))
                        .map(Kept::value);
            }

            @Override
            public Optional<String> take(String kind, String key, Instant now) {
                return Optional.ofNullable(kept.remove(kind + '\n' + key)).filter(k -> k.expires().isAfter(now))
                        .map(Kept::value);
            }

            @Override
            public void remove(String kind, String key) {
                kept.remove(kind + '\n' + key);
            }

            @Override
            public void prune(Instant now) {
                kept.values().removeIf(k -> !k.expires().isAfter(now));
            }
        };
    }
}
