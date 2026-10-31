package com.github.cleytonorocha.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "hub_habit_checkin", uniqueConstraints =
	@UniqueConstraint(name = "uq_hub_habit_checkin_date", columnNames = { "owner_id", "habit_id", "checkin_date" }))
public class HabitCheckIn {
	@Id
	@Column(length = 36, nullable = false)
	private String id;

	@TenantId
	@Column(name = "owner_id", length = 36, nullable = false)
	private String ownerId;

	@Column(name = "habit_id", length = 36, nullable = false)
	private String habitId;

	@Column(name = "checkin_date", nullable = false)
	private LocalDate checkinDate;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected HabitCheckIn() {}

	public HabitCheckIn(String habitId, LocalDate checkinDate, Instant createdAt) {
		this.id = UUID.randomUUID().toString();
		this.habitId = habitId;
		this.checkinDate = checkinDate;
		this.createdAt = createdAt;
	}

	public String getId() { return id; }
	public String getHabitId() { return habitId; }
	public LocalDate getCheckinDate() { return checkinDate; }
	public Instant getCreatedAt() { return createdAt; }
}
