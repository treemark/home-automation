package com.openbeken.model;

import com.fasterxml.jackson.annotation.JsonProperty;

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
    
    public GoogleHomeDevice() {
    }
    
    public GoogleHomeDevice(String id, String name, String room, String ip) {
        this.id = id;
        this.name = name;
        this.room = room;
        this.ip = ip;
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
    
    @Override
    public String toString() {
        return String.format("GoogleHomeDevice{id='%s', name='%s', room='%s', ip='%s'}", 
                id, name, room, ip);
    }
}
