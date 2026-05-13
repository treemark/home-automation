package com.openbeken.cli;

import com.openbeken.animation.ColorRotationAnimation;
import com.openbeken.broker.EmbeddedBroker;
import com.openbeken.discovery.MqttDiscoveryService;
import com.openbeken.discovery.OpenBekenDiscoveryService;
import com.openbeken.discovery.OpenBekenDiscoveryService.InventoryDevice;
import com.openbeken.model.OpenBekenDevice;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Interactive CLI for discovering and managing OpenBeken devices.
 * Run with: ./gradlew :mqtt:run
 */
public class OpenBekenCLI {

    private static final String DEFAULT_BROKER = "tcp://localhost:1883";
    private static final String DEFAULT_SUBNET = "192.168.86";
    private static final String DEFAULT_DEVICES_JSON = "devices.json";

    private final OpenBekenDiscoveryService discoveryService;
    private MqttDiscoveryService mqttService;
    private ColorRotationAnimation colorRotation;
    private EmbeddedBroker embeddedBroker;
    private String brokerUrl = DEFAULT_BROKER;
    private String subnet = DEFAULT_SUBNET;

    public OpenBekenCLI() {
        this.discoveryService = new OpenBekenDiscoveryService();
    }

    public static void main(String[] args) {
        OpenBekenCLI cli = new OpenBekenCLI();
        if (args.length > 0) {
            cli.runCommand(args);
        } else {
            cli.runInteractive();
        }
    }

    // --- PLACEHOLDER METHODS (filled in via replace_in_file) ---

    private void runCommand(String[] args) {
        executeCommand(args);
    }

    private void runInteractive() {
        printBanner();
        Scanner scanner = new Scanner(System.in);
        boolean running = true;
        while (running) {
            System.out.print("\nobk> ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;
            String[] parts = input.split("\\s+");
            if (parts[0].equalsIgnoreCase("exit") || parts[0].equalsIgnoreCase("quit")) {
                running = false;
            } else {
                executeCommand(parts);
            }
        }
        cleanup();
        System.out.println("Goodbye!");
    }

    private void executeCommand(String[] parts) {
        String cmd = parts[0].toLowerCase();
        try {
            switch (cmd) {
                case "help": case "?": printHelp(); break;
                case "scan": cmdScan(parts); break;
                case "inventory": case "inv": cmdInventory(parts); break;
                case "probe": cmdProbe(parts); break;
                case "mqtt-discover": case "md": cmdMqttDiscover(parts); break;
                case "mqtt-listen": case "ml": cmdMqttListen(parts); break;
                case "list": case "ls": cmdList(); break;
                case "info": cmdInfo(parts); break;
                case "on": cmdPower(parts, "1"); break;
                case "off": cmdPower(parts, "0"); break;
                case "dimmer": cmdDimmer(parts); break;
                case "color": cmdColor(parts); break;
                case "power-on-state": case "pos": cmdPowerOnState(parts); break;
                case "configure-all": case "ca": cmdConfigureAll(parts); break;
                case "rainbow": case "rotate": cmdRainbow(parts); break;
                case "stop-anim": case "sa": cmdStopAnim(); break;
                case "animations": case "anims": cmdAnimations(); break;
                case "broker": cmdBroker(parts); break;
                case "subnet": cmdSubnet(parts); break;
                case "status": cmdStatus(); break;
                default:
                    System.out.println("Unknown command: " + cmd);
                    System.out.println("Type 'help' for available commands");
            }
        } catch (Exception e) {
            System.out.println("✗ Error: " + e.getMessage());
        }
    }

    private void printBanner() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════╗");
        System.out.println("║       OpenBeken Device Discovery CLI                  ║");
        System.out.println("║  Discover & control OpenBeken devices via MQTT/HTTP   ║");
        System.out.println("║  Type 'help' for commands                             ║");
        System.out.println("╚═══════════════════════════════════════════════════════╝");
    }

