package com.main;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The newest events per machine, mirrored in memory so alert rules can read a window without a
 * query per event. History stays in the database; this holds only what the rules need.
 *
 * <p>A shared window would average unrelated hosts together and make every alert meaningless,
 * so the window is per host — and the map is bounded, because a misconfigured fleet sending
 * random identifiers must not grow memory without limit.
 */
class RecentEvents {
    private static final int MAX_TRACKED_HOSTS = 1000;
    private static final int MAX_RESTORED_HOSTS = 50;

    private final EventRepository eventRepository;
    private final int windowSize;
    private final AtomicLong storedEventCount = new AtomicLong();
    private final Map<String, Deque<LogEntry>> byHost;

    RecentEvents(EventRepository eventRepository, int windowSize) {
        this.eventRepository = eventRepository;
        this.windowSize = windowSize;
        this.byHost = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<LogEntry>> eldest) {
                return size() > MAX_TRACKED_HOSTS;
            }
        };
    }

    /** Restores each machine's window at startup; the database remains the source of truth. */
    synchronized void restore() throws SQLException {
        storedEventCount.set(eventRepository.count(null, null, null));
        List<LogEntry> newestFirst = eventRepository.recent(windowSize * MAX_RESTORED_HOSTS);
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            remember(newestFirst.get(index));
        }
    }

    synchronized boolean store(LogEntry event) throws SQLException {
        // Commit to the database before adding the event to memory, preventing acknowledged
        // data loss.
        boolean inserted = eventRepository.insertIfAbsent(event);
        if (inserted) {
            storedEventCount.incrementAndGet();
            remember(event);
        }
        return inserted;
    }

    synchronized List<LogEntry> snapshot(String hostId) {
        Deque<LogEntry> window = byHost.get(hostId);
        return window == null ? List.of() : new ArrayList<>(window);
    }

    /** The newest events across every tracked machine, for the terminal report. */
    synchronized List<LogEntry> snapshot() {
        List<LogEntry> combined = new ArrayList<>();
        for (Deque<LogEntry> window : byHost.values()) {
            combined.addAll(window);
        }
        combined.sort(Comparator.comparing(event -> event.timestamp));
        int start = Math.max(0, combined.size() - windowSize);
        return new ArrayList<>(combined.subList(start, combined.size()));
    }

    long total() {
        return storedEventCount.get();
    }

    private synchronized void remember(LogEntry event) {
        Deque<LogEntry> window = byHost.computeIfAbsent(event.hostId, key -> new ArrayDeque<>());
        window.addLast(event);
        while (window.size() > windowSize) {
            window.removeFirst();
        }
    }
}
