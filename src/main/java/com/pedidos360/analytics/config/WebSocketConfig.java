package com.pedidos360.analytics.config;

import com.pedidos360.analytics.security.EmisoresJwt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

// WebSocket con STOMP: el dashboard se suscribe a /topic/metricas y /topic/alertas.
// Solo un administrador puede conectarse: el JWT viaja en la cabecera Authorization del CONNECT.
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final EmisoresJwt emisoresJwt;
    private final List<String> origenes;

    public WebSocketConfig(EmisoresJwt emisoresJwt, @Value("${app.cors.allowed-origins}") List<String> origenes) {
        this.emisoresJwt = emisoresJwt;
        this.origenes = origenes;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/v1/analytics/ws").setAllowedOrigins(origenes.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String cabecera = accessor.getFirstNativeHeader("Authorization");
                    if (cabecera == null || !cabecera.startsWith("Bearer ")) {
                        throw new MessageDeliveryException("Falta el token en el CONNECT");
                    }
                    try {
                        Authentication auth = emisoresJwt.autenticar(cabecera.substring(7));
                        boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_Admin".equals(a.getAuthority()));
                        if (!admin) {
                            throw new MessageDeliveryException("El dashboard es solo para administradores");
                        }
                        accessor.setUser(auth);
                        log.info("Dashboard conectado: {}", auth.getName());
                    } catch (AuthenticationException e) {
                        throw new MessageDeliveryException("Token inválido: " + e.getMessage());
                    }
                }
                return message;
            }
        });
    }
}
