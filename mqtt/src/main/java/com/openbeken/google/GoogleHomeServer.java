package com.openbeken.google;

import com.sun.net.httpserver.HttpServer;
import org.eclipse.paho.client.mqttv3.MqttClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server that exposes the Google Smart Home webhook endpoints.
 *
 * Endpoints:
 *   GET/POST /auth        OAuth2 authorization (auto-approves, personal use)
 *   POST     /token       OAuth2 token exchange
 *   POST     /fulfillment Google Smart Home SYNC / QUERY / EXECUTE
 *   GET      /health      Simple health check (returns 200 OK)
 *
 * Default port: 8080 (set via GOOGLE_HOME_PORT env var or constructor arg).
 *
 * Usage:
 *   ./gradlew :mqtt:googleHome
 *   # Then expose via ngrok: ngrok http 8080
 */
public class GoogleHomeServer {

    private final int port;
    private final DeviceRegistry registry;
    private final String authToken;
    private final String oauthClientId;
    private final String oauthClientSecret;
    private final MqttClient mqtt;
    private HttpServer server;

    /**
     * @param port              HTTP port to listen on (typically 8080)
     * @param registry          Device registry (loaded from google-home-devices.json)
     * @param authToken         Static Bearer token for fulfillment auth
     * @param oauthClientId     Must match Client ID in Google Cloud console
     * @param oauthClientSecret Must match Client Secret in Google Cloud console
     * @param mqtt              Connected Paho MQTT client (may be null in testing)
     */
    public GoogleHomeServer(int port, DeviceRegistry registry, String authToken,
                            String oauthClientId, String oauthClientSecret, MqttClient mqtt) {
        this.port              = port;
        this.registry          = registry;
        this.authToken         = authToken;
        this.oauthClientId     = oauthClientId;
        this.oauthClientSecret = oauthClientSecret;
        this.mqtt              = mqtt;
    }

    /** Start the HTTP server. Call once on startup. */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        OAuthHandler      oauth       = new OAuthHandler(oauthClientId, oauthClientSecret, authToken);
        FulfillmentHandler fulfillment = new FulfillmentHandler(registry, authToken, mqtt);

        // OAuth endpoints
        server.createContext("/auth",        oauth);
        server.createContext("/token",       oauth);

        // Fulfillment webhook
        server.createContext("/fulfillment", fulfillment);

        // Health check
        server.createContext("/health", exchange -> {
            byte[] body = "OK".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        server.start();
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║   Google Home Fulfillment Server             ║");
        System.out.printf( "║   Listening on http://0.0.0.0:%-14d  ║%n", port);
        System.out.println("║                                              ║");
        System.out.println("║   Endpoints:                                 ║");
        System.out.println("║     GET/POST /auth         OAuth authorize   ║");
        System.out.println("║     POST     /token        OAuth token       ║");
        System.out.println("║     POST     /fulfillment  Google webhook    ║");
        System.out.println("║     GET      /health       Health check      ║");
        System.out.println("║                                              ║");
        System.out.printf( "║   Auth token: %-30s ║%n", authToken.substring(0, Math.min(20, authToken.length())) + "...");
        System.out.println("║                                              ║");
        System.out.println("║   Expose via ngrok:                          ║");
        System.out.println("║     ngrok http 8080                          ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.printf("[Registry] %d flashed lights, %d scenes ready%n",
                registry.getFlashedLights().size(), registry.getScenes().size());
    }

    /** Stop the server gracefully. */
    public void stop() {
        if (server != null) {
            server.stop(2);
            System.out.println("[GoogleHomeServer] Stopped.");
        }
    }

    public int getPort() { return port; }
}
