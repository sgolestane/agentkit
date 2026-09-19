package dev.agentkit.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The onboarding systems grant and remove access only for a worker's own manager, or for the onboarding agent itself
 * when its deferred work runs: {@code requested_by}, which the host binds to the person in the conversation, is checked
 * against the HRIS. And the HRIS is how the host looks a worker up for deferred work.
 */
class AGrantIsOnlyForTheHiresManagerTest {

    private static final String MARCUS = "marcus.bell@acme.example";
    private static final String LENA = "lena.ortiz@acme.example";

    private final OnboardingSystems systems = OnboardingSystems.open("onboarding", 0);

    @Test
    void theHiresManagerMayAndNoOneElseMay() {
        ToolResult byDana = call("salesforce_assign_seat", Map.of("email", MARCUS, "requested_by", "dana.kim@acme.example"));
        ToolResult byNobody = call("salesforce_assign_seat", Map.of("email", MARCUS));
        ToolResult unknown = call("salesforce_assign_seat", Map.of("email", "eve@evil.example", "requested_by", LENA));
        ToolResult byLena = call("salesforce_assign_seat", Map.of("email", MARCUS, "requested_by", LENA));

        assertThat(byDana.isError()).isTrue();
        assertThat(byDana.content()).contains("Only Marcus Bell's manager").contains("dana.kim@acme.example is not");
        assertThat(byNobody.isError()).isTrue();
        assertThat(unknown.content()).contains("no worker eve@evil.example");
        assertThat(byLena.isError()).isFalse();
        assertThat(systems.salesforceSeats()).containsExactly(MARCUS);
    }

    @Test
    void anEmployeeIdNamesTheWorkerTooAndTheAgentMayRevokeForAnyone() {
        assertThat(call("workday_enroll_benefits", Map.of("employee_id", "W-1002", "requested_by", LENA)).isError())
                .isFalse();
        assertThat(call("workday_enroll_benefits", Map.of("employee_id", "W-1001", "requested_by", LENA)).isError())
                .isTrue();
        call("salesforce_assign_seat", Map.of("email", MARCUS, "requested_by", LENA));
        assertThat(call("salesforce_release_seat", Map.of("email", MARCUS, "requested_by", "onboarding")).isError())
                .isFalse();
    }

    @Test
    void theCheckBeforeOnboardingRefusesAnyoneButTheManagerAndSaysWhatIsInPlace() {
        ToolResult byDana = call("onboarding_check", Map.of("employee_id", "W-1002", "requested_by", "dana.kim@acme.example"));
        assertThat(byDana.isError()).isTrue();
        assertThat(byDana.content()).isEqualTo("Only Marcus Bell's manager can onboard them, and "
                + "dana.kim@acme.example is not.");

        assertThat(call("onboarding_check", Map.of("employee_id", "W-1002", "requested_by", LENA)).content())
                .contains("\"allowed\":true", "\"manager\":\"" + LENA + "\"", "\"already_in_place\":{}");
        call("okta_create_user", Map.of("email", MARCUS, "first_name", "Marcus", "last_name", "Bell",
                "groups", List.of("sales", "contractors"), "requested_by", LENA));
        call("salesforce_assign_seat", Map.of("email", MARCUS, "requested_by", LENA));
        assertThat(call("onboarding_check", Map.of("employee_id", MARCUS, "requested_by", LENA)).content())
                .contains("\"okta\":\"account ACTIVE in groups [sales, contractors]\"", "\"salesforce\":\"a seat\"");
    }

    @Test
    void doingSomethingAgainChangesNothingAndSaysSo() {
        Map<String, Object> okta = Map.of("email", MARCUS, "first_name", "Marcus", "last_name", "Bell",
                "groups", List.of("sales"), "requested_by", LENA);
        assertThat(call("okta_create_user", okta).content()).startsWith("Okta: created");
        ToolResult again = call("okta_create_user", okta);
        assertThat(again.isError()).isFalse();
        assertThat(again.content()).contains("already has an active account").endsWith("nothing was changed.");

        Map<String, Object> ship = Map.of("email", MARCUS, "address", "1 Main St", "requested_by", LENA);
        call("ship_laptop", ship);
        assertThat(call("ship_laptop", ship).content()).contains("already shipped").endsWith("nothing was sent.");
        assertThat(systems.shipments()).hasSize(1);

        Map<String, Object> ticket = Map.of("category", "laptop_pickup", "for_email", MARCUS, "summary", "New York");
        assertThat(call("it_create_ticket", ticket).content()).startsWith("IT: opened ticket INC-1001");
        assertThat(call("it_create_ticket", ticket).content()).contains("INC-1001 (laptop_pickup) is already open");
    }

    @Test
    void onlyToolsThatGrantOrRevokeAskWhoIsAsking() {
        assertThat(systems.catalog().entries()).allSatisfy(entry -> {
            boolean asks = entry.tool().inputSchema().toString().contains("requested_by");
            boolean changesAccess = switch (entry.declaration().effect()) {
                case GRANT, REVOKE -> true;
                default -> false;
            };
            // And the check before onboarding, which says whether the person asking may.
            assertThat(asks).as(entry.tool().name()).isEqualTo(changesAccess
                    || entry.tool().name().equals("onboarding_check"));
        });
        assertThat(call("it_create_ticket", Map.of("category", "laptop_pickup", "for_email", MARCUS,
                "summary", "Pickup in New York")).isError()).isFalse();
    }

    @Test
    void theHrisAnswersWithTheWorkerAsASubjectOfDeferredWork() {
        call("okta_create_user", Map.of("email", MARCUS, "first_name", "Marcus", "last_name", "Bell",
                "groups", List.of("sales", "contractors"), "requested_by", LENA));
        call("salesforce_assign_seat", Map.of("email", MARCUS, "requested_by", LENA));

        String byId = call("hris_get_worker", Map.of("employee_id", "W-1002")).content();
        String byEmail = call("hris_get_worker", Map.of("employee_id", MARCUS)).content();

        assertThat(byId).isEqualTo(byEmail)
                .contains("\"id\":\"W-1002\"")
                .contains("\"identifiers\":[\"W-1002\",\"" + MARCUS + "\"]")
                .contains("\"contacts\":[\"" + LENA + "\"]")
                .contains("\"termination_date\":\"2026-12-31\"")
                .contains("\"holdings\":[\"okta\",\"salesforce\"]");
        assertThat(call("hris_get_worker", Map.of("employee_id", "W-9999")).isError()).isTrue();
    }

    private ToolResult call(String name, Map<String, Object> args) {
        return systems.catalog().entry(name).orElseThrow().tool()
                .execute(new ToolInvocation("t", name, new HashMap<>(args)));
    }
}
