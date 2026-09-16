package dev.agentkit.core.skill;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.util.OneLine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A collection of {@link Skill skills} and the source of tier-1 disclosure.
 *
 * <p>The {@link #catalog()} — each skill's name and description — is small enough
 * to sit in the system prompt at all times, letting the model decide which skill
 * to load. Full instructions and bundled resources are fetched on demand through
 * the tools produced by {@link SkillTools}.
 */
public final class SkillLibrary {

    private final Map<String, Skill> byName = new LinkedHashMap<>();

    public SkillLibrary() {
    }

    public SkillLibrary(Collection<Skill> skills) {
        Objects.requireNonNull(skills, "skills");
        skills.forEach(this::add);
    }

    /**
     * Adds a skill.
     *
     * @throws IllegalArgumentException if a skill with the same name already exists
     */
    public SkillLibrary add(Skill skill) {
        Objects.requireNonNull(skill, "skill");
        if (byName.containsKey(skill.name())) {
            throw new IllegalArgumentException("A skill named '" + skill.name() + "' already exists");
        }
        byName.put(skill.name(), skill);
        return this;
    }

    public Optional<Skill> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<Skill> skills() {
        return List.copyOf(byName.values());
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * Renders the tier-1 catalog for inclusion in a system prompt, referencing
     * the default {@link SkillTools#READ_SKILL} tool name.
     */
    public String catalog() {
        return catalog(SkillTools.READ_SKILL);
    }

    /**
     * Renders the tier-1 catalog for inclusion in a system prompt: one line per
     * skill with its name and description, plus a hint to load full instructions
     * via {@code readSkillToolName}. Returns an empty string when the library is
     * empty.
     */
    public String catalog(String readSkillToolName) {
        Objects.requireNonNull(readSkillToolName, "readSkillToolName");
        if (byName.isEmpty()) {
            return "";
        }
        List<String> entries = new ArrayList<>();
        for (Skill skill : byName.values()) {
            entries.add("- " + OneLine.of(skill.name()) + ": " + OneLine.of(skill.description()));
        }
        // The header is ours and stays outside: fencing it would tell the model to
        // disregard our own instruction to call the tool. The names and descriptions are
        // the skill author's, and a third-party bundle is the case this guards.
        return "Available skills (call the '" + readSkillToolName + "' tool with the skill name "
                + "to load its full instructions before using it):\n"
                + Spotlight.wrap(Spotlight.Kind.CATALOG, Source.of("skill-catalog"), String.join("\n", entries));
    }
}
