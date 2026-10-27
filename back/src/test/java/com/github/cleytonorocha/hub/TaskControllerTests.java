package com.github.cleytonorocha.hub;

import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.repository.TaskRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerTests {
	@Autowired MockMvc mvc;
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
	void rejectsInvalidTaskColor() throws Exception {
		mvc.perform(post("/api/tasks")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Cor inválida\",\"color\":\"red; background:url(x)\"}"))
			.andExpect(status().isBadRequest());
	}
}
