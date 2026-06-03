package com.openbeken.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents a Google Home device (light) in google-home-devices.json.
 */
public class GoogleHomeDevice {
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("room")
    private String room;
    
    @JsonProperty("ip")
    private String ip;
    
    @JsonProperty("deviceId")
    private String deviceId;  // OpenBeken device ID (e.g., obk17811957) - stable, doesn't change with IP
    
    // For Pixelblaze devices - list of patterns/programs
    @JsonProperty("programs")
    private List<PixelblazeProgram> programs;
    
    public GoogleHomeDevice() {
    }
    
    public GoogleHomeDevice(String id, String name, String room, String ip) {
        this.id = id;
        this.name = name;
        this.room = room;
        this.ip = ip;
    }
    
    public GoogleHomeDevice(String id, String name, String room, String ip, String deviceId) {
        this.id = id;
        this.name = name;
        this.room = room;
        this.ip = ip;
        this.deviceId = deviceId;
    }
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getRoom() {
        return room;
    }
    
    public void setRoom(String room) {
        this.room = room;
    }
    
    public String getIp() {
        return ip;
    }
    
    public void setIp(String ip) {
        this.ip = ip;
    }
    
    public String getDeviceId() {
        return deviceId;
    }
    
    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }
    
    public List<PixelblazeProgram> getPrograms() {
        return programs;
    }
    
    public void setPrograms(List<PixelblazeProgram> programs) {
        this.programs = programs;
    }
    
    @Override
    public String toString() {
        return String.format("GoogleHomeDevice{id='%s', name='%s', room='%s', ip='%s', deviceId='%s'}", 
                id, name, room, ip, deviceId);
    }
}
