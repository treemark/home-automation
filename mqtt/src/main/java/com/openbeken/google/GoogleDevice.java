package com.openbeken.google;

/**
 * Represents a single device or scene for the Google Smart Home fulfillment API.
 * Loaded from google-home-devices.json.
 * 
 * Use static factory methods to construct instances:
 *   GoogleDevice.light(id, name, room, ip, deviceId)
 *   GoogleDevice.scene(id, name, room, animation, group)
 */
public class GoogleDevice {

    public enum Type { LIGHT, SCENE ,PIXELBLAZE}

    private final String id;
    private final String name;
    private final String room;
    private final String ip;           // null/empty if not yet flashed
    private final String deviceId;     // OpenBeken device ID (e.g., obk17811957) - stable, doesn't change with IP
    private final String mqttTopic;    // derived from deviceId if available, else from IP (legacy)
    private final Type type;

    // For scenes only
    private final String animation;    // "rainbow", "warm", "cool"
    private final String group;        // MQTT group or comma-separated device IDs

    /** Private constructor — use static factory methods */
    private GoogleDevice(String id, String name, String room, Type type,
                         String ip, String deviceId,
                         String animation, String group) {
        this.id = id;
        this.name = name;
        this.room = room;
        this.type = type;
        this.ip = ip;
        this.deviceId = deviceId;
        this.animation = animation;
        this.group = group;

        if (type == Type.LIGHT) {
            // Priority 1: Use deviceId if available (stable, doesn't change with IP)
            // Priority 2: Fall back to IP-based topic (legacy, breaks when IP changes)
            if (deviceId != null && !deviceId.isBlank()) {
                this.mqttTopic = deviceId;
            } else if (ip != null && !ip.isBlank()) {
                this.mqttTopic = "obk" + ip.replace('.', '_');
            } else {
                this.mqttTopic = null;
            }
        } else {
            this.mqttTopic = null;
        }
    }

    /** Factory method for LIGHT devices with device ID (stable MQTT topic) */
    public static GoogleDevice light(String id, String name, String room, String ip, String deviceId) {
        return new GoogleDevice(id, name, room, Type.LIGHT, ip, deviceId, null, null);
    }

    /** Factory method for LIGHT devices (backward compatibility — topic falls back to IP) */
    public static GoogleDevice light(String id, String name, String room, String ip) {
        return new GoogleDevice(id, name, room, Type.LIGHT, ip, null, null, null);
    }

    /** Factory method for SCENE devices */
    public static GoogleDevice scene(String id, String name, String room, String animation, String group) {
        return new GoogleDevice(id, name, room, Type.SCENE, null, null, animation, group);
    }

    public static GoogleDevice pixelblaze(String id, String name, String room, String ip) {
        return new GoogleDevice(id, name, room, Type.PIXELBLAZE, ip, null, null, null);
    }

    public String getId()         { return id; }
    public String getName()       { return name; }
    public String getRoom()       { return room; }
    public String getIp()         { return ip; }
    public String getDeviceId()   { return deviceId; }
    public String getMqttTopic()  { return mqttTopic; }
    public Type getType()         { return type; }
    public String getAnimation()  { return animation; }
    public String getGroup()      { return group; }

    /** True if this LIGHT device has a confirmed MQTT topic (i.e. is flashed). */
    public boolean isFlashed() {
        return type == Type.LIGHT && mqttTopic != null;
    }

    @Override
    public String toString() {
        return String.format("GoogleDevice{id='%s', name='%s', room='%s', type=%s, topic='%s'}",
                id, name, room, type, mqttTopic);
    }
}
