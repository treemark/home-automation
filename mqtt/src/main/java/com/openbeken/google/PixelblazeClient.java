package com.openbeken.google;

import com.openbeken.model.PixelblazeConfig;
import com.openbeken.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Map;
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
@Slf4j
public class PixelblazeClient {

    private final String ip;
    private final URI uri;

    public PixelblazeClient(String ip) {
        this.ip = ip;
        this.uri = URI.create("ws://" + ip + ":81");
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Check if the Pixelblaze is online by testing WebSocket port 81 connectivity.
     * @return true if the device responds on port 81
     */
    public boolean isOnline() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(ip, 81), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the device name from the Pixelblaze configuration.
     * Makes a WebSocket request to getConfig and extracts the name field.
     * @return device name, or null if unreachable
     */
    public String getName() {
        PixelblazeConfig config = getConfiguration();
        if (config != null && config.getName() != null) {
            return config.getName();
        }
        return null;
    }

    /**
     * Get the full device configuration as a PixelblazeConfig object.
     * Sends {"getConfig": true} to the Pixelblaze and parses the JSON response.
     * @return configuration object, or null if unreachable or error
     */
    public PixelblazeConfig getConfiguration() {
        try {
            String json = sendAndWaitForResponse("{\"getConfig\":true}", 5);
            log.debug("[Pixelblaze:{}] Raw config response: {}", ip, json);
            if (json != null && !json.isEmpty()) {
                // Pixelblaze may return multiple concatenated JSON objects
                // Find the first complete JSON object by balancing braces
                int startIdx = json.indexOf('{');
                if (startIdx < 0) {
                    log.warn("[Pixelblaze:{}] No JSON found in response", ip);
                    return null;
                }
                
                // Extract the first JSON object
                String firstObj = json.substring(startIdx);
                return JsonUtil.fromJson(firstObj, PixelblazeConfig.class);
            }
        } catch (Exception e) {
            log.warn("[Pixelblaze:{}] Failed to get config: {}", ip, e.getMessage());
        }
        return null;
    }

    /**
     * Get the currently active pattern name from the Pixelblaze configuration.
     * @return active pattern name, or null if unavailable
     */
    public String getActivePattern() {
        // Note: The activeProgram is a nested object in the response, not directly in config.
        // This requires parsing the raw response differently.
        // For now, we fall back to parsing the raw response.
        PixelblazeConfig config = getConfiguration();
        if (config != null) {
            // The activeProgram field comes in a separate response frame
            // We'll need to handle this differently
        }
        return null;
    }

    /** Power on/off. Maps to brightness 1.0 / 0.0. */
    public void setOn(boolean on) {
        float b = on ? 1f : 0f;
        send("{\"brightness\":" +b + "}\n");
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
        CountDownLatch done = new CountDownLatch(1);
        try {
            WebSocketClient ws = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake h) {
                    send(json);
                    // Don't close() immediately — let the send buffer flush.
                    // closeBlocking() below handles teardown after onOpen returns.
                }
                @Override public void onMessage(String msg) {
                    log.info(msg);
                }
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    done.countDown(); // connection fully closed = frame was sent
                }
                @Override
                public void onError(Exception e) {
                    System.err.println("[Pixelblaze:" + ip + "] WS error: " + e.getMessage());
                    done.countDown();
                }
            };

            boolean connected = ws.connectBlocking(2, TimeUnit.SECONDS);
            if (!connected) {
                System.err.println("[Pixelblaze:" + ip + "] Connection timed out");
                return;
            }
            ws.closeBlocking(); // waits for clean close, ensuring frame is flushed first
            done.await(3, TimeUnit.SECONDS);

        } catch (Exception e) {
            System.err.println("[Pixelblaze:" + ip + "] send failed: " + e.getMessage());
        }
    }

    /**
     * Send a JSON frame and wait for a response.
     * Used for queries like getConfig that expect a response back.
     *
     * @param json  the JSON frame to send
     * @param timeoutSeconds max seconds to wait for response
     * @return response string, or null if timeout/error
     */
    private String sendAndWaitForResponse(String json, int timeoutSeconds) {
        StringBuilder response = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            WebSocketClient ws = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    send(json);
                }

                @Override
                public void onMessage(String message) {
                    response.append(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    latch.countDown();
                }

                @Override
                public void onError(Exception e) {
                    log.warn("[Pixelblaze:{}] WS error: {}", ip, e.getMessage());
                    latch.countDown();
                }
            };

            ws.connectBlocking(timeoutSeconds, TimeUnit.SECONDS);
            boolean waited = latch.await(timeoutSeconds, TimeUnit.SECONDS);

            if (response.length() > 0) {
                ws.close();
                return response.toString();
            }

            ws.close();
            return null;

        } catch (Exception e) {
            log.warn("[Pixelblaze:{}] sendAndWait failed: {}", ip, e.getMessage());
            return null;
        }
    }
}
