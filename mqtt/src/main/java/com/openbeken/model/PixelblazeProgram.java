package com.openbeken.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a Pixelblaze pattern/program.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PixelblazeProgram {

    @JsonProperty("name")
    private String name;

    @JsonProperty("activeProgramId")
    private String activeProgramId;

    // Empty JSON object for controls - currently unused
    @JsonProperty("controls")
    private Object controls;

    // ── Getters and Setters ─────────────────────────────────────────────────────────

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getActiveProgramId() {
        return activeProgramId;
    }

    public void setActiveProgramId(String activeProgramId) {
        this.activeProgramId = activeProgramId;
    }

    public Object getControls() {
        return controls;
    }

    public void setControls(Object controls) {
        this.controls = controls;
    }

    @Override
    public String toString() {
        return "PixelblazeProgram{" +
                "name='" + name + '\'' +
                ", activeProgramId='" + activeProgramId + '\'' +
                '}';
    }
}