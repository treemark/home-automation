package com.openbeken.discovery;

import com.openbeken.model.GoogleHomeDevice;
import com.openbeken.model.GoogleHomeDevicesConfig;
import com.openbeken.model.GoogleHomeScene;
import com.openbeken.model.OpenBekenDevice;
import com.openbeken.util.JsonUtil;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for discovering OpenBeken devices on the local network.
 * 
 * Discovery methods:
 * 1. HTTP Scan — probe known IPs or subnet for OpenBeken web interface on port 80
 * 2. Inventory — load devices.json and probe each known IP
 * 3. MQTT — subscribe to broker and listen for obk* topic prefixes (handled by MqttDiscoveryService)
 */
public class OpenBekenDiscoveryService {

    private static final int HTTP_TIMEOUT_MS = 2000;
    private static final int PORT_CHECK_TIMEOUT_MS = 500;
    private static final int SCAN_THREAD_POOL_SIZE = 50;
    private static final String DRIVER_MAPPINGS_FILE = "/driver-mappings.json";

    private final Map<String, OpenBekenDevice> discoveredDevices = new ConcurrentHashMap<>();
    private final Map<String, String> macPrefixToDriver = new HashMap<>();
    private final Path mqttConfigDir;
    private final Path cacheFilePath;

    /**
     * Constructor — loads any previously cached devices from disk and driver mappings.
     */
    public OpenBekenDiscoveryService() {
        this.mqttConfigDir = getMqttConfigDirectory();
        this.cacheFilePath = mqttConfigDir.resolve("openbeken-cache.json");
        ensureConfigDirectoryExists();
        loadDriverMappings();
        loadCache();
    }

