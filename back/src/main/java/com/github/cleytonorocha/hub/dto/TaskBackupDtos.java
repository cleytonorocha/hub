package com.github.cleytonorocha.hub.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.cleytonorocha.hub.entity.Task;

public final class TaskBackupDtos {
	private TaskBackupDtos() {}

	public record BackupFile(int formatVersion, Instant generatedAt, List<BackupTask> tasks, List<BackupSeries> series,
		List<BackupReadingItem> readingItems, List<BackupAuthor> authors) {
		public BackupFile(int formatVersion, Instant generatedAt, List<BackupTask> tasks) { this(formatVersion, generatedAt, tasks, List.of(), null, null); }
		public BackupFile(int formatVersion, Instant generatedAt, List<BackupTask> tasks, List<BackupSeries> series) { this(formatVersion, generatedAt, tasks, series, null, null); }
		public BackupFile(int formatVersion, Instant generatedAt, List<BackupTask> tasks, List<BackupSeries> series, List<BackupReadingItem> readingItems) { this(formatVersion, generatedAt, tasks, series, readingItems, null); }
	}
	public record BackupAuthor(String id, String name) {}
	public record BackupTask(String id, String title, LocalDate dueDate, LocalTime dueTime, boolean completed,
		String type, String category, String color, Task.Priority priority, String notes, String seriesId, Integer seriesPosition, List<BackupChecklistItem> checklist) {
		public BackupTask(String id, String title, LocalDate dueDate, LocalTime dueTime, boolean completed,
				String type, String category, String color, Task.Priority priority, String notes) {
			this(id, title, dueDate, dueTime, completed, type, category, color, priority, notes, null, null, null);
		}
		public BackupTask(String id, String title, LocalDate dueDate, LocalTime dueTime, boolean completed,
				String type, String category, String color, Task.Priority priority, String notes, String seriesId, Integer seriesPosition) {
			this(id, title, dueDate, dueTime, completed, type, category, color, priority, notes, seriesId, seriesPosition, null);
		}
	}
	public record BackupSeries(String id, String title, LocalDate startDate, LocalTime dueTime,
		int occurrenceCount, String timeZoneId, boolean ended) {}
	public record BackupReadingItem(String id, String title, String type, String authorOrSource, String url,
		String status, String progressMarker, @JsonInclude(JsonInclude.Include.ALWAYS) Integer totalPages,
		@JsonInclude(JsonInclude.Include.ALWAYS) Integer currentPage, String note, List<String> tags, boolean archived, Instant createdAt, Instant updatedAt,
		@JsonInclude(JsonInclude.Include.NON_NULL) String coverSha256,
		@JsonInclude(JsonInclude.Include.NON_NULL) String coverFormat,
		@JsonInclude(JsonInclude.Include.NON_NULL) Long coverSize,
		@JsonInclude(JsonInclude.Include.NON_NULL) String coverSourceUrl,
		List<String> authorIds) {
		public BackupReadingItem(String id, String title, String type, String authorOrSource, String url,
				String status, String progressMarker, String note, List<String> tags, boolean archived, Instant createdAt, Instant updatedAt) {
			this(id, title, type, authorOrSource, url, status, progressMarker, null, null, note, tags, archived, createdAt, updatedAt, null, null, null, null, List.of());
		}
		public BackupReadingItem(String id, String title, String type, String authorOrSource, String url,
				String status, String progressMarker, String note, List<String> tags, boolean archived, Instant createdAt, Instant updatedAt,
				String coverSha256, String coverFormat, Long coverSize) {
			this(id, title, type, authorOrSource, url, status, progressMarker, null, null, note, tags, archived, createdAt, updatedAt, coverSha256, coverFormat, coverSize, null, List.of());
		}
		public BackupReadingItem(String id, String title, String type, String authorOrSource, String url, String status,
				String progressMarker, Integer totalPages, Integer currentPage, String note, List<String> tags, boolean archived,
				Instant createdAt, Instant updatedAt, String coverSha256, String coverFormat, Long coverSize) {
			this(id, title, type, authorOrSource, url, status, progressMarker, totalPages, currentPage, note, tags, archived, createdAt, updatedAt,
				coverSha256, coverFormat, coverSize, null, List.of());
		}
	}
	public record BackupChecklistItem(String id, String text, boolean checked, int position) {}
	public record TaskSample(String id, String title, LocalDate dueDate) {}
	public record ReadingItemSample(String id, String title, String type, boolean archived) {}
	public record Preview(String token, int formatVersion, Instant generatedAt, int totalTasks,
		int newTasks, int identicalTasks, int conflictingTasks, int repeatedIdenticalIds,
		List<TaskSample> samples, List<TaskSample> conflicts, List<String> conflictIds,
		int totalReadingItems, int newReadingItems, int identicalReadingItems, int conflictingReadingItems,
		int repeatedIdenticalReadingIds, List<ReadingItemSample> readingSamples,
		List<ReadingItemSample> readingConflicts, List<String> readingConflictIds, boolean canImport,
		int totalCovers, int newCovers, int identicalCovers, int totalAuthors, int newAuthors, int conflictingAuthors) {
		public Preview(String token, int formatVersion, Instant generatedAt, int totalTasks,
			int newTasks, int identicalTasks, int conflictingTasks, int repeatedIdenticalIds,
			List<TaskSample> samples, List<TaskSample> conflicts, List<String> conflictIds,
			int totalReadingItems, int newReadingItems, int identicalReadingItems, int conflictingReadingItems,
			int repeatedIdenticalReadingIds, List<ReadingItemSample> readingSamples,
			List<ReadingItemSample> readingConflicts, List<String> readingConflictIds, boolean canImport,
			int totalCovers, int newCovers, int identicalCovers) {
			this(token, formatVersion, generatedAt, totalTasks, newTasks, identicalTasks, conflictingTasks, repeatedIdenticalIds,
				samples, conflicts, conflictIds, totalReadingItems, newReadingItems, identicalReadingItems, conflictingReadingItems,
				repeatedIdenticalReadingIds, readingSamples, readingConflicts, readingConflictIds, canImport, totalCovers, newCovers, identicalCovers, 0, 0, 0);
		}
	}
	public record Confirmation(String status, Preview preview, int addedTasks, int identicalTasks,
		int repeatedIdenticalIds, int addedReadingItems, int identicalReadingItems, int repeatedIdenticalReadingIds, int addedCovers, int addedAuthors) {
		public Confirmation(String status, Preview preview, int addedTasks, int identicalTasks, int repeatedIdenticalIds,
				int addedReadingItems, int identicalReadingItems, int repeatedIdenticalReadingIds) {
			this(status, preview, addedTasks, identicalTasks, repeatedIdenticalIds, addedReadingItems, identicalReadingItems, repeatedIdenticalReadingIds, 0);
		}
		public Confirmation(String status, Preview preview, int addedTasks, int identicalTasks, int repeatedIdenticalIds,
				int addedReadingItems, int identicalReadingItems, int repeatedIdenticalReadingIds, int addedCovers) {
			this(status, preview, addedTasks, identicalTasks, repeatedIdenticalIds, addedReadingItems, identicalReadingItems, repeatedIdenticalReadingIds, addedCovers, 0);
		}
	}
}
