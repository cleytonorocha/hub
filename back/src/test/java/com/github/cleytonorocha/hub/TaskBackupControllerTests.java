package com.github.cleytonorocha.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupFile;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupTask;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupReadingItem;
import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.repository.TaskRepository;
import com.github.cleytonorocha.hub.repository.ReadingItemRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** End-to-end coverage for the versioned local-task portable-copy API. */
@SpringBootTest
@AutoConfigureMockMvc
class TaskBackupControllerTests {
	private static final String BASE = "/api/tasks/portable-copy";
	private static final String FUTURE_VERSION = "{\"formatVersion\":99,\"generatedAt\":\"2026-10-28T00:00:00Z\",\"tasks\":[]}";

	@Autowired MockMvc mvc;
	@Autowired TaskRepository tasks;
	@Autowired ReadingItemRepository readingItems;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper mapper;

	@BeforeEach
	void clearTasksAndResetRevision() {
		jdbc.execute("ALTER TABLE hub_task DROP CONSTRAINT IF EXISTS chk_backup_failure");
		tasks.deleteAll();
		readingItems.deleteAll();
		jdbc.update("UPDATE hub_task_collection_state SET revision = 0 WHERE state_id = 1");
		jdbc.update("UPDATE hub_reading_collection_state SET revision = 0 WHERE state_id = 1");
	}

