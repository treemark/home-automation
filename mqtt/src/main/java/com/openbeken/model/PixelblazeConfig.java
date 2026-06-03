package com.openbeken.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POJO representing Pixelblaze device configuration.
 * 
 * Retrieved via getConfig WebSocket command.
 * 
 * @see <a href="https://www.electrictwig.com/pixelblaze/docs">Pixelblaze Documentation</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PixelblazeConfig {

    @JsonProperty("name")
    private String name;

    @JsonProperty("brandName")
    private String brandName;

    @JsonProperty("pixelCount")
    private Integer pixelCount;

    @JsonProperty("brightness")
    private Double brightness;

    @JsonProperty("maxBrightness")
    private Integer maxBrightness;

    @JsonProperty("colorOrder")
    private String colorOrder;

    @JsonProperty("dataSpeed")
    private Long dataSpeed;

    @JsonProperty("ledType")
    private Integer ledType;

    @JsonProperty("sequenceTimer")
    private Integer sequenceTimer;

    @JsonProperty("transitionDuration")
    private Integer transitionDuration;

    @JsonProperty("sequencerMode")
    private Integer sequencerMode;

    @JsonProperty("runSequencer")
    private Boolean runSequencer;

    @JsonProperty("simpleUiMode")
    private Boolean simpleUiMode;

    @JsonProperty("learningUiMode")
    private Boolean learningUiMode;

    @JsonProperty("discoveryEnable")
    private Boolean discoveryEnable;

    @JsonProperty("timezone")
    private String timezone;

    @JsonProperty("autoOffEnable")
    private Boolean autoOffEnable;

    @JsonProperty("autoOffStart")
    private String autoOffStart;

    @JsonProperty("autoOffEnd")
    private String autoOffEnd;

    @JsonProperty("cpuSpeed")
    private Integer cpuSpeed;

    @JsonProperty("networkPowerSave")
    private Boolean networkPowerSave;

    @JsonProperty("mapperFit")
    private Integer mapperFit;

    @JsonProperty("leaderId")
    private Integer leaderId;

    @JsonProperty("nodeId")
    private Integer nodeId;

    @JsonProperty("soundSrc")
    private Integer soundSrc;

    @JsonProperty("accelSrc")
    private Integer accelSrc;

    @JsonProperty("lightSrc")
    private Integer lightSrc;

    @JsonProperty("analogSrc")
    private Integer analogSrc;

    @JsonProperty("exp")
    private Integer exp;

    @JsonProperty("ver")
    private String version;

    @JsonProperty("chipId")
    private Long chipId;

    // Nested objects
    
    @JsonProperty("activeProgram")
    private PixelblazeProgram activeProgram;

    // ── Getters and Setters ─────────────────────────────────────────────────────────

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBrandName() {
        return brandName;
    }

    public void setBrandName(String brandName) {
        this.brandName = brandName;
    }

    public Integer getPixelCount() {
        return pixelCount;
    }

    public void setPixelCount(Integer pixelCount) {
        this.pixelCount = pixelCount;
    }

    public Double getBrightness() {
        return brightness;
    }

    public void setBrightness(Double brightness) {
        this.brightness = brightness;
    }

    public Integer getMaxBrightness() {
        return maxBrightness;
    }

    public void setMaxBrightness(Integer maxBrightness) {
        this.maxBrightness = maxBrightness;
    }

    public String getColorOrder() {
        return colorOrder;
    }

    public void setColorOrder(String colorOrder) {
        this.colorOrder = colorOrder;
    }

    public Long getDataSpeed() {
        return dataSpeed;
    }

    public void setDataSpeed(Long dataSpeed) {
        this.dataSpeed = dataSpeed;
    }

    public Integer getLedType() {
        return ledType;
    }

    public void setLedType(Integer ledType) {
        this.ledType = ledType;
    }

    public Integer getSequenceTimer() {
        return sequenceTimer;
    }

    public void setSequenceTimer(Integer sequenceTimer) {
        this.sequenceTimer = sequenceTimer;
    }

    public Integer getTransitionDuration() {
        return transitionDuration;
    }

    public void setTransitionDuration(Integer transitionDuration) {
        this.transitionDuration = transitionDuration;
    }

    public Integer getSequencerMode() {
        return sequencerMode;
    }

    public void setSequencerMode(Integer sequencerMode) {
        this.sequencerMode = sequencerMode;
    }

    public Boolean getRunSequencer() {
        return runSequencer;
    }

    public void setRunSequencer(Boolean runSequencer) {
        this.runSequencer = runSequencer;
    }

    public Boolean getSimpleUiMode() {
        return simpleUiMode;
    }

    public void setSimpleUiMode(Boolean simpleUiMode) {
        this.simpleUiMode = simpleUiMode;
    }

    public Boolean getLearningUiMode() {
        return learningUiMode;
    }

    public void setLearningUiMode(Boolean learningUiMode) {
        this.learningUiMode = learningUiMode;
    }

    public Boolean getDiscoveryEnable() {
        return discoveryEnable;
    }

    public void setDiscoveryEnable(Boolean discoveryEnable) {
        this.discoveryEnable = discoveryEnable;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Boolean getAutoOffEnable() {
        return autoOffEnable;
    }

    public void setAutoOffEnable(Boolean autoOffEnable) {
        this.autoOffEnable = autoOffEnable;
    }

    public String getAutoOffStart() {
        return autoOffStart;
    }

    public void setAutoOffStart(String autoOffStart) {
        this.autoOffStart = autoOffStart;
    }

    public String getAutoOffEnd() {
        return autoOffEnd;
    }

    public void setAutoOffEnd(String autoOffEnd) {
        this.autoOffEnd = autoOffEnd;
    }

    public Integer getCpuSpeed() {
        return cpuSpeed;
    }

    public void setCpuSpeed(Integer cpuSpeed) {
        this.cpuSpeed = cpuSpeed;
    }

    public Boolean getNetworkPowerSave() {
        return networkPowerSave;
    }

    public void setNetworkPowerSave(Boolean networkPowerSave) {
        this.networkPowerSave = networkPowerSave;
    }

    public Integer getMapperFit() {
        return mapperFit;
    }

    public void setMapperFit(Integer mapperFit) {
        this.mapperFit = mapperFit;
    }

    public Integer getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(Integer leaderId) {
        this.leaderId = leaderId;
    }

    public Integer getNodeId() {
        return nodeId;
    }

    public void setNodeId(Integer nodeId) {
        this.nodeId = nodeId;
    }

    public Integer getSoundSrc() {
        return soundSrc;
    }

    public void setSoundSrc(Integer soundSrc) {
        this.soundSrc = soundSrc;
    }

    public Integer getAccelSrc() {
        return accelSrc;
    }

    public void setAccelSrc(Integer accelSrc) {
        this.accelSrc = accelSrc;
    }

    public Integer getLightSrc() {
        return lightSrc;
    }

    public void setLightSrc(Integer lightSrc) {
        this.lightSrc = lightSrc;
    }

    public Integer getAnalogSrc() {
        return analogSrc;
    }

    public void setAnalogSrc(Integer analogSrc) {
        this.analogSrc = analogSrc;
    }

    public Integer getExp() {
        return exp;
    }

    public void setExp(Integer exp) {
        this.exp = exp;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Long getChipId() {
        return chipId;
    }

    public void setChipId(Long chipId) {
        this.chipId = chipId;
    }

    public PixelblazeProgram getActiveProgram() {
        return activeProgram;
    }

    public void setActiveProgram(PixelblazeProgram activeProgram) {
        this.activeProgram = activeProgram;
    }

    @Override
    public String toString() {
        return "PixelblazeConfig{" +
                "name='" + name + '\'' +
                ", version='" + version + '\'' +
                ", pixelCount=" + pixelCount +
                ", brightness=" + brightness +
                ", colorOrder='" + colorOrder + '\'' +
                ", dataSpeed=" + dataSpeed +
                ", chipId=" + chipId +
                ", activeProgram=" + activeProgram +
                '}';
    }
}