    private void printHelp() {
        System.out.println("\n=== Discovery Commands ===");
        System.out.println("  scan [subnet] [start] [end]   - Scan subnet for OpenBeken devices via HTTP");
        System.out.println("  inventory [path]              - Load devices.json and probe known IPs");
        System.out.println("  probe <ip>                    - Probe a single IP for OpenBeken");
        System.out.println("  mqtt-discover [broker-url]    - Listen on MQTT broker for devices");
        System.out.println("  mqtt-listen [seconds]         - Listen for MQTT messages (default 10s)");
        System.out.println("  list, ls                      - List all discovered devices");
        System.out.println("  info <device-id|ip>           - Show device details");
        System.out.println("\n=== Control Commands ===");
        System.out.println("  on <device-id>                - Turn device on (via MQTT)");
        System.out.println("  off <device-id>               - Turn device off (via MQTT)");
        System.out.println("  dimmer <device-id> <0-100>    - Set brightness (via MQTT)");
        System.out.println("  color <device-id> <h,s,b>     - Set HSB color (via MQTT)");
        System.out.println("\n=== Device Configuration ===");
        System.out.println("  power-on-state <device> <0-4> - Set PowerOnState (what happens on power restore)");
        System.out.println("    pos                           0=OFF, 1=ON (dumb bulb), 3=last state, 4=ON+lock");
        System.out.println("  configure-all [broker-ip]     - Configure MQTT broker + led_enableAll on ALL devices");
        System.out.println("    ca                            Sets mqtt_host to this machine's IP (auto-detected),");
        System.out.println("                                  mqtt_port=1883, group=openbeken, + led_enableAll 1");
        System.out.println("\n=== Animations ===");
        System.out.println("  rainbow <d1> <d2> <d3> [cycles] [delay] - Color rotation across 3 bulbs");
        System.out.println("    rotate                                   (120° hue offset per bulb)");
        System.out.println("  stop-anim, sa                            - Stop all running animations");
        System.out.println("  animations, anims                        - List running animations");
        System.out.println("\n=== Connection ===");
        System.out.println("  broker [url]                  - Show/set MQTT broker URL");
        System.out.println("  subnet [prefix]               - Show/set subnet prefix");
        System.out.println("  status                        - Show connection status");
        System.out.println("\n=== General ===");
        System.out.println("  help, ?                       - Show this help");
        System.out.println("  exit, quit                    - Exit CLI");
    }

    // --- Discovery commands ---

    private void cmdScan(String[] parts) {
        String sub = parts.length > 1 ? parts[1] : subnet;
        int start = parts.length > 2 ? Integer.parseInt(parts[2]) : 1;
        int end = parts.length > 3 ? Integer.parseInt(parts[3]) : 254;
        System.out.printf("\n→ Scanning %s.%d-%d for OpenBeken devices...\n", sub, start, end);
        var listener = new ConsoleDiscoveryListener();
        var found = discoveryService.scanSubnet(sub, start, end, listener);
        System.out.printf("\n✓ Scan complete. Found %d OpenBeken device(s)\n", found.size());
        if (!found.isEmpty()) {
            System.out.printf("  Cached %d device(s) to .openbeken-cache.json\n", discoveryService.getDiscoveredDevices().size());
        }
    }

    private void cmdInventory(String[] parts) {
        String path = parts.length > 1 ? parts[1] : DEFAULT_DEVICES_JSON;
        System.out.println("\n→ Loading device inventory from " + path + "...");
        List<InventoryDevice> inventory = discoveryService.loadDeviceInventory(path);
        if (inventory.isEmpty()) {
            System.out.println("✗ No devices found in inventory");
            return;
        }
        System.out.printf("  Found %d devices in inventory\n\n", inventory.size());
        System.out.println("┌──────────────────────────────────────────────────────────────────────────────────┐");
        System.out.printf("│ %-8s │ %-16s │ %-18s │ %-30s │%n", "Name", "IP", "MAC", "Product");
        System.out.println("├──────────────────────────────────────────────────────────────────────────────────┤");
        for (InventoryDevice d : inventory) {
            System.out.printf("│ %-8s │ %-16s │ %-18s │ %-30s │%n",
                    trunc(d.name, 8), trunc(d.ip, 16), trunc(d.mac, 18), trunc(d.productName, 30));
        }
        System.out.println("└──────────────────────────────────────────────────────────────────────────────────┘");

        // Probe IPs that have addresses
        List<String> ips = inventory.stream()
                .map(d -> d.ip)
                .filter(ip -> ip != null && !ip.isEmpty())
                .collect(Collectors.toList());
        System.out.printf("\n→ Probing %d IPs for OpenBeken firmware...\n", ips.size());
        var found = discoveryService.probeKnownIps(ips, new ConsoleDiscoveryListener());
        System.out.printf("\n✓ Probe complete. %d of %d devices running OpenBeken\n", found.size(), ips.size());
    }