	@Test
	void roundTripsLocalTaskFieldsAndUndatedTaskButExcludesGoogleTasks() throws Exception {
		String id = UUID.randomUUID().toString();
		BackupTask local = task(id, "Revisar acervo", null, null, true, "Pessoal", "#123456", "HIGH", "Notas preservadas");
		tasks.save(Task.restoreLocal(local.id(), local.title(), local.dueDate(), local.dueTime(), local.completed(),
			local.category(), local.color(), local.priority(), local.notes()));
		tasks.save(new Task("Task Google", LocalDate.of(2026, 11, 1), Task.Type.GOOGLE));

		MvcResult exported = mvc.perform(get(BASE + "/export"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.formatVersion").value(4))
			.andExpect(jsonPath("$.tasks.length()").value(1))
			.andExpect(jsonPath("$.readingItems.length()").value(0))
			.andExpect(jsonPath("$.tasks[0].id").value(id))
			.andExpect(jsonPath("$.tasks[0].dueDate").doesNotExist())
			.andExpect(jsonPath("$.tasks[0].category").value("Pessoal"))
			.andExpect(jsonPath("$.tasks[0].color").value("#123456"))
			.andExpect(jsonPath("$.tasks[0].priority").value("HIGH"))
			.andExpect(jsonPath("$.tasks[0].notes").value("Notas preservadas"))
			.andReturn();

		tasks.deleteAll();
		String content = exported.getResponse().getContentAsString();
		MvcResult preview = preview(content);
		assertThat(tasks.count()).isZero();
		String token = json(preview).get("token").asText();
		mvc.perform(importRequest(content, token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("IMPORTED"))
			.andExpect(jsonPath("$.addedTasks").value(1));
		Task restored = tasks.findById(id).orElseThrow();
		assertThat(restored.getDueDate()).isNull();
		assertThat(restored.getDueTime()).isNull();
		assertThat(restored.isCompleted()).isTrue();
		assertThat(restored.getCategory()).isEqualTo("Pessoal");
		assertThat(restored.getNotes()).isEqualTo("Notas preservadas");
	}

	@Test
	void importsVersionOneFilesWithoutSeriesOrChecklistFields() throws Exception {
		String id = UUID.randomUUID().toString();
		String legacy = "{\"formatVersion\":1,\"generatedAt\":\"2026-10-28T00:00:00Z\",\"tasks\":[{\"id\":\"" + id
			+ "\",\"title\":\"Arquivo antigo\",\"dueDate\":null,\"dueTime\":null,\"completed\":false,\"type\":\"LOCAL\",\"category\":\"Geral\",\"color\":\"#A986FF\",\"priority\":\"NORMAL\",\"notes\":\"\"}]}";
		String token = json(preview(legacy)).get("token").asText();
		mvc.perform(importRequest(legacy, token)).andExpect(status().isOk()).andExpect(jsonPath("$.addedTasks").value(1));
		assertThat(tasks.findById(id)).isPresent();
	}

	@Test
	void previewDoesNotWriteAndConfirmationRetryIsIdempotent() throws Exception {
		String content = file(task(UUID.randomUUID().toString(), "Nova", LocalDate.of(2026, 10, 30),
			LocalTime.of(9, 15), false, "Trabalho", "#abcdef", "NORMAL", ""));
		MvcResult preview = preview(content);
		assertThat(tasks.count()).isZero();
		String token = json(preview).get("token").asText();
		mvc.perform(importRequest(content, token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.addedTasks").value(1));
		mvc.perform(importRequest(content, token)).andExpect(status().isConflict());
		assertThat(tasks.count()).isEqualTo(1);
	}

	@Test
	void portableCopyRoundTripsArchivedReadingItemsAndPreservesTags() throws Exception {
		String id = UUID.randomUUID().toString();
		String now = "2026-10-28T10:00:00Z";
		BackupReadingItem item = new BackupReadingItem(id, "Livro físico", "BOOK", "Autora", null, "IN_PROGRESS",
			"Capítulo 4", "Uma nota", List.of("Trabalho", "Pessoal"), true, Instant.parse(now), Instant.parse(now));
		String content = mapper.writeValueAsString(new BackupFile(4, Instant.parse(now), List.of(), List.of(), List.of(item)));
		MvcResult preview = preview(content);
		assertThat(json(preview).get("newReadingItems").asInt()).isEqualTo(1);
		assertThat(readingItems.count()).isZero();
		mvc.perform(importRequest(content, json(preview).get("token").asText()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.addedReadingItems").value(1));
		MvcResult restored = mvc.perform(get("/api/reading-items/{id}", id)).andExpect(status().isOk())
			.andExpect(jsonPath("$.archived").value(true)).andExpect(jsonPath("$.progressMarker").value("Capítulo 4"))
			.andExpect(jsonPath("$.tags[0]").value("Trabalho")).andExpect(jsonPath("$.tags[1]").value("Pessoal"))
			.andReturn();
		assertThat(json(restored).get("tags").size()).isEqualTo(2);
	}

	@Test
	void readingChangesBetweenPreviewAndConfirmationRequireFreshReview() throws Exception {
		String id = UUID.randomUUID().toString();
		Instant now = Instant.parse("2026-10-28T10:00:00Z");
		BackupReadingItem incoming = new BackupReadingItem(id, "Conteúdo a importar", "ARTICLE", null, null, "TO_READ",
			"", "", List.of(), false, now, now);
		String content = mapper.writeValueAsString(new BackupFile(4, now, List.of(), List.of(), List.of(incoming)));
		String token = json(preview(content)).get("token").asText();
		mvc.perform(post("/api/reading-items").contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Mudança paralela\",\"type\":\"BOOK\"}"))
			.andExpect(status().isCreated());
		mvc.perform(importRequest(content, token)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
		assertThat(readingItems.findById(id)).isEmpty();
	}

	@Test
	void identicalIdsAreIgnoredAndExistingDifferencesBlockTheWholeImport() throws Exception {
		BackupTask same = task(UUID.randomUUID().toString(), "Já existe", null, null, false, "Geral", "#A986FF", "NORMAL", "");
		BackupTask conflict = task(UUID.randomUUID().toString(), "Atual", null, null, false, "Geral", "#A986FF", "NORMAL", "");
		tasks.save(Task.restoreLocal(same.id(), same.title(), same.dueDate(), same.dueTime(), same.completed(), same.category(), same.color(), same.priority(), same.notes()));
		tasks.save(Task.restoreLocal(conflict.id(), conflict.title(), conflict.dueDate(), conflict.dueTime(), conflict.completed(), conflict.category(), conflict.color(), conflict.priority(), conflict.notes()));
		long before = revision();
		BackupTask newTask = task(UUID.randomUUID().toString(), "Não deve entrar", null, null, false, "Geral", "#A986FF", "NORMAL", "");
		String content = file(same, task(conflict.id(), "Conflitante", null, null, false, "Geral", "#A986FF", "NORMAL", ""), newTask);
		MvcResult preview = preview(content);
		assertThat(json(preview).get("identicalTasks").asInt()).isEqualTo(1);
		assertThat(json(preview).get("conflictingTasks").asInt()).isEqualTo(1);
		mvc.perform(importRequest(content, json(preview).get("token").asText())).andExpect(status().isConflict());
		assertThat(tasks.findById(newTask.id())).isEmpty();
		assertThat(tasks.count()).isEqualTo(2);
		assertThat(revision()).isEqualTo(before);
	}

	@Test
	void rejectsInvalidAndFutureVersionFilesWithoutPartialWrites() throws Exception {
		String invalid = file(task(UUID.randomUUID().toString(), "Novo", null, null, false, "Geral", "#A986FF", "NORMAL", ""))
			.replace("\"type\":\"LOCAL\"", "\"type\":\"GOOGLE\"");
		mvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
		mvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(FUTURE_VERSION)).andExpect(status().isBadRequest());
		assertThat(tasks.count()).isZero();
		assertThat(revision()).isZero();
	}

	@Test
	void rejectsDuplicateMissingAndExtraPropertiesButAcceptsAnExactValidFile() throws Exception {
		BackupTask item = task(UUID.randomUUID().toString(), "Válida", null, null, false, "Geral", "#A986FF", "NORMAL", "");
		String valid = file(item);
		String duplicateEnvelope = valid.replaceFirst("\\\"formatVersion\\\":3", "\\\"formatVersion\\\":3,\\\"formatVersion\\\":3");
		String duplicateTask = valid.replaceFirst("\\\"title\\\":\\\"Válida\\\"", "\\\"title\\\":\\\"Válida\\\",\\\"title\\\":\\\"Válida\\\"");
		String missingEnvelopeField = valid.replaceFirst(",\\\"generatedAt\\\":\\\"[^\\\"]+\\\"", "");
		String extraEnvelopeField = valid.replaceFirst("\\\"tasks\\\":", "\\\"unexpected\\\":true,\\\"tasks\\\":");
		String missingTaskField = valid.replaceFirst(",\\\"notes\\\":\\\"\\\"", "");
		String extraTaskField = valid.replaceFirst("\\\"notes\\\":\\\"\\\"", "\\\"notes\\\":\\\"\\\",\\\"unexpected\\\":true");

		for (String invalid : List.of(duplicateEnvelope, duplicateTask, missingEnvelopeField, extraEnvelopeField, missingTaskField, extraTaskField)) {
			mvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest());
		}
		mvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(valid))
			.andExpect(status().isOk()).andExpect(jsonPath("$.newTasks").value(1));
		assertThat(tasks.count()).isZero();
		assertThat(revision()).isZero();
	}

	@Test
	void rejectsChangedDigestAndInvalidOrMissingPreviewTokens() throws Exception {
		String content = file(task(UUID.randomUUID().toString(), "Item", null, null, false, "Geral", "#A986FF", "NORMAL", ""));
		String token = json(preview(content)).get("token").asText();
		mvc.perform(importRequest(content + " ", token)).andExpect(status().isBadRequest());
		mvc.perform(importRequest(content, "invalid-token")).andExpect(status().isConflict());
		mvc.perform(post(BASE + "/import").contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isBadRequest());
		assertThat(tasks.count()).isZero();
	}

	@Test
	void changedAcervoAfterPreviewRequiresFreshReviewForCreateUpdateAndDelete() throws Exception {
		String content = file(task(UUID.randomUUID().toString(), "Arquivo", null, null, false, "Geral", "#A986FF", "NORMAL", ""));
		String token = json(preview(content)).get("token").asText();
		mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Concurrente\"}"))
			.andExpect(status().isCreated());
		mvc.perform(importRequest(content, token)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
		assertThat(tasks.count()).isEqualTo(1);

		Task existing = tasks.findAll().get(0);
		String updateToken = json(preview(content)).get("token").asText();
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/tasks/{id}", existing.getId())
			.contentType(MediaType.APPLICATION_JSON).content("{\"dueDate\":null,\"completed\":true}"))
			.andExpect(status().isOk());
		mvc.perform(importRequest(content, updateToken)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));

		String deleteToken = json(preview(content)).get("token").asText();
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/tasks/{id}", existing.getId()))
			.andExpect(status().isNoContent());
		mvc.perform(importRequest(content, deleteToken)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
		assertThat(tasks.count()).isZero();
	}

	@Test
	void failedTransactionalInsertRollsBackTasksAndRevisionAndLeavesPreviewRetryable() throws Exception {
		String content = file(task(UUID.randomUUID().toString(), "force-rollback", null, null, false, "Geral", "#A986FF", "NORMAL", ""));
		String token = json(preview(content)).get("token").asText();
		long before = revision();
		jdbc.execute("ALTER TABLE hub_task ADD CONSTRAINT chk_backup_failure CHECK (title <> 'force-rollback')");
		try {
			assertThatThrownBy(() -> mvc.perform(importRequest(content, token)))
				.hasCauseInstanceOf(DataIntegrityViolationException.class);
			assertThat(tasks.count()).isZero();
			assertThat(revision()).isEqualTo(before);
		} finally {
			jdbc.execute("ALTER TABLE hub_task DROP CONSTRAINT IF EXISTS chk_backup_failure");
		}
		mvc.perform(importRequest(content, token)).andExpect(status().isOk()).andExpect(jsonPath("$.addedTasks").value(1));
	}

	@Test
	void twoConcurrentConfirmationsNeverDuplicateImportedTasks() throws Exception {
		String content = file(task(UUID.randomUUID().toString(), "Concorrente", null, null, false, "Geral", "#A986FF", "NORMAL", ""));
		String token = json(preview(content)).get("token").asText();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<MvcResult>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) results.add(pool.submit(() -> {
				ready.countDown();
				start.await();
				return mvc.perform(importRequest(content, token)).andReturn();
			}));
			ready.await();
			start.countDown();
			int imported = 0;
			for (Future<MvcResult> result : results) {
				MvcResult response = result.get();
				if (response.getResponse().getStatus() == 200) {
					String responseStatus = json(response).get("status").asText();
					assertThat(responseStatus).isIn("IMPORTED", "REVIEW_REQUIRED");
					if (responseStatus.equals("IMPORTED")) imported++;
				} else {
					assertThat(response.getResponse().getStatus()).isEqualTo(409);
				}
			}
			assertThat(imported).isEqualTo(1);
		}
		assertThat(tasks.count()).isEqualTo(1);
	}

