package com.anonymous.chat.security;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimiter {

    private static final int MAX_TOKENS = 5;
    // 1 second in nanoseconds
    private static final long REFILL_INTERVAL_NANOS = 1_000_000_000L;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public boolean tryConsume(WebSocketSession session) {
        String sessionId = session.getId();
        return buckets.computeIfAbsent(sessionId, k -> new TokenBucket()).tryConsume();
    }

    public void removeSession(WebSocketSession session) {
        buckets.remove(session.getId());
    }

    private static class TokenBucket {
        private int tokens = MAX_TOKENS;
        private long lastRefillTime = System.nanoTime();

        // Synchronized to ensure thread-safety
        public synchronized boolean tryConsume() {
            refill();
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillTime;

            if (elapsed > REFILL_INTERVAL_NANOS) {
                // Strict reset per second:
                tokens = MAX_TOKENS;
                lastRefillTime = now;
            }
        }
    }
}