    private void cmdProbe(String[] parts) {
        if (parts.length < 2) { System.out.println("Usage: probe <ip>"); return; }
        String ip = parts[1];
        System.out.println("→ Probing " + ip + "...");
        OpenBekenDevice d = discoveryService.probeDevice(ip);
        if (d != null) {
            d.setDiscoveryMethod("manual-probe");
            System.out.println("✓ OpenBeken device found!");
            printDeviceDetail(d);
        } else {
            System.out.println("✗ No OpenBeken device at " + ip);
        }
    }

    private void cmdMqttDiscover(String[] parts) throws Exception {
        String url = parts.length > 1 ? parts[1] : brokerUrl;
        ensureMqttConnected(url);
        System.out.println("→ Subscribing to all MQTT topics for device discovery...");
        mqttService.startDiscovery();
        System.out.println("✓ MQTT discovery started. Devices will appear as they publish.");
        System.out.println("  Use 'list' to see discovered devices, 'mqtt-listen' to watch messages.");
    }

    private void cmdMqttListen(String[] parts) throws Exception {
        int seconds = parts.length > 1 ? Integer.parseInt(parts[1]) : 10;
        ensureMqttConnected(brokerUrl);
        mqttService.startDiscovery();
        System.out.printf("→ Listening for MQTT messages for %d seconds...\n\n", seconds);
        mqttService.addMessageListener((topic, payload) -> {
            String display = payload.length() > 80 ? payload.substring(0, 80) + "..." : payload;
            System.out.printf("  [MQTT] %-40s → %s\n", topic, display);
        });
        Thread.sleep(seconds * 1000L);
        System.out.printf("\n✓ Listened for %ds. Found %d device(s) via MQTT\n",
                seconds, mqttService.getDiscoveredDevices().size());
    }

    private void cmdList() {
        var httpDevices = discoveryService.getDiscoveredDevices();
        var mqttDevices = mqttService != null ? mqttService.getDiscoveredDevices() : Map.<String, OpenBekenDevice>of();
        Map<String, OpenBekenDevice> all = new HashMap<>(httpDevices);
        all.putAll(mqttDevices);
        if (all.isEmpty()) {
            System.out.println("\nNo devices discovered yet.");
            System.out.println("Try: scan, inventory, probe <ip>, or mqtt-discover");
            return;
        }
        System.out.println("\n┌─────────────────────────────────────────────────────────────────────┐");
        System.out.printf("│ %-20s │ %-16s │ %-8s │ %-8s │ %-10s │%n",
                "Device ID", "IP", "Online", "Power", "Method");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        for (OpenBekenDevice d : all.values()) {
            System.out.printf("│ %-20s │ %-16s │ %-8s │ %-8s │ %-10s │%n",
                    trunc(d.getDeviceId(), 20),
                    trunc(d.getIp() != null ? d.getIp() : "N/A", 16),
                    d.isOnline() ? "✓ Yes" : "✗ No",
                    d.getPowerState() != null ? d.getPowerState() : "?",
                    trunc(d.getDiscoveryMethod(), 10));
        }
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.printf("Total: %d device(s)\n", all.size());
    }

    private void cmdInfo(String[] parts) {
        if (parts.length < 2) { System.out.println("Usage: info <device-id|ip>"); return; }
        String key = parts[1];
        var all = new HashMap<>(discoveryService.getDiscoveredDevices());
        if (mqttService != null) all.putAll(mqttService.getDiscoveredDevices());
        OpenBekenDevice d = all.get(key);
        if (d == null) {
            d = all.values().stream().filter(dev -> key.equals(dev.getIp())).findFirst().orElse(null);
        }
        if (d != null) { printDeviceDetail(d); }
        else { System.out.println("Device not found: " + key); }
    }

    // --- Control commands ---

