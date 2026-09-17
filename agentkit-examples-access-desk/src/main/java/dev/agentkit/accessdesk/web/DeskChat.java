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
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The desk's agent for one chat turn, as the person the console belongs to — shared by the application and the
 * evals, so the evals exercise exactly what a console runs. The conversation's earlier turns reach the agent through
 * {@code ChatRuntime} itself.
 */
public final class DeskChat {

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
            String prompt = DeskAgent.systemPrompt(config, me, clock.get());
            return session.agent(llm.get(), new SimpleToolRegistry(tools), DeskAgent.agentConfig(model, prompt))
                    .name("access-desk")
                    .toolGate(DeskAgent.gate(session.approver()))
                    .build();
        };
    }
}
