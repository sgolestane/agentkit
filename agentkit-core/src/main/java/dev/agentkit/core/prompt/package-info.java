/**
 * Marking untrusted text so a model can tell it apart from its instructions.
 *
 * <p>{@link dev.agentkit.core.prompt.Spotlight} is the whole package: fence untrusted
 * spans with {@code wrap}, and make sure the prompt that receives them carries
 * {@code INSTRUCTION} explaining what the fence means. AgentKit does this for the prompts
 * it assembles, with four exceptions:
 *
 * <ul>
 *   <li>Ordinary tool and MCP <strong>results</strong> reach the transcript verbatim,
 *       because a {@code ToolResult} carries nothing to fence <em>by</em> — so a tool you
 *       write returning content someone else authored has to fence it in the handler.</li>
 *   <li>Tool and MCP <strong>descriptions</strong> reach the model as the request's
 *       {@code tools} field. That is structure the provider parses rather than a span in a
 *       prompt, so nothing here can reach it; it is the shortest path a hostile server
 *       has to the model.</li>
 *   <li>The fence's own {@code source} label sits on the marker line rather than between
 *       the markers — see
 *       {@link dev.agentkit.core.prompt.Spotlight#wrap(dev.agentkit.core.prompt.Source, java.lang.String) wrap}.</li>
 * </ul>
 *
 * <pre>{@code
 * return ToolResult.ok(Spotlight.wrap(Source.of("wiki", pageId), page.body()));
 * }</pre>
 *
 * <p>This is a <em>probabilistic</em> control — it lowers the odds a model follows an
 * injected instruction; it does not bound what a persuaded run can do. Only the fence's
 * unforgeability is deterministic. Gating which tools a turn can reach is the layer that
 * bounds the damage, and nothing here replaces it.
 */
package dev.agentkit.core.prompt;
