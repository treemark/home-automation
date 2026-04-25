package com.openbeken.discovery;

import com.openbeken.model.OpenBekenDevice;

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
import java.util.stream.Collectors;

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
    private static final String CACHE_FILE = ".openbeken-cache.json";

    private final Map<String, OpenBekenDevice> discoveredDevices = new ConcurrentHashMap<>();

    /**
     * Constructor — loads any previously cached devices from disk.
     */
    public OpenBekenDiscoveryService() {
        loadCache();
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
                    discoveredDevices.put(device.getIp(), device);
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
                    discoveredDevices.put(device.getIp(), device);
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
            Files.writeString(Path.of(CACHE_FILE), sb.toString());
        } catch (Exception e) {
            System.err.println("Warning: failed to save device cache: " + e.getMessage());
        }
    }

    /**
     * Load previously cached devices from disk.
     */
    private void loadCache() {
        Path cachePath = Path.of(CACHE_FILE);
        if (!Files.exists(cachePath)) return;
        try {
            String json = Files.readString(cachePath);
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
                        if (device != null && device.getIp() != null) {
                            discoveredDevices.put(device.getIp(), device);
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
