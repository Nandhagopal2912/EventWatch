package com.main;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fixed one-minute window per client address. Without eviction the map grows with every
 * distinct caller, so expired windows are swept on the maintenance timer.
 */
class RateLimiter {
    private static final long WINDOW_MILLIS = 60_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxRequestsPerWindow;

    RateLimiter(int maxRequestsPerWindow) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
    }

    boolean allow(String clientAddress) {
        return windows.computeIfAbsent(clientAddress, key -> new Window()).allow(maxRequestsPerWindow);
    }

    void sweepExpired() {
        long now = System.currentTimeMillis();
        windows.values().removeIf(window -> window.isExpired(now));
    }

    private static class Window {
        private long startedAt = System.currentTimeMillis();
        private int requestCount;

        synchronized boolean allow(int maximum) {
            long now = System.currentTimeMillis();
            if (now - startedAt >= WINDOW_MILLIS) {
                startedAt = now;
                requestCount = 0;
            }
            if (requestCount >= maximum) {
                return false;
            }
            requestCount++;
            return true;
        }

        synchronized boolean isExpired(long now) {
            return now - startedAt >= WINDOW_MILLIS;
        }
    }
}
