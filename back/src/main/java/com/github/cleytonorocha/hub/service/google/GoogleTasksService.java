package com.github.cleytonorocha.hub.service.google;

import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.Notification;
import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.ReminderSettings;
import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.SyncResult;
import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.Task;
import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.TaskChange;
import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.TaskList;
import com.github.cleytonorocha.hub.integration.google.GoogleTasksApiClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public final class GoogleTasksService {
	private static final String CONNECTION_ID = "primary";
	private final JdbcTemplate jdbc;
	private final GoogleOAuthService oauth;
	private final GoogleTasksApiClient google;
	private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "google-tasks-sync");
		thread.setDaemon(true);
		return thread;
	});
	private CompletableFuture<SyncResult> inFlightSync;

	public GoogleTasksService(JdbcTemplate jdbc, GoogleOAuthService oauth, GoogleTasksApiClient google) {
		this.jdbc = jdbc;
		this.oauth = oauth;
		this.google = google;
	}

	public SyncResult synchronize() {
		CompletableFuture<SyncResult> operation;
		synchronized (this) {
			if (inFlightSync == null || inFlightSync.isDone()) {
				CompletableFuture<SyncResult> started = CompletableFuture.supplyAsync(this::performSync, syncExecutor);
				inFlightSync = started;
				started.whenComplete((result, error) -> {
					synchronized (GoogleTasksService.this) {
						if (inFlightSync == started) inFlightSync = null;
					}
				});
			}
			operation = inFlightSync;
		}
		try { return operation.join(); }
		catch (CompletionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException runtime) throw runtime;
			throw exception;
		}
	}

	public List<TaskList> listTaskLists() {
		String token = oauth.accessToken();
		return google.listAllTaskLists(token).stream().map(list -> new TaskList(list.id(), list.title(), isSelected(list.id()))).toList();
	}

	public List<Task> listTasks(String listId) {
		String token = oauth.accessToken();
		List<TaskList> lists = google.listAllTaskLists(token);
		TaskList taskList = lists.stream().filter(item -> item.id().equals(listId)).findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Google task list was not found."));
		return google.listAllTasks(token, taskList.id(), taskList.title()).stream().filter(task -> !task.deleted()).toList();
	}

	public Task createTask(String listId, String title, LocalDate dueDate) {
		if (title == null || title.isBlank()) throw new IllegalArgumentException("Task title is required.");
		if (listId == null || listId.isBlank()) throw new IllegalArgumentException("Choose a Google Tasks list.");
		String token = oauth.accessToken();
		TaskList list = findList(token, listId);
		Instant attemptStarted = Instant.now();
		try {
			Task created = google.createTask(token, listId, title.trim(), dueDate, list.title());
			persistTask(created);
			reconcileReminder(created);
			return created;
		} catch (RestClientException exception) {
			List<Task> reconciled;
			try { reconciled = google.listAllTasks(oauth.accessToken(), listId, list.title()).stream().filter(task -> !task.deleted()).toList(); }
			catch (RuntimeException unavailable) { throw new GoogleSyncException("The save could not be confirmed. Refresh Google Tasks before retrying."); }
			List<Task> matches = reconciled.stream().filter(task -> title.trim().equals(task.title()) && equal(dueDate, task.dueDate()) && updatedAfter(task.updated(), attemptStarted)).toList();
			if (matches.size() == 1) {
				persistTask(matches.getFirst());
				reconcileReminder(matches.getFirst());
				return matches.getFirst();
			}
			throw new GoogleSyncException("The save could not be confirmed. Refresh Google Tasks before retrying.");
		}
	}

	public Task updateTask(String listId, String taskId, TaskChange change) {
		if (change == null || change.completed() == null || change.expectedUpdated() == null || change.expectedUpdated().isBlank()) {
			throw new IllegalArgumentException("Task version and completion state are required.");
		}
		String token = oauth.accessToken();
		TaskList list = findList(token, listId);
		Task current;
		try { current = google.getTask(token, listId, taskId, list.title()); }
		catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() == 404) throw new TaskConflictException(null);
			throw exception;
		}
		if (current == null || current.deleted()) throw new TaskConflictException(null);
		if (!change.expectedUpdated().equals(current.updated()) || (change.expectedEtag() != null && !change.expectedEtag().equals(current.etag()))) throw new TaskConflictException(current);
		Task updated;
		try { updated = google.updateTask(token, listId, taskId, change.dueDate(), change.completed(), current.etag(), list.title()); }
		catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() != 412) throw exception;
			try { throw new TaskConflictException(google.getTask(token, listId, taskId, list.title())); }
			catch (RestClientResponseException gone) {
				if (gone.getStatusCode().value() == 404) throw new TaskConflictException(null);
				throw gone;
			}
		}
		persistTask(updated);
		reconcileReminder(updated);
		return updated;
	}

	public void setListSelected(String listId, boolean selected) {
		String token = oauth.accessToken();
		TaskList list = findList(token, listId);
		int updated = jdbc.update("UPDATE google_task_list_state SET selected = ?, deleted = FALSE WHERE connection_id = ? AND task_list_id = ?", selected, CONNECTION_ID, list.id());
		if (updated == 0) jdbc.update("INSERT INTO google_task_list_state (connection_id, task_list_id, selected, deleted) VALUES (?, ?, ?, FALSE)", CONNECTION_ID, list.id(), selected);
	}

	public List<Notification> notifications(boolean unreadOnly) {
		String sql = "SELECT notification_id, task_list_id, task_id, due_date, scheduled_at, state, overdue, created_at, read_at FROM hub_task_notification WHERE connection_id = ? AND state <> 'CANCELLED'" + (unreadOnly ? " AND state = 'UNREAD'" : "") + " ORDER BY scheduled_at DESC";
		return jdbc.query(sql, (rs, row) -> new Notification(rs.getString("notification_id"), rs.getString("task_list_id"), rs.getString("task_id"), rs.getDate("due_date").toLocalDate(), rs.getTimestamp("scheduled_at").toInstant(), rs.getString("state"), rs.getBoolean("overdue"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()), CONNECTION_ID);
	}

	public void markNotificationRead(String notificationId) {
		int updated = jdbc.update("UPDATE hub_task_notification SET state = 'READ', read_at = ? WHERE connection_id = ? AND notification_id = ? AND state = 'UNREAD'", Timestamp.from(Instant.now()), CONNECTION_ID, notificationId);
		if (updated == 0) throw new IllegalArgumentException("Notification was not found or was already read.");
	}

	public void markAllNotificationsRead() {
		jdbc.update("UPDATE hub_task_notification SET state = 'READ', read_at = ? WHERE connection_id = ? AND state = 'UNREAD'", Timestamp.from(Instant.now()), CONNECTION_ID);
	}

	public ReminderSettings reminderSettings() {
		LocalTime time = jdbc.queryForObject("SELECT reminder_time FROM hub_task_settings WHERE settings_id = 1", (rs, row) -> rs.getTime(1).toLocalTime());
		return new ReminderSettings(time.toString());
	}

	public ReminderSettings saveReminderSettings(String reminderTime) {
		LocalTime newTime;
		try { newTime = LocalTime.parse(reminderTime); }
		catch (RuntimeException exception) { throw new IllegalArgumentException("Enter a valid reminder time."); }
		jdbc.update("UPDATE hub_task_settings SET reminder_time = ? WHERE settings_id = 1", java.sql.Time.valueOf(newTime));
		LocalDate today = LocalDate.now();
		List<ReminderRow> pending = jdbc.query("SELECT notification_id, due_date FROM hub_task_notification WHERE connection_id = ? AND state = 'PENDING'", (rs, row) -> new ReminderRow(rs.getString(1), rs.getDate(2).toLocalDate()), CONNECTION_ID);
		for (ReminderRow notice : pending) {
			Instant scheduled = notice.dueDate().minusDays(1).atTime(newTime).atZone(ZoneId.systemDefault()).toInstant();
			if (!notice.dueDate().isAfter(today)) {
				jdbc.update("UPDATE hub_task_notification SET state = 'CANCELLED' WHERE notification_id = ?", notice.id());
			} else if (!scheduled.isAfter(Instant.now())) {
				jdbc.update("UPDATE hub_task_notification SET scheduled_at = ?, state = 'UNREAD', overdue = TRUE WHERE notification_id = ?", Timestamp.from(scheduled), notice.id());
			} else {
				jdbc.update("UPDATE hub_task_notification SET scheduled_at = ? WHERE notification_id = ?", Timestamp.from(scheduled), notice.id());
			}
		}
		return reminderSettings();
	}

	@Scheduled(fixedDelay = 60000)
	public void deliverDueNotifications() {
		Instant now = Instant.now();
		jdbc.update("UPDATE hub_task_notification SET state = 'UNREAD', overdue = FALSE WHERE state = 'PENDING' AND scheduled_at <= ? AND due_date > ?", Timestamp.from(now), Date.valueOf(LocalDate.now()));
		jdbc.update("UPDATE hub_task_notification SET state = 'CANCELLED' WHERE state = 'PENDING' AND due_date <= ?", Date.valueOf(LocalDate.now()));
	}

	private SyncResult performSync() {
		String token = oauth.accessToken();
		List<TaskList> remoteLists = google.listAllTaskLists(token);
		String runId = UUID.randomUUID().toString();
		Set<String> seenListIds = new HashSet<>();
		List<TaskList> lists = new ArrayList<>();
		List<Task> visibleTasks = new ArrayList<>();
		for (TaskList remoteList : remoteLists) {
			String id = remoteList.id();
			seenListIds.add(id);
			boolean selected = isSelected(id);
			persistList(id, selected, runId);
			List<Task> tasks = google.listAllTasks(token, id, remoteList.title());
			for (Task task : tasks) {
				persistTask(task, runId);
				reconcileReminder(task);
				if (!task.deleted()) visibleTasks.add(task);
			}
			jdbc.update("UPDATE google_task_state SET deleted = TRUE WHERE connection_id = ? AND task_list_id = ? AND last_sync_run IS DISTINCT FROM ?", CONNECTION_ID, id, runId);
			cancelDeletedTasks(id);
			lists.add(new TaskList(id, remoteList.title(), selected));
		}
		if (!seenListIds.isEmpty()) {
			String placeholders = String.join(",", seenListIds.stream().map(ignored -> "?").toList());
			List<Object> args = new ArrayList<>();
			args.add(CONNECTION_ID);
			args.addAll(seenListIds);
			jdbc.update("UPDATE google_task_list_state SET deleted = TRUE WHERE connection_id = ? AND task_list_id NOT IN (" + placeholders + ")", args.toArray());
			jdbc.update("UPDATE google_task_state SET deleted = TRUE WHERE connection_id = ? AND task_list_id NOT IN (" + placeholders + ")", args.toArray());
			jdbc.update("UPDATE hub_task_notification SET state = 'CANCELLED' WHERE connection_id = ? AND task_list_id NOT IN (" + placeholders + ") AND state IN ('PENDING', 'UNREAD')", args.toArray());
		} else {
			jdbc.update("UPDATE google_task_list_state SET deleted = TRUE WHERE connection_id = ?", CONNECTION_ID);
			jdbc.update("UPDATE google_task_state SET deleted = TRUE WHERE connection_id = ?", CONNECTION_ID);
			jdbc.update("UPDATE hub_task_notification SET state = 'CANCELLED' WHERE connection_id = ? AND state IN ('PENDING', 'UNREAD')", CONNECTION_ID);
		}
		oauth.markSynchronized();
		return new SyncResult("CONNECTED", Instant.now(), lists, visibleTasks);
	}

	private TaskList findList(String token, String listId) {
		return google.listAllTaskLists(token).stream().filter(list -> list.id().equals(listId)).findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Google task list was not found."));
	}

	private void persistList(String id, boolean selected, String runId) {
		int updated = jdbc.update("UPDATE google_task_list_state SET deleted = FALSE, selected = ?, last_sync_run = ? WHERE connection_id = ? AND task_list_id = ?", selected, runId, CONNECTION_ID, id);
		if (updated == 0) jdbc.update("INSERT INTO google_task_list_state (connection_id, task_list_id, selected, deleted, last_sync_run) VALUES (?, ?, ?, FALSE, ?)", CONNECTION_ID, id, selected, runId);
	}

	private void persistTask(Task task) { persistTask(task, null); }

	private void persistTask(Task task, String runId) {
		Date dueDate = task.dueDate() == null ? null : Date.valueOf(task.dueDate());
		int updated = jdbc.update("UPDATE google_task_state SET remote_updated = ?, remote_etag = ?, due_date = ?, completed = ?, deleted = ?, last_sync_run = ? WHERE connection_id = ? AND task_list_id = ? AND task_id = ?",
			task.updated(), task.etag(), dueDate, task.completed(), task.deleted(), runId, CONNECTION_ID, task.listId(), task.id());
		if (updated == 0) jdbc.update("INSERT INTO google_task_state (connection_id, task_list_id, task_id, remote_updated, remote_etag, due_date, completed, deleted, last_sync_run) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			CONNECTION_ID, task.listId(), task.id(), task.updated(), task.etag(), dueDate, task.completed(), task.deleted(), runId);
	}

	private void reconcileReminder(Task task) {
		LocalDate today = LocalDate.now();
		if (task.deleted() || task.completed() || task.dueDate() == null || !task.dueDate().isAfter(today)) {
			jdbc.update("UPDATE hub_task_notification SET state = 'CANCELLED' WHERE connection_id = ? AND task_list_id = ? AND task_id = ? AND state = 'PENDING'", CONNECTION_ID, task.listId(), task.id());
			return;
		}
		LocalTime reminderTime = LocalTime.parse(reminderSettings().reminderTime());
		Instant scheduledAt = task.dueDate().minusDays(1).atTime(reminderTime).atZone(ZoneId.systemDefault()).toInstant();
		boolean overdue = !scheduledAt.isAfter(Instant.now());
		List<String> stale = jdbc.query("SELECT notification_id FROM hub_task_notification WHERE connection_id = ? AND task_list_id = ? AND task_id = ? AND due_date <> ? AND state IN ('PENDING', 'UNREAD')",
			(rs, row) -> rs.getString(1), CONNECTION_ID, task.listId(), task.id(), Date.valueOf(task.dueDate()));
		for (String id : stale) jdbc.update("UPDATE hub_task_notification SET state = 'CANCELLED' WHERE notification_id = ?", id);
		List<String> existing = jdbc.query("SELECT notification_id, state FROM hub_task_notification WHERE connection_id = ? AND task_list_id = ? AND task_id = ? AND due_date = ?",
			(rs, row) -> rs.getString("state") + ":" + rs.getString("notification_id"), CONNECTION_ID, task.listId(), task.id(), Date.valueOf(task.dueDate()));
		if (!existing.isEmpty()) {
			String[] values = existing.getFirst().split(":", 2);
			if ("CANCELLED".equals(values[0])) jdbc.update("UPDATE hub_task_notification SET scheduled_at = ?, state = ?, overdue = ?, read_at = NULL, created_at = ? WHERE notification_id = ?",
				Timestamp.from(scheduledAt), overdue ? "UNREAD" : "PENDING", overdue, Timestamp.from(Instant.now()), values[1]);
			else if ("PENDING".equals(values[0]) && overdue) jdbc.update("UPDATE hub_task_notification SET scheduled_at = ?, state = 'UNREAD', overdue = TRUE WHERE notification_id = ?", Timestamp.from(scheduledAt), values[1]);
			else if ("PENDING".equals(values[0])) jdbc.update("UPDATE hub_task_notification SET scheduled_at = ? WHERE notification_id = ?", Timestamp.from(scheduledAt), values[1]);
			return;
		}
		insertReminder(task, scheduledAt, overdue);
	}

	private void insertReminder(Task task, Instant scheduledAt, boolean overdue) {
		String state = overdue ? "UNREAD" : "PENDING";
		try {
			jdbc.update("INSERT INTO hub_task_notification (notification_id, connection_id, task_list_id, task_id, due_date, scheduled_at, state, overdue, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
				UUID.randomUUID().toString(), CONNECTION_ID, task.listId(), task.id(), Date.valueOf(task.dueDate()), Timestamp.from(scheduledAt), state, overdue, Timestamp.from(Instant.now()));
		} catch (DuplicateKeyException ignored) { }
	}

	private void cancelDeletedTasks(String listId) {
		jdbc.update("UPDATE hub_task_notification SET state = 'CANCELLED' WHERE connection_id = ? AND task_list_id = ? AND task_id IN (SELECT task_id FROM google_task_state WHERE connection_id = ? AND task_list_id = ? AND deleted = TRUE) AND state IN ('PENDING', 'UNREAD')",
			CONNECTION_ID, listId, CONNECTION_ID, listId);
	}

	private boolean isSelected(String listId) {
		List<Boolean> values = jdbc.query("SELECT selected FROM google_task_list_state WHERE connection_id = ? AND task_list_id = ? AND deleted = FALSE", (rs, row) -> rs.getBoolean(1), CONNECTION_ID, listId);
		return values.isEmpty() || values.getFirst();
	}

	private boolean updatedAfter(String updated, Instant attemptStarted) {
		if (updated == null) return false;
		try { return Instant.parse(updated).isAfter(attemptStarted.minusSeconds(5)); }
		catch (RuntimeException exception) { return false; }
	}

	private boolean equal(Object first, Object second) { return first == null ? second == null : first.equals(second); }
	private record ReminderRow(String id, LocalDate dueDate) {}

	public static final class TaskConflictException extends RuntimeException {
		private final Task currentTask;
		public TaskConflictException(Task currentTask) { super("Task changed in Google Tasks."); this.currentTask = currentTask; }
		public Task currentTask() { return currentTask; }
	}
	public static final class GoogleSyncException extends RuntimeException {
		public GoogleSyncException(String message) { super(message); }
	}
}
