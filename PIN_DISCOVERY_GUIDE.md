# OpenBeken Pin Discovery Guide

## Problem
You have a bulb at **http://192.168.86.30/** with MAC prefix **38:1f:8d** that's currently mapped to BP5758D driver, but the BP5758D pin configuration (P7=CLK, P8=DAT) isn't working.

## Discovery Methods

### Method 1: Check Current Pin Configuration
First, let's see what pins are currently configured:

```bash
# View current pin configuration
curl -s "http://192.168.86.30/cfg_pins" | grep -i "role"

# Or visit in browser:
open "http://192.168.86.30/cfg_pins"
```

Look for pins with non-zero roles. Common roles:
- **Role 7** = PWM (for direct LED control)
- **Role 23** = I2C_DAT (for I2C drivers like BP5758D)
- **Role 24** = I2C_CLK (for I2C drivers like BP5758D)

### Method 2: Check Device Index Page
View the device's main page to see active drivers:

```bash
# Check device status
curl -s "http://192.168.86.30/index" | grep -i "driver"

# Or visit in browser:
open "http://192.168.86.30/"
```

Look for text like:
- "BP5758D driver active"
- "SM2135 driver active"  
- "PWM driver active"
- "X drivers active"

If it says "0 drivers active", the current pin configuration isn't working.

### Method 3: Try PWM Configuration
Many bulbs use direct PWM control instead of I2C drivers. Let's try configuring it as a PWM bulb:

```bash
# Configure pins for PWM (5 channels: RGB + Cool White + Warm White)
# P6=WW, P7=CW, P8=B, P24=R, P26=G
curl "http://192.168.86.30/cfg_pins?0=0&1=0&2=0&3=0&4=0&5=0&6=7&7=7&8=7&9=0&10=0&11=0&12=0&13=0&14=0&15=0&16=0&17=0&18=0&19=0&20=0&21=0&22=0&23=0&24=7&25=0&26=7&27=0&28=0&r6=5&r7=4&r8=1&r24=2&r26=3"

# Set startup command
curl "http://192.168.86.30/startup_command?data=led_enableAll+1&startup_cmd=1"

# Restart device
curl "http://192.168.86.30/index?restart=1"
```

Wait 10 seconds for restart, then check:
```bash
open "http://192.168.86.30/"
```

### Method 4: Try SM2135 Configuration
SM2135 is another common I2C LED driver (similar to BP5758D):

```bash
# Configure for SM2135 (P7=CLK, P8=DAT)
curl "http://192.168.86.30/cfg_pins?0=0&1=0&2=0&3=0&4=0&5=0&6=0&7=130&8=129&9=0&10=0&11=0&12=0&13=0&14=0&15=0&16=0&17=0&18=0&19=0&20=0&21=0&22=0&23=0&24=0&25=0&26=0&27=0&28=0"

# Set startup command for SM2135
curl "http://192.168.86.30/startup_command?data=backlog+SM2135_Map+2+1+0+4+5%3B+led_enableAll+1&startup_cmd=1"

# Restart
curl "http://192.168.86.30/index?restart=1"
```

### Method 5: Try Alternative I2C Pins
Some devices use different pins for I2C communication:

```bash
# Try P6=DAT, P7=CLK for BP5758D
curl "http://192.168.86.30/cfg_pins?0=0&1=0&2=0&3=0&4=0&5=0&6=23&7=24&8=0&9=0&10=0&11=0&12=0&13=0&14=0&15=0&16=0&17=0&18=0&19=0&20=0&21=0&22=0&23=0&24=0&25=0&26=0&27=0&28=0"

# Or try P24=CLK, P26=DAT for BP5758D
curl "http://192.168.86.30/cfg_pins?0=0&1=0&2=0&3=0&4=0&5=0&6=0&7=0&8=0&9=0&10=0&11=0&12=0&13=0&14=0&15=0&16=0&17=0&18=0&19=0&20=0&21=0&22=0&23=0&24=24&25=0&26=23&27=0&28=0"

# Set startup command
curl "http://192.168.86.30/startup_command?data=backlog+BP5758D_Map+2+1+0+4+5%3B+led_enableAll+1&startup_cmd=1"

# Restart
curl "http://192.168.86.30/index?restart=1"
```

## Systematic Discovery Process

