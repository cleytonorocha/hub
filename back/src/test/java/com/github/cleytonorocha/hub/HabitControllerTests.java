package com.github.cleytonorocha.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Acceptance coverage for civil-date habit scheduling and owner permissions. */
@SpringBootTest
@AutoConfigureMockMvc
class HabitControllerTests extends AuthenticatedHubMvcTestSupport {
	private static final String API = "/api/habits";
	private static final ZoneId ZONE = ZoneId.of("UTC");
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MockMvc delegate;
	@Autowired private ObjectMapper mapper;

	@BeforeEach
	void clearHabits() {
		jdbc.update("DELETE FROM hub_habit_checkin WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_habit_pause WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_habit_schedule WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_habit WHERE owner_id=?", TEST_OWNER);
		jdbc.update("UPDATE hub_habit_user_revision SET revision=0 WHERE owner_id=?", TEST_OWNER);
	}

	@Test
	void weeklyScheduleIsCivilDateBasedAndCheckInsAreIdempotentAndCorrectable() throws Exception {
		LocalDate today = LocalDate.now(ZONE);
		LocalDate monday = mondayOf(today);
		JsonNode created = createHabit("Alongamento", today.minusDays(14), List.of(1, 3, 5));
		String id = created.get("id").asText();

		JsonNode currentWeek = week(monday);
		JsonNode habit = currentWeek.get("habits").get(0);
		assertThat(habit.get("eligible").asInt()).isEqualTo(3);

		LocalDate eligiblePastDay = monday.minusWeeks(1);
		mark(id, eligiblePastDay).andExpect(status().isOk());
		mark(id, eligiblePastDay).andExpect(status().isOk());
		JsonNode previousWeek = week(monday.minusWeeks(1));
		assertThat(previousWeek.get("habits").get(0).get("completed").asInt()).isEqualTo(1);
		unmark(id, eligiblePastDay).andExpect(status().isOk());
		assertThat(week(monday.minusWeeks(1)).get("habits").get(0).get("completed").asInt()).isZero();

		mark(id, monday.minusWeeks(1).plusDays(1)).andExpect(status().isConflict());
		mark(id, today.plusDays(1)).andExpect(status().isBadRequest());
	}

	@Test
	void frequencyEditsAndPausesPreserveTodaysExistingCheckIn() throws Exception {
		LocalDate today = LocalDate.now(ZONE);
		LocalDate monday = mondayOf(today);
		JsonNode created = createHabit("Leitura diária", today, List.of(today.getDayOfWeek().getValue()));
		String id = created.get("id").asText();
		mark(id, today).andExpect(status().isOk());
		mark(id, today).andExpect(status().isOk());

		MvcResult updated = delegate.perform(put(API + "/" + id + "?timeZoneId=UTC").with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsBytes(java.util.Map.of("name", "Leitura diária", "startDate", today,
				"weekdays", List.of(1, 2, 3, 4, 5, 6, 7), "revision", created.get("revision").asLong()))))
			.andExpect(status().isOk()).andReturn();
		assertThat(mapper.readTree(updated.getResponse().getContentAsString()).get("status").asText()).isEqualTo("UPDATED");
		JsonNode afterEdit = week(monday).get("habits").get(0);
		assertThat(afterEdit.get("days").findValues("status").stream().map(JsonNode::asText).toList()).contains("DONE");
		assertThat(afterEdit.get("weekdaysMask").asInt()).isEqualTo(127);

		delegate.perform(post(API + "/" + id + "/pause?timeZoneId=UTC").with(csrf()))
			.andExpect(status().isOk());
		JsonNode paused = week(monday);
		assertThat(paused.get("completed").asInt()).isEqualTo(1);
		assertThat(paused.get("eligible").asInt()).isEqualTo(1);
		assertThat(paused.get("habits").get(0).get("days").findValues("status").stream().map(JsonNode::asText).toList()).contains("DONE", "PAUSED");

