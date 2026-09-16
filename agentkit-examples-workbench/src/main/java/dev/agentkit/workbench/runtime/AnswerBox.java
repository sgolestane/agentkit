package dev.agentkit.workbench.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The answers a human has already given, carried into a resumed run for {@code ask_human}
 * to hand back.
 *
 * <p>The mechanics of resuming a question: there is no in-process resume in the framework,
 * so answering re-runs the ticket, and the re-run's {@code ask_human} must return the
 * answer instead of parking again. The gate consults {@link #hasRemaining()}; the tool
 * handler consumes answers in order, each exactly once. The resumed goal also carries the
 * question-and-answer fenced, so the model usually never re-asks at all — this box is the
 * backstop that makes re-asking cheap instead of a second park.
 */
public final class AnswerBox {

    /** One answered question. */
    public record Answer(String question, String answer) {
        public Answer {
            Objects.requireNonNull(question, "question");
            Objects.requireNonNull(answer, "answer");
        }
    }

    public static final AnswerBox EMPTY = new AnswerBox(List.of());

    private final List<Answer> answers;
    private final AtomicInteger consumed = new AtomicInteger();

    public AnswerBox(List<Answer> answers) {
        this.answers = new CopyOnWriteArrayList<>(answers);
    }

    public boolean hasRemaining() {
        return consumed.get() < answers.size();
    }

    /** The next unconsumed answer, or {@code null} when none remain. */
    public Answer consume() {
        int index = consumed.getAndIncrement();
        return index < answers.size() ? answers.get(index) : null;
    }

    public List<Answer> all() {
        return List.copyOf(answers);
    }
}
