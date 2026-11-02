package com.github.cleytonorocha.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import com.github.cleytonorocha.hub.service.AuthOwnershipMigration;
import com.github.cleytonorocha.hub.repository.TaskRepository;
import com.github.cleytonorocha.hub.repository.ReadingItemRepository;
import com.github.cleytonorocha.hub.service.PortableCopyArchiveCodec;
import com.github.cleytonorocha.hub.service.ReadingCoverStorageService;
import com.github.cleytonorocha.hub.service.UserPreferenceService;
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
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
	@Autowired PortableCopyArchiveCodec archiveCodec;
	@Autowired UserPreferenceService userPreferences;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired AuthOwnershipMigration ownershipMigration;
	@MockitoSpyBean ReadingCoverStorageService covers;

	@BeforeEach
	void clearTasksAndResetRevision() {
		jdbc.execute("ALTER TABLE hub_task DROP CONSTRAINT IF EXISTS chk_backup_failure");
		jdbc.execute("ALTER TABLE hub_note DROP CONSTRAINT IF EXISTS chk_backup_note_failure");
		clearOwnerPortableRows();
		jdbc.update("UPDATE hub_task_collection_state SET revision = 0 WHERE state_id = 1");
		jdbc.update("UPDATE hub_reading_collection_state SET revision = 0 WHERE state_id = 1");
		jdbc.update("DELETE FROM hub_user_preference WHERE owner_id=?", TEST_OWNER);
		assertOwnerPortableRowsEmpty();
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
		assertThat(zipEntries.keySet()).containsExactlyInAnyOrder("manifest.json", "data/tasks.json", "data/series.json", "data/readingItems.json",
			"data/authors.json", "data/habits.json", "data/focusSessions.json", "data/notes.json", "data/preferences.json");
		String content = new String(zipEntries.get("manifest.json"), java.nio.charset.StandardCharsets.UTF_8);
		JsonNode manifest = mapper.readTree(content);
		assertThat(manifest.get("formatVersion").asInt()).isEqualTo(12);
		assertThat(manifest.get("sections").size()).isEqualTo(8);
		JsonNode taskData = mapper.readTree(zipEntries.get("data/tasks.json"));
		assertThat(mapper.readTree(zipEntries.get("data/readingItems.json")).size()).isZero();
		assertThat(taskData.get(0).get("id").asText()).isEqualTo(id);
		assertThat(taskData.get(0).has("dueDate")).isTrue();
		assertThat(taskData.get(0).get("dueDate").isNull()).isTrue();
		assertThat(taskData.get(0).get("category").asText()).isEqualTo("Pessoal");
		assertThat(taskData.get(0).get("color").asText()).isEqualTo("#123456");
		assertThat(taskData.get(0).get("priority").asText()).isEqualTo("HIGH");
		assertThat(taskData.get(0).get("notes").asText()).isEqualTo("Notas preservadas");

		clearOwnerPortableRows();
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
	void portableCopyPermissionExportsAllOwnDomainsWithoutModuleReadPermissions() throws Exception {
		String noteId = UUID.randomUUID().toString();
		String habitId = UUID.randomUUID().toString();
		String focusId = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO hub_note(id,owner_id,title,body,pinned,archived,related_task_id,related_task_unavailable,revision,created_at,updated_at) VALUES(?,?,?,'conteúdo',FALSE,FALSE,NULL,FALSE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
			noteId, TEST_OWNER, "Nota permitida");
		jdbc.update("INSERT INTO hub_habit(id,owner_id,name,start_date,revision,created_at,updated_at) VALUES(?,?,?,CURRENT_DATE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
			habitId, TEST_OWNER, "Hábito preservado");
		jdbc.update("INSERT INTO hub_habit_schedule(id,owner_id,habit_id,effective_from,weekdays_mask) VALUES(?,?,?,CURRENT_DATE,127)",
			UUID.randomUUID().toString(), TEST_OWNER, habitId);
		jdbc.update("INSERT INTO hub_focus_session(id,owner_id,objective,planned_seconds,focused_seconds,session_state,started_at,ended_at,end_reason) VALUES(?,?,?,3600,300,'ENDED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'NORMAL')",
			focusId, TEST_OWNER, "Sessão preservada");
		HubAuthenticatedMvc noPortableCopy = mvcWithPermissions("TASK_READ,READING_READ,NOTES_READ");
		noPortableCopy.perform(get(BASE + "/export")).andExpect(status().isForbidden());
		HubAuthenticatedMvc portableOnly = mvcWithPermissions("PORTABLE_COPY");
		MvcResult started = portableOnly.perform(get(BASE + "/export"))
			.andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
		MvcResult completed = portableOnly.performAsyncDispatch(started).andExpect(status().isOk()).andReturn();
		Map<String, byte[]> entries = unzip(completed.getResponse().getContentAsByteArray());
		JsonNode manifest = mapper.readTree(entries.get("manifest.json"));
		assertThat(manifest.get("formatVersion").asInt()).isEqualTo(12);
		assertThat(mapper.readTree(entries.get("data/habits.json")).size()).isEqualTo(1);
		assertThat(mapper.readTree(entries.get("data/focusSessions.json")).size()).isEqualTo(1);
		assertThat(mapper.readTree(entries.get("data/notes.json")).get(0).get("id").asText()).isEqualTo(noteId);
		MvcResult preview = previewPackage(completed.getResponse().getContentAsByteArray());
		mvc.perform(importPackageRequest(completed.getResponse().getContentAsByteArray(), json(preview).get("token").asText()))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_habit WHERE owner_id=? AND id=?", Integer.class, TEST_OWNER, habitId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_focus_session WHERE owner_id=? AND id=?", Integer.class, TEST_OWNER, focusId)).isEqualTo(1);
	}

	@Test
	void portableCopyPermissionIsRequiredForExportPreviewAndConfirmationEvenAfterPreview() throws Exception {
		String content = file(task(UUID.randomUUID().toString(), "Sem permissão", null, null, false, "Geral", "#A986FF", "NORMAL", ""));
		HubAuthenticatedMvc allowed = mvcWithPermissions("PORTABLE_COPY");
		MvcResult preview = allowed.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(content))
			.andExpect(status().isOk()).andReturn();
		String token = json(preview).get("token").asText();

		mvcWithPermissions("TASK_READ").perform(get(BASE + "/export")).andExpect(status().isForbidden());
		mvcWithPermissions("TASK_READ").perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isForbidden());
		mvcWithPermissions("TASK_READ").perform(importRequest(content, token)).andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_task WHERE owner_id=?", Integer.class, TEST_OWNER)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_user_preference WHERE owner_id=?", Integer.class, TEST_OWNER)).isZero();
	}

	@Test
	void previewDoesNotWritePreferencesAndConfirmationReplacesOnlyReviewedValues() throws Exception {
		Map<String, Object> original = userPreferences.get(TEST_OWNER).preferences();
		byte[] content = v12Package(Instant.parse("2026-10-31T10:00:00Z"), List.of(), List.of(), List.of(), Map.of(), preferences(false, true, false));
		long beforeRevision = userPreferences.get(TEST_OWNER).revision();
		MvcResult preview = previewPackage(content);
		assertThat(json(preview).get("preferencesToReplace").get("weeklySummaryVisible").asBoolean()).isFalse();
		assertThat(userPreferences.get(TEST_OWNER).preferences()).isEqualTo(original);
		assertThat(userPreferences.get(TEST_OWNER).revision()).isEqualTo(beforeRevision);

		MvcResult imported = mvc.perform(importPackageRequest(content, json(preview).get("token").asText()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IMPORTED"))
			.andExpect(jsonPath("$.replacedPreferenceKeys").isArray()).andReturn();
		assertThat(json(imported).get("replacedPreferenceKeys").toString()).contains("weeklySummaryVisible");
		assertThat(userPreferences.get(TEST_OWNER).preferences()).isEqualTo(userPreferences.validatePortableCopy(preferences(false, true, false)));
		assertThat(userPreferences.get(TEST_OWNER).revision()).isEqualTo(beforeRevision + 1);
	}

	@Test
	void concurrentPreferenceEditAfterPreviewRequiresReviewWithoutImportingAnyRows() throws Exception {
		userPreferences.initialize(TEST_OWNER, userPreferences.get(TEST_OWNER).preferences());
		long beforeRevision = userPreferences.get(TEST_OWNER).revision();
		BackupTask incoming = task(UUID.randomUUID().toString(), "Não importar após revisão", null, null, false, "Geral", "#A986FF", "NORMAL", "");
		byte[] content = v12Package(Instant.parse("2026-10-31T10:00:00Z"), List.of(incoming), List.of(), List.of(), Map.of(), preferences(false, true, false));
		MvcResult preview = previewPackage(content);
		assertThat(json(preview).get("preferencesToReplace").isEmpty()).isFalse();
		userPreferences.save(TEST_OWNER, beforeRevision, preferences(true, false, true));

		MvcResult confirmation = mvc.perform(importPackageRequest(content, json(preview).get("token").asText()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVIEW_REQUIRED")).andReturn();
		assertThat(json(confirmation).get("preview").get("preferencesToReplace").isEmpty()).isFalse();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_task WHERE owner_id=? AND id=?", Integer.class, TEST_OWNER, incoming.id())).isZero();
		assertThat(userPreferences.get(TEST_OWNER).preferences()).isEqualTo(userPreferences.validatePortableCopy(preferences(true, false, true)));
	}

	@Test
	void sameIdsAndCoverImportedByTwoOwnersRemainIndependent() throws Exception {
		String otherOwner = UUID.randomUUID().toString();
		String taskId = UUID.randomUUID().toString();
		String readingId = UUID.randomUUID().toString();
		Instant generatedAt = Instant.parse("2026-10-31T10:00:00Z");
		BackupTask sharedTask = task(taskId, "Mesmo ID", null, null, false, "Geral", "#A986FF", "NORMAL", "");
		ByteArrayOutputStream imageOutput = new ByteArrayOutputStream();
		assertThat(javax.imageio.ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", imageOutput)).isTrue();
		byte[] image = imageOutput.toByteArray();
		String imageHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
		BackupReadingItem sharedReading = new BackupReadingItem(readingId, "Acervo do mesmo ID", "ARTICLE", null, null, "TO_READ", "",
			null, null, "Nota", List.of("Pessoal"), false, generatedAt.minusSeconds(60), generatedAt, imageHash, "png", (long) image.length, null, List.of());
		Path sourceImage = Files.createTempFile("hub-test-two-owner-cover-", ".png");
		Files.write(sourceImage, image);
		byte[] content;
		String firstCover = null;
		String secondCover = null;
		try {
			content = v12Package(generatedAt, List.of(sharedTask), List.of(sharedReading), List.of(),
				Map.of("assets/" + readingId + ".png", sourceImage), preferences(true, true, false));
		} finally { Files.deleteIfExists(sourceImage); }
		try {
			MvcResult firstPreview = previewPackage(content);
			mvc.perform(importPackageRequest(content, json(firstPreview).get("token").asText())).andExpect(status().isOk());
			assertTaskTenantSchemaReady();
			HubAuthenticatedMvc secondOwner = mvcAsOwnerWithPermissions(otherOwner, "PORTABLE_COPY");
			MvcResult secondPreview = secondOwner.perform(post(BASE + "/preview").contentType("application/zip").content(content))
				.andExpect(status().isOk()).andReturn();
			secondOwner.perform(importPackageRequest(content, json(secondPreview).get("token").asText())).andExpect(status().isOk());

			assertThat(jdbc.queryForObject("SELECT title FROM hub_task WHERE owner_id=? AND id=?", String.class, TEST_OWNER, taskId)).isEqualTo("Mesmo ID");
			assertThat(jdbc.queryForObject("SELECT title FROM hub_task WHERE owner_id=? AND id=?", String.class, otherOwner, taskId)).isEqualTo("Mesmo ID");
			assertThat(jdbc.queryForObject("SELECT personal_note FROM hub_reading_item WHERE owner_id=? AND id=?", String.class, TEST_OWNER, readingId)).isEqualTo("Nota");
			assertThat(jdbc.queryForObject("SELECT personal_note FROM hub_reading_item WHERE owner_id=? AND id=?", String.class, otherOwner, readingId)).isEqualTo("Nota");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_reading_item_tag WHERE owner_id=? AND reading_item_id=?", Integer.class, TEST_OWNER, readingId)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_reading_item_tag WHERE owner_id=? AND reading_item_id=?", Integer.class, otherOwner, readingId)).isEqualTo(1);
			firstCover = jdbc.queryForObject("SELECT cover_image_name FROM hub_reading_item WHERE owner_id=? AND id=?", String.class, TEST_OWNER, readingId);
			secondCover = jdbc.queryForObject("SELECT cover_image_name FROM hub_reading_item WHERE owner_id=? AND id=?", String.class, otherOwner, readingId);
			assertThat(firstCover).isNotEqualTo(secondCover);
			String firstStoredCover = firstCover;
			String secondStoredCover = secondCover;
			assertThat(covers.read(TEST_OWNER, firstStoredCover, false)).isNotEmpty();
			assertThat(covers.read(TEST_OWNER, firstStoredCover, true)).isNotEmpty();
			assertThat(covers.read(otherOwner, secondStoredCover, false)).isNotEmpty();
			assertThat(covers.read(otherOwner, secondStoredCover, true)).isNotEmpty();
			assertThatThrownBy(() -> covers.read(TEST_OWNER, secondStoredCover, false)).isInstanceOf(ReadingCoverStorageService.CoverNotFoundException.class);
			assertThatThrownBy(() -> covers.read(TEST_OWNER, secondStoredCover, true)).isInstanceOf(ReadingCoverStorageService.CoverNotFoundException.class);
			assertThatThrownBy(() -> covers.read(otherOwner, firstStoredCover, false)).isInstanceOf(ReadingCoverStorageService.CoverNotFoundException.class);
			assertThatThrownBy(() -> covers.read(otherOwner, firstStoredCover, true)).isInstanceOf(ReadingCoverStorageService.CoverNotFoundException.class);
			jdbc.update("UPDATE hub_task SET title='Alterada apenas na primeira conta' WHERE owner_id=? AND id=?", TEST_OWNER, taskId);
			assertThat(jdbc.queryForObject("SELECT title FROM hub_task WHERE owner_id=? AND id=?", String.class, otherOwner, taskId)).isEqualTo("Mesmo ID");
		} finally {
			covers.delete(TEST_OWNER, firstCover);
			covers.delete(otherOwner, secondCover);
			deleteOwnerPortableData(otherOwner);
		}
	}

	@Test
	void exportDuringAtomicCrossDomainEditCapturesOneCommittedState() throws Exception {
		String id = UUID.randomUUID().toString();
		tasks.save(Task.restoreLocal(id, "Antes", null, null, false, "Geral", "#A986FF", Task.Priority.NORMAL, ""));
		Map<String, Object> original = preferences(true, true, false);
		userPreferences.initialize(TEST_OWNER, original);
		Map<String, Object> edited = preferences(false, false, true);
		String editedJson = mapper.writeValueAsString(userPreferences.validatePortableCopy(edited));
		CountDownLatch changesStaged = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> writer = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
				jdbc.update("UPDATE hub_task_user_revision SET revision=revision+1 WHERE owner_id=?", TEST_OWNER);
				jdbc.update("UPDATE hub_task SET title='Depois' WHERE owner_id=? AND id=?", TEST_OWNER, id);
				jdbc.update("UPDATE hub_user_preference SET preferences=?,revision=revision+1 WHERE owner_id=?", editedJson, TEST_OWNER);
				changesStaged.countDown();
				try { commit.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
				return null;
			}));
			changesStaged.await();
			Future<Map<String, byte[]>> exported = pool.submit(() -> unzip(exportPackage()));
			commit.countDown();
			writer.get();
			Map<String, byte[]> entries = exported.get();
			JsonNode taskData = mapper.readTree(entries.get("data/tasks.json"));
			JsonNode preferenceData = mapper.readTree(entries.get("data/preferences.json"));
			boolean taskIsEdited = taskData.get(0).get("title").asText().equals("Depois");
			boolean preferencesAreEdited = !preferenceData.get("weeklySummaryVisible").asBoolean()
				&& preferenceData.get("sidebarPinned").asBoolean();
			assertThat(taskIsEdited).isEqualTo(preferencesAreEdited);
		}
	}

	@Test
	void invalidV12PackagesAreRejectedWithoutWritesOrStagingResidue() throws Exception {
		byte[] valid = v12Package(Instant.parse("2026-10-31T10:00:00Z"), List.of(), List.of(), List.of(), Map.of(), preferences(true, true, false));
		Map<String, byte[]> validEntries = unzip(valid);
		List<byte[]> invalidPackages = new ArrayList<>();
		invalidPackages.add(rewriteZip(tamperSection(validEntries, "sha256", "0".repeat(64))));
		invalidPackages.add(rewriteZip(tamperSection(validEntries, "size", 999L)));
		invalidPackages.add(rewriteZip(tamperSection(validEntries, "path", "../tasks.json")));
		Map<String, byte[]> withExtra = new LinkedHashMap<>(validEntries);
		withExtra.put("unexpected.txt", "extra".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		invalidPackages.add(rewriteZip(withExtra));
		invalidPackages.add(java.util.Arrays.copyOf(valid, valid.length - 8));
		invalidPackages.add(replaceZipEntryName(rewriteZip(validEntries), "data/notes.json", "data/tasks.json"));
		invalidPackages.add(oversizedExpandedV12Package());
		for (byte[] invalid : invalidPackages) {
			long stagingBefore = stagingDirectoryCount();
			mvc.perform(post(BASE + "/preview").contentType("application/zip").content(invalid))
				.andExpect(status().isBadRequest())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
					org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\"token\""))));
			assertOwnerPortableRowsEmpty();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_user_preference WHERE owner_id=?", Integer.class, TEST_OWNER)).isZero();
			assertThat(stagingDirectoryCount()).isEqualTo(stagingBefore);
		}
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
		byte[] snapshot = exportPackage();
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
		MvcResult preview = previewPackage(content);
		String token = json(preview).get("token").asText();
		mvc.perform(post("/api/reading-items").contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Mudança paralela\",\"type\":\"BOOK\"}"))
			.andExpect(status().isCreated());
		mvc.perform(importPackageRequest(content, token)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
		assertThat(readingItems.findById(id)).isEmpty();
	}

	@Test
	void versionNineHabitAndFocusDataRoundTripsThroughVersionTwelveWithRecomputedNoteLinks() throws Exception {
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

		byte[] exportedPackage = exportPackage();
		Map<String, byte[]> exportedEntries = unzip(exportedPackage);
		JsonNode manifest = mapper.readTree(exportedEntries.get("manifest.json"));
		assertThat(manifest.get("formatVersion").asInt()).isEqualTo(12);
		JsonNode tasksData = mapper.readTree(exportedEntries.get("data/tasks.json"));
		JsonNode habitsData = mapper.readTree(exportedEntries.get("data/habits.json"));
		JsonNode focusData = mapper.readTree(exportedEntries.get("data/focusSessions.json"));
		JsonNode notesData = mapper.readTree(exportedEntries.get("data/notes.json"));
		assertThat(tasksData.findValues("id").stream().map(JsonNode::asText).toList()).contains(taskId);
		assertThat(habitsData.get(0).get("id").asText()).isEqualTo(habitId);
		assertThat(habitsData.get(0).get("schedules").size()).isEqualTo(1);
		assertThat(habitsData.get(0).get("pauses").get(0).get("resumedOn").asText()).isEqualTo("2026-10-28");
		assertThat(habitsData.get(0).get("checkIns").size()).isEqualTo(2);
		assertThat(focusData.get(0).get("state").asText()).isEqualTo("PAUSED");
		assertThat(focusData.get(0).get("taskId").asText()).isEqualTo(taskId);
		assertThat(notesData.get(0).get("relatedTaskId").asText()).isEqualTo(taskId);
		assertThat(notesData.get(0).get("relatedTaskUnavailable").asBoolean()).isFalse();

		clearOwnerPortableRows();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_task WHERE owner_id=? AND id=?", Integer.class, TEST_OWNER, taskId)).isZero();
		assertOwnerPortableRowsEmpty();
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
	void failedV12ConfirmationRemovesStoredCoverAndRollsBackRowsAndRevisions() throws Exception {
		Instant generatedAt = Instant.parse("2026-10-30T10:30:00Z");
		String readingId = UUID.randomUUID().toString();
		ByteArrayOutputStream imageOutput = new ByteArrayOutputStream();
		assertThat(javax.imageio.ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", imageOutput)).isTrue();
		byte[] image = imageOutput.toByteArray();
		String imageHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
		BackupReadingItem reading = new BackupReadingItem(readingId, "Capa a importar", "ARTICLE", null, null, "TO_READ", "",
			null, null, "", List.of(), false, generatedAt.minusSeconds(60), generatedAt, imageHash, "png", (long) image.length, null, List.of());
		BackupNote failingNote = new BackupNote(UUID.randomUUID().toString(), "force-note-rollback", "Nota", List.of(), false, false,
			null, false, generatedAt.minusSeconds(60), generatedAt);
		Path sourceImage = Files.createTempFile("hub-test-cover-", ".png");
		Files.write(sourceImage, image);
		byte[] content;
		try { content = v12Package(generatedAt, List.of(reading), List.of(failingNote), Map.of("assets/" + readingId + ".png", sourceImage)); }
		finally { Files.deleteIfExists(sourceImage); }
		MvcResult preview = previewPackage(content);
		String token = json(preview).get("token").asText();
		long beforeTaskRevision = revision();
		long beforeReadingRevision = jdbc.queryForObject("SELECT revision FROM hub_reading_collection_state WHERE state_id=1", Long.class);
		long beforeHabitRevision = jdbc.queryForObject("SELECT revision FROM hub_habit_user_revision WHERE owner_id=?", Long.class, TEST_OWNER);
		long beforeNoteRevision = jdbc.queryForObject("SELECT revision FROM hub_note_user_revision WHERE owner_id=?", Long.class, TEST_OWNER);
		long beforePreferenceRevision = jdbc.queryForObject("SELECT COALESCE(MAX(revision),0) FROM hub_user_preference WHERE owner_id=?", Long.class, TEST_OWNER);
		List<String> storedCoverNames = new ArrayList<>();
		doAnswer(invocation -> {
			ReadingCoverStorageService.StoredCover stored = (ReadingCoverStorageService.StoredCover) invocation.callRealMethod();
			storedCoverNames.add(stored.name());
			return stored;
		}).when(covers).storePortable(eq(TEST_OWNER), any(byte[].class), eq("png"));
		jdbc.execute("ALTER TABLE hub_note DROP CONSTRAINT IF EXISTS chk_backup_note_failure");
		jdbc.execute("ALTER TABLE hub_note ADD CONSTRAINT chk_backup_note_failure CHECK (title <> 'force-note-rollback')");
		try {
			assertThat(json(preview).get("newCovers").asInt()).isEqualTo(1);
			assertThatThrownBy(() -> mvc.perform(importPackageRequest(content, token)))
				.hasCauseInstanceOf(DataIntegrityViolationException.class);
			assertThat(readingItems.findAll()).isEmpty();
			assertThat(tasks.count()).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_note WHERE owner_id=?", Integer.class, TEST_OWNER)).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_habit WHERE owner_id=?", Integer.class, TEST_OWNER)).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_focus_session WHERE owner_id=?", Integer.class, TEST_OWNER)).isZero();
			assertThat(revision()).isEqualTo(beforeTaskRevision);
			assertThat(jdbc.queryForObject("SELECT revision FROM hub_reading_collection_state WHERE state_id=1", Long.class)).isEqualTo(beforeReadingRevision);
			assertThat(jdbc.queryForObject("SELECT revision FROM hub_habit_user_revision WHERE owner_id=?", Long.class, TEST_OWNER)).isEqualTo(beforeHabitRevision);
			assertThat(jdbc.queryForObject("SELECT revision FROM hub_note_user_revision WHERE owner_id=?", Long.class, TEST_OWNER)).isEqualTo(beforeNoteRevision);
			assertThat(jdbc.queryForObject("SELECT COALESCE(MAX(revision),0) FROM hub_user_preference WHERE owner_id=?", Long.class, TEST_OWNER)).isEqualTo(beforePreferenceRevision);
			assertThat(storedCoverNames).hasSize(1);
			String storedName = storedCoverNames.get(0);
			assertThatThrownBy(() -> covers.read(TEST_OWNER, storedName, false)).isInstanceOf(ReadingCoverStorageService.CoverNotFoundException.class);
			assertThatThrownBy(() -> covers.read(TEST_OWNER, storedName, true)).isInstanceOf(ReadingCoverStorageService.CoverNotFoundException.class);
		} finally {
			jdbc.execute("ALTER TABLE hub_note DROP CONSTRAINT IF EXISTS chk_backup_note_failure");
			storedCoverNames.forEach(name -> covers.delete(TEST_OWNER, name));
		}
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
		Map<String, Object> missingEnvelope = mapper.readValue(valid, new tools.jackson.core.type.TypeReference<>() {});
		assertThat(missingEnvelope.remove("generatedAt")).isNotNull();
		String missingEnvelopeField = mapper.writeValueAsString(missingEnvelope);
		String extraEnvelopeField = valid.replaceFirst("\\\"tasks\\\":", "\\\"unexpected\\\":true,\\\"tasks\\\":");
		String missingTaskField = valid.replaceFirst(",\\\"notes\\\":\\\"\\\"", "");
		String extraTaskField = valid.replaceFirst("\\\"notes\\\":\\\"\\\"", "\\\"notes\\\":\\\"\\\",\\\"unexpected\\\":true");

		long beforeRevision = revision();
		long beforeReadingRevision = jdbc.queryForObject("SELECT revision FROM hub_reading_collection_state WHERE state_id=1", Long.class);
		long beforeHabitRevision = jdbc.queryForObject("SELECT revision FROM hub_habit_user_revision WHERE owner_id=?", Long.class, TEST_OWNER);
		long beforeNoteRevision = jdbc.queryForObject("SELECT revision FROM hub_note_user_revision WHERE owner_id=?", Long.class, TEST_OWNER);
		int beforePreferenceRows = jdbc.queryForObject("SELECT COUNT(*) FROM hub_user_preference WHERE owner_id=?", Integer.class, TEST_OWNER);
		long beforePreferenceRevision = jdbc.queryForObject("SELECT COALESCE(MAX(revision),0) FROM hub_user_preference WHERE owner_id=?", Long.class, TEST_OWNER);
		for (String invalid : List.of(duplicateEnvelope, duplicateTask, missingEnvelopeField, extraEnvelopeField, missingTaskField, extraTaskField)) {
			mvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\"token\""))));
		}
		mvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(valid))
			.andExpect(status().isOk()).andExpect(jsonPath("$.newTasks").value(1));
		assertThat(tasks.count()).isZero();
		assertThat(revision()).isEqualTo(beforeRevision);
		assertThat(jdbc.queryForObject("SELECT revision FROM hub_reading_collection_state WHERE state_id=1", Long.class)).isEqualTo(beforeReadingRevision);
		assertThat(jdbc.queryForObject("SELECT revision FROM hub_habit_user_revision WHERE owner_id=?", Long.class, TEST_OWNER)).isEqualTo(beforeHabitRevision);
		assertThat(jdbc.queryForObject("SELECT revision FROM hub_note_user_revision WHERE owner_id=?", Long.class, TEST_OWNER)).isEqualTo(beforeNoteRevision);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_user_preference WHERE owner_id=?", Integer.class, TEST_OWNER)).isEqualTo(beforePreferenceRows);
		assertThat(jdbc.queryForObject("SELECT COALESCE(MAX(revision),0) FROM hub_user_preference WHERE owner_id=?", Long.class, TEST_OWNER)).isEqualTo(beforePreferenceRevision);
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

	private Map<String, byte[]> tamperSection(Map<String, byte[]> original, String field, Object value) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>(original);
		Map<String, Object> manifest = mapper.readValue(entries.get("manifest.json"), new tools.jackson.core.type.TypeReference<>() {});
		@SuppressWarnings("unchecked") List<Map<String, Object>> sections = (List<Map<String, Object>>) manifest.get("sections");
		sections.get(0).put(field, value);
		entries.put("manifest.json", mapper.writeValueAsBytes(manifest));
		return entries;
	}

	private byte[] rewriteZip(Map<String, byte[]> entries) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return output.toByteArray();
	}

	private byte[] replaceZipEntryName(byte[] zip, String oldName, String newName) {
		byte[] source = oldName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] target = newName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		if (source.length != target.length) throw new IllegalArgumentException("ZIP entry replacement must keep the same UTF-8 length.");
		byte[] result = zip.clone();
		for (int index = 0; index <= result.length - source.length;) {
			int matched = 0;
			while (matched < source.length && result[index + matched] == source[matched]) matched++;
			if (matched == source.length) {
				System.arraycopy(target, 0, result, index, target.length);
				index += source.length;
			} else index++;
		}
		return result;
	}

	private long stagingDirectoryCount() throws Exception {
		Path temp = Path.of(System.getProperty("java.io.tmpdir"));
		try (var files = Files.list(temp)) {
			return files.filter(Files::isDirectory).map(path -> path.getFileName().toString())
				.filter(name -> name.startsWith("hub-copy-assets-")).count();
		}
	}

	private byte[] oversizedExpandedV12Package() throws Exception {
		long tasksSize = 100L * 1024 * 1024 + 1;
		Map<String, byte[]> sections = new LinkedHashMap<>();
		for (String name : List.of("series", "readingItems", "authors", "habits", "focusSessions", "notes")) sections.put(name, "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		sections.put("preferences", mapper.writeValueAsBytes(preferences(true, true, false)));
		List<Map<String, Object>> metadata = new ArrayList<>();
		metadata.add(Map.of("name", "tasks", "path", "data/tasks.json", "count", 0, "size", tasksSize, "sha256", hugeWhitespaceArrayDigest(tasksSize)));
		for (Map.Entry<String, byte[]> section : sections.entrySet()) {
			boolean isPreferences = section.getKey().equals("preferences");
			metadata.add(Map.of("name", section.getKey(), "path", "data/" + section.getKey() + ".json", "count", isPreferences ? 1 : 0,
				"size", section.getValue().length, "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(section.getValue()))));
		}
		byte[] manifest = mapper.writeValueAsBytes(Map.of("formatVersion", 12, "generatedAt", "2026-10-31T10:00:00Z", "sections", metadata, "assets", List.of()));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			putZipEntry(zip, "manifest.json", manifest);
			zip.putNextEntry(new java.util.zip.ZipEntry("data/tasks.json"));
			zip.write('[');
			byte[] spaces = new byte[8192];
			java.util.Arrays.fill(spaces, (byte) ' ');
			long remaining = tasksSize - 2;
			while (remaining > 0) {
				int count = (int) Math.min(spaces.length, remaining);
				zip.write(spaces, 0, count);
				remaining -= count;
			}
			zip.write(']');
			zip.closeEntry();
			for (Map.Entry<String, byte[]> section : sections.entrySet()) putZipEntry(zip, "data/" + section.getKey() + ".json", section.getValue());
		}
		return output.toByteArray();
	}

	private String hugeWhitespaceArrayDigest(long size) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update((byte) '[');
		byte[] spaces = new byte[8192];
		java.util.Arrays.fill(spaces, (byte) ' ');
		long remaining = size - 2;
		while (remaining > 0) {
			int count = (int) Math.min(spaces.length, remaining);
			digest.update(spaces, 0, count);
			remaining -= count;
		}
		digest.update((byte) ']');
		return HexFormat.of().formatHex(digest.digest());
	}

	private void putZipEntry(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
		zip.putNextEntry(new java.util.zip.ZipEntry(path));
		zip.write(bytes);
		zip.closeEntry();
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

	private byte[] v12Package(Instant generatedAt, List<BackupReadingItem> readingItems, List<BackupNote> notes,
			Map<String, Path> assets) throws Exception {
		return v12Package(generatedAt, List.of(), readingItems, notes, assets, preferences(true, true, false));
	}

	private byte[] v12Package(Instant generatedAt, List<BackupTask> taskItems, List<BackupReadingItem> readingItems,
			List<BackupNote> notes, Map<String, Path> assets, Map<String, Object> preferences) throws Exception {
		Map<String, byte[]> sections = new LinkedHashMap<>();
		sections.put("tasks", mapper.writeValueAsBytes(taskItems));
		sections.put("series", mapper.writeValueAsBytes(List.of()));
		sections.put("readingItems", mapper.writeValueAsBytes(readingItems));
		sections.put("authors", mapper.writeValueAsBytes(List.of()));
		sections.put("habits", mapper.writeValueAsBytes(List.of()));
		sections.put("focusSessions", mapper.writeValueAsBytes(List.of()));
		sections.put("notes", mapper.writeValueAsBytes(notes));
		sections.put("preferences", mapper.writeValueAsBytes(preferences));
		byte[] manifest = archiveCodec.manifestBytes(generatedAt.toString(), sections, assets);
		Path archive = Files.createTempFile("hub-v12-test-", ".zip");
		try {
			archiveCodec.write(archive, manifest, sections, assets);
			return Files.readAllBytes(archive);
		} finally { Files.deleteIfExists(archive); }
	}

	private Map<String, Object> preferences(boolean weeklySummaryVisible, boolean videoDownloaderCardVisible, boolean sidebarPinned) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("weeklySummaryVisible", weeklySummaryVisible);
		values.put("videoDownloaderCardVisible", videoDownloaderCardVisible);
		values.put("sidebarPinned", sidebarPinned);
		values.put("taskDefaults", Map.of("category", "Geral", "color", "#A986FF", "priority", "NORMAL"));
		values.put("calendarPresentations", Map.of("day", "", "week", "", "month", "", "year", ""));
		return values;
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
		return new String(unzip(exportPackage()).get("manifest.json"), java.nio.charset.StandardCharsets.UTF_8);
	}

	private void assertTaskTenantSchemaReady() {
		MigrationDiagnostic diagnostic = new TransactionTemplate(transactionManager).execute(status -> {
			String database = jdbc.queryForObject("SELECT CURRENT_CATALOG() || '/' || CURRENT_SCHEMA()", String.class);
			List<String> primaryKey = jdbc.query("SELECT kcu.column_name FROM information_schema.table_constraints tc "
				+ "JOIN information_schema.key_column_usage kcu ON tc.constraint_catalog=kcu.constraint_catalog "
				+ "AND tc.constraint_schema=kcu.constraint_schema AND tc.constraint_name=kcu.constraint_name "
				+ "AND kcu.table_schema=tc.table_schema AND kcu.table_name=tc.table_name "
				+ "WHERE UPPER(tc.table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(tc.table_name)='HUB_TASK' "
				+ "AND tc.constraint_type='PRIMARY KEY' ORDER BY kcu.ordinal_position",
				(rs, row) -> rs.getString(1), new Object[0]);
			List<String> migrations = jdbc.query("SELECT migration_id FROM hub_schema_migration "
				+ "WHERE migration_id IN (?, ?, ?) ORDER BY migration_id",
				(rs, row) -> rs.getString(1), AuthOwnershipMigration.V1, AuthOwnershipMigration.V2, AuthOwnershipMigration.V3);
			return new MigrationDiagnostic(database, primaryKey, migrations, ownershipMigration.isReady());
		});
		assertThat(diagnostic.primaryKey()).as("DB/schema=%s; migrations=%s; ready=%s",
			diagnostic.database(), diagnostic.migrations(), diagnostic.ready()).containsExactly("OWNER_ID", "ID");
		assertThat(diagnostic.migrations()).as("DB/schema=%s; PK=%s", diagnostic.database(), diagnostic.primaryKey())
			.containsExactlyInAnyOrder(AuthOwnershipMigration.V1, AuthOwnershipMigration.V2, AuthOwnershipMigration.V3);
		assertThat(diagnostic.ready()).as("DB/schema=%s; PK=%s; migrations=%s",
			diagnostic.database(), diagnostic.primaryKey(), diagnostic.migrations()).isTrue();
	}

	private record MigrationDiagnostic(String database, List<String> primaryKey, List<String> migrations, boolean ready) {}

	private byte[] exportPackage() throws Exception {
		MvcResult started = mvc.perform(get(BASE + "/export"))
			.andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
		MvcResult response = mvc.performAsyncDispatch(started).andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/zip"))
			.andReturn();
		return response.getResponse().getContentAsByteArray();
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

	private void clearOwnerPortableRows() {
		clearOwnerPortableRows(TEST_OWNER);
	}

	private void clearOwnerPortableRows(String ownerId) {
		jdbc.update("DELETE FROM hub_task_checklist_item WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_task_checklist_revision WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_task WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_task_series WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_reading_item_tag WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_reading_item_author WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_reading_item WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_reading_author WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_note_tag WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_note WHERE owner_id=?", ownerId);
		jdbc.update("UPDATE hub_focus_active_slot SET session_id=NULL WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_focus_session WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_habit_checkin WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_habit_pause WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_habit_schedule WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_habit WHERE owner_id=?", ownerId);
		jdbc.update("UPDATE hub_note_user_revision SET revision=0 WHERE owner_id=?", ownerId);
		jdbc.update("MERGE INTO hub_habit_user_revision(owner_id, revision) KEY(owner_id) VALUES(?, 0)", ownerId);
	}

	private void deleteOwnerPortableData(String ownerId) {
		clearOwnerPortableRows(ownerId);
		jdbc.update("DELETE FROM hub_focus_active_slot WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_user_preference WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_task_user_settings WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_task_user_revision WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_reading_user_revision WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_note_user_revision WHERE owner_id=?", ownerId);
		jdbc.update("DELETE FROM hub_habit_user_revision WHERE owner_id=?", ownerId);
	}

	private void assertOwnerPortableRowsEmpty() {
		for (String table : List.of("hub_task_checklist_item", "hub_task_checklist_revision", "hub_task", "hub_task_series",
				"hub_reading_item_tag", "hub_reading_item_author", "hub_reading_item", "hub_reading_author", "hub_note_tag",
				"hub_note", "hub_focus_session", "hub_habit_checkin", "hub_habit_pause", "hub_habit_schedule", "hub_habit")) {
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE owner_id=?", Integer.class, TEST_OWNER))
				.as("%s rows for test owner", table).isZero();
		}
	}
}
