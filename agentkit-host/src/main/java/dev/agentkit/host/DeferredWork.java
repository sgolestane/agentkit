package dev.agentkit.host;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.deferred.DeferredActionScheduler;
import dev.agentkit.core.deferred.DeferredActionStore;
import dev.agentkit.core.deferred.DeferredRunner;
import dev.agentkit.core.deferred.SubjectRecord;
import dev.agentkit.core.deferred.SubjectResolver;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.tool.Tool;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An organization's deferred work: what its agents schedule in conversations, and running it when it comes due.
 *
 * <p>An agent whose definition has a {@code deferred} section is given {@code schedule_deferred_action} in every turn,
 * for the person in it. What that tool may schedule, and what an action may do when it runs, are the framework's
 * ({@code dev.agentkit.core.deferred}):
 * <ul>
 *   <li>a person may schedule work only about a subject they are a contact of — the agent's own actor may schedule
 *       about any — because the work runs later as the agent, not as them;</li>
 *   <li>when it runs, it gets only the agent's tools that read, revoke, notify or request, only about that subject,
 *       with its goal fenced as a procedure and the subject's record as it is then.</li>
 * </ul>
 * Each agent keeps its own store, so its subject kinds are its own. An action runs with the agent as the current
 * version defines it: it is done on today's rules, whatever version scheduled it.
 */
public final class DeferredWork implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DeferredWork.class);

    private final OrgHost org;
    private final Function<String, DeferredActionStore> storeFor;
    private final Map<String, DeferredActionStore> stores = new ConcurrentHashMap<>();
    private final Supplier<Instant> clock;
    private final Optional<LlmClient> llm;
    private ScheduledExecutorService timer;

    /**
     * @param stores the store for an agent's deferred actions, by agent id; called once per agent
     * @param llm    the model deferred actions run on, or empty, when none run
     */
    public DeferredWork(OrgHost org, Function<String, DeferredActionStore> stores, Supplier<Instant> clock,
                        Optional<LlmClient> llm) {
        this.org = Objects.requireNonNull(org, "org");
        this.storeFor = Objects.requireNonNull(stores, "stores");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    /** The agent's deferred actions. */
    public DeferredActionStore store(String agentId) {
        return stores.computeIfAbsent(agentId, storeFor);
    }

    /** The scheduling tool for a turn of {@code agent} with {@code principal}; empty if the agent schedules nothing. */
    public Optional<Tool> schedulerFor(HostedAgent agent, Principal principal) {
        return scheduler(agent).map(s -> s.tool(principal.email()));
    }

    private Optional<DeferredActionScheduler> scheduler(HostedAgent agent) {
        if (agent.subjects().isEmpty() || agent.actor().isEmpty()) {
            return Optional.empty();
        }
        SubjectResolver subjects = agent.subjects().get();
        String actor = agent.actor().get().email();
        return Optional.of(new DeferredActionScheduler(subjects, store(agent.definition().id()), clock,
                subject -> subjects instanceof ConnectorSubjects c ? c.holdings(subject) : java.util.List.of(),
                (scheduledBy, subject) -> mayScheduleFor(actor, scheduledBy, subject)));
    }

    /** The agent itself may schedule about anything; a person only about what they are a contact of. */
    static boolean mayScheduleFor(String actor, String scheduledBy, SubjectRecord subject) {
        return scheduledBy != null && (scheduledBy.equalsIgnoreCase(actor) || subject.isContact(scheduledBy));
    }

    /** Runs every due action of every agent in the current version; returns how many ran. */
    public synchronized int runDue() {
        if (llm.isEmpty()) {
            return 0;
        }
        int ran = 0;
        for (HostedAgent agent : org.current().agents().values()) {
            if (agent.subjects().isEmpty() || agent.unavailable().isPresent()) {
                continue;
            }
            Principal actor = agent.actor().orElseThrow();
            AgentConfig config = agent.deferredConfig().orElseThrow();
            DeferredRunner runner = new DeferredRunner(store(agent.definition().id()), agent.subjects().get(),
                    agent.tools(actor),
                    (goal, tools, gate) -> Agent.builder(llm.get(), tools.registry(), config)
                            .name(agent.definition().id() + "-deferred")
                            .toolGate(gate)
                            .build()
                            .run(goal),
                    clock, null);
            ran += runner.runDue();
        }
        return ran;
    }

    /** Sweeps every {@code every} until closed; a sweep that fails is logged and the next one runs. */
    public synchronized void start(Duration every) {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "agentkit-host-deferred-" + org.org());
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                runDue();
            } catch (RuntimeException e) {
                LOG.warn("A deferred sweep of {} failed", org.org(), e);
            }
        }, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }
}