    /**
     * Get the ~/.mqtt/ configuration directory path.
     */
    private static Path getMqttConfigDirectory() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".mqtt");
    }

    /**
     * Ensure the ~/.mqtt/ directory exists, creating it if necessary.
     */
    private void ensureConfigDirectoryExists() {
        try {
            if (!Files.exists(mqttConfigDir)) {
                Files.createDirectories(mqttConfigDir);
                System.out.println("[Discovery] Created config directory: " + mqttConfigDir);
            }
        } catch (IOException e) {
            System.err.println("[Discovery] Warning: Failed to create config directory: " + e.getMessage());
        }
    }

    /**
     * Scan a subnet range for OpenBeken devices via HTTP.
     * Probes each IP's web interface to detect OpenBeken firmware.
     *
     * @param subnetPrefix e.g. "192.168.86"
     * @param startHost    start of host range (1-254)
     * @param endHost      end of host range (1-254)
     * @param listener     callback for progress updates
     * @return list of discovered devices
     */
    public List<OpenBekenDevice> scanSubnet(String subnetPrefix, int startHost, int endHost, 
                                             DiscoveryListener listener) {
        ExecutorService executor = Executors.newFixedThreadPool(SCAN_THREAD_POOL_SIZE);
        List<Future<OpenBekenDevice>> futures = new ArrayList<>();

        int total = endHost - startHost + 1;
        if (listener != null) {
            listener.onScanStarted(total);
        }

        for (int i = startHost; i <= endHost; i++) {
            final String ip = subnetPrefix + "." + i;
            futures.add(executor.submit(() -> probeDevice(ip)));
        }

        List<OpenBekenDevice> results = new ArrayList<>();
        int scanned = 0;
        for (Future<OpenBekenDevice> future : futures) {
            try {
                OpenBekenDevice device = future.get(HTTP_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
                if (device != null) {
                    device.setDiscoveryMethod("http-scan");
                    discoveredDevices.put(device.getDeviceId(), device);
                    results.add(device);
                    if (listener != null) {
                        listener.onDeviceFound(device);
                    }
                }
            } catch (Exception e) {
                // timeout or error — device not reachable
            }
            scanned++;
            if (listener != null && scanned % 10 == 0) {
                listener.onScanProgress(scanned, total);
            }
        }

        executor.shutdown();
        if (listener != null) {
            listener.onScanComplete(results.size());
        }
        saveCache();
        return results;
    }

    /**
     * Probe a list of specific IPs (e.g. from devices.json inventory).
     */
    public List<OpenBekenDevice> probeKnownIps(List<String> ips, DiscoveryListener listener) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(ips.size(), SCAN_THREAD_POOL_SIZE));
        List<Future<OpenBekenDevice>> futures = new ArrayList<>();

        if (listener != null) {
            listener.onScanStarted(ips.size());
        }

        for (String ip : ips) {
            if (ip == null || ip.isEmpty()) continue;
            futures.add(executor.submit(() -> probeDevice(ip)));
        }

        List<OpenBekenDevice> results = new ArrayList<>();
        int checked = 0;
        for (Future<OpenBekenDevice> future : futures) {
            try {
                OpenBekenDevice device = future.get(HTTP_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
                if (device != null) {
                    device.setDiscoveryMethod("inventory-probe");
                    discoveredDevices.put(device.getDeviceId(), device);
                    results.add(device);
                    if (listener != null) {
                        listener.onDeviceFound(device);
                    }
                }
            } catch (Exception e) {
                // not reachable
            }
            checked++;
            if (listener != null) {
                listener.onScanProgress(checked, ips.size());
            }
        }

        executor.shutdown();
        if (listener != null) {
            listener.onScanComplete(results.size());
        }
        saveCache();
        return results;
    }

    /**
     * Probe a single IP address to check if it's running OpenBeken firmware.
     * OpenBeken devices serve a web UI on port 80 with identifiable content.
     */
    public OpenBekenDevice probeDevice(String ip) {
        // First, quick port check
        if (!isPortOpen(ip, 80)) {
            return null;
        }

        try {
            // Try the OpenBeken info endpoint
            String infoResponse = httpGet("http://" + ip + "/cm?cmnd=Status%200");
            if (infoResponse != null && isOpenBekenResponse(infoResponse)) {
                return parseStatusResponse(ip, infoResponse);
            }

            // Fallback: try the main page and look for OpenBeken markers
            String indexResponse = httpGet("http://" + ip + "/index");
            if (indexResponse != null && isOpenBekenPage(indexResponse)) {
                OpenBekenDevice device = new OpenBekenDevice();
                device.setIp(ip);
                device.setOnline(true);
                device.setDeviceId(extractDeviceId(indexResponse, ip));
                device.setFirmware(extractFirmwareVersion(indexResponse));
                return device;
            }

            // Also try the Tasmota-compatible status endpoint
            String statusResponse = httpGet("http://" + ip + "/cm?cmnd=Status");
            if (statusResponse != null && (statusResponse.contains("OpenBK") || statusResponse.contains("obk"))) {
                OpenBekenDevice device = new OpenBekenDevice();
                device.setIp(ip);
                device.setOnline(true);
                device.setDeviceId("obk_" + ip.replace(".", "_"));
                return device;
            }

        } catch (Exception e) {
            // Not an OpenBeken device or not reachable
        }
        return null;
    }

    /**
     * Load device inventory from devices.json and return IPs.
     */
    public List<InventoryDevice> loadDeviceInventory(String devicesJsonPath) {
        List<InventoryDevice> devices = new ArrayList<>();
        try {
            String json = Files.readString(Path.of(devicesJsonPath));
            // Simple JSON parsing without external library
            // Parse array of objects looking for "name", "ip", "mac", "product_name" fields
            devices = parseDevicesJson(json);
        } catch (Exception e) {
            System.err.println("Failed to load devices.json: " + e.getMessage());
        }
        return devices;
    }

    /**
     * Quick check if a TCP port is open.
     */
    private boolean isPortOpen(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), PORT_CHECK_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Simple HTTP GET request.
     */
    private String httpGet(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);

            int status = conn.getResponseCode();
            if (status != 200) return null;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if an HTTP response looks like it came from an OpenBeken device.
     */
    private boolean isOpenBekenResponse(String response) {
        if (response == null) return false;
        String lower = response.toLowerCase();
        return lower.contains("openbk") || lower.contains("openbeken") ||
               lower.contains("bk7231") || lower.contains("\"obk") ||
               lower.contains("tasmota") || // OpenBeken is Tasmota-compatible
               lower.contains("cmnd/") || lower.contains("devicename");
    }

    /**
     * Check if an HTML page is from an OpenBeken device.
     */
    private boolean isOpenBekenPage(String html) {
        if (html == null) return false;
        String lower = html.toLowerCase();
        return lower.contains("openbk") || lower.contains("openbeken") ||
               lower.contains("bk7231") || lower.contains("cfg_mqtt") ||
               lower.contains("ota update") || lower.contains("firmware:");
    }

    /**
     * Parse a Status 0 response from OpenBeken.
     */
    private OpenBekenDevice parseStatusResponse(String ip, String response) {
        OpenBekenDevice device = new OpenBekenDevice();
        device.setIp(ip);
        device.setOnline(true);

        // Try to extract fields from JSON-like response
        device.setDeviceId(extractJsonValue(response, "DeviceName", "Topic", "Hostname"));
        if (device.getDeviceId() == null || device.getDeviceId().isEmpty()) {
            device.setDeviceId("obk_" + ip.replace(".", "_"));
        }
        device.setMac(extractJsonValue(response, "Mac", "MacAddress"));
        device.setHostname(extractJsonValue(response, "Hostname"));
        device.setMqttTopic(extractJsonValue(response, "Topic"));
        device.setFirmware(extractJsonValue(response, "Version", "FirmwareVersion"));

        // Power state
        String power = extractJsonValue(response, "POWER", "POWER1");
        if (power != null) {
            device.setPowerState(power);
        }

        // Dimmer
        String dimmer = extractJsonValue(response, "Dimmer");
        if (dimmer != null) {
            try {
                device.setDimmer(Integer.parseInt(dimmer.trim()));
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        return device;
    }

    /**
     * Extract a JSON value by trying multiple possible key names.
     */
    private String extractJsonValue(String json, String... keys) {
        for (String key : keys) {
            Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return matcher.group(1);
            }
            // Try without quotes (for numbers/booleans)
            Pattern numPattern = Pattern.compile("\"" + key + "\"\\s*:\\s*([^,}\\s]+)");
            Matcher numMatcher = numPattern.matcher(json);
            if (numMatcher.find()) {
                return numMatcher.group(1).replace("\"", "");
            }
        }
        return null;
    }

    private String extractDeviceId(String html, String ip) {
        // Look for device name in the page
        Pattern p = Pattern.compile("(?:Device|Host)(?:name|Name)[^:]*:\\s*([^<\\n]+)");
        Matcher m = p.matcher(html);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "obk_" + ip.replace(".", "_");
    }

    private String extractFirmwareVersion(String html) {
        Pattern p = Pattern.compile("(?:Firmware|Version)[^:]*:\\s*([^<\\n]+)");
        Matcher m = p.matcher(html);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /**
     * Simple JSON array parser for devices.json (avoids adding a JSON library dependency).
     */
    private List<InventoryDevice> parseDevicesJson(String json) {
        List<InventoryDevice> devices = new ArrayList<>();
        // Split by objects in the array
        int depth = 0;
        int objStart = -1;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    objStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = json.substring(objStart, i + 1);
                    InventoryDevice device = parseDeviceObject(obj);
                    if (device != null) {
                        devices.add(device);
                    }
                    objStart = -1;
                }
            }
        }
        return devices;
    }

    private InventoryDevice parseDeviceObject(String obj) {
        // Only parse top-level fields we need, skip nested "mapping" objects
        InventoryDevice device = new InventoryDevice();
        device.name = extractSimpleJsonString(obj, "name");
        device.ip = extractSimpleJsonString(obj, "ip");
        device.mac = extractSimpleJsonString(obj, "mac");
        device.productName = extractSimpleJsonString(obj, "product_name");
        device.category = extractSimpleJsonString(obj, "category");
        return device;
    }

    private String extractSimpleJsonString(String json, String key) {
        // Look for "key": "value" but only at the first nesting level
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Send a Tasmota-compatible command to an OpenBeken device via HTTP.
     * Uses the /cm?cmnd= endpoint.
     * 
     * @param ip      device IP address
     * @param command command string, e.g. "POWER1 ON", "Dimmer 50"
     * @return response body, or null if failed
     */
    public String httpCommand(String ip, String command) {
        String encoded = command.replace(" ", "%20");
        return httpGet("http://" + ip + "/cm?cmnd=" + encoded);
    }

    /**
     * Restart an OpenBeken device via HTTP.
     * Uses the /index?restart=1 endpoint.
     *
     * @param ip device IP address
     * @return true if restart command was sent successfully
     */
    public boolean restartDevice(String ip) {
        String response = httpGet("http://" + ip + "/index?restart=1");
        return response != null;
    }

    // --- Startup command management ---

    /**
     * Read the current startup command from an OpenBeken device.
     * Parses the /startup_command page and extracts the textarea content.
     *
     * @param ip device IP address
     * @return current startup command text, or null if unreachable
     */
    public String getStartupCommand(String ip) {
        String html = httpGet("http://" + ip + "/startup_command");
        if (html == null) return null;
        // Extract content between <textarea...> and </textarea>
        Pattern p = Pattern.compile("<textarea[^>]*>([^<]*)</textarea>", Pattern.DOTALL);
        Matcher m = p.matcher(html);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    /**
     * Set the startup command on an OpenBeken device.
     * Uses the /startup_command?data=...&startup_cmd=1 GET endpoint.
     *
     * @param ip      device IP address
     * @param command the full startup command text to save
     * @return true if the command was saved successfully
     */
    public boolean setStartupCommand(String ip, String command) {
        String encoded = urlEncode(command);
        String response = httpGet("http://" + ip + "/startup_command?data=" + encoded + "&startup_cmd=1");
        return response != null;
    }

    /**
     * Ensure the startup command includes `led_enableAll 1` so the light turns on at boot.
     * Reads the current command, appends led_enableAll 1 if missing, and saves it back.
     *
     * @param ip device IP address
     * @return result description: "already configured", "updated", or "failed"
     */
    public String ensureLedEnableOnStartup(String ip) {
        String current = getStartupCommand(ip);
        if (current == null) {
            return "unreachable";
        }

        // Already has led_enableAll 1?
        if (current.contains("led_enableAll 1")) {
            return "already configured";
        }

        // Build new startup command
        String newCommand;
        if (current.isEmpty()) {
            newCommand = "led_enableAll 1";
        } else if (current.contains("backlog ")) {
            // Already a backlog — append to it
            newCommand = current + "; led_enableAll 1";
        } else {
            // Wrap existing + new in backlog
            newCommand = "backlog " + current + "; led_enableAll 1";
        }

        boolean ok = setStartupCommand(ip, newCommand);
        if (!ok) {
            return "failed";
        }

        // Verify it was saved
        String verify = getStartupCommand(ip);
        if (verify != null && verify.contains("led_enableAll 1")) {
            return "updated";
        }
        return "failed (verify)";
    }

    /**
     * Ensure the startup command includes driver-specific channel mapping for correct color order.
     * Only applies to drivers that need channel mapping (BP5758D, SM2135).
     * PWM-based bulbs don't need channel mapping.
     *
     * @param ip device IP address
     * @return result description: "already configured", "updated", "not needed", or "failed"
     */
    public String ensureChannelMapping(String ip) {
        // Detect driver type
        DriverType driverType = detectDriverType(ip);

        // PWM drivers don't need channel mapping
        if (driverType == DriverType.PWM || driverType == DriverType.UNKNOWN) {
            return "not needed (" + driverType + ")";
        }

        String current = getStartupCommand(ip);
        if (current == null) {
            return "unreachable";
        }

        // Determine the appropriate mapping command based on driver type
        String mapCommand;
        String mapKeyword;
        switch (driverType) {
            case BP5758D:
                mapCommand = "BP5758D_Map 2 1 0 4 5";
                mapKeyword = "BP5758D_Map";
                break;
            case SM2135:
                mapCommand = "SM2135_Map 2 1 0 4 5";  // SM2135 may need different mapping
                mapKeyword = "SM2135_Map";
                break;
            default:
                return "not needed";
        }

        // Already has channel mapping?
        if (current.contains(mapKeyword)) {
            return "already configured (" + driverType + ")";
        }

        // Build new startup command with channel mapping
        String newCommand;
        if (current.isEmpty()) {
            newCommand = "backlog " + mapCommand + "; led_enableAll 1";
        } else if (current.contains("backlog ")) {
            // Insert map command at the beginning of backlog
            newCommand = current.replace("backlog ", "backlog " + mapCommand + "; ");
        } else {
            // Wrap in backlog with map first
            newCommand = "backlog " + mapCommand + "; " + current;
        }

        boolean ok = setStartupCommand(ip, newCommand);
        if (!ok) {
            return "failed";
        }

        // Verify it was saved
        String verify = getStartupCommand(ip);
        if (verify != null && verify.contains(mapKeyword)) {
            return "updated (" + driverType + ")";
        }
        return "failed (verify)";
    }

    /**
     * LED Driver types supported by OpenBeken devices.
     */
    public enum DriverType {
        BP5758D,    // BP5758D I2C driver (roles 23/24)
        PWM,        // Direct PWM control (role 7)
        SM2135,     // SM2135 I2C driver (roles 21/22)
        UNKNOWN
    }

    /**
     * Load driver mappings from the resource file.
     * Maps MAC address prefixes to driver types.
     */
    private void loadDriverMappings() {
        try (InputStream is = getClass().getResourceAsStream(DRIVER_MAPPINGS_FILE)) {
            if (is == null) {
                System.err.println("Warning: driver-mappings.json not found in resources");
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String json = sb.toString();
            
            // Parse mac_prefixes object
            Pattern p = Pattern.compile("\"mac_prefixes\"\\s*:\\s*\\{([^}]+)\\}");
            Matcher m = p.matcher(json);
            if (m.find()) {
                String mappings = m.group(1);
                // Parse each "prefix": "driver" pair
                Pattern entryPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");
                Matcher entryMatcher = entryPattern.matcher(mappings);
                while (entryMatcher.find()) {
                    String prefix = entryMatcher.group(1);
                    String driver = entryMatcher.group(2);
                    macPrefixToDriver.put(prefix.toLowerCase(), driver);
                }
            }
            System.out.println("Loaded " + macPrefixToDriver.size() + " MAC prefix mappings");
        } catch (Exception e) {
            System.err.println("Warning: failed to load driver mappings: " + e.getMessage());
        }
    }

    /**
     * Detect driver type by MAC address prefix lookup.
     *
     * @param mac MAC address (format: "XX:XX:XX:XX:XX:XX")
     * @return detected driver type or UNKNOWN if not found
     */
    private DriverType detectDriverTypeByMac(String mac) {
        if (mac == null || mac.length() < 8) {
            return DriverType.UNKNOWN;
        }
        
        // Extract first 8 characters (first 3 octets) as prefix
        String prefix = mac.substring(0, 8).toLowerCase();
        String driverName = macPrefixToDriver.get(prefix);
        
        if (driverName == null) {
            return DriverType.UNKNOWN;
        }
        
        try {
            return DriverType.valueOf(driverName.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Warning: unknown driver type in mapping: " + driverName);
            return DriverType.UNKNOWN;
        }
    }

    /**
     * Detect the LED driver type from MAC address (if available) or current pin configuration.
     * Tries MAC address lookup first for faster detection, then falls back to pin inspection.
     *
     * @param ip device IP address
     * @return detected driver type
     */
    public DriverType detectDriverType(String ip) {
        // Try to get MAC address first for faster detection
        String statusHtml = httpGet("http://" + ip + "/index");
        if (statusHtml != null) {
            // Extract MAC from page (format: "Device MAC: XX:XX:XX:XX:XX:XX")
            Pattern macPattern = Pattern.compile("(?:MAC|Mac)\\s*:?\\s*([0-9A-Fa-f:]{17})");
            Matcher macMatcher = macPattern.matcher(statusHtml);
            if (macMatcher.find()) {
                String mac = macMatcher.group(1);
                DriverType typeByMac = detectDriverTypeByMac(mac);
                if (typeByMac != DriverType.UNKNOWN) {
                    return typeByMac;
                }
            }
        }

        // Fallback: detect from pin configuration
        String cfgPage = httpGet("http://" + ip + "/cfg_pins");
        if (cfgPage == null) {
            return DriverType.UNKNOWN;
        }

        // Check for BP5758D (roles 23/24)
        if (cfgPage.contains(",24,") && cfgPage.contains(",23,")) {
            return DriverType.BP5758D;
        }

        // Check for SM2135 (roles 21/22)
        if (cfgPage.contains(",21,") && cfgPage.contains(",22,")) {
            return DriverType.SM2135;
        }

        // Check for PWM (role 7) - need multiple PWM pins for RGB
        int pwmCount = 0;
        for (int i = 0; i < cfgPage.length() - 10; i++) {
            if (cfgPage.substring(i, Math.min(i + 4, cfgPage.length())).equals(",7,")) {
                pwmCount++;
            }
        }
        if (pwmCount >= 3) {
            return DriverType.PWM;
        }

        return DriverType.UNKNOWN;
    }

    /**
     * Configure GPIO pins based on detected driver type (from MAC or pins).
     * For unconfigured devices, uses MAC address lookup to determine correct driver.
     * Automatically configures BP5758D or PWM pins based on detection.
     *
     * @param ip device IP address
     * @return result description: "configured", "already configured [type]", "skipped [type]", or "failed"
     */
    public String configureDriverPins(String ip) {
        // First, detect via MAC to know what type of bulb this is
        String statusHtml = httpGet("http://" + ip + "/index");
        DriverType typeByMac = DriverType.UNKNOWN;
        String mac="??";
        if (statusHtml != null) {
            Pattern macPattern = Pattern.compile("(?:MAC|Mac)\\s*:?\\s*([0-9A-Fa-f:]{17})");
            Matcher macMatcher = macPattern.matcher(statusHtml);
            if (macMatcher.find()) {
                mac = macMatcher.group(1);
                typeByMac = detectDriverTypeByMac(mac);
            }
        }

        // Now configure the appropriate driver based on MAC detection
        if (typeByMac == DriverType.PWM) {
            return configurePWMPins(ip);
        } else if (typeByMac == DriverType.BP5758D) {
            return configureBP5758DPins(ip);
        } else {
            detectDriverType(ip);
            return "UNKNOWN mac: " + mac;
        }

        // If MAC detection failed, try pin detection as fallback
//        DriverType typeByPins = detectDriverType(ip);
//        if (typeByPins != DriverType.UNKNOWN) {
//            return "already configured (" + typeByPins + ")";
//        }
//
//        // Default to BP5758D for unknown
//        return configureBP5758DPins(ip) + " (default)";
    }

    /**
     * Configure GPIO pins for PWM LED driver.
     * Sets P6, P7, P8, P24, P26 to PWM role (7) with proper channel assignments for RGB+CW+WW control.
     * Channel mapping: P8=1 (Blue), P24=2 (Red), P26=3 (Green), P7=4 (Cool White), P6=5 (Warm White)
     *
     * @param ip device IP address
     * @return result description: "configured", "already configured", or "failed"
     */
    private String configurePWMPins(String ip) {
        // Check current pin configuration
        String cfgPage = httpGet("http://" + ip + "/cfg_pins");
        if (cfgPage == null) {
            return "unreachable";
        }

        // Check if PWM pins are already configured with correct roles AND channels
        boolean alreadyConfigured = cfgPage.contains("f(\"P6 (PWM0) \",6,7, 0,5,null,)") && 
                                     cfgPage.contains("f(\"P7 (PWM1) \",7,7, 0,4,null,)") &&
                                     cfgPage.contains("f(\"P8 (PWM2) \",8,7, 0,1,null,)") &&
                                     cfgPage.contains("f(\"P24 (PWM4) \",24,7, 0,2,null,)") &&
                                     cfgPage.contains("f(\"P26 (PWM5) \",26,7, 0,3,null,)");

        if (alreadyConfigured) {
            return "already configured";
        }

        // Build pin configuration URL - set PWM pins to role 7 with channel numbers
        // URL format: /cfg_pins?0=0&1=0&...&6=7&r6=5&...
        // Where pin=role and rPin=channel
        StringBuilder url = new StringBuilder("http://" + ip + "/cfg_pins?");
        
        // First, set all pin roles
        for (int i = 0; i <= 28; i++) {
            if (i > 0) url.append("&");
            url.append(i).append("=");
            // Set PWM role (7) for RGB + warm/cool white pins
            if (i == 6 || i == 7 || i == 8 || i == 24 || i == 26) {
                url.append("7"); // PWM role
            } else {
                url.append("0"); // No role
            }
        }
        
        // Now add channel assignments for PWM pins
        // P8=channel 1 (Blue), P24=channel 2 (Red), P26=channel 3 (Green)
        // P7=channel 4 (Cool White), P6=channel 5 (Warm White)
        url.append("&r6=5");   // P6 → channel 5 (Warm White)
        url.append("&r7=4");   // P7 → channel 4 (Cool White)
        url.append("&r8=1");   // P8 → channel 1 (Blue)
        url.append("&r24=2");  // P24 → channel 2 (Red)
        url.append("&r26=3");  // P26 → channel 3 (Green)

        String response = httpGet(url.toString());
        if (response == null || !response.contains("changed")) {
            return "failed";
        }

        return "configured (PWM)";
    }

    /**
     * Configure GPIO pins for BP5758D LED driver.
     * Sets P7=BP5758D_CLK (role 24) and P8=BP5758D_DAT (role 23).
     *
     * @param ip device IP address
     * @return result description: "configured", "already configured", or "failed"
     */
    private String configureBP5758DPins(String ip) {
        // Check current pin configuration
        String cfgPage = httpGet("http://" + ip + "/cfg_pins");
        if (cfgPage == null) {
            return "unreachable";
        }

        // Check if pins are already configured (P7=24, P8=23)
        boolean alreadyConfigured = cfgPage.contains("f(\"P7 (PWM1) \",7,24,") && 
                                     cfgPage.contains("f(\"P8 (PWM2) \",8,23,");

        if (alreadyConfigured) {
            return "already configured";
        }

        // Build pin configuration URL - set all pins, with P7=24 (BP5758D_CLK) and P8=23 (BP5758D_DAT)
        StringBuilder url = new StringBuilder("http://" + ip + "/cfg_pins?");
        for (int i = 0; i <= 28; i++) {
            if (i > 0) url.append("&");
            url.append(i).append("=");
            if (i == 7) {
                url.append("24"); // BP5758D_CLK
            } else if (i == 8) {
                url.append("23"); // BP5758D_DAT
            } else {
                url.append("0"); // No role
            }
        }

        String response = httpGet(url.toString());
        if (response == null || !response.contains("changed")) {
            return "failed";
        }

        return "configured (BP5758D)";
    }

    /**
     * Configure MQTT settings on an OpenBeken device via its /cfg_mqtt_set HTTP endpoint.
     * The OpenBeken web UI form submits a GET to /cfg_mqtt_set with field names:
     *   host, port, client, group, user, password
     * Our Moquette broker uses allow_anonymous=true, so user/password are cleared.
     *
     * @param deviceIp   the device's IP address
     * @param brokerHost the MQTT broker host/IP to point the device at
     * @param brokerPort the MQTT broker port (typically 1883)
     * @param clientId   unique MQTT client topic/ID for this device (should be device ID, not IP-based)
     * @param groupTopic the MQTT group topic (e.g. "openbeken")
     * @return "configured", "already configured", or an error description
     */
    public String configureMqtt(String deviceIp, String brokerHost, int brokerPort,
                                 String clientId, String groupTopic) {
        // First, read the current MQTT config page to check existing settings
        String cfgPage = httpGet("http://" + deviceIp + "/cfg_mqtt");
        if (cfgPage == null) {
            return "unreachable";
        }

        // Check if already configured with the correct broker host
        boolean alreadyCorrect = cfgPage.contains("value=\"" + brokerHost + "\"");

        // Build the config URL matching the actual OpenBeken form submission:
        //   /cfg_mqtt_set?host=X&port=X&client=X&group=X&user=&password=
        // User and password are empty since our Moquette broker uses allow_anonymous=true
        String url = String.format(
            "http://%s/cfg_mqtt_set?host=%s&port=%d&client=%s&group=%s&user=&password=",
            deviceIp, brokerHost, brokerPort, urlEncode(clientId), urlEncode(groupTopic));

        String response = httpGet(url);
        if (response == null) {
            return "failed";
        }
        
        // Update the device object in cache with the configured MQTT topic
        OpenBekenDevice device = discoveredDevices.values().stream()
                .filter(d -> deviceIp.equals(d.getIp()))
                .findFirst()
                .orElse(null);
        if (device != null) {
            device.setMqttTopic(clientId);
            device.setMqttGroup(groupTopic);
            saveCache();
        }
        
        return alreadyCorrect ? "already configured" : "configured";
    }

    /**
     * Simple URL encoding for startup command text.
     */
    private String urlEncode(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
                c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append(String.format("%%%02X", (int) c));
            }
        }
        return sb.toString();
    }

    public Map<String, OpenBekenDevice> getDiscoveredDevices() {
        return Collections.unmodifiableMap(discoveredDevices);
    }

    public void addMqttDiscoveredDevice(OpenBekenDevice device) {
        discoveredDevices.put(device.getDeviceId(), device);
    }

    // --- Cache persistence ---

    /**
     * Save discovered devices to a JSON cache file so they survive across JVM invocations.
     */
    public void saveCache() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            int i = 0;
            for (OpenBekenDevice d : discoveredDevices.values()) {
                if (i > 0) sb.append(",\n");
                sb.append("  {");
                sb.append("\"deviceId\":").append(jsonStr(d.getDeviceId()));
                sb.append(",\"ip\":").append(jsonStr(d.getIp()));
                sb.append(",\"mac\":").append(jsonStr(d.getMac()));
                sb.append(",\"firmware\":").append(jsonStr(d.getFirmware()));
                sb.append(",\"chipset\":").append(jsonStr(d.getChipset()));
                sb.append(",\"hostname\":").append(jsonStr(d.getHostname()));
                sb.append(",\"mqttTopic\":").append(jsonStr(d.getMqttTopic()));
                sb.append(",\"mqttGroup\":").append(jsonStr(d.getMqttGroup()));
                sb.append(",\"online\":").append(d.isOnline());
                sb.append(",\"powerState\":").append(jsonStr(d.getPowerState()));
                sb.append(",\"dimmer\":").append(d.getDimmer() != null ? d.getDimmer() : "null");
                sb.append(",\"discoveryMethod\":").append(jsonStr(d.getDiscoveryMethod()));
                sb.append(",\"lastSeen\":").append(jsonStr(d.getLastSeen() != null ? d.getLastSeen().toString() : null));
                sb.append("}");
                i++;
            }
            sb.append("\n]");
            Files.writeString(cacheFilePath, sb.toString());
        } catch (Exception e) {
            System.err.println("Warning: failed to save device cache: " + e.getMessage());
        }
    }

    /**
     * Load previously cached devices from disk.
     */
    private void loadCache() {
        if (!Files.exists(cacheFilePath)) return;
        try {
            String json = Files.readString(cacheFilePath);
            // Reuse the same brace-matching approach to parse the array of device objects
            int depth = 0;
            int objStart = -1;
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') {
                    if (depth == 0) objStart = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String obj = json.substring(objStart, i + 1);
                        OpenBekenDevice device = parseCachedDevice(obj);
                        if (device != null && device.getDeviceId() != null) {
                            discoveredDevices.put(device.getDeviceId(), device);
                        }
                        objStart = -1;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: failed to load device cache: " + e.getMessage());
        }
    }

    private OpenBekenDevice parseCachedDevice(String obj) {
        OpenBekenDevice d = new OpenBekenDevice();
        d.setDeviceId(extractSimpleJsonString(obj, "deviceId"));
        d.setIp(extractSimpleJsonString(obj, "ip"));
        d.setMac(extractSimpleJsonString(obj, "mac"));
        d.setFirmware(extractSimpleJsonString(obj, "firmware"));
        d.setChipset(extractSimpleJsonString(obj, "chipset"));
        d.setHostname(extractSimpleJsonString(obj, "hostname"));
        d.setMqttTopic(extractSimpleJsonString(obj, "mqttTopic"));
        d.setMqttGroup(extractSimpleJsonString(obj, "mqttGroup"));
        d.setPowerState(extractSimpleJsonString(obj, "powerState"));
        d.setDiscoveryMethod(extractSimpleJsonString(obj, "discoveryMethod"));
        // online
        if (obj.contains("\"online\":true")) d.setOnline(true);
        // dimmer
        String dimmerStr = extractJsonValue(obj, "dimmer");
        if (dimmerStr != null && !dimmerStr.equals("null")) {
            try { d.setDimmer(Integer.parseInt(dimmerStr.trim())); } catch (NumberFormatException ignored) {}
        }
        // lastSeen
        String lastSeenStr = extractSimpleJsonString(obj, "lastSeen");
        if (lastSeenStr != null) {
            try { d.setLastSeen(Instant.parse(lastSeenStr)); } catch (Exception ignored) {}
        }
        return d;
    }

    private String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Sync discovered devices to google-home-devices.json.
     * Matches devices by short ID (last 6 chars of deviceId).
     * For existing IDs: preserves name/room, updates IP only if changed.
     * For new IDs: adds them with auto-generated names.
     */
    public void syncToGoogleHomeDevices(String googleHomeJsonPath) {
        try {
            Path path = Path.of(googleHomeJsonPath);
            GoogleHomeDevicesConfig config;
            
            // Read existing google-home-devices.json using Jackson
            if (Files.exists(path)) {
                String existingJson = Files.readString(path);
                config = JsonUtil.fromJson(existingJson, GoogleHomeDevicesConfig.class);
            } else {
                config = new GoogleHomeDevicesConfig();
                config.setComment("Google Home device registry - edit rooms/names freely. ip drives the MQTT topic (obk{ip_with_underscores}). Devices with no ip are not yet flashed.");
            }
            
            // Build map of existing devices by ID
            Map<String, GoogleHomeDevice> existingDevices = new LinkedHashMap<>();
            for (GoogleHomeDevice device : config.getDevices()) {
                existingDevices.put(device.getId(), device);
            }
            
            // Build map of shortId -> discovered device
            Map<String, OpenBekenDevice> discoveredByShortId = new HashMap<>();
            for (OpenBekenDevice device : discoveredDevices.values()) {
                if (device.getIp() == null || device.getIp().isEmpty()) continue;
                String deviceId = device.getDeviceId();
                String shortId = deviceId.length() > 10 ? deviceId.substring(deviceId.length() - 6) : deviceId;
                discoveredByShortId.put(shortId, device);
            }
            
            int updatedCount = 0;
            int newCount = 0;
            
            // Update IPs and deviceIds for existing device IDs (preserve name/room)
            for (GoogleHomeDevice ghd : existingDevices.values()) {
                OpenBekenDevice discovered = discoveredByShortId.get(ghd.getId());
                if (discovered != null) {
                    boolean updated = false;
                    
                    // Update IP if it changed
                    if (!discovered.getIp().equals(ghd.getIp())) {
                        ghd.setIp(discovered.getIp());
                        updated = true;
                    }
                    
                    // Update or set deviceId (stable across IP changes)
                    if (discovered.getDeviceId() != null && !discovered.getDeviceId().equals(ghd.getDeviceId())) {
                        ghd.setDeviceId(discovered.getDeviceId());
                        updated = true;
                    }
                    
                    if (updated) {
                        updatedCount++;
                    }
                    
                    // Remove from map so we don't add it as new
                    discoveredByShortId.remove(ghd.getId());
                }
            }
            
            // Add new devices (short IDs not in existing devices)
            for (Map.Entry<String, OpenBekenDevice> entry : discoveredByShortId.entrySet()) {
                String shortId = entry.getKey();
                OpenBekenDevice device = entry.getValue();
                
                // Make sure the ID is unique (shouldn't happen but just in case)
                String finalId = shortId;
                int suffix = 1;
                while (existingDevices.containsKey(finalId)) {
                    finalId = shortId + "_" + suffix;
                    suffix++;
                }
                
                GoogleHomeDevice newDevice = new GoogleHomeDevice(finalId, "Light " + shortId, "Uncategorized", device.getIp());
                existingDevices.put(finalId, newDevice);
                newCount++;
            }
            
            // Sort devices by room then name
            List<GoogleHomeDevice> sortedDevices = new ArrayList<>(existingDevices.values());
            sortedDevices.sort(Comparator.comparing((GoogleHomeDevice d) -> d.getRoom() + d.getName()));
            config.setDevices(sortedDevices);
            
            // Write updated JSON using Jackson
            String updatedJson = JsonUtil.toJson(config);
            Files.writeString(path, updatedJson);
            
            System.out.printf("[Sync] Updated google-home-devices.json: %d IPs updated, %d new devices added\n", 
                    updatedCount, newCount);
            
        } catch (Exception e) {
            System.err.println("[Sync] Failed to sync to google-home-devices.json: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Simple inventory device from devices.json
     */
    public static class InventoryDevice {
        public String name;
        public String ip;
        public String mac;
        public String productName;
        public String category;

        @Override
        public String toString() {
            return String.format("%-20s  IP: %-16s  MAC: %-18s  Product: %s",
                    name != null ? name : "Unknown",
                    ip != null && !ip.isEmpty() ? ip : "N/A",
                    mac != null ? mac : "N/A",
                    productName != null ? productName : "Unknown");
        }
    }

    /**
     * Listener interface for discovery progress callbacks.
     */
    public interface DiscoveryListener {
        void onScanStarted(int totalTargets);
        void onScanProgress(int scanned, int total);
        void onDeviceFound(OpenBekenDevice device);
        void onScanComplete(int foundCount);
    }
}
