package com.veriqra.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes the changes of a live session to the screens that watch it.
 *
 * <p>The teacher should not have to wait for the next poll to see a student arrive.
 * The server therefore tells the open screens that something changed, and the screen
 * reads the data it needs again. The payload is a name and not the data itself, so the
 * rules about what a screen may see stay in the places that own them.
 *
 * <p>The rooms are named by the account: a teacher watches the room of their own
 * account, and a student watches the rooms of the classes they are in.
 */
@Component
public class LiveUpdates extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdates.class);

    private final Set<WebSocketSession> clients = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper;

    public LiveUpdates(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        clients.add(session);
        log.debug("A client opened the live channel, {} are open", clients.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        clients.remove(session);
    }

    /**
     * Tells the clients of one room that something changed.
     *
     * @param room  The room, usually the identifier of the account.
     * @param topic What changed, for example {@code roster} or {@code sessions}.
     */
    public void publish(String room, String topic) {
        if (clients.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = mapper.writeValueAsString(Map.of("room", room, "topic", topic));
        } catch (IOException ex) {
            log.warn("The live update could not be written", ex);
            return;
        }
        for (WebSocketSession client : clients) {
            if (!client.isOpen()) {
                clients.remove(client);
                continue;
            }
            try {
                synchronized (client) {
                    client.sendMessage(new TextMessage(payload));
                }
            } catch (IOException ex) {
                clients.remove(client);
            }
        }
    }

    /** How many clients watch the live channel. */
    public int clients() {
        return clients.size();
    }
}
