package com.openbeken.animation;

import com.openbeken.discovery.MqttDiscoveryService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Color rotation animation engine for OpenBeken bulbs via MQTT.
 *
 * Each bulb is offset evenly around the color wheel (120° apart for 3 bulbs),
 * and all rotate through the full HSB hue spectrum simultaneously.
 * Uses QoS 0 fire-and-forget MQTT for maximum update speed.
 *
 * Usage:
 *   ColorRotationAnimation anim = new ColorRotationAnimation(mqttService);
 *   anim.start(List.of("obk1", "obk2", "obk3"), 5, 10, 50, 100, 50);
 *   // ... later ...
 *   anim.stopAll();
 */
public class ColorRotationAnimation {

    private final MqttDiscoveryService mqttService;
    private final Map<String, AtomicBoolean> runningAnimations = new ConcurrentHashMap<>();

    public ColorRotationAnimation(MqttDiscoveryService mqttService) {
        this.mqttService = mqttService;
    }

    /**
     * Start a color rotation animation across the given device IDs.
     *
     * @param deviceIds   list of MQTT device IDs (e.g. ["obk17811957", "obk2", "obk3"])
     * @param cycles      number of full 360° hue rotations (0 = infinite)
     * @param hueStep     degrees to advance per frame (e.g. 10)
     * @param delayMs     milliseconds between frames (e.g. 50)
     * @param saturation  HSB saturation 0-100 (e.g. 100)
     * @param brightness  HSB brightness 0-100 (e.g. 50)
     */
    public void start(List<String> deviceIds, int cycles, int hueStep,
                      long delayMs, int saturation, int brightness) {
        String animKey = "color-rotation-" + System.currentTimeMillis();
        AtomicBoolean running = new AtomicBoolean(true);
        runningAnimations.put(animKey, running);

        int numBulbs = deviceIds.size();
        int hueSpacing = 360 / Math.max(numBulbs, 1);

        Thread animThread = new Thread(() -> {
            try {
                // Turn on all bulbs first
                for (String id : deviceIds) {
                    mqttService.sendCommand(id, "POWER1", "1");
                }
                Thread.sleep(200);

                int totalSteps = (cycles == 0) ? Integer.MAX_VALUE : cycles * (360 / Math.max(hueStep, 1));
                int step = 0;

                System.out.println();
                while (running.get() && step < totalSteps) {
                    int baseHue = (step * hueStep) % 360;

                    for (int i = 0; i < numBulbs; i++) {
                        int hue = (baseHue + i * hueSpacing) % 360;
                        String hsb = hue + "," + saturation + "," + brightness;
                        mqttService.sendCommand(deviceIds.get(i), "HsbColor", hsb);
                    }

                    // Print a compact progress line every 36 steps (~1 full rotation at step=10)
                    if (step % 36 == 0 && cycles > 0) {
                        int currentCycle = step / (360 / Math.max(hueStep, 1)) + 1;
                        System.out.printf("  🌈 Cycle %d/%d  Hue: %d°\r", currentCycle, cycles, baseHue);
                    }

                    step++;
                    Thread.sleep(delayMs);
                }
                System.out.println();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("✗ Animation error: " + e.getMessage());
            } finally {
                runningAnimations.remove(animKey);
            }
        }, animKey);

        animThread.setDaemon(true);
        animThread.start();
    }

    /**
     * Start with sensible defaults: 3 cycles, 10° step, 50ms delay, 100% sat, 50% brightness.
     */
    public void startDefault(List<String> deviceIds) {
        start(deviceIds, 3, 10, 50, 100, 50);
    }

    /**
     * Stop all running animations.
     */
    public void stopAll() {
        runningAnimations.values().forEach(r -> r.set(false));
        runningAnimations.clear();
    }

    /**
     * Check if any animation is currently running.
     */
    public boolean isRunning() {
        return !runningAnimations.isEmpty();
    }

    /**
     * Get the set of running animation keys.
     */
    public Set<String> getRunningKeys() {
        return runningAnimations.keySet();
    }
}
