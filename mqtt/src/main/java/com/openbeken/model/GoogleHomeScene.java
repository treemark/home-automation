package com.openbeken.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a Google Home scene in google-home-devices.json.
 */
public class GoogleHomeScene {
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("room")
    private String room;
    
    @JsonProperty("animation")
    private String animation;
    
    @JsonProperty("group")
    private String group;
    
    public GoogleHomeScene() {
    }
    
    public GoogleHomeScene(String id, String name, String room, String animation, String group) {
        this.id = id;
        this.name = name;
        this.room = room;
        this.animation = animation;
        this.group = group;
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
    
    public String getAnimation() {
        return animation;
    }
    
    public void setAnimation(String animation) {
        this.animation = animation;
    }
    
    public String getGroup() {
        return group;
    }
    
    public void setGroup(String group) {
        this.group = group;
    }
    
    @Override
    public String toString() {
        return String.format("GoogleHomeScene{id='%s', name='%s', room='%s', animation='%s', group='%s'}", 
                id, name, room, animation, group);
    }
}
