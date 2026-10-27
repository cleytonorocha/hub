package com.github.cleytonorocha.hub.dto.google;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class GoogleTaskDtos {
	private GoogleTaskDtos() {}

	public record TaskList(String id, String title, boolean selected) {}
	public record Task(String id, String listId, String listTitle, String title, LocalDate dueDate, boolean completed, String updated, String etag, boolean deleted) {}
	public record TaskChange(LocalDate dueDate, Boolean completed, String expectedUpdated, String expectedEtag) {}
	public record NewTask(String listId, String title, LocalDate dueDate) {}
	public record SyncResult(String status, Instant syncedAt, List<TaskList> lists, List<Task> tasks) {}
	public record ConnectionStatus(String status, boolean configured, Instant lastSyncedAt, String message) {}
	public record ReminderSettings(String reminderTime) {}
	public record Notification(String id, String listId, String taskId, LocalDate dueDate, Instant scheduledAt, String state, boolean overdue, Instant createdAt, Instant readAt) {}
}