    private void cmdPower(String[] parts, String value) throws Exception {
        if (parts.length < 2) { System.out.println("Usage: on/off <device-id>"); return; }
        ensureMqttConnected(brokerUrl);
        String deviceId = parts[1];
        mqttService.sendCommand(deviceId, "POWER1", value);
        System.out.printf("✓ Sent POWER1=%s to %s\n", value, deviceId);
    }

    private void cmdDimmer(String[] parts) throws Exception {
        if (parts.length < 3) { System.out.println("Usage: dimmer <device-id> <0-100>"); return; }
        ensureMqttConnected(brokerUrl);
        mqttService.sendCommand(parts[1], "Dimmer", parts[2]);
        System.out.printf("✓ Sent Dimmer=%s to %s\n", parts[2], parts[1]);
    }

    private void cmdColor(String[] parts) throws Exception {
        if (parts.length < 3) { System.out.println("Usage: color <device-id> <h,s,b>"); return; }
        ensureMqttConnected(brokerUrl);
        mqttService.sendCommand(parts[1], "HsbColor", parts[2]);
        System.out.printf("✓ Sent HsbColor=%s to %s\n", parts[2], parts[1]);
    }

    // --- Device Configuration commands ---

    /**
     * Set PowerOnState on a single device via HTTP.
     * PowerOnState controls what happens when the bulb gets power restored (physical switch toggle).
     *   0 = Stay OFF, 1 = Always ON (dumb bulb behavior), 3 = Last state, 4 = ON + lock
     */
    private void cmdPowerOnState(String[] parts) {
        if (parts.length < 3) {
            System.out.println("Usage: power-on-state <device-ip> <0-4>");
            System.out.println("  0 = Stay OFF after power restore");
            System.out.println("  1 = Always turn ON (recommended — mimics dumb bulb)");
            System.out.println("  3 = Restore last saved state");
            System.out.println("  4 = Always ON + disable further changes");
            return;
        }
        String ip = parts[1];
        String value = parts[2];
        System.out.printf("→ Setting PowerOnState=%s on %s via HTTP...\n", value, ip);
        String result = discoveryService.httpCommand(ip, "PowerOnState " + value);
        if (result != null) {
            System.out.printf("✓ PowerOnState set to %s on %s\n", value, ip);
            System.out.println("  Response: " + result.trim());
            System.out.println("  Physical switch toggle will now turn the bulb ON.");
        } else {
            System.out.println("✗ Failed to reach device at " + ip);
            System.out.println("  Make sure the device is online and reachable on port 80.");
        }
    }

