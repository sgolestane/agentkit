package dev.agentkit.host.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The router's answer is a decision about the agents it was shown, or it is refused — never a way to another agent. */
class TheRouterChoosesOnlyAmongTheAgentsItWasShownTest {

    private final List<Router.Offered> shown = List.of(new Router.Offered("helpdesk", "IT Helpdesk", "Tickets.", ""),
            new Router.Offered("security-desk", "Security Desk", "Lookups.", ""));

    @Test
    void aDecisionIsReadBack() {
        assertThat(Router.parse("{\"why\":\"w\",\"action\":\"agent\",\"agent\":\"helpdesk\",\"text\":\"\"}", shown))
                .isEqualTo(new Router.ToAgent("helpdesk", "w"));
        assertThat(Router.parse("{\"why\":\"w\",\"action\":\"answer\",\"agent\":\"\",\"text\":\"Two agents.\"}", shown))
                .isEqualTo(new Router.Answer("Two agents.", "w"));
        assertThat(Router.parse("{\"why\":\"w\",\"action\":\"ask\",\"agent\":\"\",\"text\":\"Which?\"}", shown))
                .isEqualTo(new Router.Ask("Which?", "w"));
    }

    @Test
    void anAgentItWasNotShownOrAnAnswerThatIsNotADecisionIsRefused() {
        assertThatThrownBy(() -> Router.parse("{\"action\":\"agent\",\"agent\":\"payroll-admin\"}", shown))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not an agent it was shown");
        assertThatThrownBy(() -> Router.parse("Sure, onboarding!", shown)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Router.parse("{\"action\":\"grant\"}", shown)).isInstanceOf(IllegalStateException.class);
    }
}
