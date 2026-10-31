package com.github.cleytonorocha.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupTask;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupReadingItem;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupHabit;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupHabitSchedule;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupFocusSession;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupNote;
import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.controller.TaskBackupController;
import com.github.cleytonorocha.hub.repository.TaskRepository;
import com.github.cleytonorocha.hub.repository.ReadingItemRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/** End-to-end coverage for the versioned local-task portable-copy API. */
@SpringBootTest
@AutoConfigureMockMvc
class TaskBackupControllerTests extends AuthenticatedHubMvcTestSupport {
	private static final String BASE = "/api/tasks/portable-copy";
	private static final String FUTURE_VERSION = "{\"formatVersion\":99,\"generatedAt\":\"2026-10-28T00:00:00Z\",\"tasks\":[]}";

	@Autowired TaskRepository tasks;
	@Autowired TaskBackupController backupController;
	@Autowired ReadingItemRepository readingItems;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper mapper;

	@BeforeEach
	void clearTasksAndResetRevision() {
		jdbc.execute("ALTER TABLE hub_task DROP CONSTRAINT IF EXISTS chk_backup_failure");
		jdbc.execute("ALTER TABLE hub_note DROP CONSTRAINT IF EXISTS chk_backup_note_failure");
		tasks.deleteAll();
		readingItems.deleteAll();
		jdbc.update("DELETE FROM hub_note_tag WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_note WHERE owner_id=?", TEST_OWNER);
		jdbc.update("UPDATE hub_note_user_revision SET revision=0 WHERE owner_id=?", TEST_OWNER);
		jdbc.update("UPDATE hub_focus_active_slot SET session_id=NULL WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_focus_session WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_habit_checkin WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_habit_pause WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_habit_schedule WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_habit WHERE owner_id=?", TEST_OWNER);
		jdbc.update("UPDATE hub_habit_user_revision SET revision=0 WHERE owner_id=?", TEST_OWNER);
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
			.andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
		MvcResult completedExport = mvc.performAsyncDispatch(exported)
			.andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/zip"))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", org.hamcrest.Matchers.containsString("hub-portable-copy.zip")))
			.andReturn();
		Map<String, byte[]> zipEntries = unzip(completedExport.getResponse().getContentAsByteArray());
		assertThat(zipEntries.keySet()).containsExactly("manifest.json");
		String content = new String(zipEntries.get("manifest.json"), java.nio.charset.StandardCharsets.UTF_8);
		JsonNode manifest = mapper.readTree(content);
		assertThat(manifest.get("formatVersion").asInt()).isEqualTo(11);
		assertThat(manifest.get("omittedSections").size()).isZero();
		assertThat(manifest.get("readingItems").size()).isZero();
		assertThat(manifest.get("tasks").get(0).get("id").asText()).isEqualTo(id);
		assertThat(manifest.get("tasks").get(0).has("dueDate")).isTrue();
		assertThat(manifest.get("tasks").get(0).get("dueDate").isNull()).isTrue();
		assertThat(manifest.get("tasks").get(0).get("category").asText()).isEqualTo("Pessoal");
		assertThat(manifest.get("tasks").get(0).get("color").asText()).isEqualTo("#123456");
		assertThat(manifest.get("tasks").get(0).get("priority").asText()).isEqualTo("HIGH");
		assertThat(manifest.get("tasks").get(0).get("notes").asText()).isEqualTo("Notas preservadas");

		tasks.deleteAll();
		byte[] packageBytes = completedExport.getResponse().getContentAsByteArray();
		MvcResult preview = previewPackage(packageBytes);
		assertThat(tasks.count()).isZero();
		String token = json(preview).get("token").asText();
		mvc.perform(importPackageRequest(packageBytes, token))
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
	void partialReadPermissionsExportOnlyAccessibleModulesAndListOmissions() throws Exception {
		String noteId = UUID.randomUUID().toString();
		String habitId = UUID.randomUUID().toString();
		String focusId = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO hub_note(id,owner_id,title,body,pinned,archived,related_task_id,related_task_unavailable,revision,created_at,updated_at) VALUES(?,?,?,'conteúdo',FALSE,FALSE,NULL,FALSE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
			noteId, TEST_OWNER, "Nota permitida");
		jdbc.update("INSERT INTO hub_habit(id,owner_id,name,start_date,revision,created_at,updated_at) VALUES(?,?,?,CURRENT_DATE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
			habitId, TEST_OWNER, "Hábito preservado");
		jdbc.update("INSERT INTO hub_focus_session(id,owner_id,objective,planned_seconds,focused_seconds,session_state,started_at,ended_at,end_reason) VALUES(?,?,?,3600,300,'ENDED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'NORMAL')",
			focusId, TEST_OWNER, "Sessão preservada");
		HubAuthenticatedMvc partial = mvcWithPermissions("TASK_READ,READING_READ,NOTES_READ");
		MvcResult started = partial.perform(get(BASE + "/export"))
			.andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
		MvcResult completed = partial.performAsyncDispatch(started).andExpect(status().isOk()).andReturn();
		Map<String, byte[]> entries = unzip(completed.getResponse().getContentAsByteArray());
		JsonNode manifest = mapper.readTree(entries.get("manifest.json"));
		assertThat(manifest.get("formatVersion").asInt()).isEqualTo(11);
		assertThat(manifest.get("omittedSections").size()).isEqualTo(2);
		assertThat(manifest.get("omittedSections").get(0).asText()).isEqualTo("habits");
		assertThat(manifest.get("omittedSections").get(1).asText()).isEqualTo("focusSessions");
		assertThat(manifest.get("habits").size()).isZero();
		assertThat(manifest.get("focusSessions").size()).isZero();
		assertThat(manifest.get("notes").get(0).get("id").asText()).isEqualTo(noteId);
		MvcResult preview = previewPackage(completed.getResponse().getContentAsByteArray());
		mvc.perform(importPackageRequest(completed.getResponse().getContentAsByteArray(), json(preview).get("token").asText()))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_habit WHERE owner_id=? AND id=?", Integer.class, TEST_OWNER, habitId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_focus_session WHERE owner_id=? AND id=?", Integer.class, TEST_OWNER, focusId)).isEqualTo(1);
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
	void acceptsAnExactVersionFourReadingFixture() throws Exception {
		String id = UUID.randomUUID().toString();
		String legacy = "{\"formatVersion\":4,\"generatedAt\":\"2026-10-28T10:00:00Z\",\"tasks\":[],\"series\":[],\"readingItems\":[{"
			+ "\"id\":\"" + id + "\",\"title\":\"Livro antigo\",\"type\":\"BOOK\",\"authorOrSource\":\"Autora\",\"url\":null,"
			+ "\"status\":\"IN_PROGRESS\",\"progressMarker\":\"Capítulo 2\",\"note\":\"Nota\",\"tags\":[\"Arquivo\"],"
			+ "\"archived\":false,\"createdAt\":\"2026-10-28T09:00:00Z\",\"updatedAt\":\"2026-10-28T10:00:00Z\"}]}";
		MvcResult preview = preview(legacy);
		assertThat(json(preview).get("newReadingItems").asInt()).isEqualTo(1);
		mvc.perform(importRequest(legacy, json(preview).get("token").asText()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.addedReadingItems").value(1));
		assertThat(readingItems.findById(id)).isPresent();
	}

	@Test
	void acceptsVersionSevenAsZipAndRejectsItAsStandaloneJson() throws Exception {
		String versionSeven = mapper.writeValueAsString(Map.of("formatVersion", 7, "generatedAt", Instant.parse("2026-10-28T10:00:00Z"),
			"tasks", List.of(), "series", List.of(), "readingItems", List.of(), "authors", List.of()));
		mvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(versionSeven))
			.andExpect(status().isBadRequest());
		MvcResult preview = previewPackage(packageManifest(versionSeven));
		assertThat(json(preview).get("formatVersion").asInt()).isEqualTo(7);
		assertThat(json(preview).get("totalTasks").asInt()).isZero();
	}

	@Test
	void acceptsAndImportsVersionEightHabitFixtureFromZip() throws Exception {
		String habitId = UUID.randomUUID().toString();
		BackupHabit habit = new BackupHabit(habitId, "Hábito V8", LocalDate.of(2026, 10, 28),
			Instant.parse("2026-10-28T09:00:00Z"), Instant.parse("2026-10-28T10:00:00Z"),
			List.of(new BackupHabitSchedule(LocalDate.of(2026, 10, 28), 31)), List.of(), List.of());
		String versionEight = mapper.writeValueAsString(Map.of("formatVersion", 8,
			"generatedAt", Instant.parse("2026-10-28T10:00:00Z"), "tasks", List.of(), "series", List.of(),
			"readingItems", List.of(), "authors", List.of(), "habits", List.of(habit)));
		byte[] fixture = packageManifest(versionEight);
		MvcResult preview = previewPackage(fixture);
		assertThat(json(preview).get("formatVersion").asInt()).isEqualTo(8);
		assertThat(json(preview).get("newHabits").asInt()).isEqualTo(1);
		mvc.perform(importPackageRequest(fixture, json(preview).get("token").asText()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.addedHabits").value(1));
		assertThat(jdbc.queryForObject("SELECT name FROM hub_habit WHERE owner_id=? AND id=?", String.class, TEST_OWNER, habitId)).isEqualTo("Hábito V8");
	}

	@Test
	void versionsEightThroughElevenOmittedAreasNeverDeleteExistingFocusData() throws Exception {
		String habitId = UUID.randomUUID().toString();
		String focusId = UUID.randomUUID().toString();
		String noteId = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO hub_habit(id,owner_id,name,start_date,revision,created_at,updated_at) VALUES(?,?,?,CURRENT_DATE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
			habitId, TEST_OWNER, "Preservar hábito");
		jdbc.update("INSERT INTO hub_focus_session(id,owner_id,objective,planned_seconds,focused_seconds,session_state,started_at,ended_at,end_reason) VALUES(?,?,?,3600,300,'ENDED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'NORMAL')",
			focusId, TEST_OWNER, "Preservar sessão");
		jdbc.update("INSERT INTO hub_note(id,owner_id,title,body,pinned,archived,related_task_id,related_task_unavailable,revision,created_at,updated_at) VALUES(?,?,?,'corpo',FALSE,FALSE,NULL,FALSE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
			noteId, TEST_OWNER, "Preservar nota");
		for (int version = 8; version <= 11; version++) {
			byte[] fixture = packageManifest(partialVersionFile(version));
			MvcResult preview = previewPackage(fixture);
			assertThat(json(preview).get("formatVersion").asInt()).isEqualTo(version);
			mvc.perform(importPackageRequest(fixture, json(preview).get("token").asText())).andExpect(status().isOk());
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_habit WHERE owner_id=? AND id=?", Integer.class, TEST_OWNER, habitId)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_focus_session WHERE owner_id=? AND id=?", Integer.class, TEST_OWNER, focusId)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_note WHERE owner_id=? AND id=?", Integer.class, TEST_OWNER, noteId)).isEqualTo(1);
		}
	}

	@Test
	void reimportingActiveSessionSnapshotIsAnIdenticalNoOp() throws Exception {
		String id = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO hub_focus_session(id,owner_id,objective,planned_seconds,focused_seconds,session_state,started_at,resumed_at) VALUES(?,?,?,3600,0,'ACTIVE',DATEADD('SECOND',-90,CURRENT_TIMESTAMP),DATEADD('SECOND',-30,CURRENT_TIMESTAMP))",
			id, TEST_OWNER, "Sessão ativa preservada");
		jdbc.update("UPDATE hub_focus_active_slot SET session_id=? WHERE owner_id=?", id, TEST_OWNER);
		byte[] snapshot = packageManifest(exportManifest());
		MvcResult preview = previewPackage(snapshot);
		assertThat(json(preview).get("newFocusSessions").asInt()).isZero();
		assertThat(json(preview).get("identicalFocusSessions").asInt()).isEqualTo(1);
		mvc.perform(importPackageRequest(snapshot, json(preview).get("token").asText()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.addedFocusSessions").value(0))
			.andExpect(jsonPath("$.identicalFocusSessions").value(1));
		assertThat(jdbc.queryForObject("SELECT session_state FROM hub_focus_session WHERE owner_id=? AND id=?", String.class, TEST_OWNER, id)).isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("SELECT session_id FROM hub_focus_active_slot WHERE owner_id=?", String.class, TEST_OWNER)).isEqualTo(id);
	}

	@Test
	void standaloneVersionTenJsonIsRejectedByPreviewRoute() throws Exception {
		String literalVersionTen = "{\"formatVersion\":10,\"generatedAt\":\"2026-10-28T10:00:00Z\",\"tasks\":[],\"series\":[],"
			+ "\"readingItems\":[],\"authors\":[],\"habits\":[],\"focusSessions\":[],\"notes\":[]}";
		mvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(literalVersionTen))
			.andExpect(status().isBadRequest());
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
		byte[] content = packageManifest(currentReadingFile(item, now));
		MvcResult preview = previewPackage(content);
		assertThat(json(preview).get("newReadingItems").asInt()).isEqualTo(1);
		assertThat(readingItems.count()).isZero();
		mvc.perform(importPackageRequest(content, json(preview).get("token").asText()))
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
		byte[] content = packageManifest(currentReadingFile(incoming, now.toString()));
		String token = json(previewPackage(content)).get("token").asText();
		mvc.perform(post("/api/reading-items").contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Mudança paralela\",\"type\":\"BOOK\"}"))
			.andExpect(status().isCreated());
		mvc.perform(importPackageRequest(content, token)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
		assertThat(readingItems.findById(id)).isEmpty();
	}

	@Test
	void versionNineHabitAndFocusDataRoundTripsThroughVersionElevenWithRecomputedNoteLinks() throws Exception {
		String taskId = UUID.randomUUID().toString();
		String habitId = UUID.randomUUID().toString();
		String focusId = UUID.randomUUID().toString();
		String noteId = UUID.randomUUID().toString();
		tasks.save(Task.restoreLocal(taskId, "Tarefa vinculada", null, null, false, "Geral", "#A986FF", Task.Priority.NORMAL, ""));

		Instant generatedAt = Instant.parse("2026-10-28T10:30:00Z");
		BackupHabit habit = new BackupHabit(habitId, "Caminhada", LocalDate.of(2026, 10, 26),
			Instant.parse("2026-10-28T09:00:00Z"), Instant.parse("2026-10-28T10:00:00Z"),
			List.of(new BackupHabitSchedule(LocalDate.of(2026, 10, 26), 127)),
			List.of(new com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupHabitPause(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 28))),
			List.of(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28)));
		BackupFocusSession focus = new BackupFocusSession(focusId, "Revisar projeto", 1800, 420, "PAUSED",
			Instant.parse("2026-10-28T09:00:00Z"), null, Instant.parse("2026-10-28T10:00:00Z"), null, null, taskId);
		String versionNine = mapper.writeValueAsString(Map.of("formatVersion", 9, "generatedAt", generatedAt, "tasks", List.of(),
			"series", List.of(), "readingItems", List.of(), "authors", List.of(), "habits", List.of(habit), "focusSessions", List.of(focus)));
		byte[] versionNinePackage = packageManifest(versionNine);
		MvcResult v9Preview = previewPackage(versionNinePackage);
		mvc.perform(importPackageRequest(versionNinePackage, json(v9Preview).get("token").asText()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.addedHabits").value(1)).andExpect(jsonPath("$.addedFocusSessions").value(1));

		BackupNote note = new BackupNote(noteId, "Resumo", "Conteúdo preservado", List.of("Projeto"), true, false,
			taskId, true, Instant.parse("2026-10-28T09:30:00Z"), generatedAt);
		String versionTen = currentV10File(List.of(note), generatedAt);
		byte[] versionTenPackage = packageManifest(versionTen);
		MvcResult v10Preview = previewPackage(versionTenPackage);
		assertThat(json(v10Preview).get("notes").get("notesWithUnavailableTaskLinks").asInt()).isZero();
		mvc.perform(importPackageRequest(versionTenPackage, json(v10Preview).get("token").asText()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.addedNotes").value(1));
		mvc.perform(get("/api/notes/{id}", noteId)).andExpect(status().isOk())
			.andExpect(jsonPath("$.relatedTaskId").value(taskId)).andExpect(jsonPath("$.relatedTaskStatus").value("AVAILABLE"));

		JsonNode manifest = mapper.readTree(exportManifest());
		assertThat(manifest.get("formatVersion").asInt()).isEqualTo(11);
		assertThat(manifest.get("tasks").findValues("id").stream().map(JsonNode::asText).toList()).contains(taskId);
		assertThat(manifest.get("habits").get(0).get("id").asText()).isEqualTo(habitId);
		assertThat(manifest.get("habits").get(0).get("schedules").size()).isEqualTo(1);
		assertThat(manifest.get("habits").get(0).get("pauses").get(0).get("resumedOn").asText()).isEqualTo("2026-10-28");
		assertThat(manifest.get("habits").get(0).get("checkIns").size()).isEqualTo(2);
		assertThat(manifest.get("focusSessions").get(0).get("state").asText()).isEqualTo("PAUSED");
		assertThat(manifest.get("focusSessions").get(0).get("taskId").asText()).isEqualTo(taskId);
		assertThat(manifest.get("notes").get(0).get("relatedTaskId").asText()).isEqualTo(taskId);
		assertThat(manifest.get("notes").get(0).get("relatedTaskUnavailable").asBoolean()).isFalse();

		jdbc.update("DELETE FROM hub_note_tag WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_note WHERE owner_id=?", TEST_OWNER);
		jdbc.update("UPDATE hub_focus_active_slot SET session_id=NULL WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_focus_session WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_habit_checkin WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_habit_pause WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_habit_schedule WHERE owner_id=?", TEST_OWNER);
		jdbc.update("DELETE FROM hub_habit WHERE owner_id=?", TEST_OWNER);
		tasks.deleteAll();
		tasks.flush();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_task WHERE owner_id=? AND id=?", Integer.class, TEST_OWNER, taskId)).isZero();
		String exportedManifest = mapper.writeValueAsString(manifest);
		byte[] exportedPackage = packageManifest(exportedManifest);
		MvcResult roundTripPreview = previewPackage(exportedPackage);
		mvc.perform(importPackageRequest(exportedPackage, json(roundTripPreview).get("token").asText()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.addedTasks").value(1))
			.andExpect(jsonPath("$.addedHabits").value(1)).andExpect(jsonPath("$.addedFocusSessions").value(1))
			.andExpect(jsonPath("$.addedNotes").value(1));
		assertThat(jdbc.queryForObject("SELECT related_task_unavailable FROM hub_note WHERE owner_id=? AND id=?", Boolean.class, TEST_OWNER, noteId)).isFalse();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_habit_schedule WHERE owner_id=? AND habit_id=?", Integer.class, TEST_OWNER, habitId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT resumed_on FROM hub_habit_pause WHERE owner_id=? AND habit_id=?", LocalDate.class, TEST_OWNER, habitId)).isEqualTo(LocalDate.of(2026, 10, 28));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_habit_checkin WHERE owner_id=? AND habit_id=?", Integer.class, TEST_OWNER, habitId)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT task_id FROM hub_focus_session WHERE owner_id=? AND id=?", String.class, TEST_OWNER, focusId)).isEqualTo(taskId);
		assertThat(jdbc.queryForObject("SELECT session_state FROM hub_focus_session WHERE owner_id=? AND id=?", String.class, TEST_OWNER, focusId)).isEqualTo("PAUSED");
		assertThat(jdbc.queryForObject("SELECT session_id FROM hub_focus_active_slot WHERE owner_id=?", String.class, TEST_OWNER)).isEqualTo(focusId);
	}

	@Test
	void failedCrossDomainConfirmationRollsBackAllRowsAndKeepsPreviewRetryable() throws Exception {
		Instant generatedAt = Instant.parse("2026-10-28T10:30:00Z");
		BackupTask incomingTask = task(UUID.randomUUID().toString(), "Tarefa atômica", null, null, false, "Geral", "#A986FF", "NORMAL", "");
		String habitId = UUID.randomUUID().toString();
		BackupHabit incomingHabit = new BackupHabit(habitId, "Hábito atômico", LocalDate.of(2026, 10, 26),
			Instant.parse("2026-10-28T09:00:00Z"), Instant.parse("2026-10-28T10:00:00Z"),
			List.of(new BackupHabitSchedule(LocalDate.of(2026, 10, 26), 127)), List.of(), List.of());
		String focusId = UUID.randomUUID().toString();
		BackupFocusSession incomingFocus = new BackupFocusSession(focusId, "Foco atômico", 1200, 60, "PAUSED",
			Instant.parse("2026-10-28T09:00:00Z"), null, Instant.parse("2026-10-28T10:00:00Z"), null, null, incomingTask.id());
		BackupNote incomingNote = new BackupNote(UUID.randomUUID().toString(), "force-note-rollback", "Nota", List.of(), false, false,
			incomingTask.id(), false, Instant.parse("2026-10-28T09:30:00Z"), generatedAt);
		byte[] content = packageManifest(currentV10File(List.of(incomingNote), generatedAt,
			List.of(incomingTask), List.of(incomingHabit), List.of(incomingFocus)));
		String token = json(previewPackage(content)).get("token").asText();
		long beforeTaskRevision = revision();
		long beforeNoteRevision = jdbc.queryForObject("SELECT revision FROM hub_note_user_revision WHERE owner_id=?", Long.class, TEST_OWNER);
		jdbc.execute("ALTER TABLE hub_note DROP CONSTRAINT IF EXISTS chk_backup_note_failure");
		jdbc.execute("ALTER TABLE hub_note ADD CONSTRAINT chk_backup_note_failure CHECK (title <> 'force-note-rollback')");
		try {
			assertThatThrownBy(() -> mvc.perform(importPackageRequest(content, token)))
				.hasCauseInstanceOf(DataIntegrityViolationException.class);
			assertThat(tasks.count()).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_habit WHERE owner_id=?", Integer.class, TEST_OWNER)).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_focus_session WHERE owner_id=?", Integer.class, TEST_OWNER)).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_note WHERE owner_id=?", Integer.class, TEST_OWNER)).isZero();
			assertThat(revision()).isEqualTo(beforeTaskRevision);
			assertThat(jdbc.queryForObject("SELECT revision FROM hub_note_user_revision WHERE owner_id=?", Long.class, TEST_OWNER)).isEqualTo(beforeNoteRevision);
		} finally {
			jdbc.execute("ALTER TABLE hub_note DROP CONSTRAINT IF EXISTS chk_backup_note_failure");
		}
		mvc.perform(importPackageRequest(content, token)).andExpect(status().isOk())
			.andExpect(jsonPath("$.addedTasks").value(1)).andExpect(jsonPath("$.addedHabits").value(1))
			.andExpect(jsonPath("$.addedFocusSessions").value(1)).andExpect(jsonPath("$.addedNotes").value(1));
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

	}

	@Test
	void rejectsPackageDeclaredLengthAboveLimitBeforeReadingBody() {
		MockHttpServletRequest request = new MockHttpServletRequest() {
			@Override public long getContentLengthLong() {
				return com.github.cleytonorocha.hub.service.TaskBackupService.MAX_PACKAGE_BYTES + 1L;
			}
		};
		request.setContentType("application/zip");

		assertThatThrownBy(() -> backupController.preview(request))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE));
	}

	private MvcResult preview(String content) throws Exception {
		return mvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(content))
			.andExpect(status().isOk()).andReturn();
	}

	private MvcResult previewPackage(byte[] content) throws Exception {
		return mvc.perform(post(BASE + "/preview").contentType("application/zip").content(content))
			.andExpect(status().isOk()).andReturn();
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder importRequest(String content, String token) {
		return post(BASE + "/import").header("X-Task-Import-Preview", token)
			.contentType(MediaType.APPLICATION_JSON).content(content);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder importPackageRequest(byte[] content, String token) {
		return post(BASE + "/import").header("X-Task-Import-Preview", token)
			.contentType("application/zip").content(content);
	}

	private JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()); }

	private Map<String, byte[]> unzip(byte[] bytes) throws Exception {
		Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes)); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			java.util.zip.ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				output.reset();
				zip.transferTo(output);
				entries.put(entry.getName(), output.toByteArray());
			}
		}
		return entries;
	}

	private byte[] packageManifest(String content) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));
			zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return output.toByteArray();
	}

	private String currentReadingFile(BackupReadingItem item, String generatedAt) throws Exception {
		return mapper.writeValueAsString(Map.of("formatVersion", 10, "generatedAt", Instant.parse(generatedAt), "tasks", List.of(),
			"series", List.of(), "readingItems", List.of(item), "authors", List.of(), "habits", List.of(),
			"focusSessions", List.of(), "notes", List.of()));
	}

	private String currentV10File(List<BackupNote> noteItems, Instant generatedAt) throws Exception {
		return currentV10File(noteItems, generatedAt, List.of(), List.of(), List.of());
	}

	private String currentV10File(List<BackupNote> noteItems, Instant generatedAt, List<BackupTask> taskItems,
			List<BackupHabit> habitItems, List<BackupFocusSession> focusItems) throws Exception {
		return mapper.writeValueAsString(Map.of("formatVersion", 10, "generatedAt", generatedAt, "tasks", taskItems, "series", List.of(),
			"readingItems", List.of(), "authors", List.of(), "habits", habitItems, "focusSessions", focusItems, "notes", noteItems));
	}

	private String partialVersionFile(int version) throws Exception {
		Map<String, Object> manifest = new java.util.LinkedHashMap<>();
		manifest.put("formatVersion", version);
		manifest.put("generatedAt", Instant.parse("2026-10-28T10:00:00Z"));
		manifest.put("tasks", List.of());
		manifest.put("series", List.of());
		manifest.put("readingItems", List.of());
		manifest.put("authors", List.of());
		manifest.put("habits", List.of());
		if (version >= 9) manifest.put("focusSessions", List.of());
		if (version >= 10) manifest.put("notes", List.of());
		if (version >= 11) manifest.put("omittedSections", List.of("habits", "focusSessions", "notes"));
		return mapper.writeValueAsString(manifest);
	}

	private String exportManifest() throws Exception {
		MvcResult started = mvc.perform(get(BASE + "/export"))
			.andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
		MvcResult response = mvc.performAsyncDispatch(started).andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/zip"))
			.andReturn();
		return new String(unzip(response.getResponse().getContentAsByteArray()).get("manifest.json"), java.nio.charset.StandardCharsets.UTF_8);
	}

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
