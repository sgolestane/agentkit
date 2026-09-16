package dev.agentkit.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class JsonRpcPeerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonRpcPeer peer(String scriptedResponses, StringWriter out) {
        return new JsonRpcPeer(new BufferedReader(new StringReader(scriptedResponses)), out, MAPPER);
    }

    @Test
    void requestWritesTheEnvelopeAndReturnsTheMatchingResult() throws Exception {
        StringWriter out = new StringWriter();
        JsonRpcPeer peer = peer("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n", out);

        ObjectNode params = MAPPER.createObjectNode();
        params.put("q", "hi");
        JsonNode result = peer.request("ping", params);

        assertThat(result.path("ok").asBoolean()).isTrue();
        JsonNode sent = MAPPER.readTree(out.toString().trim());
        assertThat(sent.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(sent.path("id").asLong()).isEqualTo(1);
        assertThat(sent.path("method").asText()).isEqualTo("ping");
        assertThat(sent.path("params").path("q").asText()).isEqualTo("hi");
    }

    @Test
    void requestSkipsNotificationsAndUnrelatedResponses() {
        // A server log notification (no id) and a stale response (id 99) precede ours.
        String script = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"method\":\"log\",\"params\":{\"m\":\"warming up\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{\"stale\":true}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"value\":42}}") + "\n";

        JsonNode result = peer(script, new StringWriter()).request("compute", null);

        assertThat(result.path("value").asInt()).isEqualTo(42);
    }

    @Test
    void aServerRequestWhoseIdCollidesWithOursIsSkipped() {
        // A server-initiated request (has "method") carrying id 1 must NOT be mistaken
        // for our response, even though its id matches our outstanding request.
        String script = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"value\":7}}") + "\n";

        JsonNode result = peer(script, new StringWriter()).request("compute", null);

        assertThat(result.path("value").asInt()).isEqualTo(7);
    }

    @Test
    void anErrorResponseBecomesAnMcpException() {
        String script = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}\n";

        assertThatThrownBy(() -> peer(script, new StringWriter()).request("nope", null))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("-32601")
                .hasMessageContaining("Method not found");
    }

    @Test
    void aClosedConnectionBecomesAnMcpException() {
        assertThatThrownBy(() -> peer("", new StringWriter()).request("ping", null))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void notifyWritesAMessageWithNoIdAndConsumesNoResponse() throws Exception {
        StringWriter out = new StringWriter();
        JsonRpcPeer peer = peer("", out); // no response needed

        peer.notify("notifications/initialized", null);

        JsonNode sent = MAPPER.readTree(out.toString().trim());
        assertThat(sent.path("method").asText()).isEqualTo("notifications/initialized");
        assertThat(sent.has("id")).isFalse();
    }

    @Test
    void closeClosesBothStreams() {
        AtomicBoolean readerClosed = new AtomicBoolean();
        AtomicBoolean writerClosed = new AtomicBoolean();
        Reader in = new StringReader("") {
            @Override
            public void close() {
                readerClosed.set(true);
                super.close();
            }
        };
        Writer out = new StringWriter() {
            @Override
            public void close() {
                writerClosed.set(true);
            }
        };

        new JsonRpcPeer(new BufferedReader(in), out, MAPPER).close();

        assertThat(readerClosed).isTrue();
        assertThat(writerClosed).isTrue();
    }

    @Test
    void crlfFramingIsHandledLikeReadLine() {
        // A server that terminates lines with \r\n must be parsed the same as \n.
        String script = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"value\":5}}\r\n";
        JsonNode result = peer(script, new StringWriter()).request("compute", null);
        assertThat(result.path("value").asInt()).isEqualTo(5);
    }

    @Test
    void anOverlongLineIsRejectedInsteadOfExhaustingHeap() {
        // A malicious/faulty server sends a line far longer than the cap; the read must
        // abort with an McpException rather than buffering it all into memory.
        StringWriter out = new StringWriter();
        String hugeLine = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + "A".repeat(500) + "\"}\n";
        JsonRpcPeer peer = new JsonRpcPeer(
                new BufferedReader(new StringReader(hugeLine)), out, MAPPER, 64);

        assertThatThrownBy(() -> peer.request("compute", null))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("maximum line length");
    }

    @Test
    void aLineExactlyAtTheCapIsAcceptedButOneOverIsRejected() {
        // Pin the exact boundary: a line of exactly maxLineLength chars is read, and a
        // line one character longer is rejected.
        String line = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}";
        JsonRpcPeer atCap = new JsonRpcPeer(
                new BufferedReader(new StringReader(line + "\n")), new StringWriter(), MAPPER, line.length());
        assertThat(atCap.request("compute", null).path("ok").asBoolean()).isTrue();

        // One extra character before the newline pushes it over the cap.
        String overCap = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true }}";
        JsonRpcPeer over = new JsonRpcPeer(
                new BufferedReader(new StringReader(overCap + "\n")), new StringWriter(), MAPPER, line.length());
        assertThatThrownBy(() -> over.request("compute", null))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("maximum line length");
    }

    @Test
    void aNonPositiveMaxLineLengthIsRejected() {
        assertThatThrownBy(() -> new JsonRpcPeer(
                new BufferedReader(new StringReader("")), new StringWriter(), MAPPER, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestIdsIncrementPerCall() throws Exception {
        StringWriter out = new StringWriter();
        JsonRpcPeer peer = peer(String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}") + "\n", out);

        peer.request("a", null);
        peer.request("b", null);

        String[] lines = out.toString().trim().split("\n");
        assertThat(MAPPER.readTree(lines[0]).path("id").asLong()).isEqualTo(1);
        assertThat(MAPPER.readTree(lines[1]).path("id").asLong()).isEqualTo(2);
    }
}
