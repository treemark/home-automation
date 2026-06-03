package com.openbeken.google;

import com.openbeken.model.GoogleHomeDevice;
import com.openbeken.model.GoogleHomeDevicesConfig;
import com.openbeken.model.GoogleHomeScene;
import com.openbeken.util.JsonUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loads and serves the Google Home device registry.
 *
 * Source: mqtt/src/main/resources/google-home-devices.json
 * Only includes LIGHT devices with a valid IP (i.e. already flashed with OpenBeken).
 * Scenes are always included regardless of flash status.
 * 
 * Automatically watches for file changes and reloads when updated.
 */
public class DeviceRegistry {

    private final List<GoogleDevice> devices = new ArrayList<>();
    private final Map<String, GoogleDevice> byId = new LinkedHashMap<>();
    private final AtomicBoolean watching = new AtomicBoolean(false);
    private Thread watchThread;
    private final Path configFilePath;

    public DeviceRegistry() {
        String home = System.getProperty("user.home");
        this.configFilePath = Path.of(home, ".mqtt", "google-home-devices.json");
        ensureConfigFileExists();
        loadFromFile();
        startFileWatcher();
    }

    /**
     * Ensure the config file exists. If not, create it with an empty structure.
     */
    private void ensureConfigFileExists() {
        try {
            if (!Files.exists(configFilePath)) {
                // Create the directory if it doesn't exist
                Files.createDirectories(configFilePath.getParent());
                
                // Create an empty config file with basic structure
                String emptyConfig = """
                    {
                      "_comment": "Google Home device registry - edit rooms/names freely. ip drives the MQTT topic (obk{ip_with_underscores}). Devices with no ip are not yet flashed.",
                      "devices": [],
                      "scenes": []
                    }
                    """;
                Files.writeString(configFilePath, emptyConfig, StandardCharsets.UTF_8);
                System.out.println("[Registry] Created config file: " + configFilePath);
            }
        } catch (IOException e) {
            System.err.println("[Registry] Warning: Failed to create config file: " + e.getMessage());
        }
    }

    private void loadFromFile() {
        try {
            if (!Files.exists(configFilePath)) {
                System.err.println("[Registry] google-home-devices.json not found at " + configFilePath);
                return;
            }
            String json = Files.readString(configFilePath, StandardCharsets.UTF_8);
            parseJson(json);
            System.out.printf("[Registry] Loaded %d devices (%d flashed lights, %d scenes) from %s%n",
                    devices.size(),
                    devices.stream().filter(d -> d.getType() == GoogleDevice.Type.LIGHT && d.isFlashed()).count(),
                    devices.stream().filter(d -> d.getType() == GoogleDevice.Type.SCENE).count(),
                    configFilePath);
        } catch (Exception e) {
            System.err.println("[Registry] Failed to load device registry: " + e.getMessage());
        }
    }

    private void parseJson(String json) {
        GoogleHomeDevicesConfig config = JsonUtil.fromJson(json, GoogleHomeDevicesConfig.class);

        // Parse lights - convert GoogleHomeDevice to GoogleDevice
        for (GoogleHomeDevice ghd : config.getDevices()) {
            String ip = (ghd.getIp() != null) ? ghd.getIp() : "";
            String deviceId = ghd.getDeviceId();  // OpenBeken device ID (stable across IP changes)
            GoogleDevice device = GoogleDevice.light(ghd.getId(), ghd.getName(), ghd.getRoom(), ip, deviceId);
            devices.add(device);
            byId.put(ghd.getId(), device);
        }

        // Parse scenes - convert GoogleHomeScene to GoogleDevice
        for (GoogleHomeScene ghs : config.getScenes()) {
            GoogleDevice scene = GoogleDevice.scene(ghs.getId(), ghs.getName(), ghs.getRoom(),
                    ghs.getAnimation(), ghs.getGroup());
            devices.add(scene);
            byId.put(ghs.getId(), scene);
        }

        // Parse pixelblazes - convert GoogleHomeDevice to GoogleDevice (PIXELBLAZE type)
        if (config.getPixelblazes() != null) {
            for (GoogleHomeDevice ghd : config.getPixelblazes()) {
                String ip = (ghd.getIp() != null) ? ghd.getIp() : "";
                GoogleDevice pixelblaze = GoogleDevice.pixelblaze(ghd.getId(), ghd.getName(), ghd.getRoom(), ip, ghd.getPrograms());
                devices.add(pixelblaze);
                byId.put(ghd.getId(), pixelblaze);
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

    /** Only PIXELBLAZE devices. */
    public List<GoogleDevice> getPixelblazes() {
        return devices.stream()
                .filter(d -> d.getType() == GoogleDevice.Type.PIXELBLAZE)
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
    
    /**
     * Start a background thread to watch for changes to google-home-devices.json
     * and reload automatically when it's updated.
     */
    private void startFileWatcher() {
        if (watching.get()) return;
        
        watching.set(true);
        watchThread = new Thread(() -> {
            try {
                // Watch the ~/.mqtt/ directory
                Path watchDir = configFilePath.getParent();
                if (!Files.exists(watchDir)) {
                    System.err.println("[Registry] Config directory not found, file watching disabled");
                    return;
                }
                
                WatchService watchService = FileSystems.getDefault().newWatchService();
                watchDir.register(watchService, 
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
                
                System.out.println("[Registry] File watcher started for " + configFilePath);
                
                while (watching.get()) {
                    WatchKey key;
                    try {
                        key = watchService.take();
                    } catch (InterruptedException e) {
                        break;
                    }
                    
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }
                        
                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path filename = ev.context();
                        
                        if (filename.toString().equals("google-home-devices.json")) {
                            System.out.println("[Registry] google-home-devices.json changed, reloading...");
                            Thread.sleep(100); // Small delay to ensure file is fully written
                            reloadFromFile();
                        }
                    }
                    
                    boolean valid = key.reset();
                    if (!valid) {
                        break;
                    }
                }
                
                watchService.close();
            } catch (Exception e) {
                System.err.println("[Registry] File watcher error: " + e.getMessage());
            }
        }, "DeviceRegistry-FileWatcher");
        
        watchThread.setDaemon(true);
        watchThread.start();
    }
    
    /**
     * Reload device registry from the file system.
     * This is used by the file watcher when changes are detected.
     */
    private synchronized void reloadFromFile() {
        try {
            if (!Files.exists(configFilePath)) {
                System.err.println("[Registry] File not found: " + configFilePath);
                return;
            }
            
            String json = Files.readString(configFilePath, StandardCharsets.UTF_8);
            
            // Clear existing devices
            devices.clear();
            byId.clear();
            
            // Parse and reload
            parseJson(json);
            
            System.out.printf("[Registry] Reloaded %d devices (%d flashed lights, %d scenes)%n",
                    devices.size(),
                    devices.stream().filter(d -> d.getType() == GoogleDevice.Type.LIGHT && d.isFlashed()).count(),
                    devices.stream().filter(d -> d.getType() == GoogleDevice.Type.SCENE).count());
        } catch (Exception e) {
            System.err.println("[Registry] Failed to reload device registry: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Stop the file watcher thread (for cleanup).
     */
    public void stopWatching() {
        watching.set(false);
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }
}
