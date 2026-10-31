package com.github.cleytonorocha.hub.dto;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class FocusSessionDtos {
	private FocusSessionDtos() {}

	public record StartRequest(
		@NotBlank @Size(max = 240) String objective,
		@Min(60) @Max(43_200) int plannedSeconds,
		@NotBlank @Pattern(regexp = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String id,
		@Pattern(regexp = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String taskId) {}

	public record TransitionRequest(@NotNull Long revision) {}
	public record EndRequest(@NotNull Long revision, @Min(0) @Max(43_200) Integer focusedSeconds) {}
	public record SessionView(String id, String objective, int plannedSeconds, int focusedSeconds,
		String state, Instant startedAt, Instant resumedAt, Instant pausedAt, Instant endedAt,
		String endReason, String taskId, String taskTitle, boolean taskAvailable, long revision) {}
	public record HistoryPage(List<SessionView> sessions, boolean hasMore, int nextOffset) {}
}
