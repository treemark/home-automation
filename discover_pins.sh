#!/bin/bash

# OpenBeken Pin Discovery Script
# Systematically tests different pin configurations to find the working one

DEVICE_IP="${1:-192.168.86.30}"
WAIT_TIME=15  # Seconds to wait after restart

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║        OpenBeken Pin Configuration Discovery Tool             ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "Device IP: $DEVICE_IP"
echo ""

# Check if device is reachable
echo "🔍 Checking if device is reachable..."
if ! ping -c 1 -W 2 $DEVICE_IP > /dev/null 2>&1; then
    echo "❌ Device is not reachable at $DEVICE_IP"
    echo "Usage: $0 <device_ip>"
    exit 1
fi
echo "✅ Device is online"
echo ""

# Function to wait for device restart
wait_for_restart() {
    echo "⏳ Waiting ${WAIT_TIME}s for device to restart..."
    sleep $WAIT_TIME
    
    # Wait for device to come back online
    local retries=10
    while [ $retries -gt 0 ]; do
        if ping -c 1 -W 1 $DEVICE_IP > /dev/null 2>&1; then
            echo "✅ Device is back online"
            sleep 2  # Extra time for web server to be ready
            return 0
        fi
        echo "   Waiting for device... ($retries retries left)"
        sleep 2
        retries=$((retries - 1))
    done
    
    echo "⚠️  Device took longer than expected to restart"
    return 1
}

# Function to check device status
check_status() {
    local config_name="$1"
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "Testing: $config_name"
    echo "═══════════════════════════════════════════════════════════════"
    
    # Get driver status
    local driver_status=$(curl -s "http://$DEVICE_IP/index" | grep -o "[0-9]* drivers active" || echo "0 drivers active")
    echo "Driver Status: $driver_status"
    
    # Try to turn on
    echo -n "Testing ON command... "
    curl -s "http://$DEVICE_IP/cm?cmnd=POWER1%201" > /dev/null 2>&1
    sleep 2
    echo "sent"
    
    # Try to set brightness
    echo -n "Testing brightness command... "
    curl -s "http://$DEVICE_IP/cm?cmnd=dimmer%2050" > /dev/null 2>&1
    sleep 2
    echo "sent"
    
    # Try to set color
    echo -n "Testing color command (red)... "
    curl -s "http://$DEVICE_IP/cm?cmnd=led_basecolor_rgb%20FF0000" > /dev/null 2>&1
    sleep 2
    echo "sent"
    
    echo ""
    echo "📝 Please observe the bulb and answer:"
    echo "   1. Did the bulb turn on?"
    echo "   2. Did brightness change work?"
    echo "   3. Did the color change to red?"
    echo ""
    read -p "Did this configuration work? (y/n/partial): " response
    
    case $response in
        y|Y|yes|YES)
            echo ""
            echo "✅ SUCCESS! Configuration '$config_name' works!"
            echo ""
            echo "To use this configuration automatically, update:"
            echo "  mqtt/src/main/resources/driver-mappings.json"
            echo ""
            return 0
            ;;
        p|P|partial|PARTIAL)
            echo "⚠️  Partial success with '$config_name'"
            echo "Note: This configuration might need tweaking"
            return 2
            ;;
        *)
            echo "❌ Configuration '$config_name' did not work"
            return 1
            ;;
    esac
}

# Function to apply configuration
apply_config() {
    local name="$1"
    local pin_config="$2"
    local startup_cmd="$3"
    
    echo ""
    echo "→ Applying $name configuration..."
    
    # Apply pin configuration
    echo "  Setting pins..."
    curl -s "http://$DEVICE_IP/cfg_pins?$pin_config" > /dev/null 2>&1
    
    # Apply startup command
    echo "  Setting startup command..."
    curl -s "http://$DEVICE_IP/startup_command?data=$startup_cmd&startup_cmd=1" > /dev/null 2>&1
    
    # Restart device
    echo "  Restarting device..."
    curl -s "http://$DEVICE_IP/index?restart=1" > /dev/null 2>&1
    
    wait_for_restart
}

# Save current configuration
echo "💾 Backing up current configuration..."
BACKUP_DIR="pin_discovery_backup_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"
curl -s "http://$DEVICE_IP/cfg_pins" > "$BACKUP_DIR/cfg_pins.html"
curl -s "http://$DEVICE_IP/startup_command" > "$BACKUP_DIR/startup_command.html"
curl -s "http://$DEVICE_IP/index" > "$BACKUP_DIR/index.html"
echo "✅ Backup saved to: $BACKUP_DIR"
echo ""

# Show current status
echo "📊 Current Device Status:"
echo "════════════════════════════════════════════════════════════════"
curl -s "http://$DEVICE_IP/index" | grep -E "(drivers active|MAC|Channel)" | head -5
echo "════════════════════════════════════════════════════════════════"
echo ""

read -p "Ready to start testing? (y/n): " start_test
if [[ ! $start_test =~ ^[Yy] ]]; then
    echo "Exiting..."
    exit 0
fi

# Test 1: PWM Configuration (most common for newer bulbs)
PWM_PINS="0=0&1=0&2=0&3=0&4=0&5=0&6=7&7=7&8=7&9=0&10=0&11=0&12=0&13=0&14=0&15=0&16=0&17=0&18=0&19=0&20=0&21=0&22=0&23=0&24=7&25=0&26=7&27=0&28=0&r6=5&r7=4&r8=1&r24=2&r26=3"
PWM_STARTUP="led_enableAll+1"

