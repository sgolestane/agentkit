package dev.agentkit.host.repo;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Everything wrong with a repository or an agent, all at once: an operator fixing a pull request wants the whole
 * list, not the first line of it.
 */
public final class DefinitionException extends RuntimeException {

    /**
     * One thing wrong.
     *
     * @param file  the file it is in, relative to the repository
     * @param where the field, dotted ({@code tools[1].effects}), or empty for the file as a whole
     */
    public record Problem(String file, String where, String message) {
        public Problem {
            Objects.requireNonNull(file, "file");
            where = where == null ? "" : where;
            Objects.requireNonNull(message, "message");
        }

        @Override
        public String toString() {
            return file + (where.isEmpty() ? "" : " " + where) + ": " + message;
        }
    }

    private final List<Problem> problems;

    public DefinitionException(List<Problem> problems) {
        super(summary(problems));
        this.problems = List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }

    private static String summary(List<Problem> problems) {
        return problems.size() + " problem" + (problems.size() == 1 ? "" : "s") + ":\n"
                + problems.stream().map(p -> "  - " + p).collect(Collectors.joining("\n"));
    }
}
