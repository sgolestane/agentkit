package dev.agentkit.host.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.agentkit.chat.web.ChatServer;
import dev.agentkit.host.OrgHost;
import dev.agentkit.host.Tenant;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Signing in for development: pick an organization, type an email the organization's directory knows, and the
 * console is yours. A cookie says who you are and <strong>nothing authenticates it</strong> — anyone who can reach the
 * host can be anyone — so it is off unless the host is started with it, and the host says so when it is.
 *
 * <p>It stands where a real sign-in (OIDC against each organization's identity provider) will: it answers
 * {@link ChatServer.Tenants} for the console, and serves {@code /sign-in} and {@code /sign-out}.
 */
public final class DevSignIn implements ChatServer.Tenants, HttpHandler {

    static final String COOKIE = "agentkit-dev-tenant";

    private final Map<String, OrgHost> orgs;

    public DevSignIn(Map<String, OrgHost> orgs) {
        this.orgs = Map.copyOf(orgs);
    }

    @Override
    public Optional<String> of(HttpExchange exchange) {
        return cookie(exchange).flatMap(Tenant::parse).filter(t -> orgs.containsKey(t.org())).map(Tenant::id);
    }

    @Override
    public Optional<String> signIn() {
        return Optional.of("/sign-in");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/sign-out")) {
                exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
                redirect(exchange, "/sign-in");
                return;
            }
            if (!path.equals("/sign-in")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            Map<String, String> form = form(exchange);
            String org = form.getOrDefault("org", "").strip();
            String email = form.getOrDefault("email", "").strip();
            if ("POST".equals(exchange.getRequestMethod())) {
                Optional<String> refused = refusal(org, email);
                if (refused.isEmpty()) {
                    String tenant = new Tenant(org, email).id();
                    exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "="
                            + URLEncoder.encode(tenant, StandardCharsets.UTF_8) + "; Path=/; HttpOnly; SameSite=Lax");
                    redirect(exchange, "/");
                    return;
                }
                page(exchange, 400, org, email, refused.get());
                return;
            }
            page(exchange, 200, org, email, null);
        }
    }

    private Optional<String> refusal(String org, String email) {
        OrgHost host = orgs.get(org);
        if (host == null) {
            return Optional.of("There is no organization " + org + " here.");
        }
        if (email.isEmpty() || host.current().principal(email).isEmpty()) {
            return Optional.of("Nobody in " + org + "'s directory has the email " + email + ".");
        }
        return Optional.empty();
    }

    private void page(HttpExchange exchange, int status, String org, String email, String problem) throws IOException {
        StringBuilder options = new StringBuilder();
        for (String name : orgs.keySet().stream().sorted().toList()) {
            options.append("<option").append(name.equals(org) ? " selected" : "").append(">").append(escape(name))
                    .append("</option>");
        }
        String html = """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                <title>Sign in · AgentKit</title>
                <style>body{font:15px system-ui,sans-serif;max-width:26rem;margin:4rem auto;padding:0 1rem;color:#1d1d1f}
                label{display:block;margin:1rem 0 .3rem}input,select,button{font:inherit;width:100%%;padding:.5rem;box-sizing:border-box}
                button{margin-top:1.2rem;cursor:pointer}.warn{background:#fff4d6;padding:.6rem .8rem;border-radius:6px;font-size:13px}
                .bad{color:#b3261e}@media(prefers-color-scheme:dark){body{background:#1c1c1e;color:#f2f2f7}.warn{background:#3a3120}}</style>
                </head><body><h1>Sign in</h1>
                <p class="warn">Development sign-in: nothing checks who you are. Never enable it where others can reach this host.</p>
                %s<form method="post" action="/sign-in"><label for="org">Organization</label><select id="org" name="org">%s</select>
                <label for="email">Work email</label><input id="email" name="email" type="email" required value="%s" autofocus>
                <button type="submit">Sign in</button></form></body></html>
                """.formatted(problem == null ? "" : "<p class=\"bad\">" + escape(problem) + "</p>", options, escape(email));
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().add("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void redirect(HttpExchange exchange, String to) throws IOException {
        exchange.getResponseHeaders().add("Location", to);
        exchange.sendResponseHeaders(303, -1);
    }

    private static Optional<String> cookie(HttpExchange exchange) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String part : header.split(";")) {
                String[] pair = part.strip().split("=", 2);
                if (pair.length == 2 && pair[0].equals(COOKIE) && !pair[1].isBlank()) {
                    return Optional.of(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        String raw = "POST".equals(exchange.getRequestMethod())
                ? new String(exchange.getRequestBody().readNBytes(8192), StandardCharsets.UTF_8)
                : Objects.requireNonNullElse(exchange.getRequestURI().getRawQuery(), "");
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                fields.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return fields;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
