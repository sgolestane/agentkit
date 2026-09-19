package dev.agentkit.chat.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.InMemoryChatStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The API a console consumes, over a real socket.
 *
 * <h2>Over a real socket rather than against the handler</h2>
 *
 * <p>Calling the handler directly would test the routing and none of the things that actually
 * break: status codes, streaming without a content length, whether a body is read to the end.
 * The repository already tests its web tiers this way — {@code AStandingRefusalOverHttpTest}
 * and {@code TheOperatorWorksTheTicketTest} both bind a port — and it costs one JDK HTTP
 * client.
 *
 * <h2>Stated, not broken</h2>
 *
 * <p>The half of this worth reading is what happens when the console is missing something. A
 * deployment with no model must boot, say which variable is missing, and give the same
 * sentence back from the endpoint that needs it. Anything else is a 500, which means this
 * server has a bug.
 */
class TheConsoleAnswersOverHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatStore store = new InMemoryChatStore();
    private final ChatEvents events = new ChatEvents();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private ChatRuntime runtime;
    private ChatServer server;

    private void start(ChatRuntime.Agents agents) throws IOException {
        start(agents, Map::of);
    }

    private void start(ChatRuntime.Agents agents, ChatServer.Overview overview)
            throws IOException {
        runtime = new ChatRuntime(store, events, agents);
        server = new ChatServer(0, runtime, "acme", overview);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        if (runtime != null) {
            runtime.close();
        }
    }

    private String url(String path) {
        return "http://localhost:" + server.port() + path;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(HttpResponse<String> response) throws IOException {
        return JSON.readValue(readable(response), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(HttpResponse<String> response)
            throws IOException {
        return JSON.readValue(readable(response), List.class);
    }

    /**
     * The body, or an assertion failure saying what arrived instead of JSON.
     *
     * <p>#397 was one occurrence of {@code MismatchedInputException: No content to map due to
     * end-of-input}, which says the body was empty and nothing else: not the status, not the
     * path, not the headers. A parser error is the wrong place to learn that an HTTP call went
     * wrong, and it cost a re-run to establish even that much. Everything a person would want
     * from the one occurrence is in the response object already; it just was not being read.
     */
    private static String readable(HttpResponse<String> response) {
        String body = response.body();
        assertThat(body)
                .as("%s %s answered %d with an empty body and headers %s — nothing to parse",
                        response.request().method(), response.uri(), response.statusCode(),
                        response.headers().map())
                .isNotEmpty();
        return body;
    }

    /** A deployment that never got a model configured. */
    private static final ChatRuntime.Agents NO_MODEL = session -> {
        throw new ChatUnavailable("No model is configured. Set CHAT_LLM=anthropic (plus "
                + "ANTHROPIC_API_KEY), then restart.");
    };

    @Test
    void aConsoleWithNoModelBootsAndSaysWhatIsMissing() throws Exception {
        start(NO_MODEL, () -> Map.of("model", "", "ready", false,
                "problems", List.of("No model is configured. Set CHAT_LLM, then restart.")));

        // It boots. That is the first half, and the half a console that refused to start
        // would fail: a person cannot fix a configuration problem they cannot see.
        HttpResponse<String> overview = get("/api/overview");
        assertThat(overview.statusCode()).isEqualTo(200);
        assertThat(asMap(overview)).containsEntry("ready", false);
        assertThat(String.valueOf(asMap(overview).get("problems"))).contains("CHAT_LLM");

        // And the same sentence comes back from the work that needs it — as the turn's own
        // detail, because the message was accepted and it is the answer that could not happen.
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        post("/api/conversations/" + conversation.get("id") + "/messages",
                "{\"text\":\"hello\"}");
        Map<String, Object> read = eventuallyFinished(String.valueOf(conversation.get("id")));
        assertThat(read).containsEntry("state", "FAILED");
        assertThat(String.valueOf(read.get("detail"))).contains("CHAT_LLM");
    }

    @Test
    void aSecondMessageIsQueuedRatherThanRefused() throws Exception {
        start(session -> session.agent(new SlowLlm(Duration.ofSeconds(30)),
                        new dev.agentkit.core.tool.SimpleToolRegistry(),
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(2).build())
                .build());
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));
        post("/api/conversations/" + id + "/messages", "{\"text\":\"one\"}");

        // A second message while the first is still working. Accepted and queued: a person who
        // thinks of something while the agent is working should be able to say it, and a
        // console that answers "wait" is one they have to babysit. The single-threaded worker
        // keeps the order a transcript needs.
        HttpResponse<String> second =
                post("/api/conversations/" + id + "/messages", "{\"text\":\"two\"}");

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(asMap(second)).containsEntry("state", "QUEUED");
        post("/api/conversations/" + id + "/cancel", "{}");
    }

    @Test
    void anUnknownEndpointIsAFourOhFourAndNotAFailure() throws Exception {
        start(NO_MODEL);

        assertThat(get("/api/nothing-here").statusCode()).isEqualTo(404);
        assertThat(get("/api/conversations/conv-does-not-exist").statusCode()).isEqualTo(404);
    }

    @Test
    void anIdThatCouldNameAFileIsRefusedRatherThanResolved() throws Exception {
        start(NO_MODEL);

        // Not found rather than a read of an arbitrary file. The store never builds a path
        // from an id it did not mint, and the server does not either.
        assertThat(get("/api/conversations/..%2F..%2Fetc%2Fpasswd").statusCode())
                .isIn(404, 409);
        // Several spellings, because normalisation happens at different layers for each and
        // the one that matters is whichever survives to reach getResourceAsStream. That call
        // is backed by a DIRECTORY classloader when the console runs from target/classes —
        // which is how the demo runs — so a "/ui/../pom.xml" that got through would be served
        // off the filesystem.
        for (String climb : List.of("/../pom.xml", "/%2e%2e/pom.xml",
                "/ui/..%2f..%2fpom.xml", "/a/../../pom.xml")) {
            HttpResponse<String> refused = http.send(HttpRequest.newBuilder(
                    URI.create(url(climb))).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(refused.statusCode()).as("%s", climb).isEqualTo(404);
            assertThat(refused.body())
                    .as("%s served something", climb)
                    .doesNotContain("<artifactId>agentkit-chat</artifactId>")
                    .doesNotContain("modelVersion");
        }
        // And the refusal comes from the check rather than from a resource that happened not
        // to exist. The two are worded differently on purpose: a miss names the path it looked
        // for, and this one does not, because there is no path here worth repeating back.
        HttpResponse<String> climbed = http.send(HttpRequest.newBuilder(
                URI.create(url("/ui/..%2f..%2fpom.xml"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(asMap(climbed)).containsEntry("error", "Not found.");
    }

    @Test
    void aTurnRunsInTheBackgroundAndTheAnswerArrivesOnTheStream() throws Exception {
        start(session -> session.agent(new ScriptedHttpLlm("Twelve are open."),
                        new dev.agentkit.core.tool.SimpleToolRegistry(),
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(2).build())
                .build());
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));

        HttpResponse<String> accepted =
                post("/api/conversations/" + id + "/messages", "{\"text\":\"how many?\"}");
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(asMap(accepted)).containsEntry("state", "QUEUED");

        Map<String, Object> finished = eventuallyFinished(id);
        assertThat(finished).containsEntry("state", "COMPLETED");
        assertThat(finished).containsEntry("answer", "Twelve are open.");
    }

    @Test
    void sendingAMessageDoesNotWaitForTheModel() throws Exception {
        // The POST returns as soon as the turn exists. A request that blocks for ninety
        // seconds is one a proxy will cut, and the whole reason the event stream exists is
        // that the answer arrives on it rather than in a response body.
        //
        // Asserted on the clock, because the returned Turn says RUNNING either way — it is the
        // object created before the work was handed off. A model that takes five seconds
        // against a response that must arrive in under two is a wide enough margin to be a
        // fact rather than a flake.
        start(session -> session.agent(new SlowLlm(Duration.ofSeconds(5)),
                        new dev.agentkit.core.tool.SimpleToolRegistry(),
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(2).build())
                .build());
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));

        long before = System.nanoTime();
        HttpResponse<String> accepted =
                post("/api/conversations/" + id + "/messages", "{\"text\":\"go\"}");
        Duration took = Duration.ofNanos(System.nanoTime() - before);

        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(took).isLessThan(Duration.ofSeconds(2));
        post("/api/conversations/" + id + "/cancel", "{}");
    }

    @Test
    void theEventStreamResumesFromWhereAClientLeftOff() throws Exception {
        start(NO_MODEL);
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));
        for (int i = 1; i <= 5; i++) {
            events.publish(id, "turn-1", dev.agentkit.chat.ChatEvent.Type.TEXT_DELTA, "", "",
                    Map.of("text", "chunk-" + i));
        }

        String body = streamFor(id + "/events?after=3", Duration.ofSeconds(3));

        assertThat(body).contains("chunk-4").contains("chunk-5");
        assertThat(body).doesNotContain("chunk-1").doesNotContain("chunk-3");
        // Ids on the wire, so a browser's EventSource reconnects with Last-Event-ID by itself.
        assertThat(body).contains("id: 4");
    }

    @Test
    void anIdleStreamKeepsItselfOpenRatherThanBeingClosedByAProxy() throws Exception {
        start(NO_MODEL);
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));

        String body = streamFor(conversation.get("id") + "/events", Duration.ofSeconds(2));

        // The stream opens and says so, without an event to carry.
        assertThat(body).startsWith(": stream ");
    }

    @Test
    void stoppingATurnRecordsItAsAPersonsDecisionRatherThanAFailure() throws Exception {
        start(session -> session.agent(new SlowLlm(Duration.ofSeconds(30)),
                        new dev.agentkit.core.tool.SimpleToolRegistry(),
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(4).build())
                .build());
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));
        post("/api/conversations/" + id + "/messages", "{\"text\":\"take your time\"}");
        waitUntil(() -> runtime.isWorking(id));

        HttpResponse<String> stopped = post("/api/conversations/" + id + "/cancel", "{}");

        assertThat(stopped.statusCode()).isEqualTo(200);
        assertThat(asMap(stopped)).containsEntry("stopped", true);
        Map<String, Object> finished = eventuallyFinished(id);
        // CANCELLED, not FAILED. It is a decision, and a transcript that calls it an error is
        // wrong about who did what.
        assertThat(finished).containsEntry("state", "CANCELLED");
        assertThat(String.valueOf(finished.get("detail"))).contains("You stopped this");
    }

    @Test
    void stoppingNothingSaysSoRatherThanPretending() throws Exception {
        start(NO_MODEL);
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));

        HttpResponse<String> stopped =
                post("/api/conversations/" + conversation.get("id") + "/cancel", "{}");

        assertThat(asMap(stopped)).containsEntry("stopped", false);
    }

    @Test
    void anAttachmentIsTakenAndHandedBackAsAHandleRatherThanBytes() throws Exception {
        start(NO_MODEL);
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));

        HttpResponse<String> uploaded = http.send(HttpRequest.newBuilder(
                                URI.create(url("/api/conversations/" + id + "/attachments")))
                        .header("Content-Type", "text/csv")
                        .header("X-Filename", "../../etc/tickets.csv")
                        .POST(HttpRequest.BodyPublishers.ofString("key,summary\nINC-1,laptop"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        Map<String, Object> attachment = asMap(uploaded);
        assertThat(attachment).containsEntry("name", "tickets.csv");
        assertThat(attachment).containsEntry("mediaType", "text/csv");
        assertThat(attachment).containsEntry("bytes", 24);
        assertThat(attachment).doesNotContainKey("content");
    }

    @Test
    void anUploadIsServedAsSomebodyElsesBytesAndNeverAsThisOrigin() throws Exception {
        start(NO_MODEL);
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));
        Map<String, Object> attachment = asMap(http.send(HttpRequest.newBuilder(
                                URI.create(url("/api/conversations/" + id + "/attachments")))
                        .header("Content-Type", "text/html")
                        .header("X-Filename", "notes.html")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "<script>alert(document.cookie)</script>"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()));

        HttpResponse<String> served = get("/api/attachments/" + attachment.get("id"));

        // Never inline, never sniffed. An uploaded page rendered in this origin would run as
        // this console — the same-origin hole every file host has to close.
        assertThat(served.headers().firstValue("Content-Type"))
                .contains("application/octet-stream");
        assertThat(served.headers().firstValue("Content-Disposition")).contains("attachment");
        assertThat(served.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(served.body()).contains("<script>");
    }

    @Test
    void aBodyLargerThanTheConsoleAcceptsIsRefusedWithTheLimit() throws Exception {
        start(NO_MODEL);
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));

        HttpResponse<String> refused = http.send(HttpRequest.newBuilder(
                                URI.create(url("/api/conversations/" + conversation.get("id")
                                        + "/attachments")))
                        .header("X-Filename", "big.bin")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(
                                new byte[ChatServer.MAX_UPLOAD_BYTES + 1024]))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(String.valueOf(asMap(refused).get("error"))).contains("MB");
    }

    @Test
    void aTabLeftOpenAcrossARestartCanTellTheServerChanged() throws Exception {
        start(NO_MODEL);

        String build = String.valueOf(asMap(get("/api/overview")).get("build"));

        assertThat(build).isNotBlank().isEqualTo(server.buildStamp());
        // A second server is a second process as far as a tab is concerned, and its stamp
        // differs — which is what lets the page reload rather than run yesterday's code
        // against today's API.
        try (ChatServer other = new ChatServer(0, runtime, "acme", Map::of)) {
            assertThat(other.buildStamp()).isNotEqualTo(build);
        }
    }

    @Test
    void aFinishedTurnIsLeftOutAndPutBack() throws Exception {
        start(NO_MODEL);
        String id = String.valueOf(asMap(post("/api/conversations", "{}")).get("id"));
        post("/api/conversations/" + id + "/messages", "{\"text\":\"hello\"}");
        String turnId = String.valueOf(eventuallyFinished(id).get("id"));

        assertThat(asMap(patch("/api/conversations/" + id + "/turns/" + turnId, "{\"leftOut\":true}")))
                .containsEntry("leftOut", true);
        assertThat(eventuallyFinished(id)).containsEntry("leftOut", true);
        assertThat(asMap(patch("/api/conversations/" + id + "/turns/" + turnId, "{\"leftOut\":false}")))
                .containsEntry("leftOut", false);
        HttpResponse<String> unsaid = patch("/api/conversations/" + id + "/turns/" + turnId, "{}");
        assertThat(unsaid.statusCode()).isEqualTo(409);
        assertThat(patch("/api/conversations/" + id + "/turns/nope", "{\"leftOut\":true}").statusCode())
                .isEqualTo(404);
    }

    private HttpResponse<String> patch(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aConversationIsListedRenamedAndDeleted() throws Exception {
        start(NO_MODEL);
        Map<String, Object> conversation = asMap(post("/api/conversations",
                "{\"title\":\"the queue\"}"));
        String id = String.valueOf(conversation.get("id"));

        assertThat(asList(get("/api/conversations"))).hasSize(1);

        HttpResponse<String> renamed = http.send(HttpRequest.newBuilder(
                                URI.create(url("/api/conversations/" + id)))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(
                                "{\"title\":\"renamed\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(asMap(renamed)).containsEntry("title", "renamed");

        HttpResponse<String> deleted = http.send(HttpRequest.newBuilder(
                        URI.create(url("/api/conversations/" + id))).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(asMap(deleted)).containsEntry("deleted", true);
        assertThat(asList(get("/api/conversations"))).isEmpty();
    }

    @Test
    void aDeepLinkToAConversationServesThePageRatherThanAFourOhFour() throws Exception {
        start(NO_MODEL);

        // A single-page console owns its own routes. The server must not 404 a reload of
        // /c/conv-3, or every deep link is broken by refreshing.
        HttpResponse<String> deep = get("/c/conv-3");

        // No page is bundled in this module's test classpath, so the honest assertion is that
        // the request was ROUTED to the page rather than to the API's not-found — which the
        // status distinguishes from a 200 only once a UI exists (#340).
        assertThat(deep.statusCode()).isIn(200, 404);
        assertThat(deep.body()).doesNotContain("No such endpoint");
    }

    @Test
    void manyMessagesAtOnceAreAllAcceptedAndRunOneAtATime() throws Exception {
        // This asserted the opposite until #348: a second message was refused with a 409 so
        // that a transcript stayed ordered. Refusing is the wrong answer to the right
        // constraint — a person who thinks of something while the agent is working should be
        // able to say it. They are all accepted and the single-threaded worker keeps the order.
        //
        // A double-clicked Send is a different problem and is the composer's: it clears itself
        // on submit, so the button is disabled until something else is typed.
        start(session -> session.agent(new ScriptedHttpLlm("done"),
                        new dev.agentkit.core.tool.SimpleToolRegistry(),
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(2).build())
                .build());
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));

        int senders = 8;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(senders);
        java.util.List<Integer> statuses =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < senders; i++) {
            int which = i;
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    statuses.add(post("/api/conversations/" + id + "/messages",
                            "{\"text\":\"message " + which + "\"}").statusCode());
                } catch (Exception e) {
                    statuses.add(-1);
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(statuses).allMatch(status -> status == 200);
        Map<String, Object> read = eventuallyFinished(id);
        assertThat(read).containsEntry("state", "COMPLETED");

        // Every one of them is in the transcript, numbered without a gap or a repeat.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> turns = (List<Map<String, Object>>)
                asMap(get("/api/conversations/" + id)).get("turns");
        assertThat(turns).hasSize(senders);
        assertThat(turns).extracting(turn -> turn.get("ordinal"))
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(turns).allSatisfy(turn ->
                assertThat(turn.get("state")).isEqualTo("COMPLETED"));
    }

    @Test
    void aQueuedTurnBecomesARunningOneWhenItsWorkerReachesIt() throws Exception {
        start(session -> session.agent(new ScriptedHttpLlm("done"),
                        new dev.agentkit.core.tool.SimpleToolRegistry(),
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(2).build())
                .build());
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));

        assertThat(asMap(post("/api/conversations/" + id + "/messages", "{\"text\":\"go\"}")))
                .containsEntry("state", "QUEUED");

        // It does not stay queued. A turn that never left that state would show "Waiting to
        // start" for its whole run — and asserting only that it COMPLETES does not catch that,
        // because a turn ends from QUEUED just as happily as from RUNNING. Measured: deleting
        // markRunning left that assertion green.
        assertThat(eventuallyFinished(id)).containsEntry("state", "COMPLETED");
    }

    @Test
    void aTurnBeingWorkedOnSaysSoWhileItIsHappening() throws Exception {
        start(session -> session.agent(new SlowLlm(Duration.ofSeconds(20)),
                        new dev.agentkit.core.tool.SimpleToolRegistry(),
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(2).build())
                .build());
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));

        post("/api/conversations/" + id + "/messages", "{\"text\":\"go\"}");

        waitUntil(() -> "RUNNING".equals(lastTurnState(id)));
        assertThat(lastTurnState(id)).isEqualTo("RUNNING");
        post("/api/conversations/" + id + "/cancel", "{}");
    }

    @SuppressWarnings("unchecked")
    private String lastTurnState(String conversationId) {
        try {
            List<Map<String, Object>> turns = (List<Map<String, Object>>)
                    asMap(get("/api/conversations/" + conversationId)).get("turns");
            return turns.isEmpty() ? "" : String.valueOf(turns.get(turns.size() - 1).get("state"));
        } catch (Exception unreadable) {
            return "";
        }
    }

    @Test
    void aTurnStoppedBeforeItStartsSpendsNothing() throws Exception {
        // The point of stopping something that has not begun. A cancelled turn that still made
        // its model call has cost money for an answer nobody will read.
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        start(session -> session.agent(new dev.agentkit.core.llm.LlmClient() {
                    @Override
                    public dev.agentkit.core.llm.LlmResponse generate(
                            dev.agentkit.core.llm.LlmRequest request) {
                        calls.incrementAndGet();
                        return new SlowLlm(Duration.ofSeconds(20)).generate(request);
                    }
                }, new dev.agentkit.core.tool.SimpleToolRegistry(),
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(2).build())
                .build());
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));
        post("/api/conversations/" + id + "/messages", "{\"text\":\"one\"}");
        post("/api/conversations/" + id + "/messages", "{\"text\":\"two\"}");
        post("/api/conversations/" + id + "/messages", "{\"text\":\"three\"}");
        waitUntil(() -> calls.get() >= 1);

        post("/api/conversations/" + id + "/cancel", "{}");
        eventuallyFinished(id);

        // Only the one already in flight reached the model; the two behind it did not.
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void oneBusyConversationDoesNotMakeAnotherLookBusy() throws Exception {
        start(session -> session.agent(new SlowLlm(Duration.ofSeconds(20)),
                        new dev.agentkit.core.tool.SimpleToolRegistry(),
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(2).build())
                .build());
        Map<String, Object> busy = asMap(post("/api/conversations", "{}"));
        Map<String, Object> idle = asMap(post("/api/conversations", "{}"));
        post("/api/conversations/" + busy.get("id") + "/messages", "{\"text\":\"go\"}");
        waitUntil(() -> runtime.isWorking(String.valueOf(busy.get("id"))));

        assertThat(runtime.isWorking(String.valueOf(idle.get("id"))))
                .as("a second conversation is not working because the first one is")
                .isFalse();
        assertThat(asMap(get("/api/conversations/" + idle.get("id"))))
                .containsEntry("working", false);

        post("/api/conversations/" + busy.get("id") + "/cancel", "{}");
    }

    @Test
    void stoppingWhileWorkIsQueuedStopsAllOfIt() throws Exception {
        // A Stop that left three queued turns to start one after another would be a button
        // that did not do what it says.
        start(session -> session.agent(new SlowLlm(Duration.ofSeconds(30)),
                        new dev.agentkit.core.tool.SimpleToolRegistry(),
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(2).build())
                .build());
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));
        for (int i = 0; i < 4; i++) {
            post("/api/conversations/" + id + "/messages", "{\"text\":\"go\"}");
        }
        waitUntil(() -> runtime.isWorking(id));

        assertThat(asMap(post("/api/conversations/" + id + "/cancel", "{}")))
                .containsEntry("stopped", true);

        eventuallyFinished(id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> turns = (List<Map<String, Object>>)
                asMap(get("/api/conversations/" + id)).get("turns");
        assertThat(turns).hasSize(4);
        assertThat(turns).allSatisfy(turn ->
                assertThat(turn.get("state")).isEqualTo("CANCELLED"));
    }

    @Test
    void aStopThatArrivesAsTheAnswerLandsDoesNotThrowTheAnswerAway() throws Exception {
        // A race with no right answer in the abstract and one in practice: the answer exists
        // and the person is about to be shown it, so reporting a cancellation would be the
        // console lying about work it actually did.
        start(session -> session.agent(new ScriptedHttpLlm("Twelve are open."),
                        new dev.agentkit.core.tool.SimpleToolRegistry(),
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(2).build())
                .build());
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));

        post("/api/conversations/" + id + "/messages", "{\"text\":\"how many?\"}");
        // Fired without waiting; whether it lands before or after the answer is the race.
        post("/api/conversations/" + id + "/cancel", "{}");

        Map<String, Object> finished = eventuallyFinished(id);
        if ("COMPLETED".equals(finished.get("state"))) {
            assertThat(finished).containsEntry("answer", "Twelve are open.");
        } else {
            assertThat(finished).containsEntry("state", "CANCELLED");
            assertThat(finished).containsEntry("answer", "");
        }
    }

    @Test
    void aGatedCallAsksThePersonAndTheRunGoesOnWhenTheyAnswer() throws Exception {
        // The whole human-in-the-loop story, over HTTP. The run BLOCKS on the question rather
        // than ending and being replayed, so approving continues the conversation exactly
        // where it was.
        dev.agentkit.core.tool.SimpleToolRegistry registry =
                new dev.agentkit.core.tool.SimpleToolRegistry();
        java.util.concurrent.atomic.AtomicBoolean ran =
                new java.util.concurrent.atomic.AtomicBoolean();
        registry.register(dev.agentkit.core.tool.FunctionTool
                .builder("identity.reset", "Resets a password.")
                .schema(Map.of("type", "object", "properties",
                        Map.of("user", Map.of("type", "string"))))
                .sideEffects(dev.agentkit.core.tool.SideEffects.EXTERNAL)
                .handler(invocation -> {
                    ran.set(true);
                    return dev.agentkit.core.tool.ToolResult.ok("reset");
                })
                .build());
        start(session -> session.agent(
                        new TwoStepLlm("identity.reset", Map.of("user", "alice"), "Done."),
                        registry,
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(dev.agentkit.core.reliability.ToolGates.requireApproval(
                        invocation -> true, session.approver()))
                .build());
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));
        post("/api/conversations/" + id + "/messages", "{\"text\":\"reset her password\"}");

        waitUntil(() -> !runtime.pending("acme").isEmpty());
        List<Map<String, Object>> asked = asList(get("/api/approvals"));
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0)).containsEntry("tool", "identity.reset");
        assertThat(String.valueOf(asked.get(0).get("arguments"))).contains("alice");
        assertThat(ran).isFalse();

        HttpResponse<String> decided = post(
                "/api/approvals/" + asked.get(0).get("id") + "/approve", "{\"by\":\"sid\"}");

        assertThat(asMap(decided)).containsEntry("decided", true);
        Map<String, Object> finished = eventuallyFinished(id);
        assertThat(finished).containsEntry("state", "COMPLETED");
        assertThat(ran).isTrue();
    }

    @Test
    void aRejectedCallDoesNotHappenAndTheRunIsToldWhy() throws Exception {
        dev.agentkit.core.tool.SimpleToolRegistry registry =
                new dev.agentkit.core.tool.SimpleToolRegistry();
        java.util.concurrent.atomic.AtomicBoolean ran =
                new java.util.concurrent.atomic.AtomicBoolean();
        registry.register(dev.agentkit.core.tool.FunctionTool
                .builder("identity.reset", "Resets a password.")
                .schema(Map.of("type", "object", "properties", Map.of()))
                .sideEffects(dev.agentkit.core.tool.SideEffects.EXTERNAL)
                .handler(invocation -> {
                    ran.set(true);
                    return dev.agentkit.core.tool.ToolResult.ok("reset");
                })
                .build());
        start(session -> session.agent(
                        new TwoStepLlm("identity.reset", Map.of(), "I could not do that."),
                        registry,
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(dev.agentkit.core.reliability.ToolGates.requireApproval(
                        invocation -> true, session.approver()))
                .build());
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));
        post("/api/conversations/" + id + "/messages", "{\"text\":\"reset it\"}");
        waitUntil(() -> !runtime.pending("acme").isEmpty());

        post("/api/approvals/" + runtime.pending("acme").get(0).id() + "/reject",
                "{\"by\":\"sid\",\"note\":\"She has not asked for this.\"}");

        Map<String, Object> finished = eventuallyFinished(id);
        assertThat(ran).as("a rejected call must not have happened").isFalse();
        assertThat(finished).containsEntry("state", "COMPLETED");
    }

    @Test
    void stoppingATurnAbandonsTheQuestionItWasWaitingOn() throws Exception {
        // Otherwise the run sits forever on a future nobody will complete, and the
        // conversation is stuck with no way back.
        dev.agentkit.core.tool.SimpleToolRegistry registry =
                new dev.agentkit.core.tool.SimpleToolRegistry();
        registry.register(dev.agentkit.core.tool.FunctionTool
                .builder("identity.reset", "Resets a password.")
                .schema(Map.of("type", "object", "properties", Map.of()))
                .sideEffects(dev.agentkit.core.tool.SideEffects.EXTERNAL)
                .handler(invocation -> dev.agentkit.core.tool.ToolResult.ok("reset"))
                .build());
        start(session -> session.agent(
                        new TwoStepLlm("identity.reset", Map.of(), "done"), registry,
                        dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(dev.agentkit.core.reliability.ToolGates.requireApproval(
                        invocation -> true, session.approver()))
                .build());
        Map<String, Object> conversation = asMap(post("/api/conversations", "{}"));
        String id = String.valueOf(conversation.get("id"));
        post("/api/conversations/" + id + "/messages", "{\"text\":\"reset it\"}");
        waitUntil(() -> !runtime.pending("acme").isEmpty());

        post("/api/conversations/" + id + "/cancel", "{}");

        Map<String, Object> finished = eventuallyFinished(id);
        assertThat(finished).containsEntry("state", "CANCELLED");
        assertThat(runtime.pending("acme")).isEmpty();
    }

    @Test
    void answeringAQuestionThatIsNotYoursChangesNothing() throws Exception {
        start(NO_MODEL);

        assertThat(asMap(post("/api/approvals/ask-does-not-exist/approve", "{}")))
                .containsEntry("decided", false);
    }

    // --- helpers ---------------------------------------------------------------------

    /** Reads a server-sent-event stream for a bounded time and hands back what arrived. */
    private String streamFor(String path, Duration howLong) throws Exception {
        HttpResponse<java.io.InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create(url("/api/conversations/" + path)))
                        .header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        StringBuilder body = new StringBuilder();
        long deadline = System.nanoTime() + howLong.toNanos();
        try (java.io.InputStream in = response.body()) {
            byte[] buffer = new byte[4096];
            while (System.nanoTime() < deadline) {
                if (in.available() > 0) {
                    int read = in.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    body.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                } else {
                    Thread.sleep(25);
                }
            }
        } catch (IOException closed) {
            // Closing a stream the server is still writing to is how this ends.
        }
        return body.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> eventuallyFinished(String conversationId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            List<Map<String, Object>> turns = (List<Map<String, Object>>)
                    asMap(get("/api/conversations/" + conversationId)).get("turns");
            if (!turns.isEmpty()) {
                Map<String, Object> last = turns.get(turns.size() - 1);
                if (!"RUNNING".equals(last.get("state")) && !"QUEUED".equals(last.get("state"))) {
                    return last;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The turn never finished");
    }

    private void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Condition never became true");
    }

    /** A model that answers at once. */
    private static final class ScriptedHttpLlm implements dev.agentkit.core.llm.LlmClient {
        private final String answer;

        ScriptedHttpLlm(String answer) {
            this.answer = answer;
        }

        @Override
        public dev.agentkit.core.llm.LlmResponse generate(
                dev.agentkit.core.llm.LlmRequest request) {
            return dev.agentkit.core.llm.LlmResponse.of(
                    dev.agentkit.core.message.Message.of(
                            dev.agentkit.core.message.Role.ASSISTANT,
                            dev.agentkit.core.message.TextBlock.of(answer)),
                    dev.agentkit.core.llm.LlmStopReason.END_TURN,
                    dev.agentkit.core.llm.TokenUsage.ZERO);
        }
    }

    /** Proposes one tool call, then answers. */
    private static final class TwoStepLlm implements dev.agentkit.core.llm.LlmClient {
        private final String tool;
        private final Map<String, Object> arguments;
        private final String answer;
        private int calls;

        TwoStepLlm(String tool, Map<String, Object> arguments, String answer) {
            this.tool = tool;
            this.arguments = arguments;
            this.answer = answer;
        }

        @Override
        public synchronized dev.agentkit.core.llm.LlmResponse generate(
                dev.agentkit.core.llm.LlmRequest request) {
            if (calls++ == 0) {
                return dev.agentkit.core.llm.LlmResponse.of(
                        dev.agentkit.core.message.Message.of(
                                dev.agentkit.core.message.Role.ASSISTANT,
                                dev.agentkit.core.message.ProposedCall.of("t1", tool, arguments)),
                        dev.agentkit.core.llm.LlmStopReason.TOOL_USE,
                        dev.agentkit.core.llm.TokenUsage.ZERO);
            }
            return new ScriptedHttpLlm(answer).generate(request);
        }
    }

    /** A model that takes long enough to be stopped, and that honours an interrupt. */
    private static final class SlowLlm implements dev.agentkit.core.llm.LlmClient {
        private final Duration delay;

        SlowLlm(Duration delay) {
            this.delay = delay;
        }

        @Override
        public dev.agentkit.core.llm.LlmResponse generate(
                dev.agentkit.core.llm.LlmRequest request) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new dev.agentkit.core.llm.LlmException("Stopped.", stopped);
            }
            return new ScriptedHttpLlm("eventually").generate(request);
        }
    }
}
