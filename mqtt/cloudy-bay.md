# Cloudy Bay — Driver Detection & Multi-Bulb Configuration

> ⚠️ **Note**: The top section of this document was lost when it was originally pasted in.
> The content below picks up mid-way through what appears to be a PWM bulb configuration
> summary. Please restore or rewrite the introductory section covering the session context,
> goals, and full PWM pin reference when possible.

## PWM Bulb Pin Configuration

> *(Note: top of this section was lost — context above this point is missing)*

PWM bulbs use 5 pins for independent RGB + Cool White + Warm White control.
Configured by `configurePWMPins()` in `OpenBekenDiscoveryService`:

| Pin | Role | Channel | Color |
|-----|------|---------|-------|
| P6  | PWM (role 7) | 5 | Warm White |
| P7  | PWM (role 7) | 4 | Cool White |
| P8  | PWM (role 7) | 1 | Blue |
| P24 | PWM (role 7) | 2 | Red |
| P26 | PWM (role 7) | 3 | Green |

- **Startup Command**: Only `led_enableAll 1` (no channel mapping needed — PWM uses direct pin channels)
- **Known Issue**: New PWM bulb (192.168.86.111) has dimmer controls but missing RGB controls — see Problem 3 below (✅ resolved)

## Implementation Changes

### 1. Created MAC Prefix Mapping System
**File**: `mqtt/src/main/resources/driver-mappings.json`
```json
{
  "mac_prefixes": {
    "10:5a:17": "BP5758D",
    "38:1f:8d": "BP5758D",
    "38:2c:e5": "PWM"
  }
}
```

### 2. Enhanced OpenBekenDiscoveryService

#### Added Driver Detection
- `loadDriverMappings()` - Loads MAC prefix mappings on startup
- `detectDriverTypeByMac(String mac)` - Looks up driver type from MAC
- `detectDriverType(String ip)` - Tries MAC first, then pin inspection
- `DriverType` enum: BP5758D, PWM, SM2135, UNKNOWN

#### Added Driver Configuration Methods
- `configureBP5758DPins(String ip)` - Sets P7=CLK, P8=DAT for I2C driver
- `configurePWMPins(String ip)` - Sets P6,P7,P8,P24,P26 to PWM role
- `configureDriverPins(String ip)` - Smart dispatcher based on MAC detection
- `ensureChannelMapping(String ip)` - Adds driver-specific channel maps (BP5758D only)

#### Key Logic Flow
```java
configureDriverPins(ip):
1. Get MAC address from device
2. Look up driver type in mappings
3. If PWM → call configurePWMPins()
4. If BP5758D → call configureBP5758DPins()
5. Each method checks if already configured before applying
```

### 3. Updated configure-all Command

Now configures 4 settings per device:
1. **MQTT Broker** - Points to local broker
2. **GPIO Pins** - Auto-detects driver type and configures pins
3. **Channel Mapping** - Adds BP5758D_Map if needed (BP5758D only)
4. **led_enableAll** - Ensures LED turns on at startup

Output example:
```
obk17814362 (192.168.86.205)
  ✓ MQTT broker → <BROKER_IP>:1883 (configured)
  ✓ GPIO pins — configured (BP5758D)
  ✓ Channel mapping — updated (BP5758D)
  ✓ led_enableAll 1 — Updated
```

## Problems Encountered & Solutions

### Problem 1: Red/Blue Swap on BP5758D Bulbs
**Symptom**: New BP5758D bulb had wrong color order (red/blue swapped)

**Cause**: BP5758D driver needs channel mapping to specify which internal channel controls which color

**Solution**: Added `BP5758D_Map 2 1 0 4 5` to startup command
- Channel 2 = Red
- Channel 1 = Green  
- Channel 0 = Blue
- Channel 4 = Cool White
- Channel 5 = Warm White

**Implementation**: `ensureChannelMapping()` method in OpenBekenDiscoveryService

### Problem 2: Initial Logic Flaw in configureDriverPins()
**Symptom**: PWM bulb showed "already configured (PWM)" but pins weren't actually set

**Cause**: `detectDriverType()` returning PWM from MAC lookup was interpreted as "pins already configured"

**Fix**: Separated detection from configuration:
```java
// OLD (wrong):
if (detectDriverType() != UNKNOWN) {
    return "already configured";  // ← Wrong! MAC detection != pin configuration
}

// NEW (correct):
DriverType typeByMac = detectDriverTypeByMac(mac);
if (typeByMac == PWM) {
    return configurePWMPins(ip);  // ← Always try to configure
}
```

### Problem 3: PWM Bulb Missing RGB Controls
**Status**: ✅ SOLVED

**Root Cause**: PWM pins configured with wrong channel numbers

**Symptoms**:
- New PWM bulb (192.168.86.111) turns on/off ✓
- Dimmer controls work ✓
- RGB color controls missing ✗
- Only shows "Channel 0 = 100.00" instead of 5 separate channels

