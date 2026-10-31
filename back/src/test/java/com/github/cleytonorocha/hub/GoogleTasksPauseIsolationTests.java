package com.github.cleytonorocha.hub;

import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.Task;
import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.TaskList;
import com.github.cleytonorocha.hub.integration.google.GoogleTasksApiClient;
import com.github.cleytonorocha.hub.security.HubPrincipal;
import com.github.cleytonorocha.hub.service.google.GoogleOAuthService;
import com.github.cleytonorocha.hub.service.google.GoogleTasksService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Disposable H2 coverage for owner-scoped Google Tasks pause/resume behavior. */
@SpringBootTest(properties = {
	"spring.sql.init.mode=always",
	"spring.sql.init.schema-locations=classpath:schema.sql",
	"spring.jpa.hibernate.ddl-auto=none"
})
class GoogleTasksPauseIsolationTests {
	private static final String TEST_DATABASE_URL = "jdbc:h2:mem:google-pause-proof-" + UUID.randomUUID()
		+ ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
	private static final String LIST_ID = "shared-list-id";
	private static final String TASK_ID = "shared-task-id";
	private static final TaskList REMOTE_LIST = new TaskList(LIST_ID, "Shared list", true);

	@DynamicPropertySource
	static void isolatedDatabase(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
	}

	@Autowired JdbcTemplate jdbc;
	@Autowired GoogleTasksService tasks;
	@MockitoBean GoogleOAuthService oauth;
	@MockitoBean GoogleTasksApiClient google;

	@BeforeEach
	void configureRemoteMocks() {
		when(google.listAllTaskLists(anyString())).thenReturn(List.of(REMOTE_LIST));
	}

	@Test
	void pausePreservesHistoryAndCachedRowsAndSchedulerDoesNotPromotePausedPendingNotices() {
		String ownerA = UUID.randomUUID().toString();
		String ownerB = UUID.randomUUID().toString();
		String connectionA = seedOwner(ownerA, "a-" + UUID.randomUUID().toString().substring(0, 36));
		String connectionB = seedOwner(ownerB, "b-" + UUID.randomUUID().toString().substring(0, 36));
		seedListAndCachedTask(ownerA, connectionA, false, "cached-a");
		seedListAndCachedTask(ownerB, connectionB, true, "cached-b");
		LocalDate due = LocalDate.now().plusDays(3);
		String pendingId = UUID.randomUUID().toString();
		String unreadId = UUID.randomUUID().toString();
		String readId = UUID.randomUUID().toString();
		seedNotice(ownerA, connectionA, pendingId, "pending-task", due, "PENDING", Instant.now().minusSeconds(120));
		seedNotice(ownerA, connectionA, unreadId, "unread-task", due, "UNREAD", Instant.now().minusSeconds(120));
		seedNotice(ownerA, connectionA, readId, "read-task", due, "READ", Instant.now().minusSeconds(120));
		String selectedPendingId = UUID.randomUUID().toString();
		seedNotice(ownerB, connectionB, selectedPendingId, "selected-pending", due, "PENDING", Instant.now().minusSeconds(120));
		when(oauth.connectionId(ownerA)).thenReturn(connectionA);
		when(oauth.connectionId(ownerB)).thenReturn(connectionB);
		when(oauth.accessToken(ownerA)).thenReturn("token-a");
		when(oauth.accessToken(ownerB)).thenReturn("token-b");

		asOwner(ownerA, () -> { tasks.setListSelected(LIST_ID, false); return null; });
		assertThat(listSelection(ownerA)).isFalse();
		assertThat(noticeState(ownerA, pendingId)).isEqualTo("CANCELLED");
		assertThat(noticeState(ownerA, unreadId)).isEqualTo("UNREAD");
		assertThat(noticeState(ownerA, readId)).isEqualTo("READ");
		assertThat(taskLastSync(ownerA)).isEqualTo("cached-a");
		assertThat(taskDeleted(ownerA)).isFalse();

		// Simulate a stale pending row left by an already-running worker; the scheduler
		// must still honor the paused-list guard instead of promoting it to unread.
		String pausedStaleId = UUID.randomUUID().toString();
		seedNotice(ownerA, connectionA, pausedStaleId, "stale-pending", due, "PENDING", Instant.now().minusSeconds(120));
		tasks.deliverDueNotifications();
		assertThat(noticeState(ownerA, pausedStaleId)).isEqualTo("PENDING");
		assertThat(noticeState(ownerB, selectedPendingId)).isEqualTo("UNREAD");

		Task cachedRemote = new Task(TASK_ID, LIST_ID, "Shared list", "A refreshed", due, false, "old", "etag-a", false);
		Task remoteForB = new Task(TASK_ID, LIST_ID, "Shared list", "B data", due, false, "old", "etag-b", false);
		when(google.listAllTasks("token-a", LIST_ID, "Shared list")).thenReturn(List.of(cachedRemote));
		when(google.listAllTasks("token-b", LIST_ID, "Shared list")).thenReturn(List.of(remoteForB));
		clearInvocations(google);

		var pausedResult = asOwner(ownerA, tasks::synchronize);
		assertThat(pausedResult.lists()).containsExactly(new TaskList(LIST_ID, "Shared list", false));
		assertThat(pausedResult.tasks()).isEmpty();
		verify(google, never()).listAllTasks("token-a", LIST_ID, "Shared list");
		assertThat(taskLastSync(ownerA)).isEqualTo("cached-a");
		assertThat(taskDeleted(ownerA)).isFalse();
		assertThat(taskLastSync(ownerB)).isEqualTo("cached-b");
		assertThat(taskDeleted(ownerB)).isFalse();

		var selectedResult = asOwner(ownerB, tasks::synchronize);
		assertThat(selectedResult.lists()).containsExactly(new TaskList(LIST_ID, "Shared list", true));
		assertThat(selectedResult.tasks()).extracting(Task::title).containsExactly("B data");
		verify(google).listAllTasks("token-b", LIST_ID, "Shared list");
		assertThat(taskLastSync(ownerB)).isNotEqualTo("cached-b");
		assertThat(taskLastSync(ownerA)).isEqualTo("cached-a");
		assertThat(taskDeleted(ownerA)).isFalse();
	}

