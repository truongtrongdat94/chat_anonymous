package com.anonymous.chat.service;

import com.anonymous.chat.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RoomService {

    private static final Logger logger = LoggerFactory.getLogger(RoomService.class);
    private static final int MAX_QUEUE_SIZE = 5000;

    // Global Waiting Queue
    private final Queue<WebSocketSession> waitingQueue = new ConcurrentLinkedQueue<>();
    // OPTIMIZATION: Track size with AtomicInteger for O(1) performance
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final Set<String> queuedSessionIds = ConcurrentHashMap.newKeySet();

    // Active Room Map: RoomId -> Room
    private final Map<String, Room> activeRooms = new ConcurrentHashMap<>();

    // Session Map: SessionID -> RoomId
    private final Map<String, String> sessionRoomMap = new ConcurrentHashMap<>();

    // Internal lock for atomic matching only (queue head modification)
    private final Object queueLock = new Object();

    public MatchResult matchOrEnqueue(WebSocketSession session) {
        String sessionId = session.getId();

        synchronized (queueLock) {
            // 1. Guard against duplicate enqueue
            if (queuedSessionIds.contains(sessionId)) {
                return new MatchResult(MatchType.WAITING, null);
            }
            if (sessionRoomMap.containsKey(sessionId)) {
                return new MatchResult(MatchType.ERROR, null);
            }

            // 2. Try to match with existing waiter
            WebSocketSession candidate;
            while ((candidate = waitingQueue.poll()) != null) {
                // Decrement size immediately upon retrieval
                queueSize.decrementAndGet();

                if (!candidate.isOpen()) {
                    queuedSessionIds.remove(candidate.getId());
                    continue; // Lazy cleanup: candidate dead
                }

                // Match found!
                queuedSessionIds.remove(candidate.getId());
                Room room = createRoom(session, candidate);
                return new MatchResult(MatchType.MATCHED, candidate);
            }

            // 3. No match, enqueue self
            // CHECK SIZE O(1) - Efficient
            if (queueSize.get() >= MAX_QUEUE_SIZE) {
                return new MatchResult(MatchType.QUEUE_FULL, null);
            }

            waitingQueue.offer(session);
            queueSize.incrementAndGet(); // Increment size
            queuedSessionIds.add(sessionId);
            return new MatchResult(MatchType.WAITING, null);
        }
    }

    private Room createRoom(WebSocketSession session1, WebSocketSession session2) {
        String roomId = UUID.randomUUID().toString();
        Room room = new Room(roomId, session1, session2);

        activeRooms.put(roomId, room);
        sessionRoomMap.put(session1.getId(), roomId);
        sessionRoomMap.put(session2.getId(), roomId);

        logger.info("Room created");
        return room;
    }

    public WebSocketSession getPartner(WebSocketSession session) {
        String roomId = sessionRoomMap.get(session.getId());
        if (roomId == null)
            return null;
        Room room = activeRooms.get(roomId);
        if (room == null)
            return null;

        return room.getSession1().getId().equals(session.getId()) ? room.getSession2() : room.getSession1();
    }

    public WebSocketSession removeAndGetPartner(WebSocketSession session) {
        String sessionId = session.getId();

        // Remove from queue set if present
        // We do NOT remove from waitingQueue to avoid O(n).
        // Lazy cleanup will handle implementation.
        if (queuedSessionIds.remove(sessionId)) {
            // Note: queueSize might slightly overestimate transiently.
            // This is an acceptable tradeoff for O(1) performance vs O(n) removal.
            return null;
        }

        String roomId = sessionRoomMap.remove(sessionId);
        if (roomId != null) {
            Room room = activeRooms.remove(roomId);
            if (room != null) {
                WebSocketSession partner = room.getSession1().getId().equals(sessionId) ? room.getSession2()
                        : room.getSession1();
                sessionRoomMap.remove(partner.getId());
                return partner;
            }
        }

        return null;
    }

    public enum MatchType {
        WAITING, MATCHED, ERROR, QUEUE_FULL
    }

    public record MatchResult(MatchType type, WebSocketSession partner) {
    }
}
