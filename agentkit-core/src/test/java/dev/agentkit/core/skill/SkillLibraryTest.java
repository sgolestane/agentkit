package dev.agentkit.core.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SkillLibraryTest {

    @Test
    void catalogListsNamesAndDescriptions() {
        SkillLibrary library = new SkillLibrary()
                .add(Skill.of("a", "does A", "instructions A"))
                .add(Skill.of("b", "does B", "instructions B"));

        String catalog = library.catalog();
        assertThat(catalog).contains("a: does A").contains("b: does B").contains("read_skill");
    }

    @Test
    void emptyCatalogIsBlank() {
        assertThat(new SkillLibrary().catalog()).isEmpty();
        assertThat(new SkillLibrary().isEmpty()).isTrue();
    }

    @Test
    void duplicateNameRejected() {
        SkillLibrary library = new SkillLibrary().add(Skill.of("a", "d", "i"));
        assertThatThrownBy(() -> library.add(Skill.of("a", "d2", "i2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findReturnsSkill() {
        SkillLibrary library = new SkillLibrary().add(Skill.of("a", "d", "i"));
        assertThat(library.find("a")).isPresent();
        assertThat(library.find("missing")).isEmpty();
    }
}
