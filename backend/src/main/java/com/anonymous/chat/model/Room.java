package com.anonymous.chat.model;

import org.springframework.web.socket.WebSocketSession;

public class Room {
    private final String roomId;
    private final WebSocketSession session1;
    private final WebSocketSession session2;

    public Room(String roomId, WebSocketSession session1, WebSocketSession session2) {
        this.roomId = roomId;
        this.session1 = session1;
        this.session2 = session2;
    }

    public String getRoomId() {
        return roomId;
    }

    public WebSocketSession getSession1() {
        return session1;
    }

    public WebSocketSession getSession2() {
        return session2;
    }
}
