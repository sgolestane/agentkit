package dev.agentkit.chat.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.agentkit.chat.Attachment;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Turn;
import dev.agentkit.core.util.Quoted;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The transcript on disk, so a demo that is restarted is not a demo that has forgotten.
 *
 * <p>{@link InMemoryChatStore} answers every read; this subclass writes what changed and
 * loads it back on construction. That is deliberately the cheap design — the whole
 * conversation is in memory anyway, and a console holding a few hundred threads is not a
 * database problem. What it buys is that the two stores cannot disagree about what
 * {@code begin} or {@code end} mean, because there is one implementation of each.
 *
 * <h2>The layout</h2>
 *
 * <pre>
 * &lt;dir&gt;/conversations/&lt;convId&gt;.json    the conversation and all its turns
 * &lt;dir&gt;/attachments/&lt;attId&gt;.json       an attachment's handle
 * &lt;dir&gt;/attachments/&lt;attId&gt;.bin        its bytes
 * </pre>
 *
 * <p>Flat, and keyed only by ids this module minted. The tenant is <em>inside</em> the file
 * rather than in the path, which removes the question of what a hostile tenant id does to a
 * directory tree before it can be asked. Ids arriving from outside are checked by
 * {@link Ids#requireSafe} before they reach a path.
 *
 * <h2>Written whole, moved into place</h2>
 *
 * <p>Every write goes to a temporary file in the same directory and is then moved atomically
 * over the target. A console is killed with Ctrl-C in the middle of a run more often than any
 * other way it stops, and the failure that matters is not a lost turn — it is a half-written
 * one that cannot be parsed, which loses the <em>whole conversation</em> on the next boot.
 *
 * <h2>A file that will not parse is skipped, not fatal</h2>
 *
 * <p>Loading refuses to make one unreadable conversation into a console that will not start.
 * The bad file is logged, by name, and the rest load. This is the same judgement the rest of
 * the framework makes about degradation: say what is missing and keep working.
 */
public final class FileChatStore extends InMemoryChatStore {

    private static final Logger LOG = LoggerFactory.getLogger(FileChatStore.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // Instants as ISO-8601 strings rather than epoch decimals: this file is meant to
            // be readable by a person diagnosing a demo, and 1.7264928E9 is not.
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // The same rule the durable path follows, for a milder version of the same
            // reason: a file written by a newer build must not stop an older one from
            // starting. There it stalls a workflow; here it loses a conversation.
            .disable(com.fasterxml.jackson.databind.DeserializationFeature
                    .FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path conversationsDir;
    private final Path attachmentsDir;

    public FileChatStore(Path directory) {
        this(directory, Clock.systemUTC());
    }

    public FileChatStore(Path directory, Clock clock) {
        super(clock);
        Objects.requireNonNull(directory, "directory");
        this.conversationsDir = directory.resolve("conversations");
        this.attachmentsDir = directory.resolve("attachments");
        try {
            Files.createDirectories(conversationsDir);
            Files.createDirectories(attachmentsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open the chat store at " + directory, e);
        }
        loadEverything();
    }

    /** What one conversation's file holds: the conversation, and every turn of it. */
    private record Stored(Conversation conversation, List<Turn> turns) {}

    // --- loading --------------------------------------------------------------------

    private void loadEverything() {
        try (Stream<Path> files = Files.list(conversationsDir)) {
            files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(this::loadConversation);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + conversationsDir, e);
        }
        try (Stream<Path> files = Files.list(attachmentsDir)) {
            files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(this::loadAttachment);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + attachmentsDir, e);
        }
    }

    private void loadConversation(Path file) {
        try {
            Stored stored = JSON.readValue(Files.readAllBytes(file), Stored.class);
            if (stored.conversation() == null) {
                throw new IOException("no conversation in the file");
            }
            load(stored.conversation(),
                    stored.turns() == null ? List.of() : stored.turns());
        } catch (IOException | RuntimeException unreadable) {
            LOG.warn("Skipping an unreadable conversation file {}; the rest of the transcript "
                    + "loads.", Quoted.of(file.getFileName().toString()),
                    Quoted.failure(unreadable));
        }
    }

    private void loadAttachment(Path file) {
        try {
            Attachment attachment = JSON.readValue(Files.readAllBytes(file), Attachment.class);
            Path bytes = attachmentsDir.resolve(attachment.id() + ".bin");
            load(attachment, Files.exists(bytes) ? Files.readAllBytes(bytes) : new byte[0]);
        } catch (IOException | RuntimeException unreadable) {
            LOG.warn("Skipping an unreadable attachment file {}.",
                    Quoted.of(file.getFileName().toString()), Quoted.failure(unreadable));
        }
    }

    // --- writing --------------------------------------------------------------------

    @Override
    protected void changed(Conversation conversation) {
        List<Turn> conversationTurns = new ArrayList<>(
                turns(conversation.tenantId(), conversation.id()));
        write(conversationFile(conversation.id()),
                new Stored(conversation, conversationTurns));
    }

    @Override
    protected void changed(Attachment attachment, byte[] bytes) {
        write(attachmentsDir.resolve(Ids.requireSafe(attachment.id(), "An attachment id")
                + ".json"), attachment);
        writeBytes(attachmentsDir.resolve(attachment.id() + ".bin"), bytes);
    }

    @Override
    protected void forgotten(String conversationId) {
        delete(conversationFile(conversationId));
        // The attachment files go too. The in-memory half has already dropped them, so this
        // is only the disk catching up — but a "deleted" conversation whose uploads are still
        // on the filesystem is the kind of promise that is discovered in an audit.
        //
        // knowsAttachment, NOT attachment(tenant, id): the owner has just been deleted, so
        // there is no tenant left to ask about, and the tenant-scoped read would answer "not
        // yours" for EVERY file and delete every other conversation's uploads with it.
        try (Stream<Path> files = Files.list(attachmentsDir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                String id = name.endsWith(".json") ? name.substring(0, name.length() - 5)
                        : name.endsWith(".bin") ? name.substring(0, name.length() - 4) : null;
                if (id != null && !knowsAttachment(id)) {
                    delete(file);
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not tidy the attachments of a deleted conversation",
                    Quoted.failure(e));
        }
    }

    /**
     * A tenant that matches nothing, so the sweep above asks "is this id still known?" rather
     * than "does this tenant own it?".
     *
     * <p>{@code attachment} is tenant-scoped on purpose and this is the one caller that wants
     * the unscoped question. Spelled out rather than reached by adding an unscoped method to
     * the interface, which would be a hole in the scoping for every other caller too.
     */
    private String anyTenant() {
        return " no-such-tenant";
    }

    private Path conversationFile(String conversationId) {
        return conversationsDir.resolve(
                Ids.requireSafe(conversationId, "A conversation id") + ".json");
    }

    private void write(Path target, Object value) {
        try {
            Path temporary = Files.createTempFile(target.getParent(), "tmp-", ".json");
            Files.write(temporary, JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(value));
            move(temporary, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + target, e);
        }
    }

    private void writeBytes(Path target, byte[] bytes) {
        try {
            Path temporary = Files.createTempFile(target.getParent(), "tmp-", ".bin");
            Files.write(temporary, bytes);
            move(temporary, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + target, e);
        }
    }

    private static void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException notHere) {
            // Some filesystems cannot promise it. A non-atomic replace is still better than
            // writing in place, because the window in which the target is short is a move
            // rather than the whole serialization.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not delete {}", Quoted.of(String.valueOf(file.getFileName())),
                    Quoted.failure(e));
        }
    }
}
