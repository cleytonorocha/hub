package com.github.cleytonorocha.hub.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class TaskImportPreviewStore {
	private static final int MAX_ENTRIES = 100;
	private static final long TTL_SECONDS = 600;
	private final Map<String, Entry> entries = new LinkedHashMap<>();

	public synchronized String put(String ownerId, String digest, long taskRevision, long readingRevision, long habitRevision, long noteRevision,
			PortableCopyClassificationSnapshot classification) {
		return put(ownerId, digest, taskRevision, readingRevision, habitRevision, noteRevision, 0, Map.of(), classification);
	}

	public synchronized String put(String ownerId, String digest, long taskRevision, long readingRevision, long habitRevision, long noteRevision,
			long preferenceRevision, Map<String, Object> preferencesToReplace, PortableCopyClassificationSnapshot classification) {
		purgeExpired();
		while (entries.size() >= MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
		String token = UUID.randomUUID().toString();
		entries.put(token, new Entry(ownerId, digest, taskRevision, readingRevision, habitRevision, noteRevision, preferenceRevision, preferencesToReplace, classification, Instant.now()));
		return token;
	}

	public synchronized Entry get(String token, String ownerId) {
		purgeExpired();
		Entry entry = entries.get(token);
		return entry != null && entry.ownerId().equals(ownerId) ? entry : null;
	}

	public synchronized void remove(String token) { entries.remove(token); }

	private void purgeExpired() {
		Instant cutoff = Instant.now().minusSeconds(TTL_SECONDS);
		entries.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoff));
	}

	public record Entry(String ownerId, String digest, long taskRevision, long readingRevision, long habitRevision, long noteRevision,
		long preferenceRevision, Map<String, Object> preferencesToReplace, PortableCopyClassificationSnapshot classification, Instant createdAt) {}
}
