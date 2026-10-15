package com.minijudge.judge.push;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP WebSocket 配置：端点 /ws/judge（网关已配 ws 路由，见 gateway application.yml）。
 * 内存 broker 即可演示；多实例部署要换外部 relay(RabbitMQ STOMP)——注释记为演进项。
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 生产要把 * 收紧到前端域名（跨域安全）；演示期放开并在此注明
        registry.addEndpoint("/ws/judge").setAllowedOriginPatterns("*").withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 服务端向 /topic 发，客户端订阅 /topic/submission/{id}
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
