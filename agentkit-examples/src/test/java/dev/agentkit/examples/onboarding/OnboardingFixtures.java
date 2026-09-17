package dev.agentkit.examples.onboarding;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The company the onboarding tests and evals run against: seven hires in the HRIS, and what the other
 * systems already hold before any of them is onboarded.
 *
 * <p>Test data, not application code. {@link #world} builds a fresh copy every time, because a check
 * reads the world after a run and cannot tell that run's writes from an earlier one's.
 */
public final class OnboardingFixtures {

    /** Full-time, remote, Engineering; GitHub username on file. */
    public static final String PRIYA = "W-1001";
    /** Contractor in Sales, on-site in New York, with a termination date. */
    public static final String MARCUS = "W-1002";
    /** Rehire in Engineering, on-site in San Francisco, asking for production access. */
    public static final String MARIA = "W-1003";
    /** GitHub username not on file, but on his own Slack profile. */
    public static final String NOAH = "W-1004";
    /** GitHub username not on file anywhere; answers when asked. */
    public static final String AISHA = "W-1005";
    /** GitHub username not on file; a search finds a same-name account that is not hers; answers when asked. */
    public static final String JORDAN = "W-1006";
    /** GitHub username not on file anywhere; never answers. */
    public static final String ALEX = "W-1007";

    private OnboardingFixtures() {
    }

    /** A fresh world where a person answering a Slack form takes a second. */
    public static OnboardingSystems world() {
        return world(1_000);
    }

    /** A fresh world where a person answering a Slack form takes {@code replyWaitMillis}. */
    public static OnboardingSystems world(long replyWaitMillis) {
        OnboardingSystems s = OnboardingSystems.create(replyWaitMillis);

        s.addWorker(record(PRIYA, "Priya Natarajan", "priya.natarajan@acme.example", "Senior Backend Engineer",
                "Engineering", "full-time", "no", "dana.kim@acme.example",
                "work_location", "remote", "home_address", "Rua Augusta 100, 1100-053 Lisbon, Portugal",
                "github_username", "priyan-dev", "github_team", "backend",
                "production_aws_access_requested", "no", "termination_date", "none"));
        s.addWorker(record(MARCUS, "Marcus Bell", "marcus.bell@acme.example", "Account Executive",
                "Sales", "contractor", "no", "lena.ortiz@acme.example",
                "work_location", "on-site", "office", "New York", "github_username", "none",
                "production_aws_access_requested", "no", "start_date", "2026-10-01",
                "termination_date", "2026-12-31", "termination_reason", "end of contract"));
        s.addWorker(record(MARIA, "Maria Chen", "maria.chen@acme.example", "Site Reliability Engineer",
                "Engineering", "full-time", "yes (previous Okta account is deactivated)", "sam.okafor@acme.example",
                "work_location", "on-site", "office", "San Francisco",
                "github_username", "mchen-sre", "github_team", "sre",
                "production_aws_access_requested", "yes", "termination_date", "none"));
        s.addWorker(austinEngineer(NOAH, "Noah Fischer", "noah.fischer@acme.example", "platform"));
        s.addWorker(austinEngineer(AISHA, "Aisha Rahman", "aisha.rahman@acme.example", "data"));
        s.addWorker(austinEngineer(JORDAN, "Jordan Lee", "jordan.lee@acme.example", "frontend"));
        s.addWorker(austinEngineer(ALEX, "Alex Rivera", "alex.rivera@acme.example", "infra"));

        // Maria left once; her Okta account was deactivated then.
        s.addDeactivatedOktaUser("maria.chen@acme.example", "Maria", "Chen");

        // Public GitHub. An account called "Jordan Lee" exists, and it is not our Jordan Lee.
        s.addGithubUser("priyan-dev", "Priya N.");
        s.addGithubUser("mchen-sre", "Maria Chen");
        s.addGithubUser("nfischer-code", "Noah Fischer");
        s.addGithubUser("ar-codes", "");
        s.addGithubUser("jlee", "Jordan Lee");
        s.addGithubUser("jl-builds", "");

        // The Austin engineers were invited to Slack before their start date. Only Noah filled in GitHub.
        for (String email : new String[] {"noah.fischer@acme.example", "aisha.rahman@acme.example",
                "jordan.lee@acme.example", "alex.rivera@acme.example"}) {
            s.addPreboardingSlackAccount(email, Map.of("title", "New hire", "timezone", "America/Chicago"));
        }
        s.addPreboardingSlackAccount("noah.fischer@acme.example",
                Map.of("title", "New hire", "timezone", "America/Chicago", "github", "nfischer-code"));

        // What each would submit if asked. Alex never answers.
        s.setGithubUsernameReply("noah.fischer@acme.example", "nfischer-code");
        s.setGithubUsernameReply("aisha.rahman@acme.example", "ar-codes");
        s.setGithubUsernameReply("jordan.lee@acme.example", "jl-builds");
        return s;
    }

    private static Map<String, String> austinEngineer(String id, String name, String email, String team) {
        return record(id, name, email, "Software Engineer", "Engineering", "full-time", "no", "dana.kim@acme.example",
                "work_location", "on-site", "office", "Austin", "github_username", "not on file",
                "github_team", team, "production_aws_access_requested", "no", "termination_date", "none");
    }

    private static Map<String, String> record(String id, String name, String email, String title, String department,
                                              String employmentType, String rehire, String manager, String... more) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("employee_id", id);
        fields.put("name", name);
        fields.put("work_email", email);
        fields.put("title", title);
        fields.put("department", department);
        fields.put("employment_type", employmentType);
        fields.put("rehire", rehire);
        fields.put("manager", manager);
        for (int i = 0; i < more.length; i += 2) {
            fields.put(more[i], more[i + 1]);
        }
        return fields;
    }
}
