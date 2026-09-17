package dev.agentkit.accessdesk.web;

import dev.agentkit.accessdesk.deferred.DeferredActionScheduler;
import dev.agentkit.accessdesk.desk.AccessLedger;
import dev.agentkit.accessdesk.desk.CompanyClient;
import dev.agentkit.accessdesk.desk.CompanyClient.Person;
import dev.agentkit.accessdesk.desk.DeskAgent;
import dev.agentkit.accessdesk.desk.DeskConfig;
import dev.agentkit.accessdesk.desk.DeskTools;
import dev.agentkit.accessdesk.tools.ToolCatalog;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatTools;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.Turn;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.util.Cut;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The desk's agent for one chat turn, as the person the console belongs to — shared by the application and the
 * evals, so the evals exercise exactly what a console runs.
 *
 * <h2>Earlier turns</h2>
 *
 * <p>An {@code agentkit-chat} turn runs on the new message alone; the conversation's earlier turns are not in the
 * model's context. A desk conversation is full of follow-ups ("which incident?" — "INC-4211"), so this adds the
 * recent turns of the conversation to the system prompt, fenced as {@link Spotlight.Kind#ADVISORY}: the run's own
 * earlier work, acted on, unable to change the objective. It belongs in {@code agentkit-chat} and lives here
 * because this application needed it.
 */
public final class DeskChat {

    /** How many earlier turns a turn sees. */
    static final int EARLIER_TURNS = 8;

    private DeskChat() {
    }

    /**
     * @param llm      the model client, or empty when none is configured
     * @param problems what stops the desk answering; non-empty makes every turn say so
     * @param self     the runtime being built around this factory, for {@code ask_person}
     */
    public static ChatRuntime.Agents agents(DeskConfig config, Optional<LlmClient> llm, String model, CompanyClient company,
                                            ToolCatalog companyTools, AccessLedger ledger, DeferredActionScheduler scheduler,
                                            Supplier<Instant> clock, List<String> problems, AtomicReference<ChatRuntime> self) {
        return session -> {
            if (!problems.isEmpty() || llm.isEmpty()) {
                throw new ChatUnavailable(problems.isEmpty() ? "No model is configured." : String.join(" ", problems));
            }
            Person me = company.person(session.tenantId())
                    .orElseThrow(() -> new ChatUnavailable("This console's user is not in the directory."));
            DeskTools desk = new DeskTools(me.email(), ledger, company, scheduler, clock);
            List<Tool> tools = new ArrayList<>(DeskAgent.conversationTools(desk, companyTools).entries().stream()
                    .map(ToolCatalog.Entry::tool).toList());
            tools.add(ChatTools.askPerson(self.get(), session));
            String prompt = DeskAgent.systemPrompt(config, me, clock.get()) + earlierTurns(session);
            return session.agent(llm.get(), new SimpleToolRegistry(tools), DeskAgent.agentConfig(model, prompt))
                    .name("access-desk")
                    .toolGate(DeskAgent.gate(session.approver()))
                    .build();
        };
    }

    /** The conversation's recent finished turns, fenced, or nothing for its first turn. */
    static String earlierTurns(ChatRuntime.Session session) {
        List<Turn> finished = session.store().turns(session.tenantId(), session.conversationId()).stream()
                .filter(t -> !t.id().equals(session.turnId()) && t.state().isTerminal())
                .toList();
        if (finished.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Turn turn : finished.subList(Math.max(0, finished.size() - EARLIER_TURNS), finished.size())) {
            text.append("Person: ").append(Cut.to(turn.userText(), 1_000)).append('\n')
                    .append("Access Desk: ").append(Cut.to(turn.answer() == null || turn.answer().isBlank()
                            ? "(" + turn.state().name().toLowerCase(java.util.Locale.ROOT) + ")" : turn.answer(), 1_500))
                    .append("\n\n");
        }
        return "\n\nEarlier in this conversation (the newest message is the one you are answering now):\n"
                + Spotlight.wrap(Spotlight.Kind.ADVISORY, Source.of("conversation"), text.toString().strip());
    }
}
