package com.github.cleytonorocha.hub;

import com.github.cleytonorocha.hub.service.UserPreferenceService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserPreferenceControllerTests extends AuthenticatedHubMvcTestSupport {
	private static final String DOCUMENT_A = "{\"weeklySummaryVisible\":false,\"videoDownloaderCardVisible\":true,\"sidebarPinned\":false,\"taskDefaults\":{\"category\":\"Pessoal\",\"color\":\"#123456\",\"priority\":\"HIGH\"},\"calendarPresentations\":{\"day\":\"\",\"week\":\"list\",\"month\":\"\",\"year\":\"\"}}";
	private static final String DOCUMENT_B = "{\"weeklySummaryVisible\":true,\"videoDownloaderCardVisible\":false,\"sidebarPinned\":true,\"taskDefaults\":{\"category\":\"Trabalho\",\"color\":\"#ABCDEF\",\"priority\":\"LOW\"},\"calendarPresentations\":{\"day\":\"timeline\",\"week\":\"\",\"month\":\"grid\",\"year\":\"\"}}";
	@Autowired JdbcTemplate jdbc;
	@Autowired UserPreferenceService preferences;

	@BeforeEach
	void clearPreferenceRows() { jdbc.update("DELETE FROM hub_user_preference WHERE owner_id=?", TEST_OWNER); }

	@Test
	void firstLegacyInitializationReturnsAndKeepsTheStoredWinner() throws Exception {
		mvc.perform(get("/api/user-preferences"))
			.andExpect(status().isOk()).andExpect(header().string("ETag", "\"prefs-0\""))
			.andExpect(jsonPath("$.initialized").value(false));

		mvc.perform(post("/api/user-preferences/initialize").contentType(MediaType.APPLICATION_JSON).content(DOCUMENT_A))
			.andExpect(status().isOk()).andExpect(header().string("ETag", "\"prefs-0\""))
			.andExpect(jsonPath("$.preferences.sidebarPinned").value(false));
		mvc.perform(post("/api/user-preferences/initialize").contentType(MediaType.APPLICATION_JSON).content(DOCUMENT_B))
			.andExpect(status().isOk()).andExpect(jsonPath("$.preferences.taskDefaults.category").value("Pessoal"));
		assertThat(jdbc.queryForObject("SELECT revision FROM hub_user_preference WHERE owner_id=?", Long.class, TEST_OWNER)).isZero();
	}

	@Test
	void preferencesAreIsolatedByOwner() {
		String otherOwner = addOtherOwner();
		preferences.initialize(TEST_OWNER, mapper(DOCUMENT_A));
		preferences.initialize(otherOwner, mapper(DOCUMENT_B));
		assertThat(preferences.get(TEST_OWNER).preferences().get("weeklySummaryVisible")).isEqualTo(false);
		assertThat(preferences.get(otherOwner).preferences().get("weeklySummaryVisible")).isEqualTo(true);
		assertThat(preferences.get(TEST_OWNER).preferences().get("taskDefaults")).isEqualTo(Map.of("category", "Pessoal", "color", "#123456", "priority", "HIGH"));
	}

	@Test
	void malformedPersistedDocumentIsReportedAndPreserved() {
		jdbc.update("INSERT INTO hub_user_preference(owner_id,preferences,revision) VALUES(?,?,0)", TEST_OWNER, "{malformed");
		assertThatThrownBy(() -> preferences.get(TEST_OWNER)).isInstanceOf(UserPreferenceService.PreferenceIntegrityException.class);
		assertThatThrownBy(() -> preferences.initialize(TEST_OWNER, mapper(DOCUMENT_A))).isInstanceOf(UserPreferenceService.PreferenceIntegrityException.class);
		assertThat(jdbc.queryForObject("SELECT preferences FROM hub_user_preference WHERE owner_id=?", String.class, TEST_OWNER)).isEqualTo("{malformed");
	}

	@Test
	void strongEtagUpdatesIncrementRevisionAndStaleWritesReturnLatestSnapshot() throws Exception {
		mvc.perform(post("/api/user-preferences/initialize").contentType(MediaType.APPLICATION_JSON).content(DOCUMENT_A))
			.andExpect(status().isOk()).andExpect(header().string("ETag", "\"prefs-0\""));
		mvc.perform(put("/api/user-preferences").header("If-Match", "\"prefs-0\"")
				.contentType(MediaType.APPLICATION_JSON).content(DOCUMENT_B))
			.andExpect(status().isOk()).andExpect(header().string("ETag", "\"prefs-1\""))
			.andExpect(jsonPath("$.revision").value(1));
		MvcResult stale = mvc.perform(put("/api/user-preferences").header("If-Match", "\"prefs-0\"")
				.contentType(MediaType.APPLICATION_JSON).content(DOCUMENT_A))
			.andExpect(status().isConflict()).andExpect(header().string("ETag", "\"prefs-1\""))
			.andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.preferences.taskDefaults.category").value("Trabalho"))
			.andReturn();
		assertThat(stale.getResponse().getContentAsString()).contains("sidebarPinned");
		assertThat(jdbc.queryForObject("SELECT revision FROM hub_user_preference WHERE owner_id=?", Long.class, TEST_OWNER)).isEqualTo(1L);
	}

	private String addOtherOwner() {
		String id = UUID.randomUUID().toString();
		String email = id + "@preferences.test";
		jdbc.update("INSERT INTO hub_user(id,display_name,email,normalized_email,password_hash,profile_id,enabled,created_at) VALUES(?,?,?,?,?,?,TRUE,CURRENT_TIMESTAMP)",
			id, "Other preference owner", email, email, "unused-test-hash", "a0000000-0000-4000-8000-000000000002");
		return id;
	}

	private Map<String, Object> mapper(String json) {
		try { return new tools.jackson.databind.ObjectMapper().readValue(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}); }
		catch (Exception error) { throw new IllegalStateException(error); }
	}
}
