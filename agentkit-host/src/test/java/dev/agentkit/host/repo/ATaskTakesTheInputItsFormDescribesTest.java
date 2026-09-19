package dev.agentkit.host.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A task agent's input is a flat form: fields a person can fill in, in the console or as an MCP tool's arguments. Its
 * schema is checked when the repository is read; an input is checked when it is given, with every problem at once;
 * and a valid input becomes the request through the agent's goal template.
 */
class ATaskTakesTheInputItsFormDescribesTest {

    private static final String HIRE = """
            type: object
            required: [name, work_email, start_date, employment]
            properties:
              name: {type: string, title: Full name}
              work_email: {type: string, format: email, title: Work email}
              start_date: {type: string, format: date, title: Start date}
              employment: {type: string, enum: [full_time, contractor], title: Employment}
              remote: {type: boolean, title: Works remotely, description: Ship the laptop home}
              desk: {type: integer, title: Desk number}
            """;

    private static TaskInput parse(String schema, String template, List<String> problems) throws Exception {
        JsonNode node = new YAMLMapper().readTree(schema);
        return TaskInput.parse(node, template, (where, message) -> problems.add(where + ": " + message));
    }

    @Test
    void aValidInputBecomesTheRequestThroughTheTemplate() throws Exception {
        TaskInput hire = parse(HIRE, "Onboard {{name}} under the policy.\n\nNew hire:\n{{input}}", new ArrayList<>());
        Map<String, Object> input = Map.of("name", "Jo Park", "work_email", "jo.park@acme.example",
                "start_date", "2026-10-01", "employment", "contractor", "remote", true);

        assertThat(hire.problems(input)).isEmpty();
        assertThat(hire.render(input)).isEqualTo("""
                Onboard Jo Park under the policy.

                New hire:
                - name: Jo Park
                - work_email: jo.park@acme.example
                - start_date: 2026-10-01
                - employment: contractor
                - remote: true""");
        assertThat(parse(HIRE, null, new ArrayList<>()).render(Map.of("name", "Jo"))).isEqualTo("- name: Jo");
    }

    @Test
    void theRecordsARequestWritesOutAreReadBackOneAfterAnother() throws Exception {
        TaskInput hire = parse(HIRE, "Onboard {{name}}.\n{{input}}", new ArrayList<>());
        Map<String, Object> input = Map.of("name", "Jo Park", "work_email", "jo.park@acme.example",
                "start_date", "2026-10-01", "employment", "contractor");

        // The form's own request, and a template pasted twice with a field left empty.
        assertThat(hire.records(hire.render(input))).containsExactly(Map.of("name", "Jo Park",
                "work_email", "jo.park@acme.example", "start_date", "2026-10-01", "employment", "contractor"));
        assertThat(hire.records("""
                Onboard these:

                name: Jo Park
                work_email: jo.park@acme.example
                desk:

                name: Ann Lee
                work_email: ann.lee@acme.example
                owner: someone@acme.example
                """)).containsExactly(Map.of("name", "Jo Park", "work_email", "jo.park@acme.example"),
                Map.of("name", "Ann Lee", "work_email", "ann.lee@acme.example"));
        assertThat(hire.records("Please onboard Jo Park. Note: she starts on Monday.")).isEmpty();
    }

    @Test
    void theTemplateMayNameThePersonStartingItAsTheHostKnowsThem() throws Exception {
        TaskInput hire = parse(HIRE, "Onboard {{name}}. I am their manager, {{principal.name}} "
                + "({{principal.email}}); my desk is {{principal.desk}}.", new ArrayList<>());
        Map<String, String> dana = Map.of("principal.email", "dana@acme.example", "principal.name", "Dana Kim");

        assertThat(hire.render(Map.of("name", "Jo Park"), path -> java.util.Optional.ofNullable(dana.get(path))))
                .isEqualTo("Onboard Jo Park. I am their manager, Dana Kim (dana@acme.example); my desk is (unknown).");
    }

    @Test
    void anInputIsCheckedWithEveryProblemAtOnce() throws Exception {
        TaskInput hire = parse(HIRE, null, new ArrayList<>());

        assertThat(hire.problems(Map.of("name", " ", "work_email", "jo at acme", "start_date", "1 Oct",
                "employment", "intern", "remote", "maybe", "desk", 4.5, "salary", 1))).containsExactlyInAnyOrder(
                "salary is not a field of this task",
                "Full name is required",
                "Work email must be an email address",
                "Start date must be a date, such as 2026-10-01",
                "Employment must be one of [full_time, contractor]",
                "Works remotely must be true or false",
                "Desk number must be a whole number");
    }

    @Test
    void theSchemaIsAFlatFormAndTheTemplateNamesOnlyItsFields() throws Exception {
        List<String> problems = new ArrayList<>();
        TaskInput bad = parse("""
                type: object
                required: [name, missing]
                properties:
                  name: {type: string}
                  address: {type: object, properties: {city: {type: string}}}
                  seats: {type: integer, enum: [1, 2]}
                  start: {type: integer, format: date}
                """, "Hello {{nme}} {{person.email}}", problems);

        assertThat(bad).isNull();
        assertThat(problems).containsExactlyInAnyOrder(
                "properties.address.properties: is not supported; a field is flat: type, title, description, enum, format",
                "properties.address.type: must be one of [boolean, integer, number, string]",
                "properties.seats.enum: is a non-empty list, on a string field",
                "properties.start.format: is date or email, on a string field",
                "required: missing is not one of the fields",
                "goal: {{nme}} is not a field of the input",
                "goal: {{person.email}} is not a field of the input, nor principal.<field>");
    }

    @Test
    void itDescribesItselfForAPromptAndAForm() throws Exception {
        TaskInput hire = parse(HIRE, null, new ArrayList<>());

        assertThat(hire.describe()).contains("- employment (string: one of [full_time, contractor], required)")
                .contains("- remote (boolean, optional): Ship the laptop home");
        assertThat(hire.jsonSchema()).containsEntry("required", List.of("name", "work_email", "start_date", "employment"));
        assertThat(hire.jsonSchema().get("properties").toString()).contains("format=date").contains("title=Desk number");
    }
}