	@Test
	void pausedListReadsNoRemoteTasksAndReselectRefreshesOwnerScopedTaskAndNotice() {
		String ownerA = UUID.randomUUID().toString();
		String ownerB = UUID.randomUUID().toString();
		String connectionA = seedOwner(ownerA, "a-" + UUID.randomUUID().toString().substring(0, 36));
		String connectionB = seedOwner(ownerB, "b-" + UUID.randomUUID().toString().substring(0, 36));
		seedListAndCachedTask(ownerA, connectionA, false, "cached-a");
		seedListAndCachedTask(ownerB, connectionB, true, "cached-b");
		LocalDate due = LocalDate.now().plusDays(4);
		String noticeA = UUID.randomUUID().toString();
		String noticeB = UUID.randomUUID().toString();
		seedNotice(ownerA, connectionA, noticeA, TASK_ID, due, "PENDING", Instant.now().plusSeconds(60));
		seedNotice(ownerB, connectionB, noticeB, TASK_ID, due, "READ", Instant.now().plusSeconds(60));
		when(oauth.connectionId(ownerA)).thenReturn(connectionA);
		when(oauth.connectionId(ownerB)).thenReturn(connectionB);
		when(oauth.accessToken(ownerA)).thenReturn("token-a");
		when(oauth.accessToken(ownerB)).thenReturn("token-b");
		Task remoteForA = new Task(TASK_ID, LIST_ID, "Shared list", "A refreshed", due, false, "new-a", "etag-a", false);
		Task remoteForB = new Task(TASK_ID, LIST_ID, "Shared list", "B preserved", due, false, "new-b", "etag-b", false);
		when(google.listAllTasks("token-a", LIST_ID, "Shared list")).thenReturn(List.of(remoteForA));
		when(google.listAllTasks("token-b", LIST_ID, "Shared list")).thenReturn(List.of(remoteForB));

		asOwner(ownerA, () -> { tasks.setListSelected(LIST_ID, false); return null; });
		assertThat(noticeState(ownerA, noticeA)).isEqualTo("CANCELLED");
		clearInvocations(google);
		assertThat(asOwner(ownerA, () -> tasks.listTasks(LIST_ID))).isEmpty();
		verify(google, never()).listAllTasks("token-a", LIST_ID, "Shared list");

		var pausedSync = asOwner(ownerA, tasks::synchronize);
		assertThat(pausedSync.tasks()).isEmpty();
		assertThat(noticeState(ownerA, noticeA)).isEqualTo("CANCELLED");
		assertThat(noticeState(ownerB, noticeB)).isEqualTo("READ");
		verify(google, never()).listAllTasks("token-a", LIST_ID, "Shared list");

		asOwner(ownerA, () -> { tasks.setListSelected(LIST_ID, true); return null; });
		var resumed = asOwner(ownerA, tasks::synchronize);
		assertThat(resumed.tasks()).extracting(Task::title).containsExactly("A refreshed");
		assertThat(noticeState(ownerA, noticeA)).isEqualTo("PENDING");
		assertThat(taskLastSync(ownerA)).isNotEqualTo("cached-a");
		assertThat(taskLastSync(ownerB)).isEqualTo("cached-b");
		assertThat(noticeState(ownerB, noticeB)).isEqualTo("READ");
		verify(google).listAllTasks("token-a", LIST_ID, "Shared list");
	}

