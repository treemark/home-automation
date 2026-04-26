package com.openbeken.google;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Minimal OAuth2 server for Google Smart Home account linking.
 *
 * Implements the two endpoints Google requires:
 *   GET  /auth   - Authorization endpoint  (redirects with ?code=...)
 *   POST /token  - Token exchange endpoint (returns access_token JSON)
 *
 * For a personal/home setup we use a single static token.
 * Google will redirect to our /auth URL, we immediately approve and redirect
 * back with the code. /token exchanges the code for a permanent access token.
 *
 * Configuration in Google Cloud Console:
 *   Authorization URL:  https://{ngrok}/auth
 *   Token URL:          https://{ngrok}/token
 *   Client ID:          home-lights (set in GOOGLE_HOME_SETUP.md)
 *   Client Secret:      home-lights-secret
 */
public class OAuthHandler implements HttpHandler {

    private final String clientId;
    private final String clientSecret;
    private final String staticToken;

    /**
     * @param clientId     Must match the Client ID you entered in the Google Cloud console
     * @param clientSecret Must match the Client Secret you entered in the Google Cloud console
     * @param staticToken  The Bearer token used to auth fulfillment requests
     */
    public OAuthHandler(String clientId, String clientSecret, String staticToken) {
        this.clientId     = clientId;
        this.clientSecret = clientSecret;
        this.staticToken  = staticToken;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/auth")) {
            handleAuth(exchange);
        } else if (path.endsWith("/token")) {
            handleToken(exchange);
        } else {
            send(exchange, 404, "text/plain", "Not Found");
        }
    }

    /** GET /auth?response_type=code&client_id=...&redirect_uri=...&state=... */
    private void handleAuth(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String redirectUri = params.getOrDefault("redirect_uri", "");
        String state       = params.getOrDefault("state", "");

        // Auto-approve: redirect immediately with the static token as the code
        String redirect = redirectUri
                + "?code=" + staticToken
                + "&state=" + state;

        exchange.getResponseHeaders().set("Location", redirect);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    /** POST /token — exchange code or refresh_token for access_token */
    private void handleToken(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> params = parseFormBody(body);

        String grantType = params.getOrDefault("grant_type", "");
        String clientId  = params.getOrDefault("client_id", "");
        String secret    = params.getOrDefault("client_secret", "");

        // Validate client ID only (personal-use server — client_secret not critical here;
        // real security is the Bearer token checked on every fulfillment request).
        if (!this.clientId.equals(clientId)) {
            System.err.printf("[OAuth] invalid_client_id: got='%s', expected='%s'%n",
                    clientId, this.clientId);
            send(exchange, 401, "application/json",
                    "{\"error\":\"invalid_client\"}");
            return;
        }
        // Log if secret doesn't match (informational only — won't reject)
        if (!this.clientSecret.equals(secret)) {
            System.err.printf("[OAuth] ⚠ client_secret mismatch (non-fatal): got='%s', expected='%s'%n",
                    secret, this.clientSecret);
            System.err.println("[OAuth] ⚠ Update GOOGLE_OAUTH_CLIENT_SECRET to match Google Cloud console.");
        }

        // For both authorization_code and refresh_token grants, return the static token
        String json = "{"
                + "\"access_token\":\"" + staticToken + "\","
                + "\"token_type\":\"Bearer\","
                + "\"expires_in\":315360000,"
                + "\"refresh_token\":\"" + staticToken + "\""
                + "}";
        send(exchange, 200, "application/json", json);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(decode(kv[0]), decode(kv[1]));
            }
        }
        return map;
    }

    private Map<String, String> parseFormBody(String body) {
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(decode(kv[0]), decode(kv[1]));
            }
        }
        return map;
    }

    private String decode(String s) {
        try { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private void send(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