    /**
     * Configure all discovered devices:
     *  1. Set MQTT broker host/port to point at this machine
     *  2. Ensure led_enableAll 1 in startup command
     *
     * Usage: configure-all [broker-ip]
     *   If broker-ip is omitted, auto-detects this machine's LAN IP.
     */
    private void cmdConfigureAll(String[] parts) {
        var all = new HashMap<>(discoveryService.getDiscoveredDevices());
        if (mqttService != null) all.putAll(mqttService.getDiscoveredDevices());
        List<OpenBekenDevice> withIps = all.values().stream()
                .filter(d -> d.getIp() != null && !d.getIp().isEmpty())
                .collect(Collectors.toList());
        if (withIps.isEmpty()) {
            System.out.println("✗ No discovered devices with IPs. Run 'scan' or 'inventory' first.");
            return;
        }

        // Determine broker IP — explicit arg or auto-detect
        String brokerIp;
        if (parts.length > 1) {
            brokerIp = parts[1];
        } else {
            brokerIp = detectLocalIp();
        }
        if (brokerIp == null) {
            System.out.println("✗ Could not detect local IP. Please specify: configure-all <broker-ip>");
            return;
        }

        int mqttPort = 1883;
        String groupTopic = "openbeken";

        System.out.printf("\n→ Configuring %d device(s)...\n", withIps.size());
        System.out.printf("  MQTT Broker:  %s:%d\n", brokerIp, mqttPort);
        System.out.printf("  Group Topic:  %s\n", groupTopic);
        System.out.println("  Startup Cmd:  led_enableAll 1\n");

        int mqttConfigured = 0, mqttAlready = 0, mqttFail = 0;
        int ledUpdated = 0, ledAlready = 0, ledFail = 0;
        int pinsConfigured = 0, pinsAlready = 0, pinsFail = 0;
        int mapUpdated = 0, mapAlready = 0, mapFail = 0;

        for (OpenBekenDevice d : withIps) {
            String deviceIp = d.getIp();
            String deviceId = s(d.getDeviceId());
            System.out.printf("  %s (%s)\n", deviceId, deviceIp);

            // 1. Configure MQTT broker
            String clientId = "obk" + deviceIp.replace(".", "_");
            String mqttResult = discoveryService.configureMqtt(
                    deviceIp, brokerIp, mqttPort, clientId, groupTopic);
            switch (mqttResult) {
                case "configured":
                    System.out.printf("    ✓ MQTT broker → %s:%d (configured)\n", brokerIp, mqttPort);
                    mqttConfigured++;
                    break;
                case "already configured":
                    System.out.printf("    ✓ MQTT broker → %s:%d (already set)\n", brokerIp, mqttPort);
                    mqttAlready++;
                    break;
                default:
                    System.out.printf("    ✗ MQTT config FAILED (%s)\n", mqttResult);
                    mqttFail++;
                    break;
            }

            // 2. Configure GPIO pins (auto-detects driver type)
            String pinResult = discoveryService.configureDriverPins(deviceIp);
            if (pinResult.startsWith("configured")) {
                System.out.println("    ✓ GPIO pins — " + pinResult);
                pinsConfigured++;
            } else if (pinResult.startsWith("already configured")) {
                System.out.println("    ✓ GPIO pins — " + pinResult);
                pinsAlready++;
            } else {
                System.out.printf("    ✗ GPIO pins — %s\n", pinResult);
                pinsFail++;
            }

            // 3. Ensure driver-specific channel mapping in startup command
            String mapResult = discoveryService.ensureChannelMapping(deviceIp);
            if (mapResult.startsWith("updated")) {
                System.out.println("    ✓ Channel mapping — " + mapResult);
                mapUpdated++;
            } else if (mapResult.startsWith("already configured")) {
                System.out.println("    ✓ Channel mapping — " + mapResult);
                mapAlready++;
            } else if (mapResult.startsWith("not needed")) {
                System.out.println("    ○ Channel mapping — " + mapResult);
                mapAlready++;
            } else {
                System.out.printf("    ✗ Channel mapping — %s\n", mapResult);
                mapFail++;
            }

            // 4. Ensure led_enableAll 1 in startup command
            String ledResult = discoveryService.ensureLedEnableOnStartup(deviceIp);
            switch (ledResult) {
                case "updated":
                    System.out.println("    ✓ led_enableAll 1 — Updated");
                    ledUpdated++;
                    break;
                case "already configured":
                    System.out.println("    ✓ led_enableAll 1 — Already set");
                    ledAlready++;
                    break;
                default:
                    System.out.printf("    ✗ led_enableAll FAILED (%s)\n", ledResult);
                    ledFail++;
                    break;
            }
        }

        System.out.println("\n╔════════════════════════════════════════════════════════════╗");
        System.out.println("║  Configuration Summary                                     ║");
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.printf("║  MQTT broker:     %d configured, %d already OK, %d failed  \n",
                mqttConfigured, mqttAlready, mqttFail);
        System.out.printf("║  led_enableAll:   %d updated, %d already OK, %d failed     \n",
                ledUpdated, ledAlready, ledFail);
        System.out.printf("║  GPIO pins:       %d configured, %d already OK, %d failed  \n",
                pinsConfigured, pinsAlready, pinsFail);
        System.out.printf("║  BP5758D_Map:     %d updated, %d already OK, %d failed     \n",
                mapUpdated, mapAlready, mapFail);
        System.out.println("╚════════════════════════════════════════════════════════════╝");

        if (mqttConfigured > 0 || pinsConfigured > 0 || mapUpdated > 0) {
            System.out.println("\n⚠  Devices with new settings need a restart to apply changes.");
            System.out.println("   Toggle power or use: curl http://<device-ip>/index?restart=1");
        }
    }

