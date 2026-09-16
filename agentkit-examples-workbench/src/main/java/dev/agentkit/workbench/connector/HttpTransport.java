package dev.agentkit.workbench.connector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * The one seam between {@link JiraClient} and the network, so the client's parsing and
 * error normalisation are testable against canned responses while the shipped default is a
 * real {@link HttpClient}.
 */
public interface HttpTransport {

    Response send(String method, URI uri, Map<String, String> headers, String body);

    record Response(int status, String body) {}

    /** The production transport: the JDK's {@link HttpClient}, with sane timeouts. */
    static HttpTransport overTheWire() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return (method, uri, headers, body) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(request::header);
            try {
                HttpResponse<String> response = client.send(request.build(),
                        HttpResponse.BodyHandlers.ofString());
                return new Response(response.statusCode(), response.body());
            } catch (IOException unreachable) {
                throw new AlmException("The ALM could not be reached: " + unreachable.getMessage(),
                        unreachable);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AlmException("The ALM call was interrupted.", interrupted);
            }
        };
    }
}
