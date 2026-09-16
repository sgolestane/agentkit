package dev.agentkit.itops.runtime;

import dev.agentkit.core.util.Quoted;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts executions on a timer, and nothing else.
 *
 * <p>A schedule names an agent and hands it input; it does not carry logic. That boundary is
 * what keeps automation from becoming a second, worse product: the moment a schedule can
 * contain steps, there are two places operational behaviour lives, and the one inside the
 * scheduler has no tools, no supervisor and no audit trail. Here the scheduler's entire job
 * is to decide <em>when</em>, and everything about <em>what</em> belongs to the agent it
 * starts.
 *
 * <p>Intervals rather than cron expressions, for the demo's sake. A real deployment wants
 * the cron string from the schedule definition and a leader election so that two instances
 * do not both fire — though note that the ticket claim already makes a double fire harmless,
 * which is the property to aim for regardless of how good the scheduler is.
 */
public final class OpsScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpsScheduler.class);

    /**
     * A schedule, as it would be stored.
     *
     * @param name      how an operator refers to it
     * @param cron      the intended cron expression, kept for display and for the day a real
     *                  cron parser replaces {@code every}
     * @param every     the interval actually used to fire it here
     * @param agentId   which agent definition to start
     * @param input     what to hand that agent
     * @param enabled   whether it fires
     */
    public record Schedule(String name, String cron, Duration every, String agentId,
                           Map<String, Object> input, boolean enabled) {
        public Schedule {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(every, "every");
            Objects.requireNonNull(agentId, "agentId");
            // Null-tolerant, per #132's sweep, and NOT Frozen.deeply.
            //
            // Two corrections to what an earlier version of this comment said. It claimed
            // "a schedule is configuration read as JSON": there is no JSON loader for a
            // schedule anywhere in this module — the one `readValue` in itops parses HTTP
            // request bodies — and the only Schedule in the repository is built with map
            // literals in ItOpsApp. So no null can reach here today. This is defensive, and
            // saying so is better than inventing a code path for it.
            //
            // And Frozen.deeply would be the wrong instrument even so: this input is handed
            // to a task, not turned into a ToolInvocation, so its JSON-shape refusal would
            // reject values an operator may legitimately schedule and that nothing else
            // objects to. Null tolerance is what #132 asked for; the rest is not free here.
            input = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(input, "input")));
        }
    }

    /** A schedule plus its last outcome, which is what the UI shows. */
    public record ScheduleState(Schedule schedule, Instant lastRunAt, int lastStartedExecutions) {}

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "itops-scheduler");
                thread.setDaemon(true);
                return thread;
            });
    private final List<AtomicReference<ScheduleState>> states = new CopyOnWriteArrayList<>();

    /**
     * Registers a schedule and starts firing it.
     *
     * @param schedule what to run and how often
     * @param task     given the schedule's input, returns how many executions it started
     */
    public void register(Schedule schedule, java.util.function.Function<Map<String, Object>,
            Integer> task) {
        AtomicReference<ScheduleState> state =
                new AtomicReference<>(new ScheduleState(schedule, null, 0));
        states.add(state);
        if (!schedule.enabled()) {
            return;
        }
        executor.scheduleAtFixedRate(() -> {
            try {
                int started = task.apply(schedule.input());
                state.set(new ScheduleState(schedule, Instant.now(), started));
            } catch (Throwable failure) {
                // A failing tick must not cancel the timer, which is what an escaping
                // *throwable* from scheduleAtFixedRate would do — silently, and for good.
                // The catch used to say RuntimeException while the comment said what
                // ScheduledExecutorService actually does, which is to suppress every later
                // execution on any Throwable (#135). Measured by planting the old catch back
                // under the test beside this one — a 20ms schedule whose first tick throws,
                // then a wait for three more:
                //
                //   catch (Throwable)         the wait finishes in about 100ms
                //   catch (RuntimeException)  1 tick, then twenty seconds of silence
                //
                // Non-terminal, and this is the one of #135's five where that is not a
                // close call. The unit of work is one tick; the schedule is a facility
                // whose entire purpose is to keep firing, and an Error inside one agent
                // run is not evidence that the *timer* is unsound. Nor is anything here
                // fed back to a model as a handled result — the failure is recorded on the
                // ScheduleState an operator reads and logged at WARN — so #130's worry
                // about an Error passing for an ordinary handled failure does not apply.
                // "Log it and rethrow" is not an option either: the rethrow is what
                // cancels the schedule. A truly fatal Error takes the JVM with it and this
                // line never runs; a survivable one (LinkageError, StackOverflowError)
                // leaves a timer that still ticks, which is strictly better than one that
                // stopped without a word.
                log.warn("Schedule '{}' failed this tick", Quoted.of(schedule.name()),
                        Quoted.failure(failure));
                state.set(new ScheduleState(schedule, Instant.now(), 0));
            }
        }, schedule.every().toMillis(), schedule.every().toMillis(), TimeUnit.MILLISECONDS);
    }

    public List<ScheduleState> schedules() {
        return states.stream().map(AtomicReference::get).toList();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
