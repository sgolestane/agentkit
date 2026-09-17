package dev.agentkit.accessdesk.desk;

import dev.agentkit.accessdesk.deferred.DeferredActionScheduler;
import dev.agentkit.accessdesk.deferred.SubjectRecord;
import dev.agentkit.accessdesk.deferred.SubjectResolver;
import dev.agentkit.accessdesk.desk.AccessLedger.AccessRequest;
import dev.agentkit.accessdesk.desk.AccessLedger.Grant;
import dev.agentkit.accessdesk.desk.CompanyClient.Person;
import dev.agentkit.accessdesk.desk.CompanyClient.Resource;
import dev.agentkit.accessdesk.tools.Effect;
import dev.agentkit.accessdesk.tools.ToolCatalog;
import dev.agentkit.accessdesk.tools.ToolInfo;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.View;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The desk's own tools, acting as one person.
 *
 * <p><strong>The policy is text; these rules are code.</strong> Which route a request takes, what a
 * justification must say and what reminders to schedule are the customer's policy, applied by the model.
 * What must hold whatever the model concludes is enforced here, from the directory and the catalog as the
 * company systems report them:
 * <ul>
 *   <li>only low-sensitivity resources can be granted without an approver;</li>
 *   <li>access never lasts longer than the resource's {@code max_hours};</li>
 *   <li>an approver is the resource's owner or the requester's manager, and never the requester;</li>
 *   <li>only the named approver decides a request, and may shorten it but not lengthen it;</li>
 *   <li>a grant is revoked only by its holder, its approver, the resource's owner, or the desk itself.</li>
 * </ul>
 * The raw {@code grant_access} and {@code revoke_access} tools of the company systems are never given to a
 * model; access changes only through these.
 */
public final class DeskTools {

    /** The identity the desk acts as when nobody in particular does, such as a deferred action. */
    public static final String DESK = "access-desk";

    /** The subject kind deferred actions about a grant use. */
    public static final String GRANT = "grant";

    private final String me;
    private final AccessLedger ledger;
    private final CompanyClient company;
    private final DeferredActionScheduler scheduler;
    private final Supplier<Instant> clock;

