package com.openbeken.google;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes named animation scenes over MQTT.
 *
 * Scenes map to either:
 *   - A simple static color command broadcast to a group topic
 *   - A long-running animation thread (rainbow, chase, etc.)
 *
 * Invoked by FulfillmentHandler when Google sends ActivateScene.
 * Can also stop currently running animations via stopScene().
 */
public class SceneExecutor {

    private final MqttClient mqtt;
    private final Map<String, Thread> activeAnimations = new ConcurrentHashMap<>();

    public SceneExecutor(MqttClient mqtt) {
        this.mqtt = mqtt;
    }

    /**
     * Activate a scene by animation type and MQTT group.
     * @param animation  "rainbow", "warm", "cool"
     * @param group      MQTT topic suffix — "animations", "K1,K2,K3", etc.
     */
    public void activate(String animation, String group) {
        stopScene(group);  // stop previous animation on same group
        switch (animation.toLowerCase()) {
            case "rainbow" -> startRainbow(group);
            case "warm"    -> publishStatic(group, "30,20,80");   // warm white HSB
            case "cool"    -> publishStatic(group, "200,15,90");  // cool blue-white HSB
            default        -> System.err.println("[Scene] Unknown animation: " + animation);
        }
    }

    /** Stop a running animation on a group (if any). */
    public void stopScene(String group) {
        Thread t = activeAnimations.remove(group);
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    /** Stop all running animations. */
    public void stopAll() {
        activeAnimations.keySet().forEach(this::stopScene);
    }

    // ── Rainbow Animation ────────────────────────────────────────────────────

    private void startRainbow(String group) {
        Thread t = new Thread(() -> {
            // group may be "animations" (broadcast) or comma-list of device ids
            String mqttGroup = group.contains(",")
                    ? "animations"   // use group topic for multi-device
                    : group;

            int hue = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    publish("cmnd/" + mqttGroup + "/HsbColor", hue + ",100,60");
                    hue = (hue + 3) % 360;
                    Thread.sleep(50);  // ~20 Hz
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (MqttException e) {
                    System.err.println("[Scene] MQTT error in rainbow: " + e.getMessage());
                    break;
                }
            }
        }, "rainbow-" + group);
        t.setDaemon(true);
        t.start();
        activeAnimations.put(group, t);
        System.out.println("[Scene] Rainbow started on group: " + group);
    }

    // ── Static Color ─────────────────────────────────────────────────────────

    private void publishStatic(String group, String hsbColor) {
        try {
            String mqttGroup = group.contains(",") ? "animations" : group;
            publish("cmnd/" + mqttGroup + "/HsbColor", hsbColor);
            System.out.println("[Scene] Static color " + hsbColor + " → " + mqttGroup);
        } catch (MqttException e) {
            System.err.println("[Scene] MQTT publish error: " + e.getMessage());
        }
    }

    private void publish(String topic, String payload) throws MqttException {
        if (mqtt == null || !mqtt.isConnected()) return;
        MqttMessage msg = new MqttMessage(payload.getBytes());
        msg.setQos(0);
        mqtt.publish(topic, msg);
    }

    public boolean hasActiveAnimation(String group) {
        Thread t = activeAnimations.get(group);
        return t != null && t.isAlive();
    }
}
