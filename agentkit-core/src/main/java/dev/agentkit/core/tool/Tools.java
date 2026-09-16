package dev.agentkit.core.tool;

import java.util.Objects;

/** Decorators for tools you did not build. */
public final class Tools {

    private Tools() {
    }

    /**
     * The same tool, declaring {@code effects}.
     *
     * <p>{@link SideEffects} is settable on {@link FunctionTool.Builder} and otherwise only
     * by overriding {@link Tool#sideEffects()} — which you cannot do for a tool that arrives
     * from somewhere else. An MCP server's tools are the case that matters: they are
     * {@link SideEffects#UNKNOWN}, so a rehearsal refuses all of them, and without this the
     * only way out is to write a forwarding {@code Tool} yourself.
     *
     * <p>That last sentence read "hand-write a forwarding {@code Tool}" until #296, and the
     * word was doing damage: a hand-written forwarder takes the interface default for the
     * six methods the compiler does not ask for, two of which fail open at a durable
     * worker's registration check. {@link ForwardingTool} is now the base to extend for the
     * cases this method does not cover — it is what this decorator is built on.
     *
     * <p>You are vouching for the tool when you call this. Nothing verifies the claim, and
     * declaring a writer {@link SideEffects#NONE} makes a rehearsal run it.
     *
     * <pre>{@code
     * ToolRegistry rehearsable = new SimpleToolRegistry(mcp.tools().stream()
     *         .map(tool -> tool.name().startsWith("get_")
     *                 ? Tools.withSideEffects(tool, SideEffects.NONE)
     *                 : tool)
     *         .toList());
     * }</pre>
     */
    public static Tool withSideEffects(Tool tool, SideEffects effects) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(effects, "effects");
        return new Declared(tool, effects);
    }

    /**
     * {@code tool} with {@code provenance} declared, for a tool you did not author.
     *
     * <p>The sibling of {@link #withSideEffects}, and it exists for the same reason: you
     * cannot override a method on a tool that arrives from somewhere else. Without it the
     * one thing {@link Provenance#UNKNOWN} is for — telling you which tools still need
     * declaring — could never be worked down for exactly the population that needs it.
     */
    public static Tool withProvenance(Tool tool, Provenance provenance) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(provenance, "provenance");
        return new Attributed(tool, provenance);
    }

    /**
     * Forwards everything, overriding only the declaration.
     *
     * <p>{@link ForwardingTool} carries the forwarding, which was nine hand-written methods
     * here before #296 — including {@code spec()}, which must not be left to the interface
     * default, and the two gate declarations, whose default is {@code false} and so fails
     * open at a durable worker's registration check.
     */
    private static final class Declared extends ForwardingTool {

        private final Tool delegate;
        private final SideEffects effects;

        Declared(Tool delegate, SideEffects effects) {
            this.delegate = delegate;
            this.effects = effects;
        }

        @Override
        protected Tool delegate() {
            return delegate;
        }

        @Override
        public SideEffects sideEffects() {
            return effects;
        }

        @Override
        protected Tool rebuiltAround(Tool bound) {
            // The declaration is this decorator's whole reason to exist, so it has to survive
            // the rebuild: returning `bound` would hand the runner the tool without it (#317).
            return new Declared(bound, effects);
        }

        @Override
        public String toString() {
            return "Tools.withSideEffects[" + delegate + ", " + effects + "]";
        }
    }

    /** The sibling of {@link Declared}, overriding {@link Tool#provenance()} instead. */
    private static final class Attributed extends ForwardingTool {

        private final Tool delegate;
        private final Provenance declared;

        Attributed(Tool delegate, Provenance declared) {
            this.delegate = delegate;
            this.declared = declared;
        }

        @Override
        protected Tool delegate() {
            return delegate;
        }

        @Override
        public Provenance provenance() {
            return declared;
        }

        @Override
        protected Tool rebuiltAround(Tool bound) {
            return new Attributed(bound, declared);
        }

        @Override
        public String toString() {
            return "Tools.withProvenance[" + delegate + ", " + declared + "]";
        }
    }
}