    /**
     * @param me        the work email of the person these tools act as, or {@link #DESK}
     * @param scheduler the deferred action scheduler, or null to offer no scheduling
     */
    public DeskTools(String me, AccessLedger ledger, CompanyClient company, DeferredActionScheduler scheduler,
                     Supplier<Instant> clock) {
        this.me = lower(Objects.requireNonNull(me, "me"));
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.company = Objects.requireNonNull(company, "company");
        this.scheduler = scheduler;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Resolves {@value #GRANT} subjects from the ledger, for deferred actions. */
    public static SubjectResolver grantSubjects(AccessLedger ledger) {
        return new SubjectResolver() {
            @Override
            public Set<String> kinds() {
                return Set.of(GRANT);
            }

            @Override
            public Optional<SubjectRecord> resolve(String kind, String id) {
                if (!GRANT.equals(kind)) {
                    return Optional.empty();
                }
                return ledger.grant(id).map(DeskTools::asSubject);
            }
        };
    }

    /** What a grant holds, for the scheduler's feedback: the grant id a goal about it should name. */
    public static List<String> holdings(SubjectRecord subject) {
        return GRANT.equals(subject.kind()) && "ACTIVE".equals(subject.facts().get("status"))
                ? List.of(subject.id()) : List.of();
    }

    static SubjectRecord asSubject(Grant g) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("grant_id", g.id());
        facts.put("request_id", g.requestId());
        facts.put("resource_id", g.resourceId());
        facts.put("resource_name", g.resourceName());
        facts.put("level", g.level());
        facts.put("holder", g.email());
        facts.put("approved_by", g.approvedBy());
        facts.put("status", g.status().name());
        facts.put("granted_at", g.grantedAt().toString());
        facts.put("expires_at", g.expiresAt().toString());
        if (g.revokedAt() != null) {
            facts.put("revoked_at", g.revokedAt().toString());
            facts.put("revoked_by", String.valueOf(g.revokedBy()));
        }
        Set<String> contacts = new LinkedHashSet<>();
        contacts.add(g.email());
        if (g.approvedBy() != null && g.approvedBy().contains("@")) {
            contacts.add(g.approvedBy());
        }
        if (g.resourceOwner() != null) {
            contacts.add(g.resourceOwner());
        }
        return new SubjectRecord(GRANT, g.id(), Set.of(g.id(), g.email()), contacts, facts);
    }

    /** Every desk tool, with its declaration. */
    public ToolCatalog catalog() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.add(tool("grant_low_risk_access",
                        "Grant the person you are talking to access to a LOW-sensitivity resource right away, for a number "
                                + "of hours. Refused for anything that is not low sensitivity.",
                        props("resource_id", str("Resource id from list_resources"), "level", str("Access level"),
                                "hours", integer("How many hours the access lasts"),
                                "justification", str("Why the person needs it")),
                        List.of("resource_id", "level", "hours", "justification"), SideEffects.EXTERNAL, this::grantLowRisk),
                new ToolInfo("access-desk", Effect.GRANT, null));
        catalog.add(tool("submit_access_request",
                        "Submit a request for access that needs approval, naming the approver. The approver must be the "
                                + "resource's owner or the requester's manager, and cannot be the requester. The approver is "
                                + "sent a direct message.",
                        props("resource_id", str("Resource id from list_resources"), "level", str("Access level"),
                                "hours", integer("How many hours the access should last"),
                                "justification", str("Why the person needs it"),
                                "approver_email", str("Work email of who must approve")),
                        List.of("resource_id", "level", "hours", "justification", "approver_email"), SideEffects.EXTERNAL,
                        this::submit),
                new ToolInfo("access-desk", Effect.REQUEST, null));
        catalog.add(tool("pending_approvals", "List the access requests waiting for the person you are talking to to decide.",
                        props(), List.of(), SideEffects.NONE, inv -> pendingApprovals()),
                new ToolInfo("access-desk", Effect.READ, null));
        catalog.add(tool("decide_request",
                        "Approve or deny an access request waiting for the person you are talking to. When approving you may "
                                + "give fewer hours than were asked for, never more. Approving grants the access.",
                        props("request_id", str("Request id, e.g. REQ-1001"),
                                "decision", Map.of("type", "string", "enum", List.of("approve", "deny")),
                                "hours", integer("Hours to approve; leave out to approve what was asked"),
                                "note", str("Optional note for the requester")),
                        List.of("request_id", "decision"), SideEffects.EXTERNAL, this::decide),
                new ToolInfo("access-desk", Effect.GRANT, null));
        catalog.add(tool("my_access", "List the access the person you are talking to holds through the desk, and when each ends.",
                        props(), List.of(), SideEffects.NONE, inv -> myAccess()),
                new ToolInfo("access-desk", Effect.READ, null));
        catalog.add(tool("my_requests", "List the access requests the person you are talking to has made, and where each stands.",
                        props(), List.of(), SideEffects.NONE, inv -> myRequests()),
                new ToolInfo("access-desk", Effect.READ, null));
        catalog.add(tool("revoke_grant",
                        "Revoke a grant now. Allowed for the grant's holder, its approver, the resource's owner, and the desk.",
                        props("grant_id", str("Grant id, e.g. GR-1001"), "reason", str("Why it is being revoked")),
                        List.of("grant_id", "reason"), SideEffects.IDEMPOTENT, this::revoke),
                new ToolInfo("access-desk", Effect.REVOKE, "grant_id"));
        catalog.add(tool("audit_log", "Show recent access-desk activity involving the person you are talking to.",
                        props(), List.of(), SideEffects.NONE, inv -> auditLog()),
                new ToolInfo("access-desk", Effect.READ, null));
        if (scheduler != null) {
            catalog.add(scheduler.tool(me), new ToolInfo("scheduler", Effect.SCHEDULE, "subject_id"));
        }
        return catalog;
    }

    // ---------------------------------------------------------------- handlers

