package com.openbeken.google;

import com.openbeken.broker.EmbeddedBroker;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.UUID;

/**
 * Entry point for the Google Home fulfillment server.
 *
 * Starts:
 *   1. Embedded Moquette MQTT broker (port 1883)
 *   2. Paho MQTT client (connected to localhost:1883)
 *   3. Google Home HTTP server (port 8080)
 *
 * Run via:
 *   ./gradlew :mqtt:googleHome
 *
 * Then expose with ngrok:
 *   ngrok http 8080
 *
 * See mqtt/GOOGLE_HOME_SETUP.md for the full Google Cloud setup guide.
 *
 * Configuration via environment variables:
 *   GOOGLE_HOME_PORT   HTTP port (default: 8080)
 *   GOOGLE_HOME_TOKEN  Static auth token (default: auto-generated UUID)
 *   MQTT_BROKER_URL    MQTT broker URL (default: tcp://localhost:1883)
 */
public class GoogleHomeMain {

    public static void main(String[] args) throws Exception {
        int    port      = Integer.parseInt(System.getenv().getOrDefault("GOOGLE_HOME_PORT", "8080"));
        String brokerUrl = System.getenv().getOrDefault("MQTT_BROKER_URL", "tcp://localhost:1883");

        // Token: use env var if set and non-empty, otherwise generate a stable UUID
        String token = System.getenv("GOOGLE_HOME_TOKEN");
        if (token == null || token.isBlank()) {
            token = UUID.randomUUID().toString();
            System.out.println("[GoogleHome] ⚠ GOOGLE_HOME_TOKEN not set — generated one-time token.");
            System.out.println("[GoogleHome] ⚠ Set it permanently: export GOOGLE_HOME_TOKEN=\"" + token + "\"");
        }

        // OAuth client credentials — MUST match what you entered in Google Cloud console
        String oauthClientId     = System.getenv("GOOGLE_OAUTH_CLIENT_ID");
        String oauthClientSecret = System.getenv("GOOGLE_OAUTH_CLIENT_SECRET");
        if (oauthClientId == null || oauthClientId.isBlank())     oauthClientId     = "home-lights";
        if (oauthClientSecret == null || oauthClientSecret.isBlank()) oauthClientSecret = "home-lights-secret";

        System.out.println("[GoogleHome] Starting embedded MQTT broker...");

        // 1. Start the embedded Moquette broker
        EmbeddedBroker broker = new EmbeddedBroker();
        broker.start();

        // Small delay to let the broker initialize
        Thread.sleep(1000);

        // 2. Create a Paho client for publishing commands to bulbs
        MqttClient mqtt = new MqttClient(brokerUrl,
                "google-home-" + UUID.randomUUID().toString().substring(0, 8),
                new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(true);
        opts.setConnectionTimeout(10);
        mqtt.connect(opts);
        System.out.println("[GoogleHome] MQTT client connected to " + brokerUrl);

        // 3. Load device registry and start HTTP server
        DeviceRegistry    registry    = new DeviceRegistry();
        GoogleHomeServer  httpServer  = new GoogleHomeServer(
                port, registry, token, oauthClientId, oauthClientSecret, mqtt);
        httpServer.start();

        System.out.println();
        System.out.println("┌──────────────────────────────────────────────────────┐");
        System.out.println("│  Configuration (must match Google Cloud console):    │");
        System.out.printf( "│  Client ID:     %-36s│%n", oauthClientId);
        System.out.printf( "│  Client Secret: %-36s│%n", oauthClientSecret);
        System.out.printf( "│  Auth Token:    %-36s│%n", token);
        System.out.println("└──────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("Next steps:");
        System.out.println("  1. ngrok http 8080");
        System.out.println("  2. Copy the https:// ngrok URL");
        System.out.println("  3. Follow mqtt/GOOGLE_HOME_SETUP.md");
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");

        // 4. Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[GoogleHome] Shutting down...");
            httpServer.stop();
            try { mqtt.disconnect(); } catch (Exception ignored) {}
            broker.stop();
        }));

        // Keep running
        Thread.currentThread().join();
    }
}
