package com.openbeken.google;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads and serves the Google Home device registry.
 *
 * Source: mqtt/src/main/resources/google-home-devices.json
 * Only includes LIGHT devices with a valid IP (i.e. already flashed with OpenBeken).
 * Scenes are always included regardless of flash status.
 */
public class DeviceRegistry {

    private final List<GoogleDevice> devices = new ArrayList<>();
    private final Map<String, GoogleDevice> byId = new LinkedHashMap<>();

    public DeviceRegistry() {
        loadFromClasspath();
    }

    private void loadFromClasspath() {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("google-home-devices.json")) {
            if (in == null) {
                System.err.println("[Registry] google-home-devices.json not found on classpath");
                return;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            parseJson(json);
            System.out.printf("[Registry] Loaded %d devices (%d flashed lights, %d scenes)%n",
                    devices.size(),
                    devices.stream().filter(d -> d.getType() == GoogleDevice.Type.LIGHT && d.isFlashed()).count(),
                    devices.stream().filter(d -> d.getType() == GoogleDevice.Type.SCENE).count());
        } catch (Exception e) {
            System.err.println("[Registry] Failed to load device registry: " + e.getMessage());
        }
    }

    private void parseJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        // Parse lights
        JsonArray devArr = root.getAsJsonArray("devices");
        for (JsonElement el : devArr) {
            JsonObject obj = el.getAsJsonObject();
            String id   = obj.get("id").getAsString();
            String name = obj.get("name").getAsString();
            String room = obj.get("room").getAsString();
            String ip   = obj.has("ip") ? obj.get("ip").getAsString() : "";

            GoogleDevice device = new GoogleDevice(id, name, room, ip);
            devices.add(device);
            byId.put(id, device);
        }

        // Parse scenes
        if (root.has("scenes")) {
            JsonArray scArr = root.getAsJsonArray("scenes");
            for (JsonElement el : scArr) {
                JsonObject obj = el.getAsJsonObject();
                String id        = obj.get("id").getAsString();
                String name      = obj.get("name").getAsString();
                String room      = obj.get("room").getAsString();
                String animation = obj.get("animation").getAsString();
                String group     = obj.get("group").getAsString();

                GoogleDevice scene = new GoogleDevice(id, name, room, animation, group);
                devices.add(scene);
                byId.put(id, scene);
            }
        }
    }

    /** All devices (lights + scenes). */
    public List<GoogleDevice> getAllDevices() {
        return Collections.unmodifiableList(devices);
    }

    /** Only LIGHT devices that have been flashed (have an MQTT topic). */
    public List<GoogleDevice> getFlashedLights() {
        return devices.stream()
                .filter(d -> d.getType() == GoogleDevice.Type.LIGHT && d.isFlashed())
                .toList();
    }

    /** Only SCENE devices. */
    public List<GoogleDevice> getScenes() {
        return devices.stream()
                .filter(d -> d.getType() == GoogleDevice.Type.SCENE)
                .toList();
    }

    /** Lookup by device ID (K1, scene-rainbow-all, etc.). */
    public GoogleDevice findById(String id) {
        return byId.get(id);
    }

    /** Count of all registered devices (including unflashed lights and scenes). */
    public int size() {
        return devices.size();
    }
}
