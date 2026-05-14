package com.appliedvillainy.hue.service;

import com.appliedvillainy.hue.config.MoquetteConfig;
import io.moquette.broker.Server;
import io.moquette.broker.config.ClasspathResourceLoader;
import io.moquette.broker.config.IConfig;
import io.moquette.broker.config.IResourceLoader;
import io.moquette.broker.config.MemoryConfig;
import io.moquette.interception.AbstractInterceptHandler;
import io.moquette.interception.InterceptHandler;
import io.moquette.interception.messages.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Service that runs an embedded Moquette MQTT broker.
 * This allows MQTT devices to connect directly to this application
 * without needing an external broker like Zigbee2MQTT.
 */
@Service
public class MoquetteBrokerService {

    private static final Logger logger = LoggerFactory.getLogger(MoquetteBrokerService.class);

    private final MoquetteConfig moquetteConfig;
    private Server mqttBroker;
    
    public MoquetteBrokerService(MoquetteConfig moquetteConfig) {
        this.moquetteConfig = moquetteConfig;
    }

    @PostConstruct
    public void startBroker() {
        if (!moquetteConfig.isEnabled()) {
            logger.info("Moquette MQTT broker is disabled");
            return;
        }

        try {
            logger.info("Starting embedded Moquette MQTT broker on port {}", moquetteConfig.getPort());
            
            mqttBroker = new Server();
            
            // Configure broker
            IConfig config = createBrokerConfig();
            
            // Add interceptor to log messages
            List<? extends InterceptHandler> handlers = Collections.singletonList(
                new MqttMessageInterceptor()
            );
            
            mqttBroker.startServer(config, handlers);
            
            logger.info("✓ Moquette MQTT broker started successfully on port {}", moquetteConfig.getPort());
            logger.info("  MQTT devices can connect to: mqtt://localhost:{}", moquetteConfig.getPort());
            logger.info("  Use any MQTT client to connect and publish/subscribe");
            
        } catch (IOException e) {
            logger.error("Failed to start Moquette MQTT broker: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to start MQTT broker", e);
        }
    }

    @PreDestroy
    public void stopBroker() {
        if (mqttBroker != null) {
            logger.info("Stopping embedded Moquette MQTT broker...");
            mqttBroker.stopServer();
            logger.info("Moquette MQTT broker stopped");
        }
    }

    /**
     * Create broker configuration.
     */
    private IConfig createBrokerConfig() {
        Properties properties = new Properties();
        
        // Basic configuration
        properties.setProperty("port", String.valueOf(moquetteConfig.getPort()));
        properties.setProperty("host", "0.0.0.0");
        
        // WebSocket support (optional)
        properties.setProperty("websocket_port", String.valueOf(moquetteConfig.getWebsocketPort()));
        
        // Allow anonymous access (no authentication required)
        properties.setProperty("allow_anonymous", String.valueOf(moquetteConfig.isAllowAnonymous()));
        
        // Persistence configuration - use ~/.mqtt/moquette_data
        String home = System.getProperty("user.home");
        String dataPath = home + "/.mqtt/moquette_data/moquette_store.mapdb";
        properties.setProperty("persistent_store", dataPath);
        
        // QoS settings
        properties.setProperty("immediate_buffer_flush", "true");
        
        return new MemoryConfig(properties);
    }

    /**
     * Interceptor to log MQTT messages for debugging.
     */
    private static class MqttMessageInterceptor extends AbstractInterceptHandler {
        
        private static final Logger logger = LoggerFactory.getLogger(MqttMessageInterceptor.class);

        @Override
        public String getID() {
            return "mqtt-message-logger";
        }

        @Override
        public void onConnect(InterceptConnectMessage msg) {
            logger.info("Client connected: {} (username: {})", 
                msg.getClientID(), 
                msg.getUsername());
        }

        @Override
        public void onDisconnect(InterceptDisconnectMessage msg) {
            logger.info("Client disconnected: {}", msg.getClientID());
        }

        @Override
        public void onPublish(InterceptPublishMessage msg) {
            try {
                // Properly read from Netty ByteBuf (handles both direct and heap buffers)
                io.netty.buffer.ByteBuf byteBuf = msg.getPayload();
                byte[] bytes = new byte[byteBuf.readableBytes()];
                byteBuf.getBytes(byteBuf.readerIndex(), bytes);
                String payload = new String(bytes);
                
                logger.debug("Message published - Topic: {}, QoS: {}, Payload: {}", 
                    msg.getTopicName(), 
                    msg.getQos(), 
                    payload.length() > 100 ? payload.substring(0, 100) + "..." : payload);
            } catch (Exception e) {
                logger.debug("Message published - Topic: {}, QoS: {} (payload not readable)", 
                    msg.getTopicName(), 
                    msg.getQos());
            }
        }

        @Override
        public void onSubscribe(InterceptSubscribeMessage msg) {
            logger.info("Client subscribed");
        }

        @Override
        public void onUnsubscribe(InterceptUnsubscribeMessage msg) {
            logger.info("Client unsubscribed");
        }

        @Override
        public void onSessionLoopError(Throwable error) {
            logger.error("Session loop error: {}", error.getMessage(), error);
        }
    }

    public boolean isRunning() {
        return mqttBroker != null;
    }

    public int getBrokerPort() {
        return moquetteConfig.getPort();
    }
}