apply_config "PWM (5-channel)" "$PWM_PINS" "$PWM_STARTUP"
check_status "PWM (P6,P7,P8,P24,P26 with channels)"
if [ $? -eq 0 ]; then
    echo ""
    echo "🎉 Configuration found! Update driver-mappings.json:"
    echo '    "38:1f:8d": "PWM"'
    exit 0
fi

# Test 2: BP5758D with standard pins (P7=CLK, P8=DAT)
read -p "Continue to next test? (y/n): " continue_test
if [[ ! $continue_test =~ ^[Yy] ]]; then
    echo "Testing stopped by user"
    exit 0
fi

BP5758D_PINS="0=0&1=0&2=0&3=0&4=0&5=0&6=0&7=24&8=23&9=0&10=0&11=0&12=0&13=0&14=0&15=0&16=0&17=0&18=0&19=0&20=0&21=0&22=0&23=0&24=0&25=0&26=0&27=0&28=0"
BP5758D_STARTUP="backlog+BP5758D_Map+2+1+0+4+5%3B+led_enableAll+1"

apply_config "BP5758D (P7=CLK, P8=DAT)" "$BP5758D_PINS" "$BP5758D_STARTUP"
check_status "BP5758D with P7/P8"
if [ $? -eq 0 ]; then
    echo ""
    echo "🎉 Configuration found! The mapping in driver-mappings.json is correct:"
    echo '    "38:1f:8d": "BP5758D"'
    exit 0
fi

# Test 3: SM2135 Configuration
read -p "Continue to next test? (y/n): " continue_test
if [[ ! $continue_test =~ ^[Yy] ]]; then
    echo "Testing stopped by user"
    exit 0
fi

SM2135_PINS="0=0&1=0&2=0&3=0&4=0&5=0&6=0&7=130&8=129&9=0&10=0&11=0&12=0&13=0&14=0&15=0&16=0&17=0&18=0&19=0&20=0&21=0&22=0&23=0&24=0&25=0&26=0&27=0&28=0"
SM2135_STARTUP="backlog+SM2135_Map+2+1+0+4+5%3B+led_enableAll+1"

apply_config "SM2135 (P7=CLK, P8=DAT)" "$SM2135_PINS" "$SM2135_STARTUP"
check_status "SM2135 with P7/P8"
if [ $? -eq 0 ]; then
    echo ""
    echo "🎉 Configuration found! Update driver-mappings.json:"
    echo '    "38:1f:8d": "SM2135"'
    echo ""
    echo "Note: You'll need to add SM2135 support to OpenBekenDiscoveryService.java"
    exit 0
fi

# Test 4: BP5758D with alternate pins (P6=DAT, P7=CLK)
read -p "Continue to next test? (y/n): " continue_test
if [[ ! $continue_test =~ ^[Yy] ]]; then
    echo "Testing stopped by user"
    exit 0
fi

BP5758D_ALT1_PINS="0=0&1=0&2=0&3=0&4=0&5=0&6=23&7=24&8=0&9=0&10=0&11=0&12=0&13=0&14=0&15=0&16=0&17=0&18=0&19=0&20=0&21=0&22=0&23=0&24=0&25=0&26=0&27=0&28=0"

apply_config "BP5758D (P6=DAT, P7=CLK)" "$BP5758D_ALT1_PINS" "$BP5758D_STARTUP"
check_status "BP5758D with P6/P7"
if [ $? -eq 0 ]; then
    echo ""
    echo "🎉 Configuration found! This uses alternate pins."
    echo "Update OpenBekenDiscoveryService.java configureBP5758DPins() to use P6/P7"
    exit 0
fi

# Test 5: BP5758D with alternate pins (P24=CLK, P26=DAT)
read -p "Continue to final test? (y/n): " continue_test
if [[ ! $continue_test =~ ^[Yy] ]]; then
    echo "Testing stopped by user"
    exit 0
fi

BP5758D_ALT2_PINS="0=0&1=0&2=0&3=0&4=0&5=0&6=0&7=0&8=0&9=0&10=0&11=0&12=0&13=0&14=0&15=0&16=0&17=0&18=0&19=0&20=0&21=0&22=0&23=0&24=24&25=0&26=23&27=0&28=0"

apply_config "BP5758D (P24=CLK, P26=DAT)" "$BP5758D_ALT2_PINS" "$BP5758D_STARTUP"
check_status "BP5758D with P24/P26"
if [ $? -eq 0 ]; then
    echo ""
    echo "🎉 Configuration found! This uses alternate pins."
    echo "Update OpenBekenDiscoveryService.java configureBP5758DPins() to use P24/P26"
    exit 0
fi

# No configuration worked
echo ""
echo "════════════════════════════════════════════════════════════════"
echo "❌ None of the standard configurations worked"
echo "════════════════════════════════════════════════════════════════"
echo ""
echo "Next steps:"
echo "1. Check OpenBeken GitHub for your specific bulb model"
echo "2. Look for the chipset on the bulb's circuit board"
echo "3. Try the web UI at http://$DEVICE_IP/cfg to manually configure"
echo "4. Join OpenBeken community forums for help"
echo ""
echo "Your backup is saved in: $BACKUP_DIR"
echo "You can restore manually if needed"
echo ""
