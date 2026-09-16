package dev.agentkit.core.memory;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.Quoted;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tools that expose {@link MemoryStore durable memory} and
 * {@link WorkingMemory in-session notes} to the agent.
 */
public final class MemoryTools {

    public static final String MEMORY = "memory";
    public static final String REMEMBER = "remember";
    public static final String RECALL = "recall";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MemoryTools.class);

    private MemoryTools() {
    }

    /**
     * A single {@code memory} tool over a durable {@link MemoryStore}, supporting
     * the commands {@code read}, {@code write}, {@code append}, {@code delete}, and
     * {@code list}.
     */
    public static Tool memoryTool(MemoryStore store) {
        Objects.requireNonNull(store, "store");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string",
                                "enum", List.of("read", "write", "append", "delete", "list"),
                                "description", "The memory operation to perform"),
                        "path", Map.of("type", "string",
                                "description", "Memory key, e.g. 'facts/user.md' (a prefix for 'list')"),
                        "content", Map.of("type", "string",
                                "description", "Text to store (for 'write' and 'append')")),
                "required", List.of("command"));
        return FunctionTool.builder(MEMORY,
                        "Durable memory that persists across sessions. Commands: read/write/append/"
                                + "delete a key, or list keys under a path prefix. Read prior knowledge, "
                                + "and write facts you'll need in future runs. Never store secrets.")
                .schema(schema)
                // A durable root outlives the run and is shared across them, so what comes
                // back was written by an earlier run — or by whatever else has the
                // directory. The model wrote most of it, which is not the same as ours.
                .provenance(Provenance.THIRD_PARTY)
                .handler(inv -> dispatch(store, inv))
                .build();
    }

    /** A {@code remember} tool that appends a note to {@code workingMemory}. */
    public static Tool rememberTool(WorkingMemory workingMemory) {
        Objects.requireNonNull(workingMemory, "workingMemory");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("note", Map.of("type", "string",
                        "description", "A short fact or decision to keep for the rest of this task")),
                "required", List.of("note"));
        return FunctionTool.builder(REMEMBER,
                        "Record a short note in working memory for the rest of this task.")
                .schema(schema)
                // "Noted." — the framework's own word, and the only thing this returns.
                .provenance(Provenance.FIRST_PARTY)
                .handler(inv -> {
                    String note = inv.stringArgument("note");
                    if (note == null || note.isBlank()) {
                        return ToolResult.error("The 'note' argument is required.");
                    }
                    workingMemory.note(note);
                    return ToolResult.ok("Noted.");
                })
                .build();
    }

    /** A {@code recall} tool that returns the current working-memory notes. */
    public static Tool recallTool(WorkingMemory workingMemory) {
        Objects.requireNonNull(workingMemory, "workingMemory");
        return FunctionTool.builder(RECALL, "List the notes recorded in working memory for this task.")
                .readOnly()
                // The model's own earlier notes, which is not the deployment's words either.
                .provenance(Provenance.THIRD_PARTY)
                // renderAll, not render: this is the tool the prompt's own bound points at
                // when it says it is showing only the tail, so bounding it too would leave
                // the model with nowhere to go. A tool result is paid once.
                .handler(inv -> workingMemory.isEmpty()
                        ? ToolResult.ok("No notes yet.")
                        : ToolResult.ok(workingMemory.renderAll()))
                .build();
    }

    private static ToolResult dispatch(MemoryStore store, ToolInvocation inv) {
        String command = inv.stringArgument("command");
        if (command == null) {
            return ToolResult.error("The 'command' argument is required.");
        }
        String path = inv.stringArgument("path");
        String content = inv.stringArgument("content");
        try {
            return switch (command.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "read" -> requirePath(path, p -> store.read(p)
                        .map(ToolResult::ok)
                        .orElseGet(() -> ToolResult.error("No memory at '" + p + "'.")));
                case "write" -> requirePathAndContent(path, content, (p, c) -> {
                    store.write(p, c);
                    // The framework's own confirmation. This tool is a five-command
                    // multiplexer, so the declaration on it is the read path's answer and
                    // the four that report rather than return say so themselves — the case
                    // ToolResult.from was added for, in the framework's own tools.
                    return ToolResult.from(Provenance.FIRST_PARTY,
                            "Wrote memory '" + MemoryKeys.normalize(p) + "'.");
                });
                case "append" -> requirePathAndContent(path, content, (p, c) -> {
                    store.append(p, c);
                    return ToolResult.from(Provenance.FIRST_PARTY,
                            "Appended to memory '" + MemoryKeys.normalize(p) + "'.");
                });
                // Named by the key the store used, not by the argument as typed: 'Deleted
                // 'x/../y.md'.' names a key no listing will ever show, about a document
                // that is not the one destroyed. A confirmation that misreports which
                // memory went is worse than no confirmation.
                case "delete" -> requirePath(path, p -> {
                    String key = MemoryKeys.normalize(p);
                    return ToolResult.from(Provenance.FIRST_PARTY, store.delete(p)
                            ? "Deleted '" + key + "'." : "Nothing to delete at '" + key + "'.");
                });
                case "list" -> {
                    List<String> keys = store.list(path == null ? "" : path);
                    // Fenced as a catalog, the way SkillLibrary fences its own: these are
                    // names a previous run chose, coming back as something to pick from
                    // rather than something to act on. MemoryKeys refuses a key that could
                    // end a line here, so this is the second of the two — the fence says
                    // what the span is for, the key rule keeps it one span.
                    yield ToolResult.ok(keys.isEmpty() ? "Memory is empty."
                            : Spotlight.wrap(Spotlight.Kind.CATALOG, Source.of("memory-keys"),
                                    String.join("\n", keys)));
                }
                default -> ToolResult.error("Unknown command '" + command + "'.");
            };
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("Memory command '{}' failed", Quoted.of(command), Quoted.failure(e));
            return ToolResult.error("Memory operation failed.");
        }
    }

    private interface PathFn {
        ToolResult apply(String path);
    }

    private interface PathContentFn {
        ToolResult apply(String path, String content);
    }

    private static ToolResult requirePath(String path, PathFn fn) {
        if (path == null || path.isBlank()) {
            return ToolResult.error("The 'path' argument is required for this command.");
        }
        return fn.apply(path);
    }

    private static ToolResult requirePathAndContent(String path, String content, PathContentFn fn) {
        if (path == null || path.isBlank()) {
            return ToolResult.error("The 'path' argument is required for this command.");
        }
        if (content == null) {
            return ToolResult.error("The 'content' argument is required for this command.");
        }
        return fn.apply(path, content);
    }
}