    private ToolResult grantLowRisk(ToolInvocation inv) {
        Checked checked = check(inv);
        if (checked.error() != null) {
            return ToolResult.error(checked.error());
        }
        Resource resource = checked.resource();
        if (!"low".equalsIgnoreCase(resource.sensitivity())) {
            return ToolResult.error(resource.name() + " is " + resource.sensitivity()
                    + " sensitivity, so it needs an approver: use submit_access_request.");
        }
        Optional<Grant> held = activeGrant(me, resource.id(), checked.level());
        if (held.isPresent()) {
            return ToolResult.error("You already hold " + checked.level() + " on " + resource.name() + " as "
                    + held.get().id() + " until " + held.get().expiresAt() + ". Ask for an extension instead.");
        }
        Instant now = clock.get();
        String requestId = ledger.nextRequestId();
        ledger.put(new AccessRequest(requestId, me, resource.id(), resource.name(), checked.level(), checked.hours(),
                checked.justification(), "policy:low-sensitivity", AccessRequest.Status.APPROVED, now, now,
                "policy:low-sensitivity", "", checked.hours(), null));
        ledger.record(now, me, "requested", checked.level() + " on " + resource.name() + " for " + checked.hours() + "h: "
                + checked.justification(), requestId, null);
        return grantFor(requestId, me, resource, checked.level(), checked.hours(), "policy:low-sensitivity", now);
    }

    private ToolResult submit(ToolInvocation inv) {
        Checked checked = check(inv);
        if (checked.error() != null) {
            return ToolResult.error(checked.error());
        }
        Resource resource = checked.resource();
        String approver = lower(inv.stringArgument("approver_email"));
        if (approver.isEmpty()) {
            return ToolResult.error("Name the approver: the resource's owner or your manager.");
        }
        if (approver.equals(me)) {
            return ToolResult.error("Nobody may approve their own request. The approver must be someone else: "
                    + "the resource's owner, or your manager if you own it.");
        }
        Optional<Person> requester = company.person(me);
        String manager = requester.map(Person::manager).map(DeskTools::lower).orElse("");
        if (!approver.equals(lower(resource.owner())) && !approver.equals(manager)) {
            return ToolResult.error(approver + " cannot approve this. The approver must be the owner of " + resource.name()
                    + " (" + resource.owner() + ") or your manager" + (manager.isEmpty() ? "" : " (" + manager + ")") + ".");
        }
        if (company.person(approver).isEmpty()) {
            return ToolResult.error("There is nobody in the directory with the email " + approver + ".");
        }
        boolean duplicate = ledger.requests().stream().anyMatch(r -> r.requester().equals(me)
                && r.resourceId().equals(resource.id()) && r.level().equals(checked.level())
                && r.status() == AccessRequest.Status.PENDING);
        if (duplicate) {
            return ToolResult.error("You already have a pending request for " + checked.level() + " on " + resource.name() + ".");
        }
        Instant now = clock.get();
        String requestId = ledger.nextRequestId();
        ledger.put(new AccessRequest(requestId, me, resource.id(), resource.name(), checked.level(), checked.hours(),
                checked.justification(), approver, AccessRequest.Status.PENDING, now, null, null, null, null, null));
        ledger.record(now, me, "requested", checked.level() + " on " + resource.name() + " for " + checked.hours()
                + "h, approver " + approver + ": " + checked.justification(), requestId, null);
        String name = requester.map(Person::name).orElse(me);
        company.message(approver, name + " (" + me + ") asks for " + checked.level() + " access to " + resource.name()
                + " for " + checked.hours() + " hours: \"" + checked.justification() + "\". Request " + requestId
                + ". Open Access Desk to approve or deny it.");
        return ToolResult.ok("Submitted " + requestId + ": " + checked.level() + " on " + resource.name() + " for "
                + checked.hours() + " hours, waiting for " + approver + ", who has been messaged.");
    }