	@Test
	void rejectsImportAndExportLimitsInsteadOfTruncating() throws Exception {
		StringBuilder excessive = new StringBuilder("{\"formatVersion\":1,\"generatedAt\":\"2026-10-28T00:00:00Z\",\"tasks\":[");
		for (int i = 0; i < 5_001; i++) {
			if (i > 0) excessive.append(',');
			excessive.append(taskJson(task(UUID.randomUUID().toString(), "T", null, null, false, "Geral", "#A986FF", "NORMAL", "")));
		}
		excessive.append("]}");
		mvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(excessive.toString()))
			.andExpect(status().isPayloadTooLarge());
		String tooBig = " ".repeat(5 * 1024 * 1024 + 1);
		mvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(tooBig))
			.andExpect(status().isPayloadTooLarge());
		assertThat(tasks.count()).isZero();

		List<Task> many = new ArrayList<>();
		for (int i = 0; i < 5_001; i++) many.add(new Task("Task " + i, null, Task.Type.LOCAL));
		tasks.saveAll(many);
		mvc.perform(get(BASE + "/export")).andExpect(status().isPayloadTooLarge());

		tasks.deleteAll();
		many.clear();
		String largeNotes = "n".repeat(1_500);
		for (int i = 0; i < 4_000; i++) {
			many.add(new Task("Task " + i, null, Task.Type.LOCAL, "Geral", "#A986FF", Task.Priority.NORMAL, largeNotes));
		}
		tasks.saveAll(many);
		mvc.perform(get(BASE + "/export")).andExpect(status().isPayloadTooLarge());
	}

	private MvcResult preview(String content) throws Exception {
		return mvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(content))
			.andExpect(status().isOk()).andReturn();
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder importRequest(String content, String token) {
		return post(BASE + "/import").header("X-Task-Import-Preview", token)
			.contentType(MediaType.APPLICATION_JSON).content(content);
	}

	private JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()); }

	private String file(BackupTask... values) throws Exception {
		return mapper.writeValueAsString(Map.of("formatVersion", 3, "generatedAt", Instant.parse("2026-10-28T00:00:00Z"),
			"tasks", List.of(values), "series", List.of()));
	}

	private String taskJson(BackupTask task) throws Exception { return mapper.writeValueAsString(task); }

	private BackupTask task(String id, String title, LocalDate date, LocalTime time, boolean completed,
		String category, String color, String priority, String notes) {
		return new BackupTask(id, title, date, time, completed, "LOCAL", category, color, Task.Priority.valueOf(priority), notes, null, null, List.of());
	}

	private long revision() { return jdbc.queryForObject("SELECT revision FROM hub_task_collection_state WHERE state_id = 1", Long.class); }
}
