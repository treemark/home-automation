# Pixelblaze Google Home Control

Integrating Pixelblaze chips into the existing Google Home fulfillment stack via **WebSocket** —
no MQTT broker or bridge process required.

---

## Architecture

```
Hey Google → Google Smart Home API
                  → HTTPS/ngrok → FulfillmentHandler.java
                                        │
                      ┌─────────────────┴────────────────────┐
                      ▼                                       ▼
               MQTT → Moquette               WebSocket :81 → Pixelblaze
               (OpenBeken bulbs)                 (built-in WS server)
```

Pixelblaze does **not** expose an HTTP REST API. It is controlled exclusively via a
**WebSocket connection on port `81`** — the same socket its own web UI uses. JSON text
frames are sent to control brightness, variables, and active patterns.

```
ws://192.168.86.210:81
```

Since Pixelblaze doesn't require high-speed animation sync like the OpenBeken bulbs, the
WebSocket is opened on demand per command (connect → send → disconnect) rather than kept
alive, which keeps `FulfillmentHandler` stateless.

---

## Step 0 — Add WebSocket Dependency (`build.gradle`)

The JDK's `java.net.http` client does not provide a convenient WebSocket send-and-close
workflow. Add **Java-WebSocket** (TooTallNate), a lightweight pure-Java library with no
transitive dependencies:

```groovy
dependencies {
    // ... existing deps ...
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
}
```

---

## Step 1 — Add `PIXELBLAZE` Device Type (`GoogleDevice.java`)

Extend the `Type` enum and add a factory method. The existing `ip` field is reused as the
Pixelblaze device address — no new fields needed.

```java
public enum Type { LIGHT, SCENE, PIXELBLAZE }

/** Factory method for Pixelblaze devices */
public static GoogleDevice pixelblaze(String id, String name, String room, String ip) {
    return new GoogleDevice(id, name, room, Type.PIXELBLAZE, ip, null, null, null);
}
```

---

## Step 2 — Create `PixelblazeClient.java`

Drop this into `src/main/java/com/openbeken/google/`. Each method opens a WebSocket to
`ws://{ip}:81`, sends one JSON frame, then closes. This is fire-and-forget — acceptable
for voice commands which don't need animation-speed throughput.

```java
package com.openbeken.google;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Thin WebSocket client for Pixelblaze.
 * Pixelblaze has no HTTP REST API — all control is via ws://{ip}:81.
 *
 * Usage: one PixelblazeClient instance per Pixelblaze device (cache on FulfillmentHandler).
 * Each send() call reconnects if the socket has been closed.
 */
public class PixelblazeClient {

    private final String ip;
    private final URI uri;

    public PixelblazeClient(String ip) {
        this.ip = ip;
        try {
            this.uri = new URI("ws://" + ip + ":81");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Pixelblaze IP: " + ip, e);
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Power on/off. Pixelblaze has no on/off command — brightness is used instead.
     * on=true  → brightness 1.0 (full)
     * on=false → brightness 0.0 (off)
     */
    public void setOn(boolean on) {
        send("{\"brightness\":" + (on ? 1.0f : 0.0f) + "}");
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
     * Obtain IDs first via listPatterns(), then store them in google-home-devices.json.
     */
    public void activatePattern(String patternId) {
        send("{\"activeProgramId\":\"" + patternId + "\"}");
    }

    /**
     * Request the pattern list. Response arrives asynchronously — use this during
     * discovery/setup to map display names → IDs, not during fulfillment.
     * Prints results to stdout.
     */
    public void listPatterns() {
        send("{\"listPrograms\":true}");
    }

    // ── WebSocket send (connect → send → close) ────────────────────────────────

    private void send(String json) {
        CountDownLatch sent = new CountDownLatch(1);
        try {
            WebSocketClient ws = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake h) {
                    send(json);
                    sent.countDown();
                    close();
                }
                @Override public void onMessage(String msg) {}
                @Override public void onClose(int code, String reason, boolean remote) {}
                @Override public void onError(Exception e) {
                    System.err.println("[Pixelblaze:" + ip + "] WS error: " + e.getMessage());
                    sent.countDown();
                }
            };
            ws.connectBlocking(2, TimeUnit.SECONDS);
            sent.await(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[Pixelblaze:" + ip + "] send failed: " + e.getMessage());
        }
    }
}
```