    private ToolResult decide(ToolInvocation inv) {
        Optional<AccessRequest> found = ledger.request(inv.stringArgument("request_id"));
        if (found.isEmpty()) {
            return ToolResult.error("There is no request " + inv.stringArgument("request_id") + ".");
        }
        AccessRequest request = found.get();
        if (!request.approver().equals(me)) {
            return ToolResult.error("Only " + request.approver() + " can decide " + request.id() + ".");
        }
        if (request.status() != AccessRequest.Status.PENDING) {
            return ToolResult.error(request.id() + " was already " + request.status().name().toLowerCase(Locale.ROOT) + ".");
        }
        String decision = lower(inv.stringArgument("decision"));
        String note = Optional.ofNullable(inv.stringArgument("note")).map(String::strip).orElse("");
        Instant now = clock.get();
        if ("deny".equals(decision)) {
            ledger.updateRequest(request.id(), r -> new AccessRequest(r.id(), r.requester(), r.resourceId(), r.resourceName(),
                    r.level(), r.hours(), r.justification(), r.approver(), AccessRequest.Status.DENIED, r.createdAt(), now, me,
                    note, null, null));
            ledger.record(now, me, "denied", request.level() + " on " + request.resourceName() + " for " + request.requester()
                    + (note.isEmpty() ? "" : ": " + note), request.id(), null);
            company.message(request.requester(), "Your request " + request.id() + " for " + request.level() + " access to "
                    + request.resourceName() + " was denied by " + me + (note.isEmpty() ? "." : ": " + note));
            return ToolResult.ok("Denied " + request.id() + "; " + request.requester() + " has been messaged.");
        }
        if (!"approve".equals(decision)) {
            return ToolResult.error("decision must be approve or deny.");
        }
        Integer asked = integerArg(inv.argument("hours"));
        int hours = asked == null ? request.hours() : asked;
        if (hours < 1 || hours > request.hours()) {
            return ToolResult.error("You can approve between 1 and " + request.hours() + " hours for " + request.id()
                    + " — fewer than were asked for, never more.");
        }
        Optional<Resource> resource = company.resource(request.resourceId());
        if (resource.isEmpty()) {
            return ToolResult.error("The resource " + request.resourceId() + " is no longer in the catalog.");
        }
        if (hours > resource.get().max_hours()) {
            return ToolResult.error(resource.get().name() + " may be held for at most " + resource.get().max_hours() + " hours.");
        }
        ToolResult granted = grantFor(request.id(), request.requester(), resource.get(), request.level(), hours, me, now);
        if (granted.isError()) {
            return granted;
        }
        String grantId = ledger.requests().stream().filter(r -> r.id().equals(request.id())).findFirst()
                .map(AccessRequest::grantId).orElse(null);
        ledger.updateRequest(request.id(), r -> new AccessRequest(r.id(), r.requester(), r.resourceId(), r.resourceName(),
                r.level(), r.hours(), r.justification(), r.approver(), AccessRequest.Status.APPROVED, r.createdAt(), now, me,
                note, hours, r.grantId()));
        Grant grant = ledger.grant(grantId).orElseThrow();
        company.message(request.requester(), "Your request " + request.id() + " was approved by " + me + ": "
                + request.level() + " access to " + request.resourceName() + " until " + grant.expiresAt() + " (grant "
                + grant.id() + ")" + (note.isEmpty() ? "." : ". " + note));
        return ToolResult.ok(granted.content() + " " + request.requester() + " has been messaged.");
    }

    private ToolResult revoke(ToolInvocation inv) {
        Optional<Grant> found = ledger.grant(inv.stringArgument("grant_id"));
        if (found.isEmpty()) {
            return ToolResult.error("There is no grant " + inv.stringArgument("grant_id") + ".");
        }
        Grant grant = found.get();
        if (grant.status() == Grant.Status.REVOKED) {
            return ToolResult.ok(grant.id() + " was already revoked at " + grant.revokedAt() + ".");
        }
        boolean allowed = me.equals(DESK) || me.equals(grant.email()) || me.equals(lower(grant.approvedBy()))
                || me.equals(lower(grant.resourceOwner()));
        if (!allowed) {
            return ToolResult.error("Only the holder, the approver or the owner of " + grant.resourceName()
                    + " can revoke " + grant.id() + ".");
        }
        Optional<String> failed = company.revoke(grant.resourceId(), grant.email(), grant.level());
        if (failed.isPresent()) {
            return ToolResult.error("The access could not be revoked: " + failed.get());
        }
        String reason = Optional.ofNullable(inv.stringArgument("reason")).map(String::strip).orElse("");
        Instant now = clock.get();
        ledger.updateGrant(grant.id(), g -> new Grant(g.id(), g.requestId(), g.resourceId(), g.resourceName(), g.resourceOwner(),
                g.email(), g.level(), g.grantedAt(), g.expiresAt(), g.approvedBy(), Grant.Status.REVOKED, now, me, reason));
        ledger.record(now, me, "revoked", grant.level() + " on " + grant.resourceName() + " from " + grant.email()
                + (reason.isEmpty() ? "" : ": " + reason), grant.requestId(), grant.id());
        return ToolResult.ok("Revoked " + grant.id() + ": " + grant.level() + " on " + grant.resourceName() + " from "
                + grant.email() + ".");
    }

