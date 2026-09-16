package dev.agentkit.itops.tools;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.runtime.OpsContext;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The identity-provider capability: users, groups and membership.
 *
 * <p>This is the family the supervisor cares most about, because two calls with identical
 * shapes can be a routine access grant or a privilege escalation depending on one string
 * argument. The tools therefore report what they found rather than only what they did:
 * {@code find_group} says whether the group is privileged, and that fact is what
 * {@code Supervisor} reads when it decides whether {@code MEDIUM} should become
 * {@code HIGH}.
 *
 * <p>Membership changes are idempotent and say so in their result — "already a member,
 * nothing to do" is a success, not a failure. A runtime that can be interrupted between
 * acting and recording will re-run the call, and a tool that treats the second attempt as
 * an error turns a completed remediation into a failed execution.
 *
 * <p>What they report says <em>what</em> happened and not <em>to whom</em>, which is the
 * rule {@code DirectoryTools} states and {@code TicketTools} settled first: every one of
 * these seven tools built a sentence around an identifier — its own argument, or the text
 * of a connector's exception, which is that argument again — on a line with no fence
 * anywhere near it (#191). The identifiers the model has not seen are inside the fences;
 * the ones it wrote are its own arguments coming back, and it is being told which of them
 * missed.
 */
public final class IdentityTools {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityTools.class);

    /**
     * Characters of identity records the model is shown, per call.
     *
     * <p>The figure {@code TicketTools} and {@code DirectoryTools} use, for the reason
     * given there: a tool result is re-sent every turn, so an unbounded one is a per-turn
     * cost somebody else chooses. Three sites here used {@code Spotlight.wrap}, which has
     * no bound, over a display name, a group name and a membership list (#191).
     *
     * <p>The membership list is the one where the bound can lose something a decision turns
     * on, which is why the cut is logged rather than left to the in-band marker: a
     * before-and-after membership check that silently stops at twenty thousand characters
     * would show a model a group it has not finished reading.
     */
    private static final int MAX_IDENTITY_CHARS = 20_000;

    private static final String CAP_READ = "identity.read";
    private static final String CAP_MEMBERSHIP = "identity.group_membership.write";
    private static final String CAP_LIFECYCLE = "identity.lifecycle.write";

    private IdentityTools() {
    }

    public static List<ToolPolicy> policies() {
        return List.of(
                ToolPolicy.read("identity.find_user", CAP_READ, "identity"),
                ToolPolicy.read("identity.find_group", CAP_READ, "identity"),
                ToolPolicy.read("identity.get_group_members", CAP_READ, "identity"),
                // Baseline only. Adding to Employees-All and adding to
                // Production-Administrators are this same row until the supervisor reads
                // the arguments.
                ToolPolicy.write("identity.add_user_to_group", CAP_MEMBERSHIP, "identity",
                        Risk.MEDIUM, true, true),
                ToolPolicy.write("identity.remove_user_from_group", CAP_MEMBERSHIP, "identity",
                        Risk.MEDIUM, true, true),
                ToolPolicy.write("identity.suspend_user", CAP_LIFECYCLE, "identity",
                        Risk.HIGH, true, true),
                // The one operation in the demo with no way back.
                ToolPolicy.write("identity.delete_user", CAP_LIFECYCLE, "identity",
                        Risk.DESTRUCTIVE, false, false));
    }

    public static List<Tool> of(IdentityConnector identity, OpsContext context) {
        return List.of(
                FunctionTool.builder("identity.find_user",
                                "capability: identity.read. Look up an account by email and report "
                                        + "its status.")
                        .schema(single("email", "The account's email address."))
                        .readOnly()
                        // The deployment's own identity provider: it chose every byte in
                        // these records and in this tool's confirmations. Declared so a
                        // TrustFloor does not lower on the systems of record the run is
                        // here to act on.
                        .provenance(Provenance.FIRST_PARTY)
                        .handler(invocation -> {
                            String email = invocation.stringArgument("email");
                            return identity.findUser(email)
                                    .map(user -> {
                                        // Raw in the evidence on purpose; fenced by every
                                        // consumer. See DirectoryTools for the argument and
                                        // for what reducing it measurably cost (#190).
                                        context.evidence("Identity provider: account " + user.email()
                                                + " exists, status " + user.status()
                                                + (user.admin() ? ", administrative" : "") + ".");
                                        return ToolResult.ok(fenced(
                                                "email=" + user.email() + "\nname=" + user.name()
                                                        + "\nstatus=" + user.status()
                                                        + "\nadmin=" + user.admin()));
                                    })
                                    // Not "No account for <email>." The model wrote that
                                    // argument and is being told which of its own arguments
                                    // missed (#191).
                                    .orElseGet(() -> ToolResult.ok(
                                            "No account with that email address."));
                        })
                        .build(),

                FunctionTool.builder("identity.find_group",
                                "capability: identity.read. Look up a group and report whether it "
                                        + "is privileged. Check this before proposing membership "
                                        + "changes.")
                        .schema(single("group", "The group name, exactly as written."))
                        .readOnly()
                        // The deployment's own identity provider: it chose every byte in
                        // these records and in this tool's confirmations. Declared so a
                        // TrustFloor does not lower on the systems of record the run is
                        // here to act on.
                        .provenance(Provenance.FIRST_PARTY)
                        .handler(invocation -> {
                            String name = invocation.stringArgument("group");
                            return identity.findGroup(name)
                                    .map(group -> {
                                        boolean privileged = identity.isPrivileged(group.name());
                                        context.evidence("Identity provider: group " + group.name()
                                                + " exists and is "
                                                + (privileged ? "PRIVILEGED" : "non-privileged") + ".");
                                        return ToolResult.ok(fenced("group=" + group.name()
                                                + "\nprivileged=" + privileged));
                                    })
                                    .orElseGet(() ->
                                            ToolResult.ok("No group with that name."));
                        })
                        .build(),

                FunctionTool.builder("identity.get_group_members",
                                "capability: identity.read. List a group's current members. Use "
                                        + "this both to check before a change and to verify after "
                                        + "one.")
                        .schema(single("group", "The group name."))
                        .readOnly()
                        // The deployment's own identity provider: it chose every byte in
                        // these records and in this tool's confirmations. Declared so a
                        // TrustFloor does not lower on the systems of record the run is
                        // here to act on.
                        .provenance(Provenance.FIRST_PARTY)
                        .handler(invocation -> {
                            String name = invocation.stringArgument("group");
                            List<String> members = identity.groupMembers(name);
                            // A count, not the group's name — the identical defect
                            // search_tickets had, where the id was appended raw to a
                            // per-entry header, and the identical fix: the framework's line
                            // says how many, and the identifier lives inside the fence.
                            // Here the group is named by the model's own argument, so it is
                            // not even a value the model has to be given back.
                            //
                            // The count stays outside because it is ours: a hostile member
                            // list otherwise forges an entry, and inside a shared fence a
                            // forgery is indistinguishable from the real ones.
                            return ToolResult.ok(members.size() + " member(s) of that group:\n"
                                    + fenced(String.join("\n", members)));
                        })
                        .build(),

                FunctionTool.builder("identity.add_user_to_group",
                                "capability: identity.group_membership.write. Add an account to a "
                                        + "group. Safe to repeat: if the account is already a "
                                        + "member this reports so and changes nothing.")
                        .schema(pair("user", "The account's email address.",
                                "group", "The group name."))
                        .sideEffects(SideEffects.EXTERNAL)
                        // The deployment's own identity provider: it chose every byte in
                        // these records and in this tool's confirmations. Declared so a
                        // TrustFloor does not lower on the systems of record the run is
                        // here to act on.
                        .provenance(Provenance.FIRST_PARTY)
                        .handler(invocation -> {
                            String user = invocation.stringArgument("user");
                            String group = invocation.stringArgument("group");
                            try {
                                boolean changed = identity.addUserToGroup(user, group);
                                context.evidence((changed ? "Added " : "Confirmed already present: ")
                                        + user + " in " + group + ".");
                                return ToolResult.ok(changed
                                        ? "Added to the group."
                                        : "Already a member of that group; nothing to do.");
                            } catch (IllegalArgumentException notFound) {
                                return fromTheProvider(notFound);
                            }
                        })
                        .build(),

                FunctionTool.builder("identity.remove_user_from_group",
                                "capability: identity.group_membership.write. Remove an account "
                                        + "from a group. Safe to repeat.")
                        .schema(pair("user", "The account's email address.",
                                "group", "The group name."))
                        .sideEffects(SideEffects.EXTERNAL)
                        // The deployment's own identity provider: it chose every byte in
                        // these records and in this tool's confirmations. Declared so a
                        // TrustFloor does not lower on the systems of record the run is
                        // here to act on.
                        .provenance(Provenance.FIRST_PARTY)
                        .handler(invocation -> {
                            String user = invocation.stringArgument("user");
                            String group = invocation.stringArgument("group");
                            boolean changed = identity.removeUserFromGroup(user, group);
                            return ToolResult.ok(changed
                                    ? "Removed from the group."
                                    : "Was not a member of that group; nothing to do.");
                        })
                        .build(),

                FunctionTool.builder("identity.suspend_user",
                                "capability: identity.lifecycle.write. Suspend an account. The "
                                        + "account keeps its identity and can be reactivated — "
                                        + "prefer this over deletion.")
                        .schema(single("email", "The account to suspend."))
                        .sideEffects(SideEffects.EXTERNAL)
                        // The deployment's own identity provider: it chose every byte in
                        // these records and in this tool's confirmations. Declared so a
                        // TrustFloor does not lower on the systems of record the run is
                        // here to act on.
                        .provenance(Provenance.FIRST_PARTY)
                        .handler(invocation -> {
                            String email = invocation.stringArgument("email");
                            try {
                                boolean changed = identity.suspendUser(email);
                                return ToolResult.ok(changed ? "That account is now suspended."
                                        : "That account was already suspended.");
                            } catch (IllegalArgumentException notFound) {
                                return fromTheProvider(notFound);
                            }
                        })
                        .build(),

                FunctionTool.builder("identity.delete_user",
                                "capability: identity.lifecycle.write. Permanently delete an "
                                        + "account and its credentials. This cannot be undone.")
                        .schema(single("email", "The account to delete."))
                        .sideEffects(SideEffects.EXTERNAL)
                        // The deployment's own identity provider: it chose every byte in
                        // these records and in this tool's confirmations. Declared so a
                        // TrustFloor does not lower on the systems of record the run is
                        // here to act on.
                        .provenance(Provenance.FIRST_PARTY)
                        .handler(invocation -> {
                            String email = invocation.stringArgument("email");
                            try {
                                identity.deleteUser(email);
                                return ToolResult.ok("That account is deleted.");
                            } catch (IllegalArgumentException notFound) {
                                return fromTheProvider(notFound);
                            }
                        })
                        .build());
    }

    /** One identity record, bounded, and a line in the log when the bound bit. */
    private static String fenced(String record) {
        Spotlight.Bounded bounded = Spotlight.fenceBounded(Spotlight.Kind.EVIDENCE, Source.of("identity"),
                record, MAX_IDENTITY_CHARS);
        if (bounded.cut()) {
            // Logged for the reason Synthesizers.fenceOf gives — "a cap nobody is told
            // about reads as 'everything was carried'" — and because Cut's own in-band
            // marker is forgeable by whoever wrote the body. The operator gets an
            // unforgeable one. Every bounded site in core logs; these three did not exist.
            LOG.info("An identity record was cut to {} characters before the model saw it",
                    MAX_IDENTITY_CHARS);
        }
        return bounded.fence();
    }

    /**
     * A refusal from the identity provider: our frame, their words, fenced.
     *
     * <p>These four handlers returned {@code notFound.getMessage()} to the model verbatim
     * (#191), and the connector writes that message around the argument it was given —
     * {@code "No such group: " + groupName} — so a hostile group name arrived unfenced in
     * the framework's own voice, with no bound on its length either. {@code ToolResult
     * .failed} is the instrument core already built for exactly this: the frame is ours and
     * unfenced, the detail is fenced as evidence, bounded, and logged when it is cut.
     *
     * <p><strong>Renamed from {@code refused} (#278).</strong> It said the right thing
     * about the outcome and the opposite of the truth about the text. Since #276
     * {@code ToolResult.refused} is the factory for a sentence <em>this framework</em>
     * composed without reading anything, and stamps {@code FIRST_PARTY} on it; this helper
     * wraps {@code ToolResult.failed}, whose whole point is that the detail is somebody
     * else's and gets fenced. Two things one identifier apart meaning opposite things
     * about who wrote the text is a trap for the next reader, and the reader most likely
     * to fall into it is whoever adds the fifth handler here. The name now says whose
     * words come back.
     */
    private static ToolResult fromTheProvider(RuntimeException cause) {
        return ToolResult.failed("The identity provider refused the call.", Source.of("identity"), cause);
    }

    private static Map<String, Object> single(String name, String description) {
        return Map.of("type", "object",
                "properties", Map.of(name, Map.of("type", "string", "description", description)),
                "required", List.of(name));
    }

    private static Map<String, Object> pair(String first, String firstDescription,
            String second, String secondDescription) {
        return Map.of("type", "object",
                "properties", Map.of(
                        first, Map.of("type", "string", "description", firstDescription),
                        second, Map.of("type", "string", "description", secondDescription)),
                "required", List.of(first, second));
    }
}
