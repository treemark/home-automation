package com.openbeken.google;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Google Smart Home fulfillment webhook: POST /fulfillment
 *
 * Handles SYNC / QUERY / EXECUTE / DISCONNECT intents.
 * Maps Google commands to OpenBeken MQTT topics:
 *   OnOff             → cmnd/{topic}/POWER1  = 1 or 0
 *   BrightnessAbsolute→ cmnd/{topic}/Dimmer  = 0-100
 *   ColorAbsolute     → cmnd/{topic}/HsbColor= H,S,B
 *   ActivateScene     → SceneExecutor
 */
public class FulfillmentHandler implements HttpHandler {

    private static final String AGENT_USER_ID = "homeuser1";
    private static final Gson GSON = new Gson();

    private final DeviceRegistry registry;
    private final String authToken;
    private final MqttClient mqtt;
    private final SceneExecutor sceneExecutor;
    private final Map<String, JsonObject> deviceStates = new HashMap<>();

    public FulfillmentHandler(DeviceRegistry registry, String authToken, MqttClient mqtt) {
        this.registry      = registry;
        this.authToken     = authToken;
        this.mqtt          = mqtt;
        this.sceneExecutor = new SceneExecutor(mqtt);
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // Auth check
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (!("Bearer " + authToken).equals(auth)) {
            send(ex, 401, "{\"error\":\"unauthorized\"}"); return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        System.out.println("[Fulfillment] ← " + body.substring(0, Math.min(200, body.length())));

        JsonObject req       = JsonParser.parseString(body).getAsJsonObject();
        String     requestId = req.get("requestId").getAsString();
        JsonObject input     = req.getAsJsonArray("inputs").get(0).getAsJsonObject();
        String     intent    = input.get("intent").getAsString();

        JsonObject payload = switch (intent) {
            case "action.devices.SYNC"       -> sync();
            case "action.devices.QUERY"      -> query(input.getAsJsonObject("payload"));
            case "action.devices.EXECUTE"    -> execute(input.getAsJsonObject("payload"));
            case "action.devices.DISCONNECT" -> new JsonObject();
            default -> new JsonObject();
        };

        JsonObject response = new JsonObject();
        response.addProperty("requestId", requestId);
        response.add("payload", payload);

        String out = GSON.toJson(response);
        System.out.println("[Fulfillment] → " + out.substring(0, Math.min(200, out.length())));
        send(ex, 200, out);
    }

    // ── SYNC ─────────────────────────────────────────────────────────────────

    private JsonObject sync() {
        JsonArray deviceArr = new JsonArray();
        // Flashed lights
        for (GoogleDevice d : registry.getFlashedLights()) {
            JsonObject dev = new JsonObject();
            dev.addProperty("id", d.getId());
            dev.addProperty("type", "action.devices.types.LIGHT");
            JsonArray traits = new JsonArray();
            traits.add("action.devices.traits.OnOff");
            traits.add("action.devices.traits.Brightness");
            traits.add("action.devices.traits.ColorSetting");
            dev.add("traits", traits);
            JsonObject name = new JsonObject();
            name.addProperty("name", d.getName());
            dev.add("name", name);
            dev.addProperty("willReportState", false);
            dev.addProperty("roomHint", d.getRoom());
            JsonObject attrs = new JsonObject();
            attrs.addProperty("colorModel", "hsv");
            dev.add("attributes", attrs);
            deviceArr.add(dev);
        }
        // Scenes
        for (GoogleDevice s : registry.getScenes()) {
            JsonObject dev = new JsonObject();
            dev.addProperty("id", s.getId());
            dev.addProperty("type", "action.devices.types.SCENE");
            JsonArray traits = new JsonArray();
            traits.add("action.devices.traits.Scene");
            dev.add("traits", traits);
            JsonObject name = new JsonObject();
            name.addProperty("name", s.getName());
            dev.add("name", name);
            dev.addProperty("willReportState", false);
            dev.addProperty("roomHint", s.getRoom());
            JsonObject attrs = new JsonObject();
            attrs.addProperty("sceneReversible", false);
            dev.add("attributes", attrs);
            deviceArr.add(dev);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("agentUserId", AGENT_USER_ID);
        payload.add("devices", deviceArr);
        return payload;
    }

    // ── QUERY ────────────────────────────────────────────────────────────────

    private JsonObject query(JsonObject payload) {
        JsonObject devices = new JsonObject();
        for (JsonElement el : payload.getAsJsonArray("devices")) {
            String id = el.getAsJsonObject().get("id").getAsString();
            JsonObject state = deviceStates.getOrDefault(id, defaultState(id));
            devices.add(id, state);
        }
        JsonObject result = new JsonObject();
        result.add("devices", devices);
        return result;
    }

    // ── EXECUTE ──────────────────────────────────────────────────────────────

    private JsonObject execute(JsonObject payload) {
        JsonArray resultCmds = new JsonArray();
        for (JsonElement cmdEl : payload.getAsJsonArray("commands")) {
            JsonObject cmd         = cmdEl.getAsJsonObject();
            JsonArray  targetDevs  = cmd.getAsJsonArray("devices");
            JsonArray  executions  = cmd.getAsJsonArray("execution");

            List<String> succeeded = new ArrayList<>();
            for (JsonElement execEl : executions) {
                JsonObject exec    = execEl.getAsJsonObject();
                String     command = exec.get("command").getAsString();
                JsonObject params  = exec.has("params") ? exec.getAsJsonObject("params") : new JsonObject();

                for (JsonElement devEl : targetDevs) {
                    String devId = devEl.getAsJsonObject().get("id").getAsString();
                    boolean ok   = dispatchCommand(devId, command, params);
                    if (ok) succeeded.add(devId);
                }
            }
            if (!succeeded.isEmpty()) {
                JsonObject res = new JsonObject();
                JsonArray ids = new JsonArray();
                succeeded.forEach(ids::add);
                res.add("ids", ids);
                res.addProperty("status", "SUCCESS");
                resultCmds.add(res);
            }
        }
        JsonObject result = new JsonObject();
        result.add("commands", resultCmds);
        return result;
    }

    // ── Command → MQTT dispatch ───────────────────────────────────────────────

    private boolean dispatchCommand(String devId, String command, JsonObject params) {
        GoogleDevice dev = registry.findById(devId);
        if (dev == null) { System.err.println("[Fulfillment] Unknown device: " + devId); return false; }

        try {
            if (dev.getType() == GoogleDevice.Type.SCENE) {
                sceneExecutor.activate(dev.getAnimation(), dev.getGroup());
                return true;
            }
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
            System.err.println("[Fulfillment] Error dispatching to " + devId + ": " + e.getMessage());
            return false;
        }
    }

    private void pub(String topic, String payload) throws MqttException {
        if (mqtt == null || !mqtt.isConnected()) return;
        MqttMessage msg = new MqttMessage(payload.getBytes());
        msg.setQos(0);
        mqtt.publish(topic, msg);
        System.out.println("[MQTT] " + topic + " = " + payload);
    }

    private JsonObject defaultState(String devId) {
        JsonObject s = new JsonObject();
        s.addProperty("on", true);
        s.addProperty("brightness", 100);
        s.addProperty("online", registry.findById(devId) != null && registry.findById(devId).isFlashed());
        return s;
    }

    private void updateState(String devId, String key, Object value) {
        JsonObject s = deviceStates.computeIfAbsent(devId, k -> defaultState(k));
        if (value instanceof Boolean b) s.addProperty(key, b);
        else if (value instanceof Integer i) s.addProperty(key, i);
    }

    private void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
