package com.openbeken.google;

/**
 * Represents a single device or scene for the Google Smart Home fulfillment API.
 * Loaded from google-home-devices.json.
 */
public class GoogleDevice {

    public enum Type { LIGHT, SCENE }

    private final String id;
    private final String name;
    private final String room;
    private final String ip;           // null/empty if not yet flashed
    private final String mqttTopic;    // derived: obk{ip_underscored}
    private final Type type;

    // For scenes only
    private final String animation;    // "rainbow", "warm", "cool"
    private final String group;        // MQTT group or comma-separated device IDs

    /** Constructor for LIGHT devices */
    public GoogleDevice(String id, String name, String room, String ip) {
        this.id = id;
        this.name = name;
        this.room = room;
        this.ip = ip;
        this.type = Type.LIGHT;
        this.animation = null;
        this.group = null;
        this.mqttTopic = (ip != null && !ip.isBlank())
                ? "obk" + ip.replace('.', '_')
                : null;
    }

    /** Constructor for SCENE devices */
    public GoogleDevice(String id, String name, String room, String animation, String group) {
        this.id = id;
        this.name = name;
        this.room = room;
        this.ip = null;
        this.type = Type.SCENE;
        this.animation = animation;
        this.group = group;
        this.mqttTopic = null;
    }

    public String getId()         { return id; }
    public String getName()       { return name; }
    public String getRoom()       { return room; }
    public String getIp()         { return ip; }
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
