# MQTT Light Animation Engine

**High-speed coordinated lighting animations using jailbroken Tuya bulbs, OpenBeken firmware, and MQTT.**

## Overview

This module is the consolidation point for our home lighting automation platform, specifically optimized for custom high-speed animations across large numbers of smart bulbs. After evaluating several protocols and device ecosystems (Zigbee, native Tuya, Philips Hue, Tasmota), we've settled on the following stack as the best approach:

```
┌─────────────────────────────────────────────────────┐
│                  Animation Engine                    │
│              (Java / MQTT Client)                    │
│   Pulse · Wave · Chase · Color Rotation · Sweep     │
└──────────────────────┬──────────────────────────────┘
                       │ MQTT QoS 0 (fire-and-forget)
                       ▼
              ┌─────────────────┐
              │   MQTT Broker   │
              │   (Moquette)    │
              │  localhost:1883  │
              └────────┬────────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
   ┌─────────┐   ┌─────────┐   ┌─────────┐
   │ OpenBK  │   │ OpenBK  │   │ OpenBK  │  ...  (40+ bulbs)
   │ Bulb 1  │   │ Bulb 2  │   │ Bulb 3  │
   │ BK7231N │   │ BK7231N │   │ BK7231N │
   └─────────┘   └─────────┘   └─────────┘
```

## Why This Stack?

| Approach | Verdict | Notes |
|----------|---------|-------|
| **Zigbee (direct)** | ❌ Too slow | Mesh routing adds latency, poor for real-time animations |
| **Tuya Cloud API** | ❌ Rate limited | Cloud round-trip + API limits = unusable for animation |
| **Tuya Local (tinytuya)** | ⚠️ Decent | Works but protocol overhead limits update rate |
| **Philips Hue** | ❌ Expensive | 10 updates/sec hard limit per bridge, $$$$ per bulb |
| **Tasmota + MQTT** | ⚠️ Good | Great but limited BK7231N support |
| **OpenBeken + MQTT** | ✅ Best | Native BK7231N support, fast MQTT, full RGB+CCT, 20-50 Hz |

### Key Advantages
- **MQTT QoS 0**: Fire-and-forget = ~200-500 updates/sec per device
- **Group topics**: One publish reaches all devices simultaneously
- **Embedded broker**: No external dependencies (Moquette runs in-process)
- **Low latency**: Sub-10ms on local network
- **Cheap hardware**: ~$5-8 per Daybetter RGB+CCT bulb (A19, 9W)

## Device Pipeline

### Step 1: Acquire Tuya-Based Bulbs

We use **Daybetter RGBCT bulbs** (120V A19 9W) — BK7231N chipset. These are readily available and confirmed compatible.

- **Product**: `120V A19 9W SMART BULB` (product_id: `a7vjsrgl5medf61u`)
- **Chipset**: BK7231N
- **Capabilities**: RGB + Cool/Warm White, Brightness 10-1000, HSV color, Scene modes

We also use **Maxxima LED 6" Slim Panels** (12W RGB+CCT, product_id: `emudcue8z8wxbwla`) for recessed lighting.

### Step 2: Jailbreak with CloudCutter

