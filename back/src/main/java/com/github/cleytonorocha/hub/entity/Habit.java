package com.github.cleytonorocha.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "hub_habit")
public class Habit {
	@Id
	@Column(length = 36, nullable = false)
	private String id;

	@TenantId
	@Column(name = "owner_id", length = 36, nullable = false)
	private String ownerId;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Version
	@Column(nullable = false)
	private long revision;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Habit() {}

	public Habit(String name, LocalDate startDate, Instant now) {
		this.id = UUID.randomUUID().toString();
		this.name = name.strip();
		this.startDate = startDate;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static Habit restore(String id, String name, LocalDate startDate, Instant createdAt, Instant updatedAt) {
		Habit habit = new Habit(name, startDate, createdAt);
		habit.id = id;
		habit.updatedAt = updatedAt;
		return habit;
	}

	public void update(String name, LocalDate startDate, Instant now) {
		this.name = name.strip();
		this.startDate = startDate;
		this.updatedAt = now;
	}

	public String getId() { return id; }
	public String getName() { return name; }
	public LocalDate getStartDate() { return startDate; }
	public long getRevision() { return revision; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
