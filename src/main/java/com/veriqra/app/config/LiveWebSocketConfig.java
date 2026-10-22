package com.veriqra.app.config;

import com.veriqra.app.service.LiveUpdates;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * The live channel of the application.
 *
 * <p>The screens that watch a running session open a socket to this address and are told
 * when something changes. The policy of the application already allows a connection
 * from this origin, so no third party is involved.
 */
@Configuration
@EnableWebSocket
public class LiveWebSocketConfig implements WebSocketConfigurer {

    private final LiveUpdates liveUpdates;

    public LiveWebSocketConfig(LiveUpdates liveUpdates) {
        this.liveUpdates = liveUpdates;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(liveUpdates, "/api/live").setAllowedOrigins("same-origin");
    }
}