    /**
     * Auto-detect this machine's LAN IP address on the configured subnet.
     * Iterates network interfaces looking for an address matching the subnet prefix.
     * Falls back to InetAddress.getLocalHost() if no match found.
     */
    private String detectLocalIp() {
        try {
            // First, try to find an interface on our configured subnet
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    String ip = addr.getHostAddress();
                    if (ip.startsWith(subnet + ".")) {
                        return ip;
                    }
                }
            }
            // Fallback: any non-loopback IPv4 address
            ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
            // Last resort
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    // --- Connection config commands ---

    private void cmdBroker(String[] parts) {
        if (parts.length > 1) { brokerUrl = parts[1]; }
        System.out.println("MQTT Broker: " + brokerUrl);
    }

    private void cmdSubnet(String[] parts) {
        if (parts.length > 1) { subnet = parts[1]; }
        System.out.println("Subnet: " + subnet);
    }

    private void cmdStatus() {
        System.out.println("\n┌─────────────────────────────────────┐");
        System.out.println("│ Status                              │");
        System.out.println("├─────────────────────────────────────┤");
        System.out.printf("│ MQTT Broker:  %-22s│%n", brokerUrl);
        System.out.printf("│ MQTT Connected: %-20s│%n",
                mqttService != null && mqttService.isConnected() ? "✓ Yes" : "✗ No");
        System.out.printf("│ Subnet:       %-22s│%n", subnet);
        System.out.printf("│ HTTP Devices: %-22d│%n", discoveryService.getDiscoveredDevices().size());
        int mqttCount = mqttService != null ? mqttService.getDiscoveredDevices().size() : 0;
        System.out.printf("│ MQTT Devices: %-22d│%n", mqttCount);
        System.out.println("└─────────────────────────────────────┘");
    }

    // --- Helpers ---

    private void ensureMqttConnected(String url) throws Exception {
        if (mqttService == null) { mqttService = new MqttDiscoveryService(); }
        if (!mqttService.isConnected()) {
            System.out.println("→ Connecting to MQTT broker: " + url);
            System.out.println("  (Start broker first with: ./gradlew :mqtt:broker)");
            mqttService.connect(url);
            System.out.println("✓ Connected to MQTT broker");
        }
    }

    private void printDeviceDetail(OpenBekenDevice d) {
        System.out.println("\n┌─────────────────────────────────────────┐");
        System.out.println("│ Device Details                          │");
        System.out.println("├─────────────────────────────────────────┤");
        System.out.printf("│ Device ID:  %-28s│%n", s(d.getDeviceId()));
        System.out.printf("│ IP:         %-28s│%n", s(d.getIp()));
        System.out.printf("│ MAC:        %-28s│%n", s(d.getMac()));
        System.out.printf("│ Firmware:   %-28s│%n", s(d.getFirmware()));
        System.out.printf("│ Chipset:    %-28s│%n", s(d.getChipset()));
        System.out.printf("│ Hostname:   %-28s│%n", s(d.getHostname()));
        System.out.printf("│ MQTT Topic: %-28s│%n", s(d.getMqttTopic()));
        System.out.printf("│ MQTT Group: %-28s│%n", s(d.getMqttGroup()));
        System.out.printf("│ Online:     %-28s│%n", d.isOnline() ? "✓ Yes" : "✗ No");
        System.out.printf("│ Power:      %-28s│%n", s(d.getPowerState()));
        System.out.printf("│ Dimmer:     %-28s│%n", d.getDimmer() != null ? d.getDimmer() + "%" : "N/A");
        System.out.printf("│ Method:     %-28s│%n", s(d.getDiscoveryMethod()));
        System.out.printf("│ Last Seen:  %-28s│%n", d.getLastSeen() != null ? d.getLastSeen().toString() : "N/A");
        System.out.println("└─────────────────────────────────────────┘");
    }

    private String s(String v) { return v != null ? v : "N/A"; }
    private String trunc(String v, int max) {
        if (v == null) return "N/A";
        return v.length() <= max ? v : v.substring(0, max - 2) + "..";
    }

    // --- Animation commands ---

