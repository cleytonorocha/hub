package com.github.cleytonorocha.hub.security;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class LoginAttemptLimiter {
	private static final long WINDOW_NANOS = Duration.ofMinutes(15).toNanos();
	private static final int MAX_ATTEMPTS = 5;
	private final ConcurrentHashMap<String, Entry> attempts = new ConcurrentHashMap<>();
	public boolean blocked(String key) {
		Entry entry = attempts.get(key);
		if (entry == null) return false;
		if (System.nanoTime() - entry.startedAt() >= WINDOW_NANOS) { attempts.remove(key, entry); return false; }
		return entry.count().get() >= MAX_ATTEMPTS;
	}
	public void failed(String key) {
		long now = System.nanoTime();
		if (attempts.size() > 4096) attempts.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= WINDOW_NANOS);
		attempts.compute(key, (ignored, current) -> current == null || now - current.startedAt() >= WINDOW_NANOS
			? new Entry(now, new AtomicInteger(1)) : new Entry(current.startedAt(), new AtomicInteger(current.count().incrementAndGet())));
	}
	public void succeeded(String key) { attempts.remove(key); }
	private record Entry(long startedAt, AtomicInteger count) {}
}
