package com.appliedvillainy.hue.service;

import com.appliedvillainy.hue.config.WiFiProvisioningConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Service that automatically discovers and provisions OpenBeken devices.
 * 
 * This service:
 * 1. Periodically scans for WiFi networks matching "OpenBeken_XXXXXX" pattern
 * 2. Connects to discovered devices
 * 3. Configures them with your WiFi credentials
 * 4. Configures them to connect to the local MQTT broker
 * 5. Disconnects and allows device to join your network
 */
//@Service
//@ConditionalOnProperty(name = "wifi.provisioning.enabled", havingValue = "true")
public class WiFiProvisioningService {

    private static final Logger logger = LoggerFactory.getLogger(WiFiProvisioningService.class);

    private final WiFiProvisioningConfig config;
    private final HttpClient httpClient;
    private final Set<String> provisionedDevices = ConcurrentHashMap.newKeySet();
    private final Pattern ssidPattern;
    
    private volatile boolean running = false;

    public WiFiProvisioningService(WiFiProvisioningConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.ssidPattern = Pattern.compile(config.getTargetSsidPattern());
    }

    @PostConstruct
    public void start() {
        running = true;
        logger.info("===========================================");
        logger.info("WiFi Provisioning Service Started");
        logger.info("===========================================");
        logger.info("Target SSID Pattern: {}", config.getTargetSsidPattern());
        logger.info("Scan Interval: {} seconds", config.getScanIntervalSeconds());
        logger.info("Target WiFi: {}", config.getWifiSsid());
        logger.info("MQTT Broker: {}:{}", config.getMqttHost(), config.getMqttPort());
        logger.info("===========================================");
    }

    @PreDestroy
    public void stop() {
        running = false;
        logger.info("WiFi Provisioning Service stopped");
    }

    /**
     * Periodically scan for OpenBeken devices.
     * Runs every N seconds as configured.
     */
//    @Scheduled(fixedDelayString = "${wifi.provisioning.scan-interval-seconds:60}000",
//               initialDelay = 5000)
//    @Async
    public void scanForDevices() {
//        if (!running) {
//            return;
//        }

        try {
            logger.info("Scanning for OpenBeken devices...");
            List<String> availableNetworks = scanWiFiNetworks();
            
            for (String ssid : availableNetworks) {
                logger.info("Found {}",ssid);
                if (ssidPattern.matcher(ssid).matches()) {
                    if (!provisionedDevices.contains(ssid)) {
                        logger.info("Found new OpenBeken device: {}", ssid);
                        provisionDevice(ssid);
                    } else {
                        logger.debug("Device {} already provisioned, skipping", ssid);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error during device scan: {}", e.getMessage(), e);
        }
    }

    /**
     * Scan for available WiFi networks.
     * Uses platform-specific commands (macOS: airport, Linux: nmcli/iwlist, Windows: netsh)
     * 
     * NOTE: On macOS, this requires running with sudo/root privileges.
     */
    private List<String> scanWiFiNetworks() throws Exception {
        List<String> networks = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();

        try {
            ProcessBuilder pb;
            
            if (os.contains("mac")) {
                // macOS using airport utility (requires root privileges)
                pb = new ProcessBuilder(
                    "/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport",
                    "-s"
                );
            } else if (os.contains("linux")) {
                // Linux using nmcli (NetworkManager)
                pb = new ProcessBuilder("nmcli", "-t", "-f", "SSID", "dev", "wifi");
            } else if (os.contains("win")) {
                // Windows using netsh
                pb = new ProcessBuilder("netsh", "wlan", "show", "networks");
            } else {
                logger.warn("Unsupported operating system for WiFi scanning: {}", os);
                return networks;
            }

            Process process = pb.start();
            
            // Read stdout
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    
                    // Parse SSIDs based on OS
                    if (os.contains("mac") && line.length() > 0 && !line.startsWith("SSID")) {
                        // macOS format: SSID BSSID RSSI CHANNEL HT CC SECURITY
                        String[] parts = line.split("\\s+");
                        if (parts.length > 0) {
                            networks.add(parts[0]);
                        }
                    } else if (os.contains("linux")) {
                        // Linux nmcli format: SSID (one per line)
                        if (line.length() > 0 && !line.equals("--")) {
                            networks.add(line);
                        }
                    } else if (os.contains("win") && line.startsWith("SSID")) {
                        // Windows format: SSID x : NetworkName
                        String[] parts = line.split(":");
                        if (parts.length > 1) {
                            networks.add(parts[1].trim());
                        }
                    }
                }
            }
            
            // Read stderr for error messages
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String errLine;
                StringBuilder errors = new StringBuilder();
                while ((errLine = errReader.readLine()) != null) {
                    errors.append(errLine).append("\n");
                }
                if (errors.length() > 0) {
                    logger.warn("WiFi scan errors: {}", errors.toString().trim());
                }
            }
            
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                if (os.contains("mac")) {
                    logger.error("WiFi scan failed with exit code {}. On macOS, the 'airport' command requires sudo/root privileges.", exitCode);
                    logger.error("Please run this application with: sudo java -jar huebridge.jar ...");
                } else {
                    logger.error("WiFi scan failed with exit code {}", exitCode);
                }
            }
            
            if (networks.isEmpty() && os.contains("mac")) {
                logger.warn("No networks found. On macOS, make sure you're running with sudo privileges:");
                logger.warn("  sudo java -jar philips/build/libs/huebridge-0.0.1-SNAPSHOT.jar --spring.profiles.active=cli");
            }
            
            logger.debug("Found {} networks", networks.size());
            
        } catch (Exception e) {
            logger.error("Failed to scan WiFi networks: {}", e.getMessage());
            throw e;
        }