### Step 1: Get Current State
```bash
echo "=== Current Pin Configuration ==="
curl -s "http://192.168.86.30/cfg_pins"

echo -e "\n\n=== Device Status ==="
curl -s "http://192.168.86.30/index" | grep -E "(driver|MQTT|Channel)"

echo -e "\n\n=== Startup Command ==="
curl -s "http://192.168.86.30/startup_command"
```

### Step 2: Test Basic Functionality
After trying each configuration, test if the bulb responds:

```bash
# Try to turn on
curl "http://192.168.86.30/cm?cmnd=POWER1%201"
sleep 2

# Try to set brightness
curl "http://192.168.86.30/cm?cmnd=dimmer%2050"
sleep 2

# Try to set color (if RGB)
curl "http://192.168.86.30/cm?cmnd=led_basecolor_rgb%20FF0000"
```

### Step 3: Check What Works
After each test, visit the web UI and check:
1. Does the bulb turn on/off?
2. Does the brightness slider work?
3. Does the RGB color picker appear?
4. How many channels are shown? (Should be 5 for RGBCW bulbs)
5. Are any drivers shown as active?

## Common Pin Configurations by Driver Type

### BP5758D (I2C Driver)
```
P7 = I2C_CLK (role 24)
P8 = I2C_DAT (role 23)
Startup: BP5758D_Map 2 1 0 4 5; led_enableAll 1
```

### SM2135 (I2C Driver)
```
P7 = SM2135_CLK (role 130)
P8 = SM2135_DAT (role 129)
Startup: SM2135_Map 2 1 0 4 5; led_enableAll 1
```

### PWM (Direct Control)
```
P6 = PWM (role 7, channel 5) - Warm White
P7 = PWM (role 7, channel 4) - Cool White
P8 = PWM (role 7, channel 1) - Blue
P24 = PWM (role 7, channel 2) - Red
P26 = PWM (role 7, channel 3) - Green
Startup: led_enableAll 1
```

## Using the Automated CLI Tool

Once you discover the correct configuration, you can update the driver mapping:

```bash
# Edit the driver mappings file
nano mqtt/src/main/resources/driver-mappings.json
```

Change the entry for `38:1f:8d` to the correct driver type (PWM or SM2135), then use:

```bash
# Scan for the device
./gradlew :mqtt:run --args="scan 192.168.86 30 30"

# Configure it automatically
./gradlew :mqtt:run --args="configure-all"
```

## OpenBeken Community Resources

Check the OpenBeken community for known configurations:
- GitHub Issues: https://github.com/openshwprojects/OpenBK7231T_App/issues
- Search for your MAC prefix or bulb model
- Look for "pin config" or "template" in issues/discussions

## Next Steps After Discovery

Once you find the working configuration:

1. **Update driver-mappings.json**:
```json
{
  "mac_prefixes": {
    "10:5a:17": "BP5758D",
    "38:1f:8d": "PWM",  // or "SM2135" - update based on what works
    "38:2c:e5": "PWM"
  }
}
```

2. **Document your findings**:
   - Note the exact pin configuration that worked
   - Note any special startup commands needed
   - Add to your project documentation

3. **Test all functions**:
   - On/Off control
   - Brightness dimming
   - RGB color changes
   - Temperature control (cool/warm)
   - MQTT control

## Troubleshooting Tips

- **No response at all**: Check if device is reachable with `ping 192.168.86.30`
- **Device resets to default**: Configuration might not be saved; check "Save configuration" option
- **Partial control**: Some pins might be correct; try variations
- **Wrong colors**: Check channel mapping in startup command
- **Flickering**: Driver conflict; make sure only one driver type is configured

## Quick Reference: PIN Role Numbers

| Role | Description | Used For |
|------|-------------|----------|
| 0 | None | Disabled pin |
| 7 | PWM | Direct LED control |
| 23 | I2C_DAT | BP5758D, SM2135 data |
| 24 | I2C_CLK | BP5758D clock |
| 129 | SM2135_DAT | SM2135 data |
| 130 | SM2135_CLK | SM2135 clock |

## Success Indicators

You've found the correct configuration when:
- ✅ Device shows "1 drivers active" or similar
- ✅ LED turns on/off reliably
- ✅ Brightness control works smoothly
- ✅ RGB color picker appears and works
- ✅ All 5 channels show in status (for RGBCW bulbs)
- ✅ Temperature control works (cool to warm transition)
