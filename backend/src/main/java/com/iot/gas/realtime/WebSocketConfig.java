package com.iot.gas.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * WebSocket + STOMP cho dashboard realtime.
 * <ul>
 *   <li>Endpoint: ws://host:8080/ws (WebSocket thuần, không SockJS).</li>
 *   <li>CONNECT phải có header "Authorization: Bearer &lt;JWT&gt;" (kiểm tra trong StompAuthInterceptor).</li>
 *   <li>Kênh: /topic/devices/{deviceCode} - chỉ subscribe được nếu có quyền xem thiết bị đó.</li>
 * </ul>
 * HTTP /ws/** được permitAll trong SecurityConfig vì việc xác thực diễn ra ở tầng STOMP.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor authInterceptor;

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private List<String> allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins.toArray(new String[0]));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        ThreadPoolTaskScheduler heartbeat = new ThreadPoolTaskScheduler();
        heartbeat.setPoolSize(1);
        heartbeat.setThreadNamePrefix("ws-heartbeat-");
        heartbeat.initialize();
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10_000, 10_000})   // phát hiện kết nối chết sau ~10-20 s
                .setTaskScheduler(heartbeat);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
