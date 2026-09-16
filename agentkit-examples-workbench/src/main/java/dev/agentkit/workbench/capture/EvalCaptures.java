package dev.agentkit.workbench.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.workbench.connector.Alm;
import dev.agentkit.workbench.domain.Approval;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.domain.Ticket;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Turns a live run into an eval case — the workbench's discovery loop, applied to testing:
 * instead of inventing scenarios, the product captures the ones that actually happened,
 * with the operator's own decisions as the ground truth.
 *
 * <p>A captured case is everything a replay needs to put a fresh agent in the same
 * situation and hold it to what the person ratified: the ticket <em>as filed</em> (the
 * integration identity's own comments stripped, status reset to open, assignment
 * cleared — the state the run started from, reconstructed from the state it left), the
 * knowledge the run had, the mode it ran in, and the facts of how it ended — what parked
 * and of which kind, which writes actually ran, where the ticket finished. The replay
 * suite derives checks from those facts rather than from anything stored as an
 * expectation, so the derivation can improve without invalidating the data.
 *
 * <p>What each terminal state teaches a replay:
 *
 * <ul>
 *   <li>A run that raised a <strong>question</strong> — asking was right; the replay must
 *       ask rather than guess or punt.</li>
 *   <li>A run that raised an <strong>action</strong>, or any supervised run — the ladder
 *       held; a from-scratch replay must park for a person before anything changes.</li>
 *   <li>A completed <strong>preview</strong> — a rehearsal; the replay must change
 *       nothing.</li>
 *   <li>A completed <strong>AUTO</strong> run — a golden trajectory; the replay must
 *       complete, perform the same writes, and leave the ticket where the original
 *       did.</li>
 * </ul>
 *
 * <p>A {@code FAILED} run, or a cancelled one that never asked anybody, is refused: it
 * teaches a replay nothing it could be held to.
 */
public final class EvalCaptures {

    /** The write tools a completed capture holds a replay to. */
    private static final Set<String> WRITES =
            Set.of("jira.add_comment", "jira.assign_to_me", "jira.transition_ticket");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One requester-side comment, as the run saw it. */
    public record CapturedComment(String author, String body) {}

    /**
     * One live run, as a replay can be held to it.
     *
     * @param runId           the run this was captured from
     * @param ticketKey       the ticket it worked
     * @param mode            the trust setting it ran at
     * @param finalStatus     how the run ended
     * @param parkedKind      {@code ACTION}, {@code QUESTION}, or {@code null} when the
     *                        run asked nobody
     * @param parkedTool      the tool the park named, when one did
     * @param writesThatRan   the ALM writes that actually executed, in order
     * @param finalTicketCategory where the ticket ended ({@code OPEN}/{@code IN_PROGRESS}/
     *                        {@code DONE}), for holding a completed replay to the same place
     * @param summary         the ticket as filed
     * @param description     the ticket as filed — somebody else's words; escape where rendered
     * @param priority        the ALM's priority name, or {@code null}
     * @param reporter        who filed it
     * @param comments        requester-side comments only; the integration's are stripped
     * @param learnings       what the agent knew when the run happened
     * @param capturedAt      when this was taken
     */
    public record CapturedCase(String runId, String ticketKey, Run.Mode mode,
                               Run.Status finalStatus, String parkedKind, String parkedTool,
                               List<String> writesThatRan, String finalTicketCategory,
                               String summary, String description, String priority,
                               String reporter, List<CapturedComment> comments,
                               List<String> learnings, String capturedAt) {

        public CapturedCase {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(ticketKey, "ticketKey");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(finalStatus, "finalStatus");
            writesThatRan = List.copyOf(writesThatRan == null ? List.of() : writesThatRan);
            comments = List.copyOf(comments == null ? List.of() : comments);
            learnings = List.copyOf(learnings == null ? List.of() : learnings);
        }
    }

    private final Path dir;
    private final WorkbenchStore store;
    private final Alm alm;
    private final Learnings learnings;
    private final String tenantId;

    public EvalCaptures(Path dir, WorkbenchStore store, Alm alm, Learnings learnings,
            String tenantId) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.store = Objects.requireNonNull(store, "store");
        this.alm = Objects.requireNonNull(alm, "alm");
        this.learnings = Objects.requireNonNull(learnings, "learnings");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    }

    /**
     * Captures {@code runId} and writes it to the capture directory.
     *
     * @throws IllegalArgumentException if the run is unknown, still running, or ended in a
     *     way that teaches a replay nothing
     */
    public Path capture(String runId) {
        CapturedCase captured = read(runId);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("case-" + runId + ".json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), captured);
            return file;
        } catch (IOException failed) {
            throw new UncheckedIOException("The captured case could not be written", failed);
        }
    }

    /** The case, without writing it — what {@link #capture} persists. */
    public CapturedCase read(String runId) {
        Run run = store.run(tenantId, runId)
                .orElseThrow(() -> new IllegalArgumentException("No run " + runId + "."));
        if (run.status() == Run.Status.PENDING || run.status() == Run.Status.RUNNING) {
            throw new IllegalArgumentException("Run " + runId + " has not finished; capture "
                    + "it once it has.");
        }
        Optional<Approval> raised = store.approvals(tenantId).stream()
                .filter(approval -> approval.runId().equals(runId))
                .findFirst();
        if (run.status() == Run.Status.FAILED
                || (run.status() == Run.Status.CANCELLED && raised.isEmpty())) {
            throw new IllegalArgumentException("Run " + runId + " ended " + run.status()
                    + " without asking anybody, which teaches a replay nothing it could be "
                    + "held to.");
        }

        Ticket ticket = alm.ticket(run.ticketKey())
                .orElseThrow(() -> new IllegalArgumentException("Ticket " + run.ticketKey()
                        + " is no longer visible to the signed-in identity."));
        Alm.Me me = alm.myself();
        List<CapturedComment> requesterSide = new ArrayList<>();
        for (Ticket.Comment comment : alm.comments(run.ticketKey())) {
            // The integration's own comments are the run's output, not the run's input;
            // a replay seeded with them would be answering a ticket that already answers
            // itself.
            if (comment.author() != null && (comment.author().equals(me.displayName())
                    || comment.author().equals(me.email()))) {
                continue;
            }
            requesterSide.add(new CapturedComment(comment.author(), comment.body()));
        }

        List<String> writes = store.events(runId).stream()
                .filter(event -> event.type() == Run.Event.Type.TOOL_COMPLETED)
                .filter(event -> "RAN".equals(String.valueOf(event.detail().get("disposition"))))
                .map(event -> String.valueOf(event.detail().get("tool")))
                .filter(WRITES::contains)
                .toList();

        return new CapturedCase(runId, run.ticketKey(), run.mode(), run.status(),
                raised.map(approval -> approval.kind().name()).orElse(null),
                raised.map(Approval::toolName).orElse(null),
                writes, ticket.statusCategory().name(),
                ticket.summary(), ticket.description(), ticket.priority(), ticket.reporter(),
                requesterSide, learnings.recall(), Instant.now().toString());
    }

    /** Every captured case under {@code dir}, oldest file first; empty when none exist. */
    public static List<CapturedCase> load(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(file -> {
                        try {
                            return JSON.readValue(file.toFile(), CapturedCase.class);
                        } catch (IOException unreadable) {
                            throw new UncheckedIOException(
                                    "Captured case " + file + " could not be read", unreadable);
                        }
                    })
                    .toList();
        } catch (IOException unlistable) {
            throw new UncheckedIOException("Capture directory " + dir + " could not be listed",
                    unlistable);
        }
    }
}