**Analysis**: Pin configuration comparison revealed the issue:

| Pin | Working (192.168.86.60) | Broken (192.168.86.111) |
|-----|------------------------|------------------------|
| P6  | PWM role, channel **5** | PWM role, channel **0** ❌ |
| P7  | PWM role, channel **4** | PWM role, channel **0** ❌ |
| P8  | PWM role, channel **1** | PWM role, channel **0** ❌ |
| P24 | PWM role, channel **2** | PWM role, channel **0** ❌ |
| P26 | PWM role, channel **3** | PWM role, channel **0** ❌ |

**Problem**: All pins assigned to channel 0 ties them together for simple dimming. Each pin needs its own channel number for independent RGB+CW+WW control.

**Solution**: Set proper channel assignments:
- P6 → channel 5 (Warm White)
- P7 → channel 4 (Cool White)
- P8 → channel 1 (Blue)
- P24 → channel 2 (Red)
- P26 → channel 3 (Green)

**Fix Applied**: Updated configurePWMPins() to include channel numbers in URL

**Verification** (2026-05-12):
- Applied fix to 192.168.86.111 via curl with channel parameters (&r6=5, &r7=4, &r8=1, &r24=2, &r26=3)
- Device restarted
- ✅ RGB color picker now appears with [ACTIVE] tag
- ✅ Temperature slider present
- ✅ Channels now show as: "Channel 1 = 100.00, Channel 2 = 100.00, Channel 3 = 0.00, Channel 4 = 0.00, Channel 5 = 0.00"
- ✅ **Issue RESOLVED** - Full RGB control restored

## Configuration Commands

### Manual Configuration (if needed)

#### Set BP5758D Pins
```bash
curl "http://<ip>/cfg_pins?0=0&1=0&2=0&3=0&4=0&5=0&6=0&7=24&8=23&9=0&10=0&11=0&12=0&13=0&14=0&15=0&16=0&17=0&18=0&19=0&20=0&21=0&22=0&23=0&24=0&25=0&26=0&27=0&28=0"
```

#### Set PWM Pins  
```bash
curl "http://<ip>/cfg_pins?0=0&1=0&2=0&3=0&4=0&5=0&6=7&7=7&8=7&9=0&10=0&11=0&12=0&13=0&14=0&15=0&16=0&17=0&18=0&19=0&20=0&21=0&22=0&23=0&24=7&25=0&26=7&27=0&28=0"
```

#### Set Startup Commands
```bash
# BP5758D bulb
curl "http://<ip>/startup_command?data=backlog+BP5758D_Map+2+1+0+4+5%3B+led_enableAll+1&startup_cmd=1"

# PWM bulb
curl "http://<ip>/startup_command?data=led_enableAll+1&startup_cmd=1"
```

#### Restart Device
```bash
curl "http://<ip>/index?restart=1"
```

### Using CLI Tool
```bash
# Scan for devices
./gradlew :mqtt:run --args="scan 192.168.86 1 254"

# Configure all discovered devices
./gradlew :mqtt:run --args="configure-all"

# List devices
./gradlew :mqtt:run --args="list"
```

## File Locations

- **Driver Mappings**: `mqtt/src/main/resources/driver-mappings.json`
- **Discovery Service**: `mqtt/src/main/java/com/openbeken/discovery/OpenBekenDiscoveryService.java`
- **CLI Tool**: `mqtt/src/main/java/com/openbeken/cli/OpenBekenCLI.java`
- **Device Cache**: `.openbeken-cache.json`

## Useful Debugging URLs

- Device index: `http://<ip>/index`
- Pin configuration: `http://<ip>/cfg_pins`
- Startup command: `http://<ip>/startup_command`
- MQTT config: `http://<ip>/cfg_mqtt`

## Testing Checklist

For new bulbs after configuration:

- [ ] Pins configured correctly (check /cfg_pins)
- [ ] Startup command set (check /startup_command)
- [ ] Device restarted
- [ ] Driver shows as active on index page ("X drivers active")
- [ ] MQTT connected (shows green on index page)
- [ ] On/Off control works
- [ ] Dimmer control works
- [ ] RGB color control works (if RGB bulb)

## Next Session TODO

1. ~~**Diagnose PWM RGB issue** on 192.168.86.111~~ ✅ **RESOLVED** (2026-05-12)
   - Root cause: all PWM pins were assigned to channel 0 instead of channels 1–5
   - Fix: updated `configurePWMPins()` to assign correct channel numbers per pin
   - See **Problem 3** above for full details and verification steps

2. **Test with more bulb models**
   - Add new MAC prefixes to driver-mappings.json as discovered
   - Verify auto-configuration works for each type

3. **Consider enhancements**
   - Add SM2135 driver support if needed
   - Add driver-specific RGB channel ordering if needed
   - Create troubleshooting guide for common issues
