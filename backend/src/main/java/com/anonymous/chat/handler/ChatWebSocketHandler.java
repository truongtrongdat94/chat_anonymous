package com.anonymous.chat.handler;

import com.anonymous.chat.security.RateLimiter;
import com.anonymous.chat.service.RoomService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    // Regex: Allow most chars except < > { } [ ] \ /, 1-50 chars.
    private static final Pattern NAME_PATTERN = Pattern.compile("^[^<>{}\\[\\]\\\\\\/]{1,50}$");

    private final RoomService roomService;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    // Map storing user names
    private final Map<WebSocketSession, String> userNames = new ConcurrentHashMap<>();

    // Executor for kicking zombie sockets
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public ChatWebSocketHandler(RoomService roomService, RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.roomService = roomService;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    // Ensure thread cleanup on server shutdown
    @PreDestroy
    public void cleanup() {
        scheduler.shutdown();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("Connection established: {}", session.getId());

        // Zombie Socket Protection: 5 seconds to JOIN or kick
        scheduler.schedule(() -> {
            if (session.isOpen() && !userNames.containsKey(session)) {
                try {
                    logger.warn("Kicking zombie session: {}", session.getId());
                    session.close(CloseStatus.POLICY_VIOLATION);
                } catch (IOException e) {
                    // Ignore
                }
            }
        }, 5, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!rateLimiter.tryConsume(session)) {
            return;
        }

        // Message size is enforced by WebSocketConfig (Container level)

        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String type = json.path("type").asText();

            if ("PING".equals(type)) {
                session.sendMessage(new TextMessage("{\"type\": \"PONG\"}"));
                return;
            }

            if ("JOIN".equals(type)) {
                handleJoin(session, json);
            } else {
                handleChat(session, message);
            }
        } catch (Exception e) {
            logger.error("Error handling message from session " + session.getId(), e);
            session.close(CloseStatus.BAD_DATA);
        }
    }

    private void handleJoin(WebSocketSession session, JsonNode json) throws IOException {
        String rawName = json.path("name").asText("Stranger");
        logger.info("Received JOIN request from session {} with name: '{}'", session.getId(), rawName);

        // Security Validation (XSS Prevention)
        if (!NAME_PATTERN.matcher(rawName).matches()) {
            logger.warn("Validation failed for name: '{}'. Pattern: {}", rawName, NAME_PATTERN.pattern());
            sendJson(session, new ErrorResponse("ERROR", "Invalid name. Alphanumeric only, max 50 chars."));
            return;
        }

        // Prevent Double JOIN
        if (userNames.putIfAbsent(session, rawName) != null) {
            return;
        }

        matchUser(session);
    }

    private void handleChat(WebSocketSession session, TextMessage message) throws IOException {
        // Enforce Authentication
        if (!userNames.containsKey(session)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        WebSocketSession partner = roomService.getPartner(session);
        if (partner != null && partner.isOpen()) {
            partner.sendMessage(message);
        } else {
            sendJson(session, new ErrorResponse("ERROR", "No partner connected"));
        }
    }

    private void matchUser(WebSocketSession session) throws IOException {
        RoomService.MatchResult result = roomService.matchOrEnqueue(session);

        switch (result.type()) {
            case MATCHED -> {
                WebSocketSession partner = result.partner();
                String myName = userNames.getOrDefault(session, "Anonymous");
                String partnerName = userNames.getOrDefault(partner, "Anonymous");

                sendJson(partner, new MatchResponse("MATCHED", "INITIATOR", myName));
                sendJson(session, new MatchResponse("MATCHED", "PEER", partnerName));
            }
            case WAITING -> sendJson(session, new BaseResponse("WAITING"));
            case QUEUE_FULL -> {
                sendJson(session, new ErrorResponse("ERROR", "Queue full"));
                session.close(CloseStatus.SERVER_ERROR);
            }
            case ERROR -> {
                sendJson(session, new ErrorResponse("ERROR", "System error"));
                session.close(CloseStatus.SERVER_ERROR);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        logger.info("Connection closed: {}", session.getId());

        try {
            rateLimiter.removeSession(session);
            userNames.remove(session);

            WebSocketSession partner = roomService.removeAndGetPartner(session);
            if (partner != null && partner.isOpen()) {
                sendJson(partner, new BaseResponse("PARTNER_DISCONNECTED"));
                partner.close(CloseStatus.NORMAL);
            }
        } catch (Exception e) {
            logger.error("Error during cleanup", e);
        }
    }

    private void sendJson(WebSocketSession session, Object responseObj) throws IOException {
        if (session.isOpen()) {
            String json = objectMapper.writeValueAsString(responseObj);
            session.sendMessage(new TextMessage(json));
        }
    }

    // DTOs
    record BaseResponse(String type) {
    }

    record ErrorResponse(String type, String message) {
    }

    record MatchResponse(String type, String role, String partnerName) {
    }
}
