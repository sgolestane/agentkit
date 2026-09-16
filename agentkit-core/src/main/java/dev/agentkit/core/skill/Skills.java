package dev.agentkit.core.skill;

import dev.agentkit.core.tool.DisclosingToolRegistry;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import java.util.Objects;

/**
 * Convenience wiring for the skills subsystem.
 *
 * <p>Skills only work when <em>both</em> halves are wired: the tier-1
 * {@link SkillLibrary#catalog() catalog} in the system prompt, and the
 * {@link SkillTools} tools registered so the model can act on it. These helpers
 * do both from one call so the two cannot drift apart.
 *
 * <p>The skill tools must be <strong>always-available</strong> — the catalog
 * instructs the model to call {@code read_skill}, so it must be visible from the
 * first turn (not hidden behind tool search).
 */
public final class Skills {

    private Skills() {
    }

    /**
     * Combines a base system prompt with the library's tier-1 catalog. If the
     * library is empty the base prompt is returned unchanged.
     */
    public static String systemPrompt(String basePrompt, SkillLibrary library) {
        Objects.requireNonNull(library, "library");
        String catalog = library.catalog();
        if (catalog.isEmpty()) {
            return basePrompt == null ? "" : basePrompt;
        }
        if (basePrompt == null || basePrompt.isBlank()) {
            return catalog;
        }
        return basePrompt + "\n\n" + catalog;
    }

    /** Registers the skill tools into a {@link SimpleToolRegistry}. */
    public static SimpleToolRegistry registerInto(SimpleToolRegistry registry, SkillLibrary library) {
        Objects.requireNonNull(registry, "registry");
        for (Tool tool : SkillTools.forLibrary(library)) {
            registry.register(tool);
        }
        return registry;
    }

    /** Adds the skill tools as always-available tools on a disclosing registry builder. */
    public static DisclosingToolRegistry.Builder registerAlwaysAvailable(
            DisclosingToolRegistry.Builder builder, SkillLibrary library) {
        Objects.requireNonNull(builder, "builder");
        for (Tool tool : SkillTools.forLibrary(library)) {
            builder.alwaysAvailable(tool);
        }
        return builder;
    }
}
