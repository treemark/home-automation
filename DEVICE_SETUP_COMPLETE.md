hel# ✅ OpenBeken Device Setup Complete!

## 🎉 Success Summary

Your OpenBeken device at **http://192.168.86.66/** has been successfully added to the system and is ready for control!

### What Was Done

1. ✅ **Device Identified**: OpenBK7231N_17811957 (MAC: 10:5A:17:81:19:57)
2. ✅ **MQTT Configured**: Device connected to embedded Moquette broker at 192.168.1.5:1883
3. ✅ **Connection Verified**: MQTT Status shows "connected" (green)
4. ✅ **Application Running**: HueBridgeApplication started on port 8080
5. ✅ **Control Tested**: HTTP commands successfully sent and received

## 🎮 How to Control Your Device

### Method 1: Interactive Script (Easiest)
```bash
./control_openbeken_device.sh
```
This script provides an interactive menu to control your device with options to turn it on/off, toggle, and check status.

### Method 2: Direct HTTP Commands
```bash
# Turn on
curl "http://192.168.86.66/cm?cmnd=POWER1%201"

# Turn off
curl "http://192.168.86.66/cm?cmnd=POWER1%200"

# Toggle
curl "http://192.168.86.66/cm?cmnd=POWER1%202"
```

### Method 3: MQTT Commands
```bash
# Turn on (if mosquitto-clients installed)
mosquitto_pub -h localhost -p 1883 -t 'cmnd/obk17811957/POWER1' -m '1'

# Turn off
mosquitto_pub -h localhost -p 1883 -t 'cmnd/obk17811957/POWER1' -m '0'
```

### Method 4: Web Interface
Open in your browser: **http://192.168.86.66/**

## ⚙️ Important Next Step: Configure Device Pins

Your device currently shows **"0 drivers active"**, which means the physical pins haven't been configured yet. To make the device actually control something (lights, relays, etc.):

1. **Access Configuration Page**:
   ```
   http://192.168.86.66/cfg
   ```

2. **Click "Configure Module"**

3. **Select Device Template** or **Manually Configure Pins**:
   - Choose a pre-configured template if your device matches a known model
   - Or manually assign pin roles (Relay, PWM, Button, etc.)

4. **Common Pin Configurations**:
   - **Relay/Switch**: Set pin as "Rel" (Relay)
   - **LED/Light**: Set pins as "PWM" for dimmable control
   - **Button**: Set pin as "Btn" for physical button input

5. **Save and Restart** the device

Once pins are configured, your POWER commands will control the actual physical outputs!

## 📊 System Status

### Device Information
- **IP Address**: 192.168.86.66
- **Device Name**: obk17811957
- **MQTT Client ID**: obk17811957
- **Base Topic**: zigbee2mqtt
- **Firmware**: OpenBK7231N v1.18.219

### Application Information
- **Application URL**: http://localhost:8080
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **Health Check**: http://localhost:8080/actuator/health
- **Log File**: /tmp/philips_app.log

### MQTT Broker
- **Host**: 192.168.1.5 (local machine)
- **Port**: 1883
- **Type**: Embedded Moquette
- **Anonymous Access**: Enabled

## 📚 Documentation

- **Setup Guide**: `philips/OPENBEKEN_MQTT_SETUP.md`
- **MQTT Quickstart**: `philips/MQTT_QUICKSTART.md`
- **MQTT Devices API**: `philips/MQTT_DEVICES.md`
- **Control Script**: `./control_openbeken_device.sh`

## 🔧 Useful Commands

### Check Device Status
```bash
curl -s "http://192.168.86.66/index?state=1" | grep "MQTT State"
```

### View Application Logs
```bash
tail -f /tmp/philips_app.log
```

### Check Application Health
```bash
curl http://localhost:8080/actuator/health
```

### Restart Application
```bash
# Stop current instance (Ctrl+C in the terminal where it's running)
# Or kill the process:
pkill -f "philips:bootRun"

# Start again:
./gradlew :philips:bootRun
```

## 🚀 Advanced Usage

### Monitor MQTT Messages
If you have `mosquitto-clients` installed:
```bash
# Monitor all device messages
mosquitto_sub -h localhost -p 1883 -t 'obk17811957/#' -v

# Monitor all messages
mosquitto_sub -h localhost -p 1883 -t '#' -v
```

### Custom Commands
OpenBeken supports many commands. Send them via HTTP:
```bash
# Get help
curl "http://192.168.86.66/cm?cmnd=help"

# Check status
curl "http://192.168.86.66/cm?cmnd=status"

# Restart device
curl "http://192.168.86.66/cm?cmnd=restart"
```

### Integration with Home Automation
Once your device pins are configured and working:
- The device will publish state changes to MQTT automatically
- You can integrate with Home Assistant, Node-RED, or other platforms
- Subscribe to topics: `obk17811957/+/get` for state updates

## 🐛 Troubleshooting

### Device Not Responding
```bash
# Check if device is reachable
ping 192.168.86.66

# Check web interface
curl http://192.168.86.66/
```

### MQTT Connection Issues
```bash
# Verify MQTT broker is running
nc -zv 192.168.1.5 1883

# Check application logs
tail -f /tmp/philips_app.log | grep -i mqtt
```

### Device Shows "disconnected"
1. Restart the application (which runs the MQTT broker)
2. Wait 10-15 seconds for device to reconnect
3. Check logs for connection errors

## 🎯 Quick Reference

| Task | Command |
|------|---------|
| Interactive Control | `./control_openbeken_device.sh` |
| Turn On | `curl "http://192.168.86.66/cm?cmnd=POWER1%201"` |
| Turn Off | `curl "http://192.168.86.66/cm?cmnd=POWER1%200"` |
| Device Web UI | Open `http://192.168.86.66/` |
| Configure Pins | Open `http://192.168.86.66/cfg` |
| Application UI | Open `http://localhost:8080/swagger-ui.html` |
| View Logs | `tail -f /tmp/philips_app.log` |

## 📝 Notes

- **No Physical Control Yet**: The device is configured and connected, but you need to configure the pin assignments (step above) before it can control physical devices
- **MQTT Topics**: OpenBeken uses `cmnd/` prefix for commands and publishes to `obk17811957/` topics
- **Application Design**: The current application is primarily designed for Zigbee2MQTT devices, but works with OpenBeken via the embedded MQTT broker
- **Direct Control Recommended**: For OpenBeken devices, direct HTTP or MQTT commands work best until the application is extended to support OpenBeken's topic structure

## 🔗 Useful Links

- [OpenBeken Documentation](https://github.com/openshwprojects/OpenBK7231T_App/blob/main/docs/README.md)
- [OpenBeken Forum](https://www.elektroda.com/rtvforum/forum390.html)
- [Device Templates](https://openbekeniot.github.io/webapp/devicesList.html)
- [YouTube Tutorials](https://www.youtube.com/@elektrodacom/videos)

---

**Setup Date**: 2025-12-31  
**Device MAC**: 10:5A:17:81:19:57  
**Firmware Version**: 1.18.219  
**Status**: ✅ Connected and Ready
