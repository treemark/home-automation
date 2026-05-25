
choose wlan0
apt-get update
apt-get install -y network-manager git python3 python3-pip python3-venv hostapd dnsmasq rfkill iw curl

curl -fsSL https://get.docker.com -o get-docker.sh
sh get-docker.sh
apt-get install network-manager

git clone https://github.com/tuya-cloudcutter/tuya-cloudcutter.git
cd tuya-cloudcutter

systemctl stop NetworkManager
systemctl stop wpa_supplicant
systemctl restart wpa_supplicant
killall wpa_supplicant


systemctl status NetworkManager
rfkill unblock wifi

systemctl restart systemd-resolved
killall wpa_supplicant
./tuya-cloudcutter.sh -w wlan1

Selected Device Slug: daybetter-rgbct-bulb-v1.2.16
Selected Profile: oem-bk7231n-light-ty-1.2.16-sdk-2.3.1-40.00
Selected Firmware: OpenBeken-v1.18.219_bk7231n.ug.bin

nmcli device set wlan0 managed yes

nmcli -t -f SSID,SECURITY dev wifi list --rescan yes ifname wlan1

nmcli dev wifi connect "SmartLife-1957" ifname wlan1 name "SmartLife-1957"

nmcli -f IP4.GATEWAY con show "SmartLife-1957"


For recessed downlights


[?] How do you want to choose the device?: From device-profiles (i.e. custom profile)
By manufacturer/device name
By firmware version and name
► From device-profiles (i.e. custom profile)

[?] Select device profile: cloudy-bay-ljd006smwh-6in-recessed-light-cb3l-v1.2.8
cloudy-bay-6inch-recessed-15w-rgbct-downlight-wb3s-1.3.2
cloudy-bay-6inch-recessed-rgbct-downlight-v3-cb2l-1.1.7
cloudy-bay-ljd006gbsmwh-4pk-6in-gimbal-recessed-light
cloudy-bay-ljd006smwh-6in-recessed-light-cb3l-v1.2.6
► cloudy-bay-ljd006smwh-6in-recessed-light-cb3l-v1.2.8 <--- this one
daybetter-rgbct-bulb-v1.2.16

Selected Device Slug: cloudy-bay-ljd006smwh-6in-recessed-light-cb3l-v1.2.8
Selected Profile: oem-bk7231n-ceiling-light-ty-1.2.8-sdk-2.3.1-40.00
Selected Firmware: OpenBeken-v1.18.219_bk7231n.ug.bin

reboot the light as instructed.. first to fast blink then to slow.

