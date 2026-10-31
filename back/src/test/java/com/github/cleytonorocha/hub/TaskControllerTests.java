package com.github.cleytonorocha.hub;

import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.repository.TaskRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerTests extends AuthenticatedHubMvcTestSupport {
	@Autowired TaskRepository tasks;

	@BeforeEach
	void clearTasks() { tasks.deleteAll(); }

	@Test
	void createsAndListsOfflineTask() throws Exception {
		MvcResult created = mvc.perform(post("/api/tasks")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Planejar a semana\",\"dueDate\":\"2026-10-27\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.type").value("LOCAL"))
			.andExpect(jsonPath("$.completed").value(false))
			.andExpect(jsonPath("$.category").value("Geral"))
			.andExpect(jsonPath("$.color").value("#A986FF"))
			.andExpect(jsonPath("$.priority").value("NORMAL"))
			.andReturn();

		String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
		mvc.perform(get("/api/tasks"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(id))
			.andExpect(jsonPath("$[0].title").value("Planejar a semana"));
	}

	@Test
	void acceptsGoogleAsTaskType() throws Exception {
		mvc.perform(post("/api/tasks")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Task importada\",\"type\":\"GOOGLE\",\"category\":\"Trabalho\",\"color\":\"#e05278\",\"priority\":\"HIGH\",\"notes\":\"Preparar material\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.type").value("GOOGLE"))
			.andExpect(jsonPath("$.category").value("Trabalho"))
			.andExpect(jsonPath("$.color").value("#e05278"))
			.andExpect(jsonPath("$.priority").value("HIGH"))
			.andExpect(jsonPath("$.notes").value("Preparar material"));
	}

	@Test
	void updatesDateAndCompletion() throws Exception {
		Task task = tasks.save(new Task("Revisar projeto", null, Task.Type.LOCAL));
		mvc.perform(patch("/api/tasks/{id}", task.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"dueDate\":\"2026-10-28\",\"completed\":true,\"category\":\"Estudo\",\"color\":\"#17b890\",\"priority\":\"LOW\",\"notes\":\"Ler capítulo 3\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dueDate").value("2026-10-28"))
			.andExpect(jsonPath("$.completed").value(true))
			.andExpect(jsonPath("$.category").value("Estudo"))
			.andExpect(jsonPath("$.color").value("#17b890"))
			.andExpect(jsonPath("$.priority").value("LOW"))
			.andExpect(jsonPath("$.notes").value("Ler capítulo 3"));
	}

	@Test
	void omittedChecklistConfirmationDefaultsToFalse() throws Exception {
		Task task = tasks.save(new Task("Concluir tarefa", null, Task.Type.LOCAL));
		mvc.perform(patch("/api/tasks/{id}", task.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"completed\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.completed").value(true));
	}

	@Test
	void rejectsInvalidTaskColor() throws Exception {
		mvc.perform(post("/api/tasks")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Cor inválida\",\"color\":\"red; background:url(x)\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void globalSearchReturnsTheLocalTaskCollection() throws Exception {
		tasks.save(new Task("Planejar semana", LocalDate.of(2026, 11, 2), Task.Type.LOCAL));
		mvc.perform(get("/api/tasks/search").param("q", "planejar").param("status", "all").param("period", "all").param("page", "0").param("size", "50"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].title").value("Planejar semana"));
	}

	@Test
	void createsAWeeklySeriesAcrossYearBoundaryAndRetriesWithoutDuplicates() throws Exception {
		String operationId = UUID.randomUUID().toString();
		String body = "{\"operationId\":\"" + operationId + "\",\"title\":\"Revisar agenda\",\"startDate\":\"2026-12-27\",\"occurrenceCount\":4,\"timeZoneId\":\"America/Sao_Paulo\"}";
		mvc.perform(post("/api/task-series").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.occurrences.length()", org.hamcrest.Matchers.is(4)))
			.andExpect(jsonPath("$.occurrences[0].dueDate").value("2026-12-27"))
			.andExpect(jsonPath("$.occurrences[1].dueDate").value("2027-01-03"))
			.andExpect(jsonPath("$.occurrences[3].dueDate").value("2027-01-17"));
		mvc.perform(post("/api/task-series").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.occurrences.length()", org.hamcrest.Matchers.is(4)));
		org.assertj.core.api.Assertions.assertThat(tasks.count()).isEqualTo(4);
	}

	@Test
	void endingWeeklySeriesRemovesOnlyFuturePendingOccurrences() throws Exception {
		LocalDate today = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
		String body = "{\"operationId\":\"" + UUID.randomUUID() + "\",\"title\":\"Rotina\",\"startDate\":\"" + today.minusDays(7)
			+ "\",\"occurrenceCount\":4,\"timeZoneId\":\"America/Sao_Paulo\"}";
		MvcResult created = mvc.perform(post("/api/task-series").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated()).andReturn();
		String seriesId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.series.id");
		String completedId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.occurrences[2].id");
		mvc.perform(patch("/api/tasks/{id}", completedId).contentType(MediaType.APPLICATION_JSON)
			.content("{\"dueDate\":\"" + today.plusDays(7) + "\",\"completed\":true}"))
			.andExpect(status().isOk());
		MvcResult preview = mvc.perform(get("/api/task-series/{id}/close-preview", seriesId).param("timeZoneId", "America/Sao_Paulo"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.pendingFutureCount").value(1)).andReturn();
		Number revisionValue = com.jayway.jsonpath.JsonPath.read(preview.getResponse().getContentAsString(), "$.revision");
		long revision = revisionValue.longValue();
		String close = "{\"today\":\"" + today + "\",\"timeZoneId\":\"America/Sao_Paulo\",\"expectedRevision\":" + revision + "}";
		mvc.perform(post("/api/task-series/{id}/close", seriesId).contentType(MediaType.APPLICATION_JSON).content(close))
			.andExpect(status().isOk()).andExpect(jsonPath("$.removedOccurrences").value(1)).andExpect(jsonPath("$.preservedOccurrences").value(3));
		org.assertj.core.api.Assertions.assertThat(tasks.countBySeriesId(seriesId)).isEqualTo(3);
	}

	@Test
	void checklistUsesRevisionAndRequiresExplicitCompletionWithPendingItems() throws Exception {
		Task task = tasks.save(new Task("Preparar envio", null, Task.Type.LOCAL));
		String id1 = UUID.randomUUID().toString();
		String id2 = UUID.randomUUID().toString();
		String checklist = "{\"expectedRevision\":0,\"items\":[{\"id\":\"" + id1 + "\",\"text\":\"Separar\",\"checked\":false,\"position\":0},{\"id\":\"" + id2 + "\",\"text\":\"Separar\",\"checked\":true,\"position\":1}]}";
		mvc.perform(put("/api/tasks/{id}/checklist", task.getId()).contentType(MediaType.APPLICATION_JSON).content(checklist))
			.andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.items.length()", org.hamcrest.Matchers.is(2)));
		mvc.perform(patch("/api/tasks/{id}", task.getId()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"completed\":true}"))
			.andExpect(status().isConflict());
		mvc.perform(patch("/api/tasks/{id}", task.getId()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"completed\":true,\"confirmPendingChecklist\":true}"))
			.andExpect(status().isOk());
		mvc.perform(get("/api/tasks/{id}/checklist", task.getId()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].checked").value(false));
		mvc.perform(patch("/api/tasks/{id}", task.getId()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"completed\":false}"))
			.andExpect(status().isOk());
		mvc.perform(put("/api/tasks/{id}/checklist", task.getId()).contentType(MediaType.APPLICATION_JSON).content(checklist))
			.andExpect(status().isConflict());
	}
}
