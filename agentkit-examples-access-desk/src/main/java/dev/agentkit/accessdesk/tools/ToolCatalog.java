package dev.agentkit.accessdesk.tools;

import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Tools together with what each declares about itself. Registries are cut from a catalog by
 * declaration, so which tools a caller gets is a rule over {@link ToolInfo}, not a list of names.
 */
public final class ToolCatalog {

    /** A tool and its declaration. */
    public record Entry(Tool tool, ToolInfo info) {
        public Entry {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(info, "info");
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public ToolCatalog() {
    }

    public ToolCatalog(Collection<Entry> entries) {
        entries.forEach(this::add);
    }

    /** Adds a tool; a second tool with the same name is refused rather than silently shadowing the first. */
    public synchronized ToolCatalog add(Tool tool, ToolInfo info) {
        return add(new Entry(tool, info));
    }

    public synchronized ToolCatalog add(Entry entry) {
        if (entries.putIfAbsent(entry.tool().name(), entry) != null) {
            throw new IllegalArgumentException("Two tools are named " + entry.tool().name());
        }
        return this;
    }

    /** Every entry of {@code other}, added to this catalog. */
    public synchronized ToolCatalog addAll(ToolCatalog other) {
        other.entries().forEach(this::add);
        return this;
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public synchronized Optional<Entry> entry(String toolName) {
        return Optional.ofNullable(entries.get(toolName));
    }

    /** What the named tool declares, if it is in this catalog. */
    public Optional<ToolInfo> info(String toolName) {
        return entry(toolName).map(Entry::info);
    }

    /** Only the entries whose declaration matches. */
    public ToolCatalog where(Predicate<ToolInfo> declared) {
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : entries()) {
            if (declared.test(entry.info())) {
                kept.add(entry);
            }
        }
        return new ToolCatalog(kept);
    }

    /** A registry of every tool in this catalog. */
    public ToolRegistry registry() {
        return new SimpleToolRegistry(entries().stream().map(Entry::tool).toList());
    }
}