    private void cmdRainbow(String[] parts) throws Exception {
        List<String> deviceIds = new ArrayList<>();
        int cycles = 3;
        long delayMs = 50;

        // Check if called with no device IDs or with "rotate" keyword
        // e.g. "rainbow rotate", "rainbow", "rotate" — auto-load all discovered devices
        boolean autoLoad = (parts.length == 1) ||
                (parts.length == 2 && parts[1].equalsIgnoreCase("rotate"));

        if (autoLoad) {
            deviceIds = loadDiscoveredDeviceIds();
            if (deviceIds.isEmpty()) {
                System.out.println("✗ No discovered devices found.");
                System.out.println("  Run 'scan', 'inventory', or 'probe <ip>' first to discover devices.");
                System.out.println("  Or specify devices manually:");
                System.out.println("  Usage: rainbow <device1> <device2> <device3> [cycles] [delayMs]");
                return;
            }
        } else {
            // Collect device IDs (everything that doesn't parse as a number)
            for (int i = 1; i < parts.length; i++) {
                try {
                    int val = Integer.parseInt(parts[i]);
                    // First number = cycles, second = delay
                    if (cycles == 3 && deviceIds.size() >= 2) { cycles = val; }
                    else if (deviceIds.size() >= 2) { delayMs = val; }
                    else { deviceIds.add(parts[i]); }
                } catch (NumberFormatException e) {
                    deviceIds.add(parts[i]);
                }
            }
            if (deviceIds.size() < 2) {
                System.out.println("Usage: rainbow <device1> <device2> <device3> [cycles] [delayMs]");
                System.out.println("  Or:  rainbow rotate   — auto-load all discovered devices from cache");
                System.out.println("  Example: rainbow obk1 obk2 obk3");
                System.out.println("  Example: rainbow obk1 obk2 obk3 5 30");
                return;
            }
        }

        ensureMqttConnected(brokerUrl);
        if (colorRotation == null) {
            colorRotation = new ColorRotationAnimation(mqttService);
        }

        System.out.println("\n╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║        🌈 Color Rotation Animation                       ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.printf("  Devices:    %d bulbs\n", deviceIds.size());
        for (String id : deviceIds) {
            System.out.printf("              → %s\n", id);
        }
        System.out.printf("  Hue Offset: %d° per bulb (%d bulbs)\n", 360 / deviceIds.size(), deviceIds.size());
        System.out.printf("  Cycles:     %s\n", cycles == 0 ? "∞ (infinite — use 'stop-anim' to stop)" : cycles);
        System.out.printf("  Delay:      %dms (%d Hz)\n", delayMs, 1000 / Math.max(delayMs, 1));
        System.out.printf("  Hue Step:   10°  (36 frames per cycle)\n");
        System.out.println();

        colorRotation.start(deviceIds, cycles, 10, delayMs, 100, 50);
        System.out.println("✓ Animation started! Use 'stop-anim' or Ctrl-C to stop.");
    }

    /**
     * Load MQTT topic IDs from the discovery cache (.openbeken-cache.json).
     * The cache is automatically loaded by OpenBekenDiscoveryService on startup.
     * Also includes any MQTT-discovered devices if connected.
     *
     * Uses the mqttTopic field if available (IP-based topic from configure-all),
     * otherwise derives from current IP, or falls back to deviceId.
     */
    private List<String> loadDiscoveredDeviceIds() {
        Map<String, OpenBekenDevice> all = new HashMap<>(discoveryService.getDiscoveredDevices());
        if (mqttService != null) {
            all.putAll(mqttService.getDiscoveredDevices());
        }
        List<String> ids = new ArrayList<>();
        for (OpenBekenDevice d : all.values()) {
            String mqttTopic = null;
            String ip = d.getIp();
            
            // Priority 1: Use saved mqttTopic if it matches current IP
            if (d.getMqttTopic() != null && ip != null) {
                String expectedTopic = "obk" + ip.replace(".", "_");
                if (d.getMqttTopic().equals(expectedTopic)) {
                    mqttTopic = d.getMqttTopic();
                }
            }
            
            // Priority 2: Derive from current IP
            if (mqttTopic == null && ip != null && !ip.isEmpty()) {
                mqttTopic = "obk" + ip.replace(".", "_");
            }
            
            // Priority 3: Fall back to deviceId
            if (mqttTopic == null && d.getDeviceId() != null) {
                mqttTopic = d.getDeviceId();
            }
            
            if (mqttTopic != null) {
                ids.add(mqttTopic);
                System.out.printf("  📡 %s → MQTT: %s (%s)\n", 
                    d.getDeviceId() != null ? d.getDeviceId() : "?",
                    mqttTopic,
                    ip != null ? ip : "N/A");
            }
        }
        return ids;
    }

    /**
     * Load bulb MQTT device IDs from devices.json.
     * Filters to category "dj" (lights) with valid IPs, and derives the MQTT
     * client ID using the same pattern as configure-all: obk{ip_with_underscores}
     */
    private List<String> loadBulbIdsFromDevicesJson(String path) {
        List<String> ids = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String json = sb.toString();
            // Simple JSON parsing — extract category, ip, and name for each device
            // Split by each object boundary
            int idx = 0;
            while (true) {
                int start = json.indexOf("{", idx);
                if (start < 0) break;
                // Find the matching close brace at depth 1
                int depth = 0;
                int end = -1;
                for (int i = start; i < json.length(); i++) {
                    if (json.charAt(i) == '{') depth++;
                    else if (json.charAt(i) == '}') {
                        depth--;
                        if (depth == 0) { end = i; break; }
                    }
                }
                if (end < 0) break;
                String obj = json.substring(start, end + 1);
                idx = end + 1;

                // Check category is "dj" (light bulb)
                String category = extractJsonString(obj, "category");
                if (!"dj".equals(category)) continue;

                // Get IP
                String ip = extractJsonString(obj, "ip");
                if (ip == null || ip.isEmpty()) continue;

                // Get name for display
                String name = extractJsonString(obj, "name");

                // Derive MQTT device ID: same as configure-all pattern
                String mqttId = "obk" + ip.replace(".", "_");
                ids.add(mqttId);
                System.out.printf("  📡 %s (%s) → MQTT: %s\n", name, ip, mqttId);
            }
        } catch (Exception e) {
            System.out.println("✗ Error loading " + path + ": " + e.getMessage());
        }
        return ids;
    }

