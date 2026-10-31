package com.github.cleytonorocha.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "hub_note")
public class Note {
	@Id @Column(length = 36, nullable = false) private String id;
	@TenantId @Column(name = "owner_id", length = 36, nullable = false) private String ownerId;
	@Column(nullable = false, length = 512) private String title;
	@Column(nullable = false, length = 2048) private String body;
	@Column(nullable = false) private boolean pinned;
	@Column(nullable = false) private boolean archived;
	@Column(name = "related_task_id", length = 36) private String relatedTaskId;
	@Column(name = "related_task_unavailable", nullable = false) private boolean relatedTaskUnavailable;
	@Version @Column(nullable = false) private long revision;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;
	@Transient private List<String> tags = new ArrayList<>();

	protected Note() {}

	public Note(String title, String body, List<String> tags, boolean pinned, String relatedTaskId, Instant now) {
		this.id = UUID.randomUUID().toString();
		this.title = title.strip();
		this.body = body;
		this.tags = new ArrayList<>(tags == null ? List.of() : tags);
		this.pinned = pinned;
		this.relatedTaskId = relatedTaskId;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static Note restore(String id, String title, String body, List<String> tags, boolean pinned, boolean archived,
			String relatedTaskId, boolean relatedTaskUnavailable, Instant createdAt, Instant updatedAt) {
		Note note = new Note(title, body, tags, pinned, relatedTaskId, createdAt);
		note.id = id;
		note.archived = archived;
		note.relatedTaskUnavailable = relatedTaskUnavailable;
		note.updatedAt = updatedAt;
		return note;
	}

	public void update(String title, String body, List<String> tags, boolean pinned, boolean archived,
			String relatedTaskId, boolean relatedTaskUnavailable, Instant now) {
		this.title = title.strip();
		this.body = body;
		this.tags = new ArrayList<>(tags);
		this.pinned = pinned;
		this.archived = archived;
		this.relatedTaskId = relatedTaskId;
		this.relatedTaskUnavailable = relatedTaskUnavailable;
		this.updatedAt = now;
	}

	public String getId() { return id; }
	public String getTitle() { return title; }
	public String getBody() { return body; }
	public boolean isPinned() { return pinned; }
	public boolean isArchived() { return archived; }
	public String getRelatedTaskId() { return relatedTaskId; }
	public boolean isRelatedTaskUnavailable() { return relatedTaskUnavailable; }
	public long getRevision() { return revision; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
	public List<String> getTags() { return List.copyOf(tags); }
	public void restoreTags(List<String> value) { this.tags = new ArrayList<>(value == null ? List.of() : value); }
}
