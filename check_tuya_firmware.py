#!/usr/bin/env python3
"""
Check Firmware Versions of Tuya Bulbs

This script queries all your Tuya bulbs to determine their firmware versions.
This is critical for determining OTA flash compatibility:
- Firmware < 2.0: Can likely be OTA flashed with tuya-cloudcutter
- Firmware >= 2.0: May need serial flashing
"""

import tinytuya
import json
from concurrent.futures import ThreadPoolExecutor, as_completed
import time

def load_devices():
    """Load devices from devices.json"""
    try:
        with open('old_tuya-devices.json', 'r') as f:
            data = json.load(f)
            if isinstance(data, list):
                return data
            return data.get('devices', [])
    except FileNotFoundError:
        print("❌ devices.json not found!")
        return []

def get_device_info(device_info):
    """Query a single device for its firmware version and other info"""
    device_name = device_info.get('name', 'Unknown')
    ip_address = device_info.get('ip', '')
    
    # Skip devices without IP
    if not ip_address:
        return {
            'name': device_name,
            'ip': 'N/A',
            'status': 'No IP',
            'firmware': 'N/A',
            'model': device_info.get('product_name', 'N/A'),
            'category': device_info.get('category', 'N/A')
        }
    
    # Skip non-bulb devices
    category = device_info.get('category', '')
    if category not in ['dj', '']:
        return None
    
    version = device_info.get('version', '3.3')
    if not version:
        version = '3.3'
    
    try:
        # Create device object
        device = tinytuya.BulbDevice(
            dev_id=device_info['id'],
            address=ip_address,
            local_key=device_info['key'],
            version=float(version)
        )
        
        # Set timeout to avoid hanging
        device.set_socketTimeout(3)
        
        # Get device status
        status = device.status()
        
        if status and 'dps' in status:
            # Try to extract firmware version from status
            firmware = 'Unknown'
            
            # Some devices report firmware in different ways
            if 'version' in status:
                firmware = status['version']
            elif 'sw_ver' in status:
                firmware = status['sw_ver']
            
            # Check DPS for version info (common keys: 1-20)
            dps_values = status.get('dps', {})
            
            return {
                'name': device_name,
                'ip': ip_address,
                'status': 'Online',
                'firmware': firmware,
                'model': device_info.get('product_name', 'N/A'),
                'category': category or 'bulb',
                'protocol_version': version,
                'dps_keys': list(dps_values.keys())
            }
        else:
            return {
                'name': device_name,
                'ip': ip_address,
                'status': 'No Response',
                'firmware': 'N/A',
                'model': device_info.get('product_name', 'N/A'),
                'category': category,
                'protocol_version': version
            }
            
    except Exception as e:
        return {
            'name': device_name,
            'ip': ip_address,
            'status': f'Error: {str(e)[:30]}',
            'firmware': 'N/A',
            'model': device_info.get('product_name', 'N/A'),
            'category': category
        }

def main():
    print("\n" + "=" * 80)
    print("TUYA BULB FIRMWARE VERSION CHECKER")
    print("=" * 80)
    
    devices_info = load_devices()
    
    if not devices_info:
        print("❌ No devices found in devices.json")
        return
    
    print(f"\n📱 Found {len(devices_info)} devices in devices.json")
    print("🔍 Querying firmware versions (this may take a minute)...\n")
    
    results = []
    
    # Query all devices in parallel for speed
    with ThreadPoolExecutor(max_workers=10) as executor:
        future_to_device = {
            executor.submit(get_device_info, device): device 
            for device in devices_info
        }
        
        for future in as_completed(future_to_device):
            result = future.result()
            if result:  # Skip non-bulb devices
                results.append(result)
                # Print progress
                print(f"✓ {result['name']:30s} - {result['status']}")
    
    # Sort by name
    results.sort(key=lambda x: x['name'])
    
    # Display results in a nice table
    print("\n" + "=" * 80)
    print("RESULTS")
    print("=" * 80)
    print(f"\n{'Name':<30} {'IP':<16} {'Status':<12} {'Firmware':<12} {'Model':<20}")
    print("-" * 100)
    
    online_count = 0
    unknown_fw_count = 0
    ota_flashable = 0
    
    for result in results:
        name = result['name'][:29]
        ip = result['ip'][:15]
        status = result['status'][:11]
        firmware = str(result['firmware'])[:11]
        model = result['model'][:19]
        
        print(f"{name:<30} {ip:<16} {status:<12} {firmware:<12} {model:<20}")
        
        if result['status'] == 'Online':
            online_count += 1
            if result['firmware'] == 'Unknown':
                unknown_fw_count += 1
            # Check if potentially OTA flashable (firmware < 2.0)
            try:
                fw_ver = float(result['firmware'].split('.')[0])
                if fw_ver < 2:
                    ota_flashable += 1
            except:
                pass
    
    # Summary
    print("\n" + "=" * 80)
    print("SUMMARY")
    print("=" * 80)
    print(f"📊 Total bulbs: {len(results)}")
    print(f"✅ Online: {online_count}")
    print(f"❓ Unknown firmware: {unknown_fw_count}")
    
    if ota_flashable > 0:
        print(f"\n🎉 Potentially OTA flashable (FW < 2.0): {ota_flashable} bulbs")
        print("   → Try tuya-cloudcutter for these bulbs!")
    
    if online_count - ota_flashable - unknown_fw_count > 0:
        newer_fw = online_count - ota_flashable - unknown_fw_count
        print(f"\n⚠️  Newer firmware (FW >= 2.0): {newer_fw} bulbs")
        print("   → These likely need serial flashing")
    
    if unknown_fw_count > 0:
        print(f"\n❓ Unknown firmware: {unknown_fw_count} bulbs")
        print("   → Firmware version not reported in status")
        print("   → Check Smart Life app or try OTA flashing anyway")
    
    print("\n💡 TIP: For detailed per-bulb info, check Smart Life app:")
    print("   Device → Settings (⚙️) → Device Information → Firmware Version")
    
    print("\n📖 See TASMOTA_FLASH_GUIDE.md for flashing instructions")
    print("=" * 80 + "\n")

if __name__ == "__main__":
    main()
