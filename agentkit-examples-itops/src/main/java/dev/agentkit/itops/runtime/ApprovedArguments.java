package dev.agentkit.itops.runtime;

import dev.agentkit.core.prompt.Source;
import java.util.Map;

/**
 * The one spelling of "the arguments a human approved", used both to show them to the
 * resumed run and to decide whether what it proposes is the same call (#141).
 *
 * <h2>Why one method and not two</h2>
 *
 * <p>{@code Supervisor.matchesApproved} is exact map equality, and the resumed run's only
 * source for those arguments is the text {@code ExecutionRunner} put in the goal. So the
 * moment rendering became lossy — which fencing made it — the two sides could disagree, and
 * a run that reproduced <em>exactly what it was shown</em> would be refused.
 *
 * <p>Measured before this existed, with a value carrying a fullwidth character:
 *
 * <pre>
 * goal shows fullwidth? false      goal shows folded? true
 * gate on the ORIGINAL approved map: allowed
 * gate on what the goal SHOWED:      DENIED, new approval raised
 * </pre>
 *
 * <p>The human then approves the new one, the resume renders the same folded text, and it
 * parks again. That is a loop with no exit, and it is worse than the injection it came
 * from: an approval that can never be consumed.
 *
 * <p>So both sides ask this class. What is shown is what is compared, by construction.
 *
 * <h2>Where the rendering itself lives</h2>
 *
 * <p>{@link ArgumentText}, since #178 gave it a second reader: the reviewing model's prompt
 * needed the identical treatment and was doing {@code Map.toString()}. The normalise-before-
 * quoting rule and the bounds are documented there. This class is the approval-specific
 * half — which label, and the equality above.
 *
 */
public final class ApprovedArguments {

    /**
     * The one label for this fence, and a framework constant on purpose.
     *
     * <p>A label sits on the marker line, <em>outside</em> the fence, and
     * {@code Spotlight.label} admitted about eighty characters including spaces and colons.
     * Anything external here is a sentence the model reads outside every fence (#178). A
     * {@code Source} is the type that will not hold one (#69).
     */
    private static final Source LABEL = Source.of("approved-arguments");

    private ApprovedArguments() {
    }

    /**
     * The canonical form of {@code arguments} — what the resumed run is shown, and what its
     * proposal is compared against. See {@link ArgumentText#asShown}.
     */
    public static Map<String, Object> asShown(Map<String, Object> arguments) {
        return ArgumentText.asShown(arguments);
    }

    /** Whether every approved argument fits, so the resumed run can reproduce the set. */
    public static boolean fitsInAPrompt(Map<String, Object> arguments) {
        return ArgumentText.fitsInAPrompt(arguments);
    }

    /** The approved arguments, fenced as evidence. */
    public static String fence(Map<String, Object> arguments) {
        return ArgumentText.fence(LABEL, arguments);
    }
}
