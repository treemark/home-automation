package com.openbeken.model;

import java.time.Instant;

/**
 * Represents a discovered OpenBeken device on the network.
 */
public class OpenBekenDevice {
    private String deviceId;       // e.g. "obk17811957"
    private String ip;             // e.g. "192.168.86.66"
    private String mac;            // MAC address if available
    private String firmware;       // OpenBeken firmware version
    private String chipset;        // e.g. "BK7231N"
    private String hostname;       // mDNS or device hostname
    private String mqttTopic;      // MQTT client topic
    private String mqttGroup;      // MQTT group topic
    private boolean online;        // reachable / online status
    private String powerState;     // ON / OFF
    private Integer dimmer;        // 0-100
    private String discoveryMethod; // "http", "mqtt", "inventory"
    private Instant lastSeen;
    private String productName;    // from devices.json if matched
    private String tuyaName;       // friendly name from devices.json

    public OpenBekenDevice() {
        this.lastSeen = Instant.now();
    }

    public OpenBekenDevice(String deviceId, String ip) {
        this.deviceId = deviceId;
        this.ip = ip;
        this.lastSeen = Instant.now();
    }

    // Getters and setters
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public String getMac() { return mac; }
    public void setMac(String mac) { this.mac = mac; }

    public String getFirmware() { return firmware; }
    public void setFirmware(String firmware) { this.firmware = firmware; }

    public String getChipset() { return chipset; }
    public void setChipset(String chipset) { this.chipset = chipset; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getMqttTopic() { return mqttTopic; }
    public void setMqttTopic(String mqttTopic) { this.mqttTopic = mqttTopic; }

    public String getMqttGroup() { return mqttGroup; }
    public void setMqttGroup(String mqttGroup) { this.mqttGroup = mqttGroup; }

    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }

    public String getPowerState() { return powerState; }
    public void setPowerState(String powerState) { this.powerState = powerState; }

    public Integer getDimmer() { return dimmer; }
    public void setDimmer(Integer dimmer) { this.dimmer = dimmer; }

    public String getDiscoveryMethod() { return discoveryMethod; }
    public void setDiscoveryMethod(String discoveryMethod) { this.discoveryMethod = discoveryMethod; }

    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getTuyaName() { return tuyaName; }
    public void setTuyaName(String tuyaName) { this.tuyaName = tuyaName; }

    @Override
    public String toString() {
        return String.format("OpenBekenDevice{id='%s', ip='%s', online=%s, method='%s'}",
                deviceId, ip, online, discoveryMethod);
    }
}
