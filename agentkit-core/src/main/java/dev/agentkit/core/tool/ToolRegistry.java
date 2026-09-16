package dev.agentkit.core.tool;

import java.util.List;
import java.util.Optional;

/**
 * A collection of tools available to an agent, plus the policy for which tool
 * specifications are advertised to the model on a given turn.
 *
 * <p>In Phase 1 the policy is trivial: every registered tool is advertised. Later
 * phases add <em>progressive disclosure</em> — deferring most tools and revealing
 * them on demand via search — behind this same interface, so the agent loop is
 * unaffected.
 */
public interface ToolRegistry {

    /** Looks up a tool by name. */
    Optional<Tool> find(String name);

    /** All registered tools. */
    List<Tool> tools();

    /**
     * The tool specifications to advertise to the model for the current turn.
     * A simple registry returns every tool's spec; a disclosing registry returns
     * only the currently revealed subset.
     */
    List<ToolSpec> advertisedSpecs();
}