    private ToolResult myAccess() {
        List<Grant> mine = ledger.grants().stream().filter(g -> g.email().equals(me) && g.status() == Grant.Status.ACTIVE).toList();
        if (mine.isEmpty()) {
            return ToolResult.ok("You hold no access through the desk right now.");
        }
        Instant now = clock.get();
        StringBuilder digest = new StringBuilder("You hold " + mine.size() + " grant(s):");
        List<List<Object>> rows = new ArrayList<>();
        for (Grant g : mine) {
            String left = remaining(now, g.expiresAt());
            digest.append("\n- ").append(g.id()).append(": ").append(g.level()).append(" on ").append(g.resourceName())
                    .append(" until ").append(g.expiresAt()).append(" (").append(left).append(")");
            rows.add(List.of(g.id(), g.resourceName(), g.level(), g.expiresAt().toString(), left, g.approvedBy()));
        }
        return ToolResult.ok(digest.toString()).withView(View.table(List.of(View.Column.text("Grant"),
                View.Column.text("Resource"), View.Column.text("Level"), View.Column.date("Expires"),
                View.Column.text("Left"), View.Column.text("Approved by")), rows));
    }

    private ToolResult myRequests() {
        List<AccessRequest> mine = ledger.requests().stream().filter(r -> r.requester().equals(me)).toList();
        return requestsResult(mine, "You have made no requests.", "Your requests");
    }

    private ToolResult pendingApprovals() {
        List<AccessRequest> waiting = ledger.requests().stream()
                .filter(r -> r.approver().equals(me) && r.status() == AccessRequest.Status.PENDING).toList();
        return requestsResult(waiting, "Nothing is waiting for your approval.", "Waiting for your decision");
    }

    private ToolResult requestsResult(List<AccessRequest> requests, String none, String title) {
        if (requests.isEmpty()) {
            return ToolResult.ok(none);
        }
        StringBuilder digest = new StringBuilder(title + ":");
        List<List<Object>> rows = new ArrayList<>();
        for (AccessRequest r : requests) {
            digest.append("\n- ").append(r.id()).append(": ").append(r.requester()).append(" asks for ").append(r.level())
                    .append(" on ").append(r.resourceName()).append(" (").append(r.resourceId()).append(") for ")
                    .append(r.hours()).append("h, because \"").append(r.justification()).append("\"; ")
                    .append(r.status().name().toLowerCase(Locale.ROOT)).append(", approver ").append(r.approver())
                    .append(r.grantId() == null ? "" : ", grant " + r.grantId());
            rows.add(List.of(r.id(), r.requester(), r.resourceName(), r.level(), r.hours(), r.justification(),
                    r.status().name().toLowerCase(Locale.ROOT), r.approver()));
        }
        return ToolResult.ok(digest.toString()).withView(View.table(List.of(View.Column.text("Request"),
                View.Column.text("Requester"), View.Column.text("Resource"), View.Column.text("Level"),
                View.Column.number("Hours"), View.Column.text("Justification"), View.Column.text("Status"),
                View.Column.text("Approver")), rows));
    }

    private ToolResult auditLog() {
        List<AccessLedger.AuditEvent> events = ledger.audit().stream()
                .filter(e -> e.actor().equals(me) || e.detail().toLowerCase(Locale.ROOT).contains(me))
                .limit(30).toList();
        if (events.isEmpty()) {
            return ToolResult.ok("No activity involving you yet.");
        }
        StringBuilder digest = new StringBuilder("Recent activity:");
        List<View.Moment> moments = new ArrayList<>();
        for (AccessLedger.AuditEvent e : events) {
            digest.append("\n- ").append(e.at()).append(" ").append(e.actor()).append(" ").append(e.action()).append(" ")
                    .append(e.detail());
            moments.add(new View.Moment(e.action(), e.actor() + " " + e.action(), e.detail(), e.at().toString(), false));
        }
        return ToolResult.ok(digest.toString()).withView(View.timeline("Access Desk activity", moments));
    }