    /**
     * Extract a simple top-level string value from a JSON object string.
     * Only matches the FIRST occurrence of "key":"value" (not nested).
     */
    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        // Find the colon after the key
        int colon = json.indexOf(":", idx + pattern.length());
        if (colon < 0) return null;
        // Find the opening quote of the value
        int qStart = json.indexOf("\"", colon + 1);
        if (qStart < 0) return null;
        int qEnd = json.indexOf("\"", qStart + 1);
        if (qEnd < 0) return null;
        return json.substring(qStart + 1, qEnd);
    }

    private void cmdStopAnim() {
        if (colorRotation == null || !colorRotation.isRunning()) {
            System.out.println("No animations running.");
            return;
        }
        colorRotation.stopAll();
        System.out.println("✓ All animations stopped.");
    }

    private void cmdAnimations() {
        if (colorRotation == null || !colorRotation.isRunning()) {
            System.out.println("No animations running.");
            return;
        }
        var keys = colorRotation.getRunningKeys();
        System.out.printf("\nRunning animations: %d\n", keys.size());
        for (String key : keys) {
            System.out.println("  ▶ " + key);
        }
    }

    private void cleanup() {
        if (colorRotation != null) { colorRotation.stopAll(); }
        if (mqttService != null) { mqttService.disconnect(); }
        if (embeddedBroker != null) { embeddedBroker.stop(); }
    }

    /**
     * Console-based discovery listener for progress output.
     */
    private static class ConsoleDiscoveryListener implements OpenBekenDiscoveryService.DiscoveryListener {
        @Override public void onScanStarted(int total) {
            System.out.printf("  Scanning %d targets...\n", total);
        }
        @Override public void onScanProgress(int scanned, int total) {
            System.out.printf("  Progress: %d/%d (%.0f%%)\r", scanned, total, (100.0 * scanned / total));
        }
        @Override public void onDeviceFound(OpenBekenDevice device) {
            System.out.printf("  ✓ Found: %-20s at %s\n", device.getDeviceId(), device.getIp());
        }
        @Override public void onScanComplete(int foundCount) {
            System.out.printf("  Scan finished: %d device(s) found\n", foundCount);
        }
    }
}
