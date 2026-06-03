# Pixelblaze Google Home Control

Integrating Pixelblaze chips into the existing Google Home fulfillment stack via direct HTTP — no MQTT broker or bridge process required.

---

## Architecture

```
Hey Google → Google Smart Home API
                  → HTTPS/ngrok → FulfillmentHandler.java
                                        │
                      ┌─────────────────┴──────────────────┐
                      ▼                                     ▼
               MQTT → Moquette                  HTTP → Pixelblaze
               (OpenBeken bulbs)                (built-in webserver)
```

Pixelblaze exposes a built-in HTTP REST API (`/sendVars`, `/brightness`, `/activateProgram`). Since Pixelblaze doesn't require high-speed animation sync like the OpenBeken bulbs, MQTT is unnecessary — `FulfillmentHandler` calls the Pixelblaze webserver directly when a command targets a Pixelblaze device.

---

## Step 1 — Add `PIXELBLAZE` Device Type

**`GoogleDevice.java`** — extend the `Type` enum and add a factory method:

```java
public enum Type { LIGHT, SCENE, PIXELBLAZE }

/** Factory method for Pixelblaze devices */
public static GoogleDevice pixelblaze(String id, String name, String room, String ip) {
    return new GoogleDevice(id, name, room, Type.PIXELBLAZE, ip, null, null, null);
}
```

The existing `ip` field is reused as the Pixelblaze device address. No new fields needed.

---

## Step 2 — Create `PixelblazeClient.java`

Drop this file into `src/main/java/com/openbeken/google/`. It is a thin HTTP wrapper around the Pixelblaze REST API using the JDK's built-in `java.net.http.HttpClient` (no new dependencies).

```java
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

    /** brightness: 0–100 (Google scale) → 0.0–1.0 (Pixelblaze scale) */
    public void setBrightness(int percent) {
        float b = percent / 100f;
        sendVars("{\"brightness\":" + b + "}");
    }

    /**
     * h: 0–360, s: 0–100, v: 0–100 (Google HSV)
     * Sends as normalized floats to Pixelblaze pattern vars h/s/v.
     * The active pattern must read these vars for color to apply.
     */
    public void setColor(int h, int s, int v) {
        float hf = h / 360f;
        float sf = s / 100f;
        float vf = v / 100f;
        sendVars("{\"h\":" + hf + ",\"s\":" + sf + ",\"v\":" + vf + "}");
    }

    /** Activate a named pattern by display name */
    public void setPattern(String patternName) {
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
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
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
```

### Optional: Cache clients per device

To avoid constructing a new `HttpClient` on every command, cache instances on `FulfillmentHandler`:

```java
private final Map<String, PixelblazeClient> pbClients = new HashMap<>();

private PixelblazeClient pbClient(GoogleDevice dev) {
    return pbClients.computeIfAbsent(dev.getId(), k -> new PixelblazeClient(dev.getIp()));
}
```

---

## Step 3 — Wire into `FulfillmentHandler.dispatchCommand()`

Add a Pixelblaze branch **before** the existing MQTT `switch` block:

