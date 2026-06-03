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
            String json = sendAndWaitForResponse("{\"getConfig\":true}", 5, "name");
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

    /**
     * Get the list of available patterns/programs from the Pixelblaze.
     * Sends listPrograms and parses the binary frame response.
     *
     * Protocol (per Pixelblaze WebSocket API docs):
     *   byte 0:   0x07 (program list frame type)
     *   byte 1:   flags — 0x01 = start, 0x04 = end, 0x05 = single frame (start+end)
     *   byte 2..n: tab-separated "id\tname\n" entries, one per line
     *
     * The list may be split across multiple binary frames; we concatenate payloads
     * until the end flag (0x04) is set.
     *
     * @return list of programs, or null if unavailable
     */
    public java.util.List<com.openbeken.model.PixelblazeProgram> getPrograms() {
        java.util.List<com.openbeken.model.PixelblazeProgram> programs = new java.util.ArrayList<>();
        StringBuilder payload = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            WebSocketClient ws = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    send("{\"listPrograms\":true}");
                }

                @Override
                public void onMessage(java.nio.ByteBuffer bytes) {
                    if (bytes.remaining() < 2) return;

                    byte frameType = bytes.get();
                    byte flags     = bytes.get();

                    if (frameType == 0x07) {
                        // Decode the TSV payload from this frame and append to buffer
                        byte[] data = new byte[bytes.remaining()];
                        bytes.get(data);
                        payload.append(new String(data, java.nio.charset.StandardCharsets.UTF_8));

                        // End flag set — we have the full list
                        if ((flags & 0x04) != 0) {
                            latch.countDown();
                            close();
                        }
                    }
                }

                @Override public void onMessage(String message) { /* ignore text/stats frames */ }
                @Override public void onClose(int code, String reason, boolean remote) { latch.countDown(); }
                @Override public void onError(Exception e) {
                    log.warn("[Pixelblaze:{}] WS error: {}", ip, e.getMessage());
                    latch.countDown();
                }
            };

            ws.connectBlocking(5, TimeUnit.SECONDS);
            latch.await(5, TimeUnit.SECONDS);
            ws.close();

            // Parse the concatenated TSV: "id\tname\n" per line
            for (String line : payload.toString().split("\n")) {
                String[] parts = line.split("\t", 2);
                if (parts.length == 2) {
                    String id   = parts[0].trim();
                    String name = parts[1].trim();
                    if (!id.isEmpty()) {
                        com.openbeken.model.PixelblazeProgram prog =
                            new com.openbeken.model.PixelblazeProgram();
                        prog.setActiveProgramId(id);
                        prog.setName(name);
                        programs.add(prog);
                    }
                }
            }

            log.info("[Pixelblaze:{}] Found {} programs", ip, programs.size());
            return programs.isEmpty() ? null : programs;

        } catch (Exception e) {
            log.warn("[Pixelblaze:{}] Failed to get programs: {}", ip, e.getMessage());
        }
        return null;
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
    /**
     * Send a JSON frame and wait for a response that contains a complete, valid JSON object
     * matching the given responseKey.
     *
     * Pixelblaze streams periodic stats frames (fps, mem, uptime, etc.) as soon as a WebSocket
     * connection opens. The actual response to a command (e.g. listPrograms, getConfig) arrives
     * somewhere in that stream, sandwiched between stats frames.
     *
     * Strategy:
     *   - Buffer all incoming text across messages (Pixelblaze may split or concat frames)
     *   - After each message, scan the buffer for complete JSON objects (brace-balanced)
     *   - As soon as we find one that contains the responseKey, close the connection and return it
     *   - If the timeout elapses first, close and return null
     *
     * @param json           the JSON frame to send
     * @param timeoutSeconds max seconds to wait for the expected response
     * @param responseKey    the JSON key we're waiting for (e.g. "listPrograms", "config")
     * @return the first complete JSON object containing responseKey, or null on timeout/error
     */
    private String sendAndWaitForResponse(String json, int timeoutSeconds, String responseKey) {
        StringBuilder buffer = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        // Holds the matched JSON object once found
        java.util.concurrent.atomic.AtomicReference<String> result =
            new java.util.concurrent.atomic.AtomicReference<>(null);

        try {
            WebSocketClient ws = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    send(json);
                }

                @Override
                public void onMessage(String message) {
                    buffer.append(message);
                    // Scan buffer for complete JSON objects and check each for responseKey
                    String found = extractJsonContaining(buffer.toString(), responseKey);
                    if (found != null) {
                        result.set(found);
                        latch.countDown();
                        close(); // done — stop streaming
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    latch.countDown(); // unblock await in case of unexpected close
                }

                @Override
                public void onError(Exception e) {
                    log.warn("[Pixelblaze:{}] WS error: {}", ip, e.getMessage());
                    latch.countDown();
                }
            };

            ws.connectBlocking(timeoutSeconds, TimeUnit.SECONDS);
            latch.await(timeoutSeconds, TimeUnit.SECONDS);
            ws.close();

            return result.get(); // null if timed out without finding responseKey

        } catch (Exception e) {
            log.warn("[Pixelblaze:{}] sendAndWait failed: {}", ip, e.getMessage());
            return null;
        }
    }

    /**
     * Overload that preserves backward-compatible call sites which don't specify a responseKey.
     * Returns the first complete JSON object found in any response message.
     */
    private String sendAndWaitForResponse(String json, int timeoutSeconds) {
        return sendAndWaitForResponse(json, timeoutSeconds, null);
    }

    /**
     * Scans a raw buffer of potentially concatenated JSON objects and returns the first
     * complete, brace-balanced JSON object that contains the given key as a top-level field.
     *
     * If key is null, returns the first complete JSON object regardless of content.
     *
     * Pixelblaze concatenates JSON frames without delimiters, e.g.:
     *   {"fps":29.5,...}{"listPrograms":[...]}{"fps":30.1,...}
     * This method finds each object by tracking brace depth and scanning char-by-char.
     *
     * @param buffer raw accumulated text from the WebSocket
     * @param key    top-level JSON key to look for, or null to return any complete object
     * @return matching JSON object string, or null if not yet found
     */
    private String extractJsonContaining(String buffer, String key) {
        int i = 0;
        int len = buffer.length();
        while (i < len) {
            // Find start of next JSON object
            int start = buffer.indexOf('{', i);
            if (start < 0) break;

            // Walk forward tracking brace depth to find the matching close brace
            int depth = 0;
            boolean inString = false;
            boolean escape = false;
            int end = -1;

            for (int j = start; j < len; j++) {
                char c = buffer.charAt(j);
                if (escape) {
                    escape = false;
                    continue;
                }
                if (c == '\\' && inString) {
                    escape = true;
                    continue;
                }
                if (c == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) continue;

                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = j;
                        break;
                    }
                }
            }

            if (end < 0) break; // incomplete object — wait for more data

            String candidate = buffer.substring(start, end + 1);

            // If no key filter, return the first complete object
            if (key == null) return candidate;

            // Quick substring check before attempting a full parse
            if (candidate.contains("\"" + key + "\"")) {
                try {
                    com.fasterxml.jackson.databind.JsonNode node =
                        JsonUtil.getObjectMapper().readTree(candidate);
                    if (node.isObject() && node.has(key)) {
                        return candidate;
                    }
                } catch (Exception ignored) {
                    // Malformed — skip this candidate and keep scanning
                }
            }

            i = end + 1; // advance past this object and look at the next
        }
        return null; // not found yet
    }
}