        return networks;
    }

    /**
     * Provision a specific device by SSID (skips scanning).
     * Assumes you're already connected to the device's WiFi network.
     * @param ssid The SSID of the device to provision
     * @return true if successful, false otherwise
     */
    public boolean provisionSpecificDevice(String ssid) {
        logger.info("========================================");
        logger.info("Provisioning specific device: {}", ssid);
        logger.info("Assuming already connected to device WiFi");
        logger.info("========================================");

        try {
            // Get local IP for MQTT broker
            String mqttHost = config.getMqttHost();
            if ("auto".equalsIgnoreCase(mqttHost)) {
                mqttHost = getLocalIPAddress();
                logger.info("Auto-detected local IP: {}", mqttHost);
            }

            // Configure the device
            logger.info("Configuring device...");
            boolean configured = configureOpenBekenDevice(
                config.getWifiSsid(),
                config.getWifiPassword(),
                mqttHost,
                config.getMqttPort(),
                config.getMqttTopic()
            );

            if (configured) {
                logger.info("✓ Successfully configured device: {}", ssid);
                provisionedDevices.add(ssid);
                
                logger.info("========================================");
                logger.info("Device {} should now reboot and connect!", ssid);
                logger.info("It will publish to MQTT broker at {}:{}", mqttHost, config.getMqttPort());
                logger.info("========================================");
                return true;
            } else {
                logger.error("✗ Failed to configure device: {}", ssid);
                return false;
            }

        } catch (Exception e) {
            logger.error("Error provisioning device {}: {}", ssid, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Provision a specific OpenBeken device (with auto-connect).
     */
    public void provisionDevice(String ssid) {
        logger.info("========================================");
        logger.info("Provisioning device: {}", ssid);
        logger.info("========================================");

        try {
            // Step 1: Connect to the device's AP
            logger.info("Step 1: Connecting to device AP...");
            if (!connectToNetwork(ssid, config.getDeviceApPassword())) {
                logger.error("Failed to connect to device AP: {}", ssid);
                return;
            }
            
            // Wait for connection to establish
            Thread.sleep(5000);

            // Step 2: Get local IP for MQTT broker
            String mqttHost = config.getMqttHost();
            if ("auto".equalsIgnoreCase(mqttHost)) {
                mqttHost = getLocalIPAddress();
                logger.info("Auto-detected local IP: {}", mqttHost);
            }

            // Step 3: Configure the device
            logger.info("Step 2: Configuring device...");
            boolean configured = configureOpenBekenDevice(
                config.getWifiSsid(),
                config.getWifiPassword(),
                mqttHost,
                config.getMqttPort(),
                config.getMqttTopic()
            );

            if (configured) {
                logger.info("✓ Successfully configured device: {}", ssid);
                provisionedDevices.add(ssid);
                
                // Step 4: Disconnect and let device connect to target network
                logger.info("Step 3: Disconnecting from device AP...");
                disconnectFromCurrentNetwork();
                
                // Wait for device to reboot and connect
                Thread.sleep(10000);
                
                logger.info("========================================");
                logger.info("Device {} should now be connected!", ssid);
                logger.info("It will publish to MQTT broker at {}:{}", mqttHost, config.getMqttPort());
                logger.info("========================================");
            } else {
                logger.error("✗ Failed to configure device: {}", ssid);
            }

        } catch (Exception e) {
            logger.error("Error provisioning device {}: {}", ssid, e.getMessage(), e);
        }
    }

    /**
     * Connect to a WiFi network.
     */
    private boolean connectToNetwork(String ssid, String password) throws Exception {
        String os = System.getProperty("os.name").toLowerCase();
        
        ProcessBuilder pb;
        if (os.contains("mac")) {
            // macOS using networksetup
            pb = new ProcessBuilder(
                "networksetup", "-setairportnetwork",
                getWiFiInterface(), ssid, password
            );
        } else if (os.contains("linux")) {
            // Linux using nmcli
            if (password.isEmpty()) {
                pb = new ProcessBuilder("nmcli", "dev", "wifi", "connect", ssid);
            } else {
                pb = new ProcessBuilder("nmcli", "dev", "wifi", "connect", ssid, "password", password);
            }
        } else {
            logger.warn("Automatic WiFi connection not supported on this OS");
            return false;
        }

        Process process = pb.start();
        int exitCode = process.waitFor();
        
        return exitCode == 0;
    }

    /**
     * Disconnect from current WiFi network.
     */
    private void disconnectFromCurrentNetwork() throws Exception {
        String os = System.getProperty("os.name").toLowerCase();
        
        ProcessBuilder pb;
        if (os.contains("mac")) {
            pb = new ProcessBuilder(
                "networksetup", "-setairportpower", getWiFiInterface(), "off"
            );
            pb.start().waitFor();
            Thread.sleep(1000);
            pb = new ProcessBuilder(
                "networksetup", "-setairportpower", getWiFiInterface(), "on"
            );
        } else if (os.contains("linux")) {
            pb = new ProcessBuilder("nmcli", "dev", "disconnect", "wlan0");
        } else {
            logger.warn("Disconnect not supported on this OS");
            return;
        }

        pb.start().waitFor();
    }

    /**
     * Get WiFi interface name (macOS).
     */
    private String getWiFiInterface() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("networksetup", "-listallhardwareports");
        Process process = pb.start();
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            boolean nextIsWiFi = false;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Wi-Fi") || line.contains("AirPort")) {
                    nextIsWiFi = true;
                } else if (nextIsWiFi && line.startsWith("Device:")) {
                    return line.split(":")[1].trim();
                }
            }
        }
        
        return "en0";  // Default
    }

    /**
     * Configure OpenBeken device via HTTP API.
     */
    private boolean configureOpenBekenDevice(String ssid, String password,
                                             String mqttHost, int mqttPort, String mqttTopic) {
        try {
            // OpenBeken typically runs a web server at 192.168.4.1
            String deviceIp = "192.168.4.1";
            
            // Configure WiFi
            String wifiUrl = String.format(
                "http://%s/cfg?ssid=%s&password=%s",
                deviceIp,
                urlEncode(ssid),
                urlEncode(password)
            );
            
            logger.info("Configuring WiFi...");
            HttpRequest wifiRequest = HttpRequest.newBuilder()
                    .uri(URI.create(wifiUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            
            HttpResponse<String> wifiResponse = httpClient.send(
                wifiRequest,
                HttpResponse.BodyHandlers.ofString()
            );
            
            if (wifiResponse.statusCode() != 200) {
                logger.error("WiFi configuration failed: HTTP {}", wifiResponse.statusCode());
                return false;
            }

            // Configure MQTT
            String mqttUrl = String.format(
                "http://%s/cfg_mqtt?host=%s&port=%d&topic=%s",
                deviceIp,
                mqttHost,
                mqttPort,
                mqttTopic
            );
            
            logger.info("Configuring MQTT...");
            HttpRequest mqttRequest = HttpRequest.newBuilder()
                    .uri(URI.create(mqttUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            
            HttpResponse<String> mqttResponse = httpClient.send(
                mqttRequest,
                HttpResponse.BodyHandlers.ofString()
            );
            
            if (mqttResponse.statusCode() != 200) {
                logger.error("MQTT configuration failed: HTTP {}", mqttResponse.statusCode());
                return false;
            }

            // Trigger reboot
            String rebootUrl = String.format("http://%s/reboot", deviceIp);
            logger.info("Rebooting device...");
            
            HttpRequest rebootRequest = HttpRequest.newBuilder()
                    .uri(URI.create(rebootUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            
            try {
                httpClient.send(rebootRequest, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                // Device may close connection during reboot, this is expected
                logger.debug("Device rebooting (connection closed)");
            }

            return true;

        } catch (Exception e) {
            logger.error("Error configuring OpenBeken device: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get the local IP address of this machine.
     */
    private String getLocalIPAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                
                // Skip loopback and inactive interfaces
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    
                    // Return first non-loopback IPv4 address
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get local IP address: {}", e.getMessage());
        }
        
        return "localhost";
    }

    /**
     * URL encode a string.
     */
    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Get list of provisioned devices.
     */
    public Set<String> getProvisionedDevices() {
        return new HashSet<>(provisionedDevices);
    }

    /**
     * Clear provisioned devices list (for re-provisioning).
     */
    public void clearProvisionedDevices() {
        provisionedDevices.clear();
        logger.info("Cleared provisioned devices list");
    }
}
