package com.traffic.dashboard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        System.out.println("[DEBUG] WebSocketConfig.configureMessageBroker() called");
        try {
            registry.enableSimpleBroker("/topic");
            registry.setApplicationDestinationPrefixes("/app");
            System.out.println("[DEBUG] WebSocketConfig.configureMessageBroker() - message broker configured");
        } catch (Exception e) {
            System.err.println("[DEBUG] WebSocketConfig.configureMessageBroker() failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        System.out.println("[DEBUG] WebSocketConfig.registerStompEndpoints() called");
        try {
            registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
            System.out.println("[DEBUG] WebSocketConfig.registerStompEndpoints() - STOMP endpoint registered at /ws");
        } catch (Exception e) {
            System.err.println("[DEBUG] WebSocketConfig.registerStompEndpoints() failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}