	private String seedOwner(String owner, String connection) {
		String profile = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO hub_permission_profile(id,name,normalized_name,permissions,system_profile) VALUES(?,?,?,?,FALSE)",
			profile, "Google pause test", "google-pause-" + profile, "TASK_READ,TASK_WRITE");
		String email = owner + "@example.test";
		jdbc.update("INSERT INTO hub_user(id,display_name,email,normalized_email,password_hash,profile_id,enabled,created_at) VALUES(?,?,?,?,?,?,TRUE,CURRENT_TIMESTAMP)",
			owner, owner, email, email, "unused", profile);
		jdbc.update("INSERT INTO hub_task_user_settings(owner_id,reminder_time) VALUES(?,?)", owner, java.sql.Time.valueOf(LocalTime.of(9, 0)));
		jdbc.update("INSERT INTO google_connection(connection_id,owner_id,status,updated_at) VALUES(?,?,?,?)",
			connection, owner, "CONNECTED", Timestamp.from(Instant.now()));
		return connection;
	}

	private void seedListAndCachedTask(String owner, String connection, boolean selected, String lastSync) {
		jdbc.update("INSERT INTO google_task_list_state(owner_id,connection_id,task_list_id,selected,deleted,last_sync_run) VALUES(?,?,?,?,FALSE,?)",
			owner, connection, LIST_ID, selected, lastSync);
		jdbc.update("INSERT INTO google_task_state(owner_id,connection_id,task_list_id,task_id,remote_updated,remote_etag,due_date,completed,deleted,last_sync_run) VALUES(?,?,?,?,?,?,?,?,FALSE,?)",
			owner, connection, LIST_ID, TASK_ID, "cached", "cached-etag", Date.valueOf(LocalDate.now().plusDays(5)), false, lastSync);
	}

	private void seedNotice(String owner, String connection, String id, String taskId, LocalDate due, String state, Instant scheduled) {
		jdbc.update("INSERT INTO hub_task_notification(owner_id,notification_id,connection_id,task_list_id,task_id,due_date,scheduled_at,state,overdue,created_at) VALUES(?,?,?,?,?,?,?,?,FALSE,?)",
			owner, id, connection, LIST_ID, taskId, Date.valueOf(due), Timestamp.from(scheduled), state, Timestamp.from(Instant.now()));
	}

	private boolean listSelection(String owner) {
		return jdbc.queryForObject("SELECT selected FROM google_task_list_state WHERE owner_id=? AND task_list_id=?", Boolean.class, owner, LIST_ID);
	}

	private String taskLastSync(String owner) {
		return jdbc.queryForObject("SELECT last_sync_run FROM google_task_state WHERE owner_id=? AND task_list_id=? AND task_id=?",
			String.class, owner, LIST_ID, TASK_ID);
	}

	private boolean taskDeleted(String owner) {
		return jdbc.queryForObject("SELECT deleted FROM google_task_state WHERE owner_id=? AND task_list_id=? AND task_id=?",
			Boolean.class, owner, LIST_ID, TASK_ID);
	}

	private String noticeState(String owner, String notificationId) {
		return jdbc.queryForObject("SELECT state FROM hub_task_notification WHERE owner_id=? AND notification_id=?",
			String.class, owner, notificationId);
	}

	private <T> T asOwner(String owner, Supplier<T> action) {
		SecurityContext previous = SecurityContextHolder.getContext();
		var context = SecurityContextHolder.createEmptyContext();
		HubPrincipal principal = new HubPrincipal(owner, owner, owner + "@example.test", "unused", true,
			"test-profile", "Google pause test", "TASK_READ,TASK_WRITE,SETTINGS_READ,SETTINGS_WRITE");
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, "unused", principal.getAuthorities()));
		SecurityContextHolder.setContext(context);
		try { return action.get(); }
		finally { SecurityContextHolder.setContext(previous); }
	}
}
