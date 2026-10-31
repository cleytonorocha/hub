package com.github.cleytonorocha.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.repository.TaskRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class FocusSessionControllerTests extends AuthenticatedHubMvcTestSupport {
	private static final String API = "/api/focus-sessions";
	private static final String OTHER_OWNER = "b0000000-0000-4000-8000-000000000001";

	@Autowired JdbcTemplate jdbc;
	@Autowired TaskRepository tasks;

	@BeforeEach
	void clearFocusSessions() {
		jdbc.update("UPDATE hub_focus_active_slot SET session_id=NULL WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_focus_session WHERE owner_id=?", TEST_OWNER);
	}

	@Test
	void rejectsSecondStartAndStaleTransitions() throws Exception {
		String id = UUID.randomUUID().toString();
		mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(startJson(id, null)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("ACTIVE"))
			.andExpect(jsonPath("$.taskId").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.taskAvailable").value(false));
		mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(startJson(UUID.randomUUID().toString(), null)))
			.andExpect(status().isConflict());
		mvc.perform(post(API + "/{id}/pause", id).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2}"))
			.andExpect(status().isConflict());
		mvc.perform(post(API + "/{id}/pause", id).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
		mvc.perform(post(API + "/{id}/end", id).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}"))
			.andExpect(status().isConflict());
	}

	@Test
	void pauseResumeExcludePausedTimeAndEndRetryIsIdempotent() throws Exception {
		String id = UUID.randomUUID().toString();
		mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(startJson(id, null))).andExpect(status().isCreated());
		jdbc.update("UPDATE hub_focus_session SET resumed_at=DATEADD('SECOND',-120,CURRENT_TIMESTAMP) WHERE owner_id=? AND id=?", TEST_OWNER, id);
		mvc.perform(post(API + "/{id}/pause", id).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PAUSED"));
		int focusedAtPause = jdbc.queryForObject("SELECT focused_seconds FROM hub_focus_session WHERE owner_id=? AND id=?", Integer.class, TEST_OWNER, id);
		assertThat(focusedAtPause).isBetween(119, 130);
		mvc.perform(post(API + "/{id}/resume", id).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACTIVE"))
			.andExpect(jsonPath("$.focusedSeconds").value(focusedAtPause));
		jdbc.update("UPDATE hub_focus_session SET resumed_at=DATEADD('SECOND',-15,CURRENT_TIMESTAMP) WHERE owner_id=? AND id=?", TEST_OWNER, id);
		String end = "{\"revision\":2}";
		mvc.perform(post(API + "/{id}/end", id).contentType(MediaType.APPLICATION_JSON).content(end))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ENDED"))
			.andExpect(jsonPath("$.endReason").value("EARLY"));
		int endedSeconds = jdbc.queryForObject("SELECT focused_seconds FROM hub_focus_session WHERE owner_id=? AND id=?", Integer.class, TEST_OWNER, id);
		assertThat(endedSeconds).isBetween(focusedAtPause + 14, focusedAtPause + 25);
		mvc.perform(post(API + "/{id}/end", id).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.focusedSeconds").value(endedSeconds));
	}

	@Test
	void separatesFocusAndTaskPermissionsAndHidesTaskDetails() throws Exception {
		String taskId = UUID.randomUUID().toString();
		tasks.save(Task.restoreLocal(taskId, "Privileged task title", null, null, false, "Geral", "#A986FF", Task.Priority.NORMAL, ""));
		String sessionId = UUID.randomUUID().toString();
		mvcWithPermissions("FOCUS_WRITE").perform(post(API).contentType(MediaType.APPLICATION_JSON).content(startJson(sessionId, taskId)))
			.andExpect(status().isForbidden());
		mvcWithPermissions("FOCUS_READ").perform(get(API + "/active")).andExpect(status().isOk());
		mvcWithPermissions("FOCUS_WRITE").perform(get(API + "/active")).andExpect(status().isForbidden());
		mvcWithPermissions("FOCUS_READ").perform(post(API).contentType(MediaType.APPLICATION_JSON).content(startJson(UUID.randomUUID().toString(), null)))
			.andExpect(status().isForbidden());
		mvcWithPermissions("FOCUS_WRITE,TASK_READ").perform(post(API).contentType(MediaType.APPLICATION_JSON).content(startJson(sessionId, taskId)))
			.andExpect(status().isCreated());
		mvcWithPermissions("FOCUS_READ").perform(get(API + "/active"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(taskId))
			.andExpect(jsonPath("$.taskTitle").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.taskAvailable").value(false));
		mvcWithPermissions("FOCUS_READ,TASK_READ").perform(get(API + "/active"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.taskTitle").value("Privileged task title"))
			.andExpect(jsonPath("$.taskAvailable").value(true));
		mvcWithPermissions("FOCUS_WRITE,TASK_READ").perform(post(API + "/{id}/end", sessionId)
			.contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}")).andExpect(status().isOk());
		mvcWithPermissions("FOCUS_READ").perform(get(API))
			.andExpect(status().isOk()).andExpect(jsonPath("$.sessions[0].taskId").value(taskId))
			.andExpect(jsonPath("$.sessions[0].taskTitle").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.sessions[0].taskAvailable").value(false));
	}

	@Test
	void focusHistoryAndActiveSessionAreIsolatedByOwner() throws Exception {
		String id = UUID.randomUUID().toString();
		mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(startJson(id, null))).andExpect(status().isCreated());
		mvcAsOwnerWithPermissions(OTHER_OWNER, "FOCUS_READ").perform(get(API + "/active"))
			.andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
		mvcAsOwnerWithPermissions(OTHER_OWNER, "FOCUS_READ").perform(get(API))
			.andExpect(status().isOk()).andExpect(jsonPath("$.sessions").isEmpty());
	}

	private String startJson(String id, String taskId) {
		return "{\"id\":\"" + id + "\",\"objective\":\"Revisar proposta\",\"plannedSeconds\":3600,\"taskId\":"
			+ (taskId == null ? "null" : "\"" + taskId + "\"") + "}";
	}
}