    // ---------------------------------------------------------------- helpers

    private ToolResult grantFor(String requestId, String email, Resource resource, String level, int hours, String approvedBy,
                                Instant now) {
        Optional<String> failed = company.grant(resource.id(), email, level);
        if (failed.isPresent()) {
            return ToolResult.error("The access could not be granted: " + failed.get());
        }
        String grantId = ledger.nextGrantId();
        Instant expires = now.plus(Duration.ofHours(hours));
        ledger.put(new Grant(grantId, requestId, resource.id(), resource.name(), lower(resource.owner()), email, level, now,
                expires, approvedBy, Grant.Status.ACTIVE, null, null, null));
        ledger.updateRequest(requestId, r -> new AccessRequest(r.id(), r.requester(), r.resourceId(), r.resourceName(),
                r.level(), r.hours(), r.justification(), r.approver(), r.status(), r.createdAt(), r.decidedAt(), r.decidedBy(),
                r.note(), r.approvedHours(), grantId));
        ledger.record(now, approvedBy.startsWith("policy:") ? DESK : approvedBy, "granted", level + " on " + resource.name()
                + " to " + email + " until " + expires, requestId, grantId);
        return ToolResult.ok("Granted " + level + " on " + resource.name() + " to " + email + " as " + grantId + ", expiring at "
                + expires + " (" + hours + "h). Request " + requestId + ".");
    }

    private Optional<Grant> activeGrant(String email, String resourceId, String level) {
        return ledger.grants().stream().filter(g -> g.email().equals(email) && g.resourceId().equals(resourceId)
                && g.level().equals(level) && g.status() == Grant.Status.ACTIVE).findFirst();
    }

    private record Checked(Resource resource, String level, int hours, String justification, String error) {
        static Checked failed(String error) {
            return new Checked(null, null, 0, null, error);
        }
    }

    /** The checks every request shares: the resource, the level, the hours and a justification. */
    private Checked check(ToolInvocation inv) {
        if (me.equals(DESK)) {
            return Checked.failed("The desk does not request access for itself.");
        }
        String resourceId = Optional.ofNullable(inv.stringArgument("resource_id")).map(String::strip).orElse("");
        Optional<Resource> resource = company.resource(resourceId);
        if (resource.isEmpty()) {
            return Checked.failed("There is no resource with id \"" + resourceId + "\". Use list_resources to find it.");
        }
        String level = lower(inv.stringArgument("level"));
        if (!resource.get().levels().contains(level)) {
            return Checked.failed(resource.get().name() + " has no level \"" + level + "\"; its levels are "
                    + resource.get().levels() + ".");
        }
        Integer hours = integerArg(inv.argument("hours"));
        if (hours == null || hours < 1) {
            return Checked.failed("hours must be a whole number of at least 1.");
        }
        if (hours > resource.get().max_hours()) {
            return Checked.failed(resource.get().name() + " may be held for at most " + resource.get().max_hours()
                    + " hours; " + hours + " were asked for.");
        }
        String justification = Optional.ofNullable(inv.stringArgument("justification")).map(String::strip).orElse("");
        if (justification.isEmpty()) {
            return Checked.failed("A justification is required.");
        }
        return new Checked(resource.get(), level, hours, justification, null);
    }

    private static String remaining(Instant now, Instant until) {
        long minutes = Math.max(0, Duration.between(now, until).toMinutes());
        return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
    }

    private static Integer integerArg(Object value) {
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Handlers run one at a time per ledger, so a check and the write it guards cannot interleave with another's. */
    private FunctionTool tool(String name, String description, Map<String, Object> properties, List<String> required,
                              SideEffects sideEffects, Function<ToolInvocation, ToolResult> handler) {
        return FunctionTool.builder(name, description)
                .schema(Map.of("type", "object", "properties", properties, "required", required))
                .sideEffects(sideEffects)
                .provenance(Provenance.FIRST_PARTY)
                .handler(inv -> {
                    synchronized (ledger) {
                        return handler.apply(inv);
                    }
                })
                .build();
    }

    private static Map<String, Object> props(Object... pairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.put((String) pairs[i], pairs[i + 1]);
        }
        return properties;
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    static String lower(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }
}
