package dev.agentkit.core.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SkillParserTest {

    @Test
    void parsesFrontmatterAndBody() {
        String md = """
                ---
                name: pdf-forms
                description: Fill out PDF forms accurately
                ---
                # Filling forms

                Do the thing.
                """;
        SkillParser.Parsed parsed = SkillParser.parse(md);
        assertThat(parsed.name()).isEqualTo("pdf-forms");
        assertThat(parsed.description()).isEqualTo("Fill out PDF forms accurately");
        assertThat(parsed.body()).startsWith("# Filling forms").contains("Do the thing.");
    }

    @Test
    void stripsQuotesFromValues() {
        String md = """
                ---
                name: "my skill"
                description: 'a quoted description'
                ---
                body
                """;
        SkillParser.Parsed parsed = SkillParser.parse(md);
        assertThat(parsed.name()).isEqualTo("my skill");
        assertThat(parsed.description()).isEqualTo("a quoted description");
    }

    @Test
    void handlesCrlfLineEndings() {
        String md = "---\r\nname: x\r\ndescription: y\r\n---\r\nbody\r\n";
        SkillParser.Parsed parsed = SkillParser.parse(md);
        assertThat(parsed.name()).isEqualTo("x");
        assertThat(parsed.body()).isEqualTo("body");
    }

    @Test
    void missingNameIsRejected() {
        assertThatThrownBy(() -> SkillParser.parse("---\ndescription: y\n---\nbody"))
                .isInstanceOf(SkillFormatException.class);
    }

    @Test
    void missingDescriptionIsRejected() {
        assertThatThrownBy(() -> SkillParser.parse("---\nname: x\n---\nbody"))
                .isInstanceOf(SkillFormatException.class);
    }

    @Test
    void missingFrontmatterIsRejected() {
        assertThatThrownBy(() -> SkillParser.parse("# just markdown\n"))
                .isInstanceOf(SkillFormatException.class);
    }

    @Test
    void unclosedFrontmatterIsRejected() {
        assertThatThrownBy(() -> SkillParser.parse("---\nname: x\ndescription: y\nbody"))
                .isInstanceOf(SkillFormatException.class);
    }

    @Test
    void duplicateKeyIsRejected() {
        assertThatThrownBy(() -> SkillParser.parse("---\nname: a\nname: b\ndescription: d\n---\nbody"))
                .isInstanceOf(SkillFormatException.class);
    }

    @Test
    void leadingBomIsStripped() {
        SkillParser.Parsed parsed = SkillParser.parse("﻿---\nname: x\ndescription: y\n---\nbody");
        assertThat(parsed.name()).isEqualTo("x");
    }

    @Test
    void bodyLineEqualToDelimiterIsNotConsumed() {
        SkillParser.Parsed parsed = SkillParser.parse("---\nname: x\ndescription: y\n---\nintro\n---\nmore");
        assertThat(parsed.body()).contains("intro").contains("---").contains("more");
    }

    @Test
    void toSkillProducesInMemorySkill() {
        Skill skill = SkillParser.toSkill("---\nname: s\ndescription: d\n---\nhello");
        assertThat(skill.name()).isEqualTo("s");
        assertThat(skill.instructions()).isEqualTo("hello");
        assertThat(skill.hasResources()).isFalse();
        assertThat(skill.directory()).isEmpty();
    }
}
