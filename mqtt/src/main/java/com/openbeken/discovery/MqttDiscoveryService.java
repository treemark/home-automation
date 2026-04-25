package com.openbeken.discovery;

import com.openbeken.model.OpenBekenDevice;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MQTT-based discovery for OpenBeken devices.
 * 
 * Connects to the MQTT broker and subscribes to wildcard topics to detect
 * OpenBeken devices that are actively publishing. OpenBeken devices typically
 * publish status updates on topics like:
 *   - stat/{deviceId}/POWER1
 *   - tele/{deviceId}/STATE
 *   - cmnd/{deviceId}/...
 *   - {deviceId}/connected
 */
public class MqttDiscoveryService implements MqttCallback {

    private static final Pattern OBK_TOPIC_PATTERN = Pattern.compile(
            "(?:stat|tele|cmnd)/([^/]+)/.*|([^/]+)/(?:connected|status|POWER|Dimmer).*"
    );

    private MqttClient client;
    private final Map<String, OpenBekenDevice> mqttDevices = new ConcurrentHashMap<>();
    private final List<MqttMessageListener> messageListeners = new ArrayList<>();
    private boolean connected = false;
    private String brokerUrl;

    /**
     * Connect to an MQTT broker and start listening for device messages.
     */
    public void connect(String brokerUrl) throws MqttException {
        this.brokerUrl = brokerUrl;
        client = new MqttClient(brokerUrl, "obk-discovery-" + UUID.randomUUID().toString().substring(0, 8),
                new MemoryPersistence());

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(60);

        client.setCallback(this);
        client.connect(options);
        connected = true;
    }

    /**
     * Subscribe to wildcard topics to discover OpenBeken devices.
     */
    public void startDiscovery() throws MqttException {
        if (!connected) {
            throw new IllegalStateException("Not connected to MQTT broker");
        }

        // Subscribe to common OpenBeken/Tasmota topic patterns
        client.subscribe("#", 0);  // Subscribe to everything to catch all device traffic
    }

    /**
     * Stop discovery and disconnect.
     */
    public void stopDiscovery() {
        if (client != null && client.isConnected()) {
            try {
                client.unsubscribe("#");
            } catch (MqttException e) {
                // ignore
            }
        }
    }

    /**
     * Disconnect from the broker.
     */
    public void disconnect() {
        if (client != null && client.isConnected()) {
            try {
                client.disconnect();
            } catch (MqttException e) {
                System.err.println("Error disconnecting: " + e.getMessage());
            }
        }
        connected = false;
    }

    /**
     * Publish a command to a device.
     */
    public void publish(String topic, String payload) throws MqttException {
        if (!connected) {
            throw new IllegalStateException("Not connected to MQTT broker");
        }
        MqttMessage message = new MqttMessage(payload.getBytes());
        message.setQos(0);
        client.publish(topic, message);
    }

    /**
     * Send a command to an OpenBeken device.
     */
    public void sendCommand(String deviceId, String command, String value) throws MqttException {
        String topic = "cmnd/" + deviceId + "/" + command;
        publish(topic, value);
    }

    @Override
    public void connectionLost(Throwable cause) {
        System.err.println("MQTT connection lost: " + cause.getMessage());
        connected = false;
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        String payload = new String(message.getPayload());

        // Try to identify device from topic
        String deviceId = extractDeviceId(topic);
        if (deviceId != null && !deviceId.isEmpty()) {
            OpenBekenDevice device = mqttDevices.computeIfAbsent(deviceId, id -> {
                OpenBekenDevice d = new OpenBekenDevice();
                d.setDeviceId(id);
                d.setDiscoveryMethod("mqtt");
                d.setOnline(true);
                return d;
            });

            device.setLastSeen(Instant.now());
            device.setOnline(true);

            // Parse state from known topics
            if (topic.contains("POWER") || topic.contains("power")) {
                device.setPowerState(payload.trim());
            }
            if (topic.contains("Dimmer") || topic.contains("dimmer")) {
                try {
                    device.setDimmer(Integer.parseInt(payload.trim()));
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }

        // Notify listeners
        for (MqttMessageListener listener : messageListeners) {
            listener.onMessage(topic, payload);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // fire-and-forget
    }

    /**
     * Extract device ID from an MQTT topic.
     */
    private String extractDeviceId(String topic) {
        // Pattern: stat/DEVICE_ID/... or tele/DEVICE_ID/... or cmnd/DEVICE_ID/...
        Matcher m = OBK_TOPIC_PATTERN.matcher(topic);
        if (m.matches()) {
            String id = m.group(1);
            if (id != null) return id;
            return m.group(2);
        }

        // Fallback: split by / and take first or second segment
        String[] parts = topic.split("/");
        if (parts.length >= 2) {
            // Skip known prefixes
            if (parts[0].equals("stat") || parts[0].equals("tele") || parts[0].equals("cmnd")) {
                return parts[1];
            }
            return parts[0];
        }
        return null;
    }

    public Map<String, OpenBekenDevice> getDiscoveredDevices() {
        return Collections.unmodifiableMap(mqttDevices);
    }

    public boolean isConnected() {
        return connected && client != null && client.isConnected();
    }

    public String getBrokerUrl() {
        return brokerUrl;
    }

    public void addMessageListener(MqttMessageListener listener) {
        messageListeners.add(listener);
    }

    /**
     * Listener for raw MQTT messages.
     */
    public interface MqttMessageListener {
        void onMessage(String topic, String payload);
    }
}
