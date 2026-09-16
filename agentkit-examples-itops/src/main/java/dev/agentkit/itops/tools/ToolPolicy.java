package dev.agentkit.itops.tools;

import dev.agentkit.itops.domain.Risk;
import java.util.Objects;

/**
 * What the platform knows about a tool before anyone calls it.
 *
 * <p>Held next to the tool rather than inside it, because these are facts the
 * <em>supervisor</em> needs and the tool's own handler has no use for. A tool that carried
 * its own risk rating would also be a tool that could be asked to lower it.
 *
 * @param name        the registered tool name
 * @param capability  the family it belongs to, e.g. {@code identity.group_membership.write};
 *                    this is what progressive disclosure searches on
 * @param connector   which external system it reaches
 * @param baselineRisk the risk before any argument is considered; the supervisor may raise
 *                    it and may never lower it
 * @param reversible  whether the effect can be undone afterwards
 * @param idempotent  whether repeating the identical call is harmless, which is what makes
 *                    crash recovery safe
 */
public record ToolPolicy(String name, String capability, String connector, Risk baselineRisk,
                         boolean reversible, boolean idempotent) {

    public ToolPolicy {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(connector, "connector");
        Objects.requireNonNull(baselineRisk, "baselineRisk");
    }

    /** A read-only tool: no effect, therefore trivially reversible and idempotent. */
    public static ToolPolicy read(String name, String capability, String connector) {
        return new ToolPolicy(name, capability, connector, Risk.READ, true, true);
    }

    /** A tool that changes something. */
    public static ToolPolicy write(String name, String capability, String connector, Risk risk,
            boolean reversible, boolean idempotent) {
        return new ToolPolicy(name, capability, connector, risk, reversible, idempotent);
    }
}
