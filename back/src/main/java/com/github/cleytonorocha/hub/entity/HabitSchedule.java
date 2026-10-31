package com.github.cleytonorocha.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "hub_habit_schedule", uniqueConstraints =
	@UniqueConstraint(name = "uq_hub_habit_schedule_date", columnNames = { "owner_id", "habit_id", "effective_from" }))
public class HabitSchedule {
	@Id
	@Column(length = 36, nullable = false)
	private String id;

	@TenantId
	@Column(name = "owner_id", length = 36, nullable = false)
	private String ownerId;

	@Column(name = "habit_id", length = 36, nullable = false)
	private String habitId;

	@Column(name = "effective_from", nullable = false)
	private LocalDate effectiveFrom;

	@Column(name = "weekdays_mask", nullable = false)
	private int weekdaysMask;

	protected HabitSchedule() {}

	public HabitSchedule(String habitId, LocalDate effectiveFrom, int weekdaysMask) {
		this.id = UUID.randomUUID().toString();
		this.habitId = habitId;
		this.effectiveFrom = effectiveFrom;
		this.weekdaysMask = weekdaysMask;
	}

	public String getId() { return id; }
	public String getHabitId() { return habitId; }
	public LocalDate getEffectiveFrom() { return effectiveFrom; }
	public int getWeekdaysMask() { return weekdaysMask; }
}