### Cache clients on `FulfillmentHandler`

Avoid reconstructing the client URI on every command — cache one instance per device:

```java
// In FulfillmentHandler:
private final Map<String, PixelblazeClient> pbClients = new HashMap<>();

private PixelblazeClient pbClient(GoogleDevice dev) {
    return pbClients.computeIfAbsent(dev.getId(), k -> new PixelblazeClient(dev.getIp()));
}
```

---

## Step 3 — Wire into `FulfillmentHandler.dispatchCommand()`

Add a Pixelblaze branch **before** the existing OpenBeken MQTT block:

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

        // --- Pixelblaze: WebSocket, no MQTT ---
        if (dev.getType() == GoogleDevice.Type.PIXELBLAZE) {
            PixelblazeClient pb = pbClient(dev);
            switch (command) {
                case "action.devices.commands.OnOff" ->
                    // Pixelblaze has no on/off — setOn() maps to brightness 1.0/0.0
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

Add a `"pixelblazes"` array alongside `"devices"` and `"scenes"`:

```json
{
  "_comment": "Google Home device registry",
  "devices": [ ... ],
  "scenes":  [ ... ],
  "pixelblazes": [
    { "id": "pb-desk",  "name": "Desk Strip",  "room": "Office",      "ip": "192.168.86.210" },
    { "id": "pb-couch", "name": "Couch Strip", "room": "Living Room", "ip": "192.168.86.211" }
  ]
}
```

Update `DeviceRegistry.java` to parse the `"pixelblazes"` array and call
`GoogleDevice.pixelblaze(id, name, room, ip)` for each entry — the same pattern already
used to load `"devices"` as `LIGHT` instances.

---

## Step 5 — Extend `OpenBekenDiscoveryService` for Pixelblaze

`OpenBekenDiscoveryService` currently discovers devices by probing **port 80** for an
OpenBeken web interface [cite:38]. Pixelblaze also serves its web UI on port 80 but
responds differently — and critically, it listens on **port 81** for WebSocket connections.

Add a `probePixelblaze(String ip)` method alongside the existing `probeDevice()`:

```java
/**
 * Probe a single IP to check if it's a Pixelblaze.
 * Pixelblaze serves its web UI on port 80 and WebSocket API on port 81.
 * We check port 81 reachability as the definitive identifier — OpenBeken
 * devices do NOT open port 81.
 *
 * @return a DiscoveredPixelblaze record if confirmed, null otherwise
 */
public DiscoveredPixelblaze probePixelblaze(String ip) {
    // Port 80 must be open (web UI present)
    if (!isPortOpen(ip, 80)) return null;

    // Port 81 open = WebSocket = Pixelblaze (not OpenBeken)
    if (!isPortOpen(ip, 81)) return null;

    // Optionally: fetch http://{ip}/ and check for Pixelblaze title/marker
    String index = httpGet("http://" + ip + "/");
    if (index == null || !index.contains("Pixelblaze")) return null;

    System.out.println("[Discovery] Pixelblaze found at " + ip);
    return new DiscoveredPixelblaze(ip);
}

/** Scan a subnet for both OpenBeken AND Pixelblaze devices in one pass. */
public ScanResults scanSubnetAll(String subnetPrefix, int startHost, int endHost,
                                  DiscoveryListener listener) {
    ExecutorService executor = Executors.newFixedThreadPool(SCAN_THREAD_POOL_SIZE);
    List<Future<ScanResult>> futures = new ArrayList<>();
    int total = endHost - startHost + 1;
    if (listener != null) listener.onScanStarted(total);

    for (int i = startHost; i <= endHost; i++) {
        final String ip = subnetPrefix + "." + i;
        futures.add(executor.submit(() -> {
            // Try OpenBeken first (port 80 only)
            OpenBekenDevice obk = probeDevice(ip);
            if (obk != null) return ScanResult.openBeken(obk);

            // Then try Pixelblaze (port 80 + port 81)
            DiscoveredPixelblaze pb = probePixelblaze(ip);
            if (pb != null) return ScanResult.pixelblaze(pb);

            return null;
        }));
    }

    List<OpenBekenDevice> obkResults = new ArrayList<>();
    List<DiscoveredPixelblaze> pbResults  = new ArrayList<>();
    int scanned = 0;
    for (Future<ScanResult> f : futures) {
        try {
            ScanResult r = f.get(HTTP_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
            if (r != null) {
                if (r.openBeken != null) obkResults.add(r.openBeken);
                if (r.pixelblaze != null) pbResults.add(r.pixelblaze);
                if (listener != null) listener.onDeviceFound(null); // adapt as needed
            }
        } catch (Exception ignored) {}
        if (listener != null && ++scanned % 10 == 0)
            listener.onScanProgress(scanned, total);
    }
    executor.shutdown();
    if (listener != null) listener.onScanComplete(obkResults.size() + pbResults.size());
    return new ScanResults(obkResults, pbResults);
}

// ── Supporting types ──────────────────────────────────────────────────────────

public record DiscoveredPixelblaze(String ip) {}

public record ScanResults(
    List<OpenBekenDevice>      openBekenDevices,
    List<DiscoveredPixelblaze> pixelblazeDevices
) {}

private record ScanResult(OpenBekenDevice openBeken, DiscoveredPixelblaze pixelblaze) {
    static ScanResult openBeken(OpenBekenDevice d)   { return new ScanResult(d, null); }
    static ScanResult pixelblaze(DiscoveredPixelblaze p) { return new ScanResult(null, p); }
}
```

> **Why port 81 as the discriminator?** OpenBeken devices only open port 80. Pixelblaze
> opens both 80 (web UI) and 81 (WebSocket API). Checking port 81 reachability is faster
> than parsing HTML and avoids false positives from other devices on the subnet.

---

## Step 6 — Pattern Control via the Modes Trait

Declare `action.devices.traits.Modes` on a Pixelblaze device during SYNC. Google maps voice
commands like *"set desk strip pattern to fire"* to `action.devices.commands.SetModes`, which
the handler resolves to a `PixelblazeProgram` ID and calls `activatePattern()`.

Programs are stored directly in `google-home-devices.json` alongside each Pixelblaze device —
no separate scene entries needed.

---

### 6.1 — Update `google-home-devices.json`

Add a `programs` array to each Pixelblaze entry. Each entry is a `PixelblazeProgram` — the
`id` comes from `{"listPrograms":true}` via WebSocket (see `PixelblazeClient.listPatterns()`):

```json
"pixelblazes": [
  {
    "id": "pb-desk",
    "name": "Desk Strip",
    "room": "Office",
    "ip": "192.168.86.210",
    "programs": [
      { "id": "GBtBx5PvfSLjKuGcd", "name": "fire"    },
      { "id": "aK9mzXqRtLpWvBnYs", "name": "rainbow" },
      { "id": "mN3pQwYvBcDkFhJrT", "name": "solid"   }
    ]
  }
]
```

The `name` field becomes the Google Home mode setting name — keep it lowercase, single-word
or hyphenated. This is exactly what you say to Google, so name programs naturally
(`fire`, `slow-rainbow`, `white-pulse`) rather than using internal Pixelblaze display names
which may have spaces and version numbers.

---

### 6.2 — Add `programs` to `GoogleHomeDevice`

```java
// In GoogleHomeDevice.java
private List<PixelblazeProgram> programs;

public List<PixelblazeProgram> getPrograms() {
    return programs != null ? programs : Collections.emptyList();
}
```

`PixelblazeProgram` is already mapped in `PixelblazeConfig` — reuse it here directly.

---

### 6.3 — Expose `programs` on `GoogleDevice`

```java
// In GoogleDevice.java
private final List<PixelblazeProgram> programs;

// Update the pixelblaze() factory:
public static GoogleDevice pixelblaze(String id, String name, String room, String ip,
                                       List<PixelblazeProgram> programs) {
    return new GoogleDevice(id, name, room, Type.PIXELBLAZE, ip, null, null, null, programs);
}

public List<PixelblazeProgram> getPrograms() {
    return programs != null ? programs : Collections.emptyList();
}
```

Update `DeviceRegistry.parseJson()` to pass `programs` through:

```java
for (GoogleHomeDevice ghd : config.getPixelblazes()) {
    String ip = (ghd.getIp() != null) ? ghd.getIp() : "";
    GoogleDevice pixelblaze = GoogleDevice.pixelblaze(
        ghd.getId(), ghd.getName(), ghd.getRoom(), ip, ghd.getPrograms()
    );
    devices.add(pixelblaze);
    byId.put(ghd.getId(), pixelblaze);
}
```

---

### 6.4 — Update `FulfillmentHandler.sync()` — Add Modes Trait

In the Pixelblaze loop inside `sync()`, add the `Modes` trait and its `attributes` block
when the device has programs configured:

```java
for (GoogleDevice pb : registry.getPixelblazes()) {
    JsonObject dev = new JsonObject();
    dev.addProperty("id", pb.getId());
    dev.addProperty("type", "action.devices.types.LIGHT");

    JsonArray traits = new JsonArray();
    traits.add("action.devices.traits.OnOff");
    traits.add("action.devices.traits.Brightness");
    traits.add("action.devices.traits.ColorSetting");

    JsonObject attrs = new JsonObject();
    attrs.addProperty("colorModel", "hsv");

    // Add Modes trait only if programs are configured
    if (!pb.getPrograms().isEmpty()) {
        traits.add("action.devices.traits.Modes");

        JsonObject modesDef = new JsonObject();
        modesDef.addProperty("name", "pattern");

        JsonArray nameValues = new JsonArray();
        JsonObject nameVal = new JsonObject();
        JsonArray synonyms = new JsonArray();
        synonyms.add("pattern");
        synonyms.add("animation");
        nameVal.add("name_synonym", synonyms);
        nameVal.addProperty("lang", "en");
        nameValues.add(nameVal);
        modesDef.add("name_values", nameValues);
        modesDef.addProperty("ordered", false);

        // One setting per PixelblazeProgram
        JsonArray settings = new JsonArray();
        for (PixelblazeProgram prog : pb.getPrograms()) {
            JsonObject setting = new JsonObject();
            setting.addProperty("setting_name", prog.getName());
            JsonArray settingValues = new JsonArray();
            JsonObject sv = new JsonObject();
            JsonArray settingSynonyms = new JsonArray();
            settingSynonyms.add(prog.getName());
            sv.add("setting_synonym", settingSynonyms);
            sv.addProperty("lang", "en");
            settingValues.add(sv);
            setting.add("setting_values", settingValues);
            settings.add(setting);
        }
        modesDef.add("settings", settings);

        JsonArray availableModes = new JsonArray();
        availableModes.add(modesDef);
        attrs.add("availableModes", availableModes);
    }

    dev.add("traits", traits);
    dev.add("attributes", attrs);
    // ... name, roomHint, willReportState as before
    deviceArr.add(dev);
}
```

---

### 6.5 — Handle `SetModes` in `dispatchCommand()`

Add this case to the `PIXELBLAZE` branch alongside the existing `OnOff`, `BrightnessAbsolute`,
and `ColorAbsolute` cases:

```java
case "action.devices.commands.SetModes": {
    JsonObject modeSettings = params.getAsJsonObject("updateModeSettings");
    String requestedPattern = modeSettings.get("pattern").getAsString();

    dev.getPrograms().stream()
        .filter(p -> p.getName().equalsIgnoreCase(requestedPattern))
        .findFirst()
        .ifPresentOrElse(
            prog -> {
                pbClient(dev).activatePattern(prog.getId());
                System.out.printf("[Fulfillment] Pixelblaze %s → pattern '%s' (%s)%n",
                    dev.getName(), prog.getName(), prog.getId());
            },
            () -> System.err.printf("[Fulfillment] Unknown pattern '%s' on %s%n",
                requestedPattern, dev.getName())
        );
    break;
}
```

---

### 6.6 — QUERY — Report Active Mode State

Google's QUERY intent expects the current mode value. Reverse-map the active program ID back
to its name for Google's response:

```java
// In the QUERY block for PIXELBLAZE devices:
PixelblazeConfig config = pbClient(dev).getConfiguration();
if (config != null && config.getActiveProgram() != null) {
    String activeProgramId = config.getActiveProgram().getId();
    dev.getPrograms().stream()
        .filter(p -> p.getId().equals(activeProgramId))
        .findFirst()
        .ifPresent(p -> {
            JsonObject currentModes = new JsonObject();
            currentModes.addProperty("pattern", p.getName());
            state.add("currentModes", currentModes);
        });
}
```

---

## Pixelblaze WebSocket API Reference

| JSON Frame (send to `ws://{ip}:81`) | Purpose |
|---|---|
| `{"brightness": 1.0}` | Power on (Pixelblaze has no on/off — use brightness) |
| `{"brightness": 0.0}` | Power off |
| `{"brightness": 0.5}` | Global brightness (0.0–1.0) |
| `{"setVars": {"h": 0.0, "s": 1.0, "v": 1.0}}` | Push HSV vars to active pattern |
| `{"activeProgramId": "<id>"}` | Switch to a pattern by ID |
| `{"listPrograms": true}` | Request pattern list (response is async JSON frame) |
| `{"getVars": true}` | Request current pattern variable values |

> **Pattern IDs are not display names.** Call `listPrograms` once during setup to get the
> ID→name mapping, then store the IDs you need in `google-home-devices.json` under each
> Pixelblaze entry (e.g. `"scenePatternId": "abc123"`).
>
> **Color via `setVars` requires pattern cooperation.** The active Pixelblaze pattern must
> declare `export var h, s, v` and use them to drive LED color. A "Solid HSV" pattern
> doing exactly this should be loaded as the default pattern on each chip.

---

## Google Home Voice Commands

| Voice Command | Google Intent | Pixelblaze WS Frame |
|---|---|---|
| "Turn on the desk strip" | `OnOff {on: true}` | `{"brightness":1.0}` (no on/off — uses brightness) |
| "Turn off the couch strip" | `OnOff {on: false}` | `{"brightness":0.0}` (no on/off — uses brightness) |
| "Set desk strip to 40%" | `BrightnessAbsolute {brightness: 40}` | `{"brightness":0.4}` |
| "Set desk strip to red" | `ColorAbsolute {spectrumHSV: ...}` | `{"setVars":{"h":0,"s":1,"v":1}}` |
| "Set desk strip pattern to fire" | `SetModes {pattern: "fire"}` | `{"activeProgramId":"GBtBx5Pv..."}` |
| "Set desk strip pattern to rainbow" | `SetModes {pattern: "rainbow"}` | `{"activeProgramId":"aK9mzXqR..."}` |
| "Set desk strip animation to solid" | `SetModes {pattern: "solid"}` | `{"activeProgramId":"mN3pQwYv..."}` |

---

## Files Changed

| File | Change |
|---|---|
| `build.gradle` | Add `org.java-websocket:Java-WebSocket:1.5.6` |
| `src/main/java/com/openbeken/google/GoogleDevice.java` | Add `PIXELBLAZE` to `Type` enum; add `pixelblaze()` factory with `programs`; add `getPrograms()` |
| `src/main/java/com/openbeken/google/GoogleHomeDevice.java` | Add `programs` field and `getPrograms()` |
| `src/main/java/com/openbeken/google/PixelblazeClient.java` | **New file** — WebSocket client for Pixelblaze |
| `src/main/java/com/openbeken/google/FulfillmentHandler.java` | Add `PIXELBLAZE` branch in `dispatchCommand()` (OnOff/Brightness/Color/SetModes); add Modes trait in `sync()`; add QUERY mode state; add `pbClients` cache |
| `src/main/java/com/openbeken/google/DeviceRegistry.java` | Parse `"pixelblazes"` array; pass `programs` through to `GoogleDevice` |
| `src/main/java/com/openbeken/discovery/OpenBekenDiscoveryService.java` | Add `probePixelblaze()`, `scanSubnetAll()`, and supporting record types |
| `~/.mqtt/google-home-devices.json` | Add `"pixelblazes"` array with device entries and `programs` per device — **see config note below** |

> **Config file location**: `GoogleHomeMain` loads `google-home-devices.json` from
> `~/.mqtt/google-home-devices.json` at runtime. The copy previously at
> `src/main/resources/google-home-devices.json` is not used and has been removed.
> See `mqtt/GOOGLE_HOME_SETUP.md` for details.
