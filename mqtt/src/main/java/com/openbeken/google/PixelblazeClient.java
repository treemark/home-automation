package com.openbeken.google;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class PixelblazeClient {

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public PixelblazeClient(String ip) {
        this.baseUrl = "http://" + ip;
    }

    /** on=true → brightness 1.0, on=false → brightness 0.0 */
    public void setOn(boolean on) {
        sendVars("{\"on\":" + on + "}");
    }

    /** brightness: 0–100 (Google) → 0.0–1.0 (Pixelblaze) */
    public void setBrightness(int percent) {
        float b = percent / 100f;
        sendVars("{\"brightness\":" + b + "}");
    }

    /** h: 0–360, s: 0–100, v: 0–100 (Google HSV) → Pixelblaze pattern vars */
    public void setColor(int h, int s, int v) {
        float hf = h / 360f;
        float sf = s / 100f;
        float vf = v / 100f;
        sendVars("{\"h\":" + hf + ",\"s\":" + sf + ",\"v\":" + vf + "}");
    }

    /** Activate a named pattern by name */
    public void setPattern(String patternName) {
        // GET /activateProgram?name=<pattern>
        send(baseUrl + "/activateProgram?name=" + patternName.replace(" ", "%20"));
    }

    private void sendVars(String json) {
        send(baseUrl + "/sendVars", json);
    }

    private void send(String url) {
        send(url, null);
    }

    private void send(String url, String jsonBody) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3));
            if (jsonBody != null) {
                req.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .header("Content-Type", "application/json");
            } else {
                req.GET();
            }
            http.send(req.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("[Pixelblaze] HTTP error → " + url + " : " + e.getMessage());
        }
    }
}
