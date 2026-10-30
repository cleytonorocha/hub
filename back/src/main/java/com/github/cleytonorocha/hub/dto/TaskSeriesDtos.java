package com.github.cleytonorocha.hub.dto;

import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.entity.TaskSeries;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class TaskSeriesDtos {
	private TaskSeriesDtos() {}

	public record CreateSeriesRequest(
		@Pattern(regexp = "^[0-9a-fA-F-]{36}$") String operationId,
		@NotBlank @Size(max = 1024) String title,
		@NotNull LocalDate startDate,
		LocalTime dueTime,
		@Min(2) @Max(52) int occurrenceCount,
		@NotBlank @Size(max = 64) String timeZoneId,
		@Size(max = 64) String category,
		@Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
		Task.Priority priority,
		@Size(max = 2000) String notes) {}

	public record SeriesView(TaskSeries series, List<Task> occurrences) {}
	public record ClosePreview(String seriesId, int pendingFutureCount, LocalDate today, String timeZoneId, long revision) {}
	public record CloseRequest(@NotNull LocalDate today, @NotBlank @Size(max = 64) String timeZoneId, long expectedRevision) {}
	public record CloseResult(String status, int removedOccurrences, int preservedOccurrences, LocalDate today) {}
}
