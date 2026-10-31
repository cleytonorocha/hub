package com.github.cleytonorocha.hub.dto;

import com.github.cleytonorocha.hub.entity.Habit;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class HabitDtos {
	private HabitDtos() {}

	public record CreateHabitRequest(@NotBlank @Size(max = 120) String name,
		@NotNull LocalDate startDate, @NotNull @Size(min = 1, max = 7) List<Integer> weekdays) {}

	public record UpdateHabitRequest(@NotBlank @Size(max = 120) String name,
		@NotNull LocalDate startDate,
		@NotNull @Size(min = 1, max = 7) List<Integer> weekdays, @NotNull Long revision) {}

	public record HabitSummary(String id, String name, LocalDate startDate, long revision,
		Instant createdAt, Instant updatedAt) {
		public static HabitSummary from(Habit habit) {
			return new HabitSummary(habit.getId(), habit.getName(), habit.getStartDate(), habit.getRevision(), habit.getCreatedAt(), habit.getUpdatedAt());
		}
	}

	public record DayView(LocalDate date, String status, boolean checked, boolean eligible) {}
	public record HabitWeek(String id, String name, LocalDate startDate, long revision,
		int weekdaysMask, boolean paused, boolean canEditStartDate, List<DayView> days, int completed, int eligible) {}
	public record WeekView(LocalDate weekStart, LocalDate weekEnd, LocalDate today, String timeZoneId,
		List<HabitWeek> habits, int completed, int eligible) {}
	public record MutationResult(String id, long revision, String status) {}
}
