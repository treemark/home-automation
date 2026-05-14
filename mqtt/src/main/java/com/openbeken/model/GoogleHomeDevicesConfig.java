package com.openbeken.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the top-level structure of google-home-devices.json.
 */
public class GoogleHomeDevicesConfig {
    
    @JsonProperty("_comment")
    private String comment;
    
    @JsonProperty("devices")
    private List<GoogleHomeDevice> devices = new ArrayList<>();
    
    @JsonProperty("scenes")
    private List<GoogleHomeScene> scenes = new ArrayList<>();
    
    public GoogleHomeDevicesConfig() {
    }
    
    public String getComment() {
        return comment;
    }
    
    public void setComment(String comment) {
        this.comment = comment;
    }
    
    public List<GoogleHomeDevice> getDevices() {
        return devices;
    }
    
    public void setDevices(List<GoogleHomeDevice> devices) {
        this.devices = devices;
    }
    
    public List<GoogleHomeScene> getScenes() {
        return scenes;
    }
    
    public void setScenes(List<GoogleHomeScene> scenes) {
        this.scenes = scenes;
    }
    
    @Override
    public String toString() {
        return String.format("GoogleHomeDevicesConfig{devices=%d, scenes=%d}", 
                devices.size(), scenes.size());
    }
}