Flash OpenBeken firmware over-the-air using [tuya-cloudcutter](https://github.com/tuya-cloudcutter/tuya-cloudcutter). No disassembly required.

**Hardware**: Orange Pi Zero (ARM-based SBC with WiFi for AP spoofing)

```bash
# On the Orange Pi:
cd tuya-cloudcutter
./tuya-cloudcutter.sh -w wlan1

# Select:
#   Device Slug:  daybetter-rgbct-bulb-v1.2.16
#   Profile:      oem-bk7231n-light-ty-1.2.16-sdk-2.3.1-40.00
#   Firmware:     OpenBeken-v1.18.219_bk7231n.ug.bin
```

See [ORANGEPI_CLOUDCUTTER_GUIDE.md](../ORANGEPI_CLOUDCUTTER_GUIDE.md) for the full flashing procedure.

### Step 3: Configure MQTT on Each Device

Once flashed, each bulb runs a web interface. Configure MQTT to point at our broker:

```bash
# Automated setup via HTTP
DEVICE_IP="192.168.86.66"  # find via router or OpenBeken AP
curl "http://$DEVICE_IP/cfg_mqtt?mqtt_host=192.168.1.5&mqtt_port=1883&mqtt_client_id=obk$(echo $DEVICE_IP | tr '.' '_')&mqtt_group=animations"
curl "http://$DEVICE_IP/index?restart=1"
```

**MQTT Settings per device:**
| Setting | Value |
|---------|-------|
| Host | `192.168.1.5` (broker machine) |
| Port | `1883` |
| Client Topic | `obk{DEVICE_MAC_SUFFIX}` (unique per device) |
| Group Topic | `animations` (shared — enables broadcast) |
| QoS | 0 |

See [OPENBEKEN_MQTT_SETUP.md](../philips/OPENBEKEN_MQTT_SETUP.md) for detailed configuration instructions.

### Step 4: Animate

Control devices via MQTT commands:

```bash
# Turn on a single device
mosquitto_pub -h localhost -p 1883 -t 'cmnd/obk17811957/POWER1' -m '1'

# Set color (HSB: Hue 0-360, Saturation 0-100, Brightness 0-100)
mosquitto_pub -h localhost -p 1883 -t 'cmnd/obk17811957/HsbColor' -m '120,100,50'

# Broadcast to ALL devices in the animation group
mosquitto_pub -h localhost -p 1883 -t 'cmnd/animations/POWER1' -m '1'

# Brightness sweep (all devices at once)
for b in $(seq 0 5 100); do
  mosquitto_pub -h localhost -p 1883 -t 'cmnd/animations/Dimmer' -m "$b"
  sleep 0.03
done
```

## OpenBeken Device Discovery CLI

The `mqtt` module now includes an interactive CLI for discovering and controlling OpenBeken devices.

### Quick Start

```bash
# Run the interactive CLI
./gradlew :mqtt:run

# Or run a single command
./gradlew :mqtt:run --args="scan 192.168.86"
./gradlew :mqtt:run --args="inventory"
./gradlew :mqtt:run --args="probe 192.168.86.66"
```

### CLI Commands

| Command | Description |
|---------|-------------|
| `scan [subnet] [start] [end]` | Scan subnet for OpenBeken devices via HTTP (default: 192.168.86.1-254) |
| `inventory [path]` | Load `devices.json` and probe all known IPs for OpenBeken firmware |
| `probe <ip>` | Probe a single IP address for OpenBeken |
| `mqtt-discover [broker-url]` | Connect to MQTT broker and listen for device traffic |
| `mqtt-listen [seconds]` | Watch raw MQTT messages for N seconds (default: 10) |
| `list` / `ls` | List all discovered devices |
| `info <device-id\|ip>` | Show detailed info for a device |
| `on <device-id>` | Turn device on via MQTT |
| `off <device-id>` | Turn device off via MQTT |
| `dimmer <device-id> <0-100>` | Set brightness via MQTT |
| `color <device-id> <h,s,b>` | Set HSB color via MQTT |
| `broker [url]` | Show/set MQTT broker URL |
| `subnet [prefix]` | Show/set subnet prefix for scanning |
| `status` | Show connection status |

### Discovery Methods

1. **HTTP Scan** (`scan`) — Probes each IP on a subnet for OpenBeken's web interface on port 80. Uses 50 concurrent threads for fast scanning (~5 seconds for a /24 subnet).

2. **Inventory Probe** (`inventory`) — Loads `devices.json` (Tuya device inventory) and probes each known IP to check if it's been flashed with OpenBeken firmware.

3. **MQTT Passive Discovery** (`mqtt-discover`) — Connects to the MQTT broker and listens for device traffic. Identifies devices by their topic prefixes (e.g., `stat/obk17811957/POWER1`).

4. **Single Probe** (`probe`) — Tests one specific IP address.

### Example Session

```
╔═══════════════════════════════════════════════════════╗
║       OpenBeken Device Discovery CLI                  ║
║  Discover & control OpenBeken devices via MQTT/HTTP   ║
║  Type 'help' for commands                             ║
╚═══════════════════════════════════════════════════════╝

obk> inventory
→ Loading device inventory from devices.json...
  Found 40 devices in inventory
│ Name     │ IP               │ MAC                │ Product                        │
│ O3       │ 192.168.86.58    │ 10:5a:17:81:19:57  │ 120V A19 9W SMART BULB         │
│ K1       │ 192.168.86.35    │ 9c:9c:1f:82:5d:55  │ Maxxima LED 6" Slim Panel...   │
...
→ Probing 38 IPs for OpenBeken firmware...
  ✓ Found: obk17811957          at 192.168.86.66
✓ Probe complete. 1 of 38 devices running OpenBeken

obk> on obk17811957
✓ Sent POWER1=1 to obk17811957

obk> color obk17811957 120,100,50
✓ Sent HsbColor=120,100,50 to obk17811957
```

## Project Structure

```
mqtt/
├── README.md                          # This file
├── build.gradle                       # Dependencies (Paho MQTT, application plugin)
└── src/
    ├── main/
    │   ├── java/
    │   │   ├── com/openbeken/
    │   │   │   ├── cli/
    │   │   │   │   └── OpenBekenCLI.java          # Interactive CLI entry point
    │   │   │   ├── discovery/
    │   │   │   │   ├── OpenBekenDiscoveryService.java  # HTTP scanning & inventory probe
    │   │   │   │   └── MqttDiscoveryService.java       # MQTT-based device discovery
    │   │   │   └── model/
    │   │   │       └── OpenBekenDevice.java        # Device model
    │   │   ├── Main.java              # Legacy entry point
    │   │   └── ZigbeeMqttClient.java  # Legacy MQTT client (to be refactored)
    │   └── resources/
    └── test/
```

### Related Code in `philips/` (to be migrated here)

The `philips` module contains working implementations that should be consolidated into this module:

| File | Purpose | Status |
|------|---------|--------|
| `OpenBekenAnimationService.java` | Animation engine (pulse, wave, chase, color rotation, brightness sweep) | **Working** — migrate here |
| `MoquetteBrokerService.java` | Embedded MQTT broker (Moquette) | **Working** — migrate here |
| `MqttDeviceService.java` | Device discovery, state tracking, command dispatch | **Working** — migrate here |
| `OpenBekenAnimationController.java` | REST API for triggering animations | **Working** — migrate here |

## Available Animations

Implemented in `OpenBekenAnimationService` (currently in `philips/`):

| Animation | Description | Best For |
|-----------|-------------|----------|
| **Pulse** | Rapid on/off blinking | Attention, alerts |
| **Brightness Sweep** | Smooth fade up/down (20-30 Hz) | Ambient mood |
| **Wave** | Sequential activation across devices | Spatial effects |
| **Color Rotation** | HSB hue cycle (30-60 Hz) | Party mode, ambiance |
| **Chase** | One-at-a-time running light | Directional effects |
| **Synchronized Flash** | All devices flash together | Impact, sync |

## OpenBeken MQTT Command Reference

```bash
# Power
cmnd/{device}/POWER1    →  0 | 1 | 2(toggle)

# Dimmer (0-100)
cmnd/{device}/Dimmer    →  50

# Color (HSB)
cmnd/{device}/HsbColor  →  {hue},{saturation},{brightness}
                            e.g. "120,100,50" = green at 50% brightness

# Speed/Transition
cmnd/{device}/Speed     →  5
```

## Performance Notes

| Metric | HTTP | MQTT QoS 0 |
|--------|------|------------|
| Single device update rate | 50-100/sec | 200-500/sec |
| 10-device broadcast | 5-10/sec each | 100+/sec each |
| Bytes per command | ~500 | ~50-100 |
| Connection overhead | Per-request TCP | Persistent |

**Practical limits**: OpenBeken devices reliably handle **20-50 updates/second**. Beyond that, WiFi congestion and device processing become bottlenecks.

See [OPENBEKEN_PERFORMANCE_GUIDE.md](../philips/OPENBEKEN_PERFORMANCE_GUIDE.md) for detailed benchmarks.

## Device Inventory

We have **40+ Tuya devices** across the house (see `devices.json`):

**Bulb Types:**
- **Daybetter A19 9W** — Standard RGB+CCT smart bulbs (BK7231N chipset)
- **Maxxima 6" Slim Panel 12W** — Recessed RGB+CCT ceiling lights

**Device Groups:**
| Prefix | Location | Count |
|--------|----------|-------|
| O1-O3 | Various | 3 |
| K1-K6 | Kitchen | 6 |
| L1-L6 | Living room | 6 |
| P1-P4 | Panels | 4 |
| Lp1-Lp6 | Lamp posts | 6 |
| Pool table 1-3 | Pool room | 3 |
| B1-B2, Br3 | Bedrooms | 3 |
| H1, G1-G2 | Hall/Garage | 3 |
| Star2, Star3 | Decorative | 2 |
| Bp1, Kp1-Kp2 | Kitchen pendants | 3 |
| Wok1, Patio | Outdoor | 2 |

**Already flashed**: `obk17811957` (O3) — confirmed working with OpenBeken + MQTT

## Building & Running

```bash
# Build the mqtt module
./gradlew :mqtt:build

# Run (currently the basic MQTT client)
./gradlew :mqtt:run
```

## Dependencies

```groovy
// build.gradle
dependencies {
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
```

## Development Notes

> **⚠️ Large file generation note**: When using AI-assisted code generation tools (e.g., Cline), Java source files in this module should be kept under ~200 lines each. Larger files can exceed output token limits and fail to write. If a class is growing large, break it into smaller files (e.g., separate command handler classes, helper utilities) and use `replace_in_file` for incremental edits rather than full file rewrites.

## Roadmap

- [ ] **Migrate animation services** from `philips/` into this module
- [ ] **Refactor `ZigbeeMqttClient`** → `OpenBekenMqttClient` with QoS 0 defaults
- [ ] **Embed Moquette broker** in this module (currently in `philips/`)
- [ ] **Batch flash remaining bulbs** via CloudCutter (40+ devices)
- [ ] **Device registry** — track flashed vs. stock firmware devices
- [ ] **Animation sequencer** — define complex multi-device animation timelines
- [ ] **Web UI** for live animation control and device management
- [ ] **Audio-reactive mode** — sync animations to music input

## Key Documentation

| Document | Description |
|----------|-------------|
| [ORANGEPI_CLOUDCUTTER_GUIDE.md](../ORANGEPI_CLOUDCUTTER_GUIDE.md) | How to flash bulbs with the Orange Pi |
| [CLOUDCUTTER_DOCKER_EXPLAINED.md](../CLOUDCUTTER_DOCKER_EXPLAINED.md) | Docker setup for CloudCutter |
| [OPENBEKEN_MQTT_SETUP.md](../philips/OPENBEKEN_MQTT_SETUP.md) | Configuring MQTT on flashed devices |
| [OPENBEKEN_PERFORMANCE_GUIDE.md](../philips/OPENBEKEN_PERFORMANCE_GUIDE.md) | HTTP vs MQTT benchmarks |
| [DEVICE_SETUP_COMPLETE.md](../DEVICE_SETUP_COMPLETE.md) | First device setup walkthrough |
| [TASMOTA_FLASH_GUIDE.md](../TASMOTA_FLASH_GUIDE.md) | Tasmota alternative (deprecated in favor of OpenBeken) |

## Quick Test

```bash
# 1. Start the broker (if using the philips module for now)
./gradlew :philips:bootRun &

# 2. Monitor all MQTT messages
mosquitto_sub -h localhost -p 1883 -t '#' -v &

# 3. Send a color rotation to all devices in the animation group
for hue in $(seq 0 10 360); do
  mosquitto_pub -h localhost -p 1883 -t 'cmnd/animations/HsbColor' -m "$hue,100,50"
  sleep 0.03
done
```
