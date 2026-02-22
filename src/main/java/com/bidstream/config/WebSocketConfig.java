package com.bidstream.config;

import com.bidstream.common.security.JwtService;
import java.security.Principal;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket (PDR §15.1). {@code /topic/auctions/{id}} carries price/state broadcasts;
 * {@code /user/queue/notifications} carries targeted per-user messages. A single in-memory
 * broker is enough - PDR §15.3 explicitly notes broadcast needs no cross-node coordination or
 * shared broker, since each node's tick reads the same Redis key and fans out only to its own
 * locally-connected sessions.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtService jwtService;

    public WebSocketConfig(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new JwtStompChannelInterceptor(jwtService));
    }

    private record JwtStompChannelInterceptor(JwtService jwtService) implements ChannelInterceptor {

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                List<String> authHeaders = accessor.getNativeHeader("Authorization");
                String token = extractBearerToken(authHeaders);
                JwtService.DecodedToken decoded = jwtService.verifyAccessToken(token);
                accessor.setUser((Principal) () -> decoded.userId().toString());
            }
            return message;
        }

        private String extractBearerToken(List<String> authHeaders) {
            if (authHeaders == null || authHeaders.isEmpty()) {
                throw new org.springframework.security.authentication.BadCredentialsException(
                        "Missing Authorization header on STOMP CONNECT");
            }
            String header = authHeaders.get(0);
            if (!header.startsWith("Bearer ")) {
                throw new org.springframework.security.authentication.BadCredentialsException(
                        "Authorization header must be a Bearer token");
            }
            return header.substring("Bearer ".length());
        }
    }
}