		delegate.perform(delete(API + "/" + id + "/check-ins/" + today + "?timeZoneId=UTC").with(csrf()))
			.andExpect(status().isOk());
		assertThat(week(monday).get("eligible").asInt()).isZero();
		delegate.perform(post(API + "/" + id + "/resume?timeZoneId=UTC").with(csrf()))
			.andExpect(status().isOk());
		mark(id, today).andExpect(status().isOk());
		assertThat(week(monday).get("completed").asInt()).isEqualTo(1);
	}

	@Test
	void frequencyEditWithoutTodaysCheckInAppliesImmediately() throws Exception {
		LocalDate today = LocalDate.now(ZONE);
		LocalDate monday = mondayOf(today);
		int nextWeekday = today.getDayOfWeek().getValue() % 7 + 1;
		JsonNode created = createHabit("Estudo", today, List.of(today.getDayOfWeek().getValue()));
		String id = created.get("id").asText();

		delegate.perform(put(API + "/" + id + "?timeZoneId=UTC").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsBytes(java.util.Map.of("name", "Estudo", "startDate", today,
				"weekdays", List.of(nextWeekday), "revision", created.get("revision").asLong()))))
			.andExpect(status().isOk());
		JsonNode updated = week(monday).get("habits").get(0);
		assertThat(updated.get("weekdaysMask").asInt()).isEqualTo(1 << (nextWeekday - 1));
		JsonNode todayView = null;
		for (JsonNode day : updated.get("days")) if (day.get("date").asText().equals(today.toString())) todayView = day;
		assertThat(todayView).isNotNull();
		assertThat(todayView.get("status").asText()).isEqualTo("NOT_SCHEDULED");
	}

	@Test
	void futureStartHasNoCurrentWeekOpportunitiesAndCannotBeMovedIntoThePast() throws Exception {
		LocalDate today = LocalDate.now(ZONE);
		LocalDate monday = mondayOf(today);
		JsonNode created = createHabit("Curso", today.plusDays(7), List.of(1, 2, 3, 4, 5, 6, 7));
		String id = created.get("id").asText();
		assertThat(week(monday).get("eligible").asInt()).isZero();

		delegate.perform(put(API + "/" + id + "?timeZoneId=UTC").with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsBytes(java.util.Map.of("name", "Curso", "startDate", today.minusDays(1),
				"weekdays", List.of(1, 2, 3, 4, 5, 6, 7), "revision", created.get("revision").asLong()))))
			.andExpect(status().isConflict());
		assertThat(week(monday).get("habits").get(0).get("startDate").asText()).isEqualTo(today.plusDays(7).toString());
	}

	@Test
	void readPermissionCanListButCannotMutateHabits() throws Exception {
		HubAuthenticatedMvc reader = mvcWithPermissions("HABITS_READ");
		reader.perform(get(API + "?weekStart=" + mondayOf(LocalDate.now(ZONE)) + "&timeZoneId=UTC"))
			.andExpect(status().isOk());
		reader.perform(post(API).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsBytes(java.util.Map.of("name", "Sem escrita", "startDate", LocalDate.now(ZONE), "weekdays", List.of(1)))))
			.andExpect(status().isForbidden());
	}

	private JsonNode createHabit(String name, LocalDate startDate, List<Integer> weekdays) throws Exception {
		MvcResult response = mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsBytes(java.util.Map.of("name", name, "startDate", startDate, "weekdays", weekdays))))
			.andExpect(status().isCreated()).andReturn();
		return mapper.readTree(response.getResponse().getContentAsString());
	}

	private JsonNode week(LocalDate monday) throws Exception {
		MvcResult response = mvc.perform(get(API + "?weekStart=" + monday + "&timeZoneId=UTC"))
			.andExpect(status().isOk()).andReturn();
		return mapper.readTree(response.getResponse().getContentAsString());
	}

	private org.springframework.test.web.servlet.ResultActions mark(String id, LocalDate date) throws Exception {
		return mvc.perform(put(API + "/" + id + "/check-ins/" + date + "?timeZoneId=UTC").with(csrf()));
	}

	private org.springframework.test.web.servlet.ResultActions unmark(String id, LocalDate date) throws Exception {
		return mvc.perform(delete(API + "/" + id + "/check-ins/" + date + "?timeZoneId=UTC").with(csrf()));
	}

	private static LocalDate mondayOf(LocalDate date) {
		return date.minusDays(date.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
	}
}
