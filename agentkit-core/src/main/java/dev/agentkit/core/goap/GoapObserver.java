package dev.agentkit.core.goap;

/**
 * Watches a {@link GoapRunner} run as it happens.
 *
 * <p>The seam a GOAP run otherwise has no substitute for. Because the order is computed
 * rather than written down, a run is opaque until it finishes — so this is where per-action
 * logging, a progress display, and a per-action OpenTelemetry span go. Wrapping each
 * handler would give you the last of those and none of the first two, since a handler
 * cannot see the plan it was chosen from.
 *
 * <p>Every method is a no-op by default; implement only what you need.
 *
 * <h2>What happens if you throw (#173)</h2>
 *
 * <p><strong>Nothing, to the run.</strong> Every callback below is dispatched through a
 * guard: a throw — including an {@code Error} — is absorbed, the run carries on, and the
 * caller still gets a {@link GoapResult}. It is then handed to the deployment's
 * {@code GoapRunner.Builder.onObservationFailure} handler with the callback's name, which
 * defaults to a log line.
 *
 * <p>It said the opposite until #173, and said it deliberately: "an observer that throws
 * takes the run down with it — this is a reporting seam, not a policy one". The second half
 * is the premise for the opposite conclusion. A reporting seam that can abort the thing it
 * reports on is not a reporting seam, and {@link #onActionEnd} fires <em>after</em> the
 * action has run. Measured with an observer that threw there:
 *
 * <pre>
 * action actually ran : true
 * GoapResult returned : none — the exception escaped run()
 * onFinish called     : false
 * </pre>
 *
 * <p>So the action's side effect happened, every fact the trace had established was lost,
 * and an observer keeping per-run state was left holding a run it would never see the end
 * of — one line away from the {@code catch (RuntimeException | Error)} the runner puts
 * around the action itself for exactly that reason.
 *
 * <p>Two consequences worth knowing before writing one:
 *
 * <ul>
 *   <li><strong>This is not a policy seam.</strong> An observer cannot refuse anything, and
 *       one written as though it could now fails without stopping the step. A gate that
 *       wants to refuse belongs inside the action's handler, where it can return a failure
 *       the planner will route around.</li>
 *   <li><strong>Your failures are yours to notice.</strong> If the record this observer
 *       keeps is a control rather than a convenience, set
 *       {@code onObservationFailure} — the framework cannot know whether a dropped
 *       {@code onPlan} matters and a dropped {@code onActionEnd} does.</li>
 * </ul>
 *
 * <p>{@code AgentObserver} answers this question the same way, in the same words, and that
 * is the point of #173: two observer seams in one library gave opposite answers and nothing
 * told a reader which one they were holding except reading both.
 */
public interface GoapObserver {

    /** An observer that does nothing. */
    GoapObserver NONE = new GoapObserver() { };

    /**
     * A plan has been computed and its first action is about to run.
     *
     * <p>Called before every action, not once per run — the whole point is that the plan is
     * recomputed each time. A plan that changes between calls is the interesting signal.
     */
    default void onPlan(ActionPlan plan, WorldState state) {
    }

    /** An action is about to run. */
    default void onActionStart(Action action, WorldState state) {
    }

    /** An action has finished, successfully or not. */
    default void onActionEnd(Action action, ActionOutcome outcome) {
    }

    /** The run has ended, for whatever reason. */
    default void onFinish(GoapResult result) {
    }
}
