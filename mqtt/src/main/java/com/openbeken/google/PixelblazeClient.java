package com.openbeken.google;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket client for Pixelblaze LED controller.
 * 
 * Pixelblaze is controlled exclusively via WebSocket on port 81.
 * Each command opens a connection, sends one JSON frame, then closes.
 * 
 * Usage: one PixelblazeClient instance per device (cache recommended).
 */
public class PixelblazeClient {

    private final String ip;
    private final URI uri;

    public PixelblazeClient(String ip) {
        this.ip = ip;
        this.uri = URI.create("ws://" + ip + ":81");
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Power on/off. Maps to brightness 1.0 / 0.0. */
    public void setOn(boolean on) {
        send("{\"on\":" + on + "}");
    }

    /**
     * Global brightness.
     * @param percent 0–100 (Google scale) → normalized to 0.0–1.0 (Pixelblaze scale)
     */
    public void setBrightness(int percent) {
        float b = Math.max(0f, Math.min(1f, percent / 100f));
        send("{\"brightness\":" + b + "}");
    }

    /**
     * Set color via pattern variables.
     * The active Pixelblaze pattern must declare: export var h, s, v
     *
     * @param h hue        0–360  (Google) → 0.0–1.0 (Pixelblaze)
     * @param s saturation 0–100  (Google) → 0.0–1.0 (Pixelblaze)
     * @param v value      0–100  (Google) → 0.0–1.0 (Pixelblaze)
     */
    public void setColor(int h, int s, int v) {
        float hf = h / 360f;
        float sf = s / 100f;
        float vf = v / 100f;
        send("{\"setVars\":{\"h\":" + hf + ",\"s\":" + sf + ",\"v\":" + vf + "}}");
    }

    /**
     * Activate a pattern by its ID (not display name).
     * Obtain IDs via listPatterns() during discovery/setup.
     */
    public void activatePattern(String patternId) {
        send("{\"activeProgramId\":\"" + patternId + "\"}");
    }

    /**
     * Request the pattern list. Response arrives asynchronously.
     * Use during discovery/setup to map display names → IDs.
     */
    public void listPatterns() {
        send("{\"listPrograms\":true}");
    }

    // ── WebSocket send (connect → send → close) ───────────────────────────

    /**
     * Send a JSON frame over WebSocket.
     * Opens connection, sends frame, then closes immediately.
     */
    private void send(String json) {
        CountDownLatch latch = new CountDownLatch(1);
        
        try {
            WebSocketClient ws = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    send(json);
                    latch.countDown();
                    close();
                }

                @Override
                public void onMessage(String message) {
                    // Log pattern list responses during discovery
                    if (message.contains("listPrograms") || message.contains("programs")) {
                        System.out.println("[Pixelblaze:" + ip + "] " + message);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    // Connection closed normally
                }

                @Override
                public void onError(Exception e) {
                    System.err.println("[Pixelblaze:" + ip + "] WS error: " + e.getMessage());
                    latch.countDown();
                }
            };

            ws.connectBlocking(2, TimeUnit.SECONDS);
            latch.await(3, TimeUnit.SECONDS);
            
        } catch (Exception e) {
            System.err.println("[Pixelblaze:" + ip + "] send failed: " + e.getMessage());
        }
    }
}
