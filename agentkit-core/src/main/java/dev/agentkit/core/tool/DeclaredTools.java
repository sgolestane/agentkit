package dev.agentkit.core.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Tools together with what each declares about itself ({@link ToolDeclaration}). Registries are cut from it by
 * declaration, so which tools a caller gets is a rule over declarations, not a list of names.
 *
 * <p>Two tools with one name are refused rather than letting the second shadow the first: a rule that allowed
 * "the read called {@code lookup}" must not end up handing out a different {@code lookup}.
 */
public final class DeclaredTools {

    /** A tool and its declaration. */
    public record Entry(Tool tool, ToolDeclaration declaration) {
        public Entry {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(declaration, "declaration");
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public DeclaredTools() {
    }

    public DeclaredTools(Collection<Entry> entries) {
        entries.forEach(this::add);
    }

    /** Adds a tool with its declaration. */
    public DeclaredTools add(Tool tool, ToolDeclaration declaration) {
        return add(new Entry(tool, declaration));
    }

    /** Adds an entry. */
    public synchronized DeclaredTools add(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entries.putIfAbsent(entry.tool().name(), entry) != null) {
            throw new IllegalArgumentException("Two tools are named " + entry.tool().name());
        }
        return this;
    }

    /** Every entry of {@code other}, added to these. */
    public DeclaredTools addAll(DeclaredTools other) {
        other.entries().forEach(this::add);
        return this;
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public synchronized Optional<Entry> entry(String toolName) {
        return Optional.ofNullable(toolName == null ? null : entries.get(toolName));
    }

    /** What the named tool declares, if it is one of these. */
    public Optional<ToolDeclaration> declaration(String toolName) {
        return entry(toolName).map(Entry::declaration);
    }

    /** Only the entries whose declaration matches. */
    public DeclaredTools where(Predicate<ToolDeclaration> declared) {
        Objects.requireNonNull(declared, "declared");
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : entries()) {
            if (declared.test(entry.declaration())) {
                kept.add(entry);
            }
        }
        return new DeclaredTools(kept);
    }

    /** A registry of every tool here. */
    public ToolRegistry registry() {
        return new SimpleToolRegistry(entries().stream().map(Entry::tool).toList());
    }
}
