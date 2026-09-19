package dev.agentkit.host;

import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Step;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.host.auth.CallerSigner;
import dev.agentkit.host.repo.OrgRepo;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells a person when a decision has waited for them — a confirmation an agent stopped for, or a question it asked —
 * longer than their organization's {@code notify.after}: once for each conversation while decisions wait in it, through the connector tool {@code org.yaml}
 * names, with a link to the conversation. A person who never opens the console otherwise finds out only when they do.
 *
 * <p>Each instance tells people about the decisions it holds, which are the turns it runs. The call is made as the
 * host ({@code agent:agentkit}), not as the person, since it is the host telling them.
 */
public final class Nudges {

    private static final Logger LOG = LoggerFactory.getLogger(Nudges.class);

    private final Map<String, OrgHost> orgs;
    private final Supplier<ChatRuntime> runtime;
    private final Function<String, String> link;
    private final Supplier<Instant> clock;
    private final Set<String> told = ConcurrentHashMap.newKeySet();

    /** @param link a conversation's address in the console, from its id */
    public Nudges(Map<String, OrgHost> orgs, Supplier<ChatRuntime> runtime, Function<String, String> link,
                  Supplier<Instant> clock) {
        this.orgs = Map.copyOf(orgs);
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.link = Objects.requireNonNull(link, "link");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Tells each person about each decision that has waited long enough and they have not been told of; how many. */
    public int tellWhoIsWaited() {
        Instant now = clock.get();
        int sent = 0;
        List<ChatRuntime.PendingDecision> pending = runtime.get().pendingAll();
        told.retainAll(pending.stream().map(ChatRuntime.PendingDecision::id).toList());
        for (ChatRuntime.PendingDecision decision : pending) {
            Optional<Tenant> tenant = Tenant.parse(decision.tenantId());
            OrgHost org = tenant.map(t -> orgs.get(t.org())).orElse(null);
            if (org == null || told.contains(decision.id())) {
                continue;
            }
            Optional<OrgRepo.NotifySpec> spec = org.current().repo().notifications();
            if (spec.isEmpty() || decision.askedAt().plus(Duration.ofSeconds(spec.get().afterSeconds())).isAfter(now)) {
                continue;
            }
            told.add(decision.id());
            // One message for a conversation while any of its decisions is still waiting: steps carried out at once
            // can stop for several confirmations together, and one message says it.
            boolean saidAlready = pending.stream().anyMatch(other -> !other.id().equals(decision.id())
                    && other.tenantId().equals(decision.tenantId())
                    && other.conversationId().equals(decision.conversationId()) && told.contains(other.id()));
            if (!saidAlready && tell(org, spec.get(), tenant.get(), decision)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean tell(OrgHost org, OrgRepo.NotifySpec spec, Tenant tenant, ChatRuntime.PendingDecision decision) {
        AgentHost current = org.current();
        Optional<Tool> tool = current.connectors().catalog(spec.tool().connector())
                .flatMap(catalog -> catalog.entry(spec.tool().tool())).map(DeclaredTools.Entry::tool);
        if (tool.isEmpty()) {
            LOG.warn("Could not tell {} a decision waits: {} was not reached", tenant.email(), spec.tool());
            return false;
        }
        String what = decision.kind() == ChatRuntime.PendingDecision.Kind.QUESTION
                ? "an agent asked you something and is waiting for your answer"
                : "an agent is waiting for you to confirm " + decision.tool();
        String text = "AgentKit: " + what + ". Open the conversation to decide: " + link.apply(decision.conversationId());
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(spec.to(), tenant.email());
        arguments.put(spec.text(), text);
        CallerSigner.Caller caller = new CallerSigner.Caller(org.org(), "agent:agentkit", null, "agentkit",
                current.repo().version(), decision.conversationId(), decision.turnId());
        ToolResult result;
        try {
            result = current.connectors().asserted(spec.tool().connector(), tool.get(), caller)
                    .execute(new ToolInvocation("notify-" + decision.id(), tool.get().name(), arguments));
        } catch (RuntimeException e) {
            LOG.warn("Could not tell {} a decision waits", tenant.email(), e);
            return false;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("to", tenant.email());
        detail.put("via", spec.tool().toString());
        detail.put("decision", decision.id());
        detail.put("isError", result.isError());
        runtime.get().store().addStep(decision.tenantId(), decision.conversationId(), decision.turnId(), Step.Kind.NOTE,
                "notified", detail, 0, result.isError());
        return !result.isError();
    }
}
