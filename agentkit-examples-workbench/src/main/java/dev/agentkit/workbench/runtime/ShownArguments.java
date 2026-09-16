package dev.agentkit.workbench.runtime;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.util.Quoted;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one spelling of "the arguments a human approved" — what the resumed run is shown, and
 * what its proposal is compared against.
 *
 * <p>One method decides both, so what is shown and what is accepted cannot drift. Fencing
 * is lossy (NFKC folds fullwidth characters), and the resumed run's only source for the
 * approved arguments is the rendering: comparing the raw map against a proposal
 * reconstructed from the rendered one refuses a run that reproduced exactly what it was
 * told, parks it again, and loops. The itops example measured that loop; this module keeps
 * its repair.
 */
public final class ShownArguments {

    private static final Source LABEL = Source.of("approved-arguments");
    private static final int MAX_SHOWN = 100;
    private static final int MAX_CHARS = 2_000;

    private ShownArguments() {
    }

    /** The canonical form — NFKC over keys and string values, everything else untouched. */
    public static Map<String, Object> asShown(Map<String, Object> arguments) {
        Map<String, Object> shown = new LinkedHashMap<>();
        arguments.forEach((key, value) -> shown.put(normalise(key),
                value instanceof String text ? normalise(text) : value));
        return shown;
    }

    /** Whether every argument fits, so the resumed run can reproduce the set intact. */
    public static boolean fitsInAPrompt(Map<String, Object> arguments) {
        return entries(arguments).size() <= MAX_SHOWN && !render(arguments).cut();
    }

    /** The approved arguments, fenced as evidence for the resume goal. */
    public static String fence(Map<String, Object> arguments) {
        return render(arguments).fence();
    }

    private static Spotlight.Bounded render(Map<String, Object> arguments) {
        return Spotlight.fenceBounded(Spotlight.Kind.EVIDENCE, LABEL,
                Quoted.each(entries(arguments), MAX_SHOWN), MAX_CHARS);
    }

    private static List<String> entries(Map<String, Object> arguments) {
        List<String> rendered = new ArrayList<>();
        asShown(arguments).forEach((key, value) -> rendered.add(key + "=" + value));
        return rendered;
    }

    private static String normalise(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC);
    }
}
