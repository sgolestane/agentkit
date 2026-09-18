package dev.agentkit.host;

/** The test helpdesk connector, for tests in other packages. */
public final class HelpdeskConnectorAccess implements AutoCloseable {

    private final HelpdeskConnector helpdesk;

    public HelpdeskConnectorAccess() throws java.io.IOException {
        this.helpdesk = new HelpdeskConnector();
    }

    public String url() {
        return helpdesk.url();
    }

    public String token() {
        return HelpdeskConnector.TOKEN;
    }

    @Override
    public void close() {
        helpdesk.close();
    }
}
