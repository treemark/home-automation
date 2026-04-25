package com.openbeken.broker;

import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;

import java.util.Collections;
import java.util.Properties;

/**
 * Lightweight embedded Moquette MQTT broker.
 * Starts on port 1883 so OpenBeken devices and the CLI client can connect
 * without needing an external broker process.
 */
public class EmbeddedBroker {

    private Server server;
    private final int port;
    private boolean running;

    public EmbeddedBroker(int port) {
        this.port = port;
    }

    public EmbeddedBroker() {
        this(1883);
    }

    /**
     * Start the embedded MQTT broker. Safe to call multiple times.
     */
    public void start() {
        if (running) return;
        try {
            server = new Server();
            Properties props = new Properties();
            props.setProperty("port", String.valueOf(port));
            props.setProperty("host", "0.0.0.0");
            props.setProperty("allow_anonymous", "true");
            props.setProperty("immediate_buffer_flush", "true");

            server.startServer(new MemoryConfig(props), Collections.emptyList());
            running = true;
            System.out.printf("✓ Embedded MQTT broker started on port %d\n", port);
        } catch (Exception e) {
            System.err.println("✗ Failed to start embedded broker: " + e.getMessage());
            // If port is in use, that's fine — an external broker may be running
            if (e.getMessage() != null && e.getMessage().contains("Address already in use")) {
                System.out.println("  (Port " + port + " already in use — using existing broker)");
                running = true; // treat as success, external broker is available
            }
        }
    }

    /**
     * Stop the broker.
     */
    public void stop() {
        if (server != null) {
            server.stopServer();
            running = false;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }
}
