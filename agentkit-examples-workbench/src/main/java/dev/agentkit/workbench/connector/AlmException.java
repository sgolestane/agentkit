package dev.agentkit.workbench.connector;

/**
 * A provider call that did not do what was asked, normalised so tool handlers can report it
 * to the model without leaking transport details.
 *
 * <p>{@code notFound()} is the one distinction handlers branch on — "no such ticket" is an
 * answer the model can act on, everything else is a fault to surface.
 */
public class AlmException extends RuntimeException {

    private final int status;

    public AlmException(String message, int status) {
        super(message);
        this.status = status;
    }

    public AlmException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int status() {
        return status;
    }

    public boolean notFound() {
        return status == 404;
    }
}