```java
private boolean dispatchCommand(String devId, String command, JsonObject params) {
    GoogleDevice dev = registry.findById(devId);
    if (dev == null) { System.err.println("[Fulfillment] Unknown device: " + devId); return false; }

    try {
        // --- Scenes (unchanged) ---
        if (dev.getType() == GoogleDevice.Type.SCENE) {
            sceneExecutor.activate(dev.getAnimation(), dev.getGroup());
            return true;
        }

        // --- Pixelblaze: direct HTTP, no MQTT ---
        if (dev.getType() == GoogleDevice.Type.PIXELBLAZE) {
            PixelblazeClient pb = new PixelblazeClient(dev.getIp());
            switch (command) {
                case "action.devices.commands.OnOff" ->
                    pb.setOn(params.get("on").getAsBoolean());
                case "action.devices.commands.BrightnessAbsolute" ->
                    pb.setBrightness(params.get("brightness").getAsInt());
                case "action.devices.commands.ColorAbsolute" -> {
                    JsonObject hsv = params.getAsJsonObject("color").getAsJsonObject("spectrumHSV");
                    pb.setColor(
                        (int) hsv.get("hue").getAsDouble(),
                        (int) (hsv.get("saturation").getAsDouble() * 100),
                        (int) (hsv.get("value").getAsDouble() * 100)
                    );
                }
                default -> System.out.println("[Fulfillment] Unhandled Pixelblaze command: " + command);
            }
            return true;
        }

        // --- OpenBeken LIGHT: existing MQTT path (unchanged) ---
        if (!dev.isFlashed()) { System.err.println("[Fulfillment] Device not flashed: " + devId); return false; }
        String topic = dev.getMqttTopic();
        switch (command) {
            case "action.devices.commands.OnOff" -> {
                boolean on = params.get("on").getAsBoolean();
                pub("cmnd/" + topic + "/POWER1", on ? "1" : "0");
                updateState(devId, "on", on);
            }
            case "action.devices.commands.BrightnessAbsolute" -> {
                int bri = params.get("brightness").getAsInt();
                pub("cmnd/" + topic + "/Dimmer", String.valueOf(bri));
                updateState(devId, "brightness", bri);
            }
            case "action.devices.commands.ColorAbsolute" -> {
                JsonObject hsv = params.getAsJsonObject("color").getAsJsonObject("spectrumHSV");
                int h = (int) hsv.get("hue").getAsDouble();
                int s = (int) (hsv.get("saturation").getAsDouble() * 100);
                int v = (int) (hsv.get("value").getAsDouble() * 100);
                pub("cmnd/" + topic + "/HsbColor", h + "," + s + "," + v);
            }
            default -> System.out.println("[Fulfillment] Unhandled command: " + command);
        }
        return true;
    } catch (Exception e) {
        System.err.println("[Fulfillment] Error dispatching " + command + " → " + devId + ": " + e.getMessage());
        return false;
    }
}
```

---

## Step 4 — Register Devices in `google-home-devices.json`

Add a `"pixelblazes"` array to the existing config file alongside `"devices"` and `"scenes"`:

```json
{
  "_comment": "Google Home device registry",
  "devices": [ ... ],
  "scenes":  [ ... ],
  "pixelblazes": [
    { "id": "pb-desk",   "name": "Desk Strip",   "room": "Office",      "ip": "192.168.86.210" },
    { "id": "pb-couch",  "name": "Couch Strip",  "room": "Living Room", "ip": "192.168.86.211" }
  ]
}
```

Then update `DeviceRegistry.java` to parse the `"pixelblazes"` array and call `GoogleDevice.pixelblaze(id, name, room, ip)` for each entry — the same pattern already used for `"devices"` loading `LIGHT` instances.

---

## Pixelblaze API Reference

| Endpoint | Method | Body / Params | Purpose |
|---|---|---|---|
| `/sendVars` | POST | `{"on": true}` | Power on/off |
| `/sendVars` | POST | `{"brightness": 0.5}` | Global brightness (0.0–1.0) |
| `/sendVars` | POST | `{"h": 0.0, "s": 1.0, "v": 1.0}` | Push HSV vars to active pattern |
| `/activateProgram` | GET | `?name=Fire` | Switch to a named pattern |

> **Note on `/sendVars` and color:** `sendVars` pushes values into the running pattern's variable namespace. For color control to work, the active Pixelblaze pattern must declare and read `h`, `s`, `v` vars (e.g., `export var h, s, v`). A generic "solid HSV" pattern that reads these vars should be set as the default pattern on each chip.

---

## Google Home Commands

Once registered, these voice commands work automatically:

| Voice Command | Google Intent | Pixelblaze API Call |
|---|---|---|
| "Turn on the desk strip" | `OnOff {on: true}` | `POST /sendVars {"on":true}` |
| "Turn off the couch strip" | `OnOff {on: false}` | `POST /sendVars {"on":false}` |
| "Set desk strip to 40%" | `BrightnessAbsolute {brightness: 40}` | `POST /sendVars {"brightness":0.4}` |
| "Set desk strip to red" | `ColorAbsolute {spectrumHSV: ...}` | `POST /sendVars {"h":0,"s":1,"v":1}` |

---

## Files Changed

| File | Change |
|---|---|
| `src/main/java/com/openbeken/google/GoogleDevice.java` | Add `PIXELBLAZE` to `Type` enum; add `pixelblaze()` factory method |
| `src/main/java/com/openbeken/google/PixelblazeClient.java` | **New file** — HTTP client for Pixelblaze REST API |
| `src/main/java/com/openbeken/google/FulfillmentHandler.java` | Add `PIXELBLAZE` branch in `dispatchCommand()` |
| `src/main/java/com/openbeken/google/DeviceRegistry.java` | Parse `"pixelblazes"` array from config |
| `src/main/resources/google-home-devices.json` | Add `"pixelblazes"` array with device entries |
