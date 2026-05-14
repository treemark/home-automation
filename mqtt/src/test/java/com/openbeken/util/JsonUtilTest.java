package com.openbeken.util;

import com.openbeken.model.GoogleHomeDevice;
import com.openbeken.model.GoogleHomeDevicesConfig;
import com.openbeken.model.GoogleHomeScene;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test Jackson JSON serialization/deserialization.
 */
public class JsonUtilTest {

    @Test
    public void testGoogleHomeDeviceSerialization() {
        GoogleHomeDevice device = new GoogleHomeDevice("test123", "Test Light", "Living Room", "192.168.1.100");
        
        // Serialize to JSON
        String json = JsonUtil.toJson(device);
        assertNotNull(json);
        assertTrue(json.contains("test123"));
        assertTrue(json.contains("Test Light"));
        assertTrue(json.contains("Living Room"));
        assertTrue(json.contains("192.168.1.100"));
        
        // Deserialize back
        GoogleHomeDevice deserialized = JsonUtil.fromJson(json, GoogleHomeDevice.class);
        assertEquals("test123", deserialized.getId());
        assertEquals("Test Light", deserialized.getName());
        assertEquals("Living Room", deserialized.getRoom());
        assertEquals("192.168.1.100", deserialized.getIp());
    }
    
    @Test
    public void testGoogleHomeSceneSerialization() {
        GoogleHomeScene scene = new GoogleHomeScene("scene-1", "Rainbow", "Kitchen", "rainbow", "K1,K2,K3");
        
        // Serialize to JSON
        String json = JsonUtil.toJson(scene);
        assertNotNull(json);
        assertTrue(json.contains("scene-1"));
        assertTrue(json.contains("Rainbow"));
        assertTrue(json.contains("rainbow"));
        assertTrue(json.contains("K1,K2,K3"));
        
        // Deserialize back
        GoogleHomeScene deserialized = JsonUtil.fromJson(json, GoogleHomeScene.class);
        assertEquals("scene-1", deserialized.getId());
        assertEquals("Rainbow", deserialized.getName());
        assertEquals("Kitchen", deserialized.getRoom());
        assertEquals("rainbow", deserialized.getAnimation());
        assertEquals("K1,K2,K3", deserialized.getGroup());
    }
    
    @Test
    public void testGoogleHomeDevicesConfigSerialization() {
        GoogleHomeDevicesConfig config = new GoogleHomeDevicesConfig();
        config.setComment("Test comment");
        
        GoogleHomeDevice device1 = new GoogleHomeDevice("dev1", "Light 1", "Room 1", "192.168.1.1");
        GoogleHomeDevice device2 = new GoogleHomeDevice("dev2", "Light 2", "Room 2", "192.168.1.2");
        config.setDevices(Arrays.asList(device1, device2));
        
        GoogleHomeScene scene1 = new GoogleHomeScene("scene1", "Scene 1", "Room 1", "rainbow", "dev1,dev2");
        config.setScenes(Arrays.asList(scene1));
        
        // Serialize to JSON
        String json = JsonUtil.toJson(config);
        assertNotNull(json);
        assertTrue(json.contains("Test comment"));
        assertTrue(json.contains("dev1"));
        assertTrue(json.contains("dev2"));
        assertTrue(json.contains("scene1"));
        
        // Deserialize back
        GoogleHomeDevicesConfig deserialized = JsonUtil.fromJson(json, GoogleHomeDevicesConfig.class);
        assertEquals("Test comment", deserialized.getComment());
        assertEquals(2, deserialized.getDevices().size());
        assertEquals(1, deserialized.getScenes().size());
        assertEquals("dev1", deserialized.getDevices().get(0).getId());
        assertEquals("scene1", deserialized.getScenes().get(0).getId());
    }
    
    @Test
    public void testCompleteGoogleHomeDevicesJson() {
        String json = """
            {
              "_comment": "Test configuration",
              "devices": [
                { "id": "20E01A", "name": "Light 1", "room": "Kitchen", "ip": "192.168.86.60" },
                { "id": "20E78A", "name": "Light 2", "room": "Living Room", "ip": "192.168.86.135" }
              ],
              "scenes": [
                { "id": "scene-rainbow-kitchen", "name": "Rainbow Kitchen", "room": "Kitchen", "animation": "rainbow", "group": "K1,K2,K3" }
              ]
            }
            """;
        
        // Parse the JSON
        GoogleHomeDevicesConfig config = JsonUtil.fromJson(json, GoogleHomeDevicesConfig.class);
        
        assertNotNull(config);
        assertEquals("Test configuration", config.getComment());
        assertEquals(2, config.getDevices().size());
        assertEquals(1, config.getScenes().size());
        
        // Check first device
        GoogleHomeDevice device1 = config.getDevices().get(0);
        assertEquals("20E01A", device1.getId());
        assertEquals("Light 1", device1.getName());
        assertEquals("Kitchen", device1.getRoom());
        assertEquals("192.168.86.60", device1.getIp());
        
        // Check scene
        GoogleHomeScene scene = config.getScenes().get(0);
        assertEquals("scene-rainbow-kitchen", scene.getId());
        assertEquals("Rainbow Kitchen", scene.getName());
        assertEquals("Kitchen", scene.getRoom());
        assertEquals("rainbow", scene.getAnimation());
        assertEquals("K1,K2,K3", scene.getGroup());
        
        // Serialize back and verify it round-trips
        String serialized = JsonUtil.toJson(config);
        GoogleHomeDevicesConfig roundTrip = JsonUtil.fromJson(serialized, GoogleHomeDevicesConfig.class);
        assertEquals(config.getDevices().size(), roundTrip.getDevices().size());
        assertEquals(config.getScenes().size(), roundTrip.getScenes().size());
    }
}
