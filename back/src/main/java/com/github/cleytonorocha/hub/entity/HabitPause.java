package com.github.cleytonorocha.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "hub_habit_pause")
public class HabitPause {
	@Id
	@Column(length = 36, nullable = false)
	private String id;

	@TenantId
	@Column(name = "owner_id", length = 36, nullable = false)
	private String ownerId;

	@Column(name = "habit_id", length = 36, nullable = false)
	private String habitId;

	@Column(name = "paused_on", nullable = false)
	private LocalDate pausedOn;

	@Column(name = "resumed_on")
	private LocalDate resumedOn;

	protected HabitPause() {}

	public HabitPause(String habitId, LocalDate pausedOn) {
		this.id = UUID.randomUUID().toString();
		this.habitId = habitId;
		this.pausedOn = pausedOn;
	}

	public void resume(LocalDate date) { this.resumedOn = date; }
	public String getId() { return id; }
	public String getHabitId() { return habitId; }
	public LocalDate getPausedOn() { return pausedOn; }
	public LocalDate getResumedOn() { return resumedOn; }
}
