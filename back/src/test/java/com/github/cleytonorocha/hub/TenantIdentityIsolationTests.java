package com.github.cleytonorocha.hub;

import com.github.cleytonorocha.hub.dto.TaskChecklistDtos.ChecklistItem;
import com.github.cleytonorocha.hub.dto.TaskChecklistDtos.SaveChecklistRequest;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.Confirmation;
import com.github.cleytonorocha.hub.entity.ReadingAuthor;
import com.github.cleytonorocha.hub.entity.ReadingItem;
import com.github.cleytonorocha.hub.entity.ReadingItemAuthorLink;
import com.github.cleytonorocha.hub.entity.ReadingItemTag;
import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.entity.TaskSeries;
import com.github.cleytonorocha.hub.repository.ReadingAuthorRepository;
import com.github.cleytonorocha.hub.repository.ReadingItemRepository;
import com.github.cleytonorocha.hub.repository.TaskRepository;
import com.github.cleytonorocha.hub.repository.TaskSeriesRepository;
import com.github.cleytonorocha.hub.security.HubPrincipal;
import com.github.cleytonorocha.hub.service.TaskChecklistService;
import com.github.cleytonorocha.hub.service.TaskBackupService;
import com.github.cleytonorocha.hub.service.TaskBackupService.PortableCopy;
import com.github.cleytonorocha.hub.service.ReadingItemAssociationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Release gate for the selected Hibernate identity model: public String IDs
 * may repeat across owners, while every persistence context remains tenant
 * bound. This uses a disposable in-memory H2 database and separate transactions.
 */
@SpringBootTest(properties = {
	"spring.sql.init.mode=always",
	"spring.sql.init.schema-locations=classpath:schema.sql",
	"spring.jpa.hibernate.ddl-auto=none",
	"logging.level.org.springframework.jdbc.datasource.init.ScriptUtils=DEBUG",
	"logging.level.org.springframework.boot.sql.init=DEBUG"
})
class TenantIdentityIsolationTests {
	private static final String TEST_DATABASE_URL = "jdbc:h2:mem:tenant-identity-proof-" + UUID.randomUUID()
		+ ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
	private static final String OWNER_A = "tenant-a";
	private static final String OWNER_B = "tenant-b";
	private static final String TASK_ID = "same-task-id";
	private static final String SERIES_ID = "same-series-id";
	private static final String ITEM_ID = "same-reading-id";
	private static final String AUTHOR_ID = "same-author-id";
	private static final String CHECKLIST_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
	private static final String PORTABLE_COPY_PERMISSIONS = "TASK_READ,TASK_WRITE,READING_READ,READING_WRITE,HABITS_READ,HABITS_WRITE,FOCUS_READ,FOCUS_WRITE,NOTES_READ,NOTES_WRITE";

	@Autowired JdbcTemplate jdbc;
	@Autowired Environment environment;
	@Autowired TaskRepository tasks;
	@Autowired TaskSeriesRepository series;
	@Autowired ReadingItemRepository readingItems;
	@Autowired ReadingAuthorRepository authors;
	@Autowired TaskChecklistService checklists;
	@Autowired TaskBackupService backups;
	@Autowired ReadingItemAssociationService readingAssociations;
	@Autowired PlatformTransactionManager transactionManager;
	@PersistenceContext EntityManager entityManager;

	@DynamicPropertySource
	static void isolatedDatabase(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
	}

	@Test
	void repeatedIdsRemainIndependentAcrossConcurrentTenantTransactions() throws Exception {
		System.out.println("TENANT_TEST_PROPERTIES=" + java.util.Map.of(
			"spring.datasource.url", String.valueOf(environment.getProperty("spring.datasource.url")),
			"spring.sql.init.mode", String.valueOf(environment.getProperty("spring.sql.init.mode")),
			"spring.sql.init.schema-locations", String.valueOf(environment.getProperty("spring.sql.init.schema-locations")),
			"spring.jpa.hibernate.ddl-auto", String.valueOf(environment.getProperty("spring.jpa.hibernate.ddl-auto"))));
		var classpathSchemas = Thread.currentThread().getContextClassLoader().getResources("schema.sql");
		while (classpathSchemas.hasMoreElements()) {
			URL url = classpathSchemas.nextElement();
			String script = new String(url.openStream().readAllBytes(), StandardCharsets.UTF_8);
			int start = script.indexOf("CREATE TABLE IF NOT EXISTS hub_task (");
			int end = start < 0 ? -1 : script.indexOf(");", start);
			System.out.println("TENANT_TEST_CLASSPATH_SCHEMA=" + url + " HUB_TASK_DDL="
				+ (start < 0 || end < 0 ? "not found" : script.substring(start, end + 2).replaceAll("\\s+", " ")));
		}
		System.out.println("TENANT_TEST_DATABASE_SCHEMA=" + jdbc.queryForObject("SELECT DATABASE() || '/' || CURRENT_SCHEMA()", String.class));
		List<String> taskPrimaryKey = jdbc.query("SELECT kcu.column_name FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu ON tc.constraint_catalog=kcu.constraint_catalog AND tc.constraint_schema=kcu.constraint_schema AND tc.constraint_name=kcu.constraint_name WHERE UPPER(tc.table_name)='HUB_TASK' AND tc.constraint_type='PRIMARY KEY' ORDER BY kcu.ordinal_position",
			(rs, row) -> rs.getString(1));
		System.out.println("TENANT_TEST_HUB_TASK_PRIMARY_KEY=" + taskPrimaryKey);
		assertThat(taskPrimaryKey).containsExactly("OWNER_ID", "ID");
		seedOwners();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch inserted = new CountDownLatch(2);
		CountDownLatch readStart = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<?> a = executor.submit(() -> lifecycle(OWNER_A, "A", ready, start, inserted, readStart));
			Future<?> b = executor.submit(() -> lifecycle(OWNER_B, "B", ready, start, inserted, readStart));
			ready.await();
			start.countDown();
			inserted.await();
			readStart.countDown();
			a.get();
			b.get();
		}

		ownerTransaction(OWNER_A, () -> {
			assertThat(tasks.findById(TASK_ID)).isPresent().get().extracting(Task::getNotes).isEqualTo("updated-A");
			assertThat(series.findById(SERIES_ID)).isPresent().get().extracting(TaskSeries::isEnded).isEqualTo(true);
			assertThat(readingItems.findById(ITEM_ID)).isPresent().get().extracting(ReadingItem::getTitle).isEqualTo("reading-A-updated");
			assertThat(authors.findById(AUTHOR_ID)).isPresent().get().extracting(ReadingAuthor::getName).isEqualTo("author-A");
			assertThat(entityManager.find(ReadingItemTag.class, new ReadingItemTag.Key(ITEM_ID, 0)).getTag()).isEqualTo("tag-A");
			assertThat(entityManager.find(ReadingItemAuthorLink.class, new ReadingItemAuthorLink.Key(ITEM_ID, AUTHOR_ID))).isNotNull();
			assertThat(checklists.get(TASK_ID).items()).containsExactly(new ChecklistItem(CHECKLIST_ID, "check-A", false, 0));
			entityManager.clear();
			SecurityContext ownerContext = SecurityContextHolder.getContext();
			setOwnerContext(OWNER_B);
			try {
				// A live Session must remain bound to its original tenant even if a
				// caller accidentally changes SecurityContext mid-transaction.
				assertThat(tasks.findById(TASK_ID)).isPresent().get().extracting(Task::getNotes).isEqualTo("updated-A");
			} finally { SecurityContextHolder.setContext(ownerContext); }
			return null;
		});
		ownerTransaction(OWNER_B, () -> {
			assertThat(tasks.findById(TASK_ID)).isEmpty(); // B deleted its own row.
			assertThat(series.findById(SERIES_ID)).isPresent().get().extracting(TaskSeries::isEnded).isEqualTo(true);
			assertThat(readingItems.findById(ITEM_ID)).isPresent().get().extracting(ReadingItem::getTitle).isEqualTo("reading-B-updated");
			assertThat(authors.findById(AUTHOR_ID)).isPresent().get().extracting(ReadingAuthor::getName).isEqualTo("author-B");
			assertThat(entityManager.find(ReadingItemTag.class, new ReadingItemTag.Key(ITEM_ID, 0)).getTag()).isEqualTo("tag-B");
			assertThat(entityManager.find(ReadingItemAuthorLink.class, new ReadingItemAuthorLink.Key(ITEM_ID, AUTHOR_ID))).isNotNull();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_task_checklist_item WHERE owner_id=?", Integer.class, OWNER_B)).isZero();
			return null;
		});
	}

	@Test
	void portableCopyCanBeImportedIndependentlyAndPreviewTokensBelongToTheirOwner() throws Exception {
		String sourceOwner = UUID.randomUUID().toString();
		String targetOwner = UUID.randomUUID().toString();
		seedOwners(sourceOwner, targetOwner);
		String taskId = UUID.randomUUID().toString();
		String checklistTaskId = UUID.randomUUID().toString();
		String seriesId = UUID.randomUUID().toString();
		String itemId = UUID.randomUUID().toString();
		String authorId = UUID.randomUUID().toString();
		String noteId = UUID.randomUUID().toString();
		String checklistId = UUID.randomUUID().toString();
		assertThatThrownBy(() -> ownerTransaction(sourceOwner, backups::exportPortableCopy))
			.isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
			.hasMessageContaining("Seu perfil não pode exportar ou importar uma cópia completa dos próprios dados.");
		Path archive = ownerTransaction(sourceOwner, "TASK_READ,TASK_WRITE,READING_READ,HABITS_READ,FOCUS_READ,NOTES_READ", () -> {
			TaskSeries localSeries = TaskSeries.restore(seriesId, "Series portátil", LocalDate.of(2026, 11, 1), null, 3, "UTC", false);
			series.saveAndFlush(localSeries);
			tasks.saveAndFlush(Task.restoreLocal(taskId, "Tarefa portátil", LocalDate.of(2026, 11, 3), null, false,
				"Pessoal", "#A986FF", Task.Priority.NORMAL.name(), "notas", seriesId, 1));
			tasks.saveAndFlush(Task.restoreLocal(checklistTaskId, "Tarefa com checklist", LocalDate.of(2026, 11, 4), null, false,
				"Pessoal", "#A986FF", Task.Priority.NORMAL.name(), "", null, null));
			ReadingAuthor author = authors.saveAndFlush(ReadingAuthor.restore(authorId, "Autora portátil"));
			ReadingItem item = ReadingItem.restore(itemId, "Leitura portátil", "BOOK", null, null,
				"TO_READ", "", "nota", List.of("tag-portable"), false, Instant.now(), Instant.now());
			readingItems.saveAndFlush(item);
			item.replaceAuthors(Set.of(author), Instant.now());
			readingAssociations.save(item);
			checklists.save(checklistTaskId, new SaveChecklistRequest(0, List.of(new ChecklistItem(checklistId, "Etapa portátil", false, 0))));
			jdbc.update("INSERT INTO hub_note(id,owner_id,title,body,pinned,archived,related_task_id,related_task_unavailable,revision,created_at,updated_at) VALUES(?,?,?,'Nota portátil',FALSE,FALSE,?,FALSE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", noteId, sourceOwner, "Nota vinculada", taskId);
			jdbc.update("INSERT INTO hub_note_tag(owner_id,note_id,tag_order,tag,normalized_tag) VALUES(?,?,0,'cópia','copia')", sourceOwner, noteId);
			PortableCopy copy = backups.exportPortableCopy();
			return copy.archivePath();
		});
		try {
		String sourcePreviewToken = ownerTransaction(sourceOwner, PORTABLE_COPY_PERMISSIONS, () -> backups.preview(archive).token());
		assertThatThrownBy(() -> ownerTransaction(targetOwner, PORTABLE_COPY_PERMISSIONS, () -> backups.confirm(archive, sourcePreviewToken)))
			.isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

		var firstPreview = ownerTransaction(targetOwner, PORTABLE_COPY_PERMISSIONS, () -> backups.preview(archive));
		assertThat(firstPreview.newTasks()).isEqualTo(2);
		assertThat(firstPreview.newReadingItems()).isEqualTo(1);
		assertThat(firstPreview.newAuthors()).isEqualTo(1);
		assertThat(firstPreview.notes().newNotes()).isEqualTo(1);
		Confirmation imported = ownerTransaction(targetOwner, PORTABLE_COPY_PERMISSIONS, () -> backups.confirm(archive, firstPreview.token()));
		assertThat(imported.status()).isEqualTo("IMPORTED");
		assertThat(imported.addedTasks()).isEqualTo(2);
		assertThat(imported.addedReadingItems()).isEqualTo(1);
		assertThat(imported.addedNotes()).isEqualTo(1);

		Confirmation repeated = ownerTransaction(targetOwner, PORTABLE_COPY_PERMISSIONS, () -> {
			var preview = backups.preview(archive);
			return backups.confirm(archive, preview.token());
		});
		assertThat(repeated.status()).isEqualTo("IMPORTED");
		assertThat(repeated.addedTasks()).isZero();
		assertThat(repeated.addedReadingItems()).isZero();
		assertThat(repeated.addedNotes()).isZero();
		assertThat(repeated.identicalTasks()).isEqualTo(2);
		assertThat(repeated.identicalNotes()).isEqualTo(1);

		Path conflictingArchive = rewritePortableManifestTitle(archive, "Leitura portátil", "Leitura alterada");
		try {
			var conflictingPreview = ownerTransaction(targetOwner, PORTABLE_COPY_PERMISSIONS, () -> backups.preview(conflictingArchive));
			assertThat(conflictingPreview.conflictingReadingItems()).isEqualTo(1);
			assertThat(conflictingPreview.readingConflictIds()).containsExactly(itemId);
			assertThatThrownBy(() -> ownerTransaction(targetOwner, PORTABLE_COPY_PERMISSIONS,
				() -> backups.confirm(conflictingArchive, conflictingPreview.token())))
				.isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
		} finally { java.nio.file.Files.deleteIfExists(conflictingArchive); }

		ownerTransaction(sourceOwner, () -> {
			assertThat(tasks.findById(taskId)).isPresent().get().extracting(Task::getTitle).isEqualTo("Tarefa portátil");
			assertThat(tasks.countBySeriesId(seriesId)).isEqualTo(1);
			assertThat(checklists.get(checklistTaskId).items()).containsExactly(new ChecklistItem(checklistId, "Etapa portátil", false, 0));
			assertThat(readingItems.findById(itemId)).isPresent().get().extracting(ReadingItem::getTitle).isEqualTo("Leitura portátil");
			return null;
		});
		ownerTransaction(targetOwner, () -> {
			assertThat(tasks.findById(taskId)).isPresent().get().extracting(Task::getTitle).isEqualTo("Tarefa portátil");
			assertThat(tasks.countBySeriesId(seriesId)).isEqualTo(1);
			assertThat(checklists.get(checklistTaskId).items()).containsExactly(new ChecklistItem(checklistId, "Etapa portátil", false, 0));
			assertThat(readingItems.findById(itemId)).isPresent().get().extracting(ReadingItem::getTitle).isEqualTo("Leitura portátil");
			assertThat(authors.findById(authorId)).isPresent().get().extracting(ReadingAuthor::getName).isEqualTo("Autora portátil");
			assertThat(jdbc.queryForObject("SELECT related_task_id FROM hub_note WHERE owner_id=? AND id=?", String.class, targetOwner, noteId)).isEqualTo(taskId);
			assertThat(jdbc.queryForObject("SELECT related_task_unavailable FROM hub_note WHERE owner_id=? AND id=?", Boolean.class, targetOwner, noteId)).isFalse();
			return null;
		});
		} finally { java.nio.file.Files.deleteIfExists(archive); }
	}

	private Path rewritePortableManifestTitle(Path source, String title, String replacement) throws java.io.IOException {
		Path destination = java.nio.file.Files.createTempFile("hub-portable-conflict-", ".zip");
		try (var input = new java.util.zip.ZipInputStream(java.nio.file.Files.newInputStream(source));
			var output = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(destination))) {
			java.util.zip.ZipEntry entry;
			boolean replaced = false;
			while ((entry = input.getNextEntry()) != null) {
				byte[] bytes = input.readAllBytes();
				if (entry.getName().equals("manifest.json")) {
					String json = new String(bytes, StandardCharsets.UTF_8);
					String updated = json.replace("\"title\":\"" + title + "\"", "\"title\":\"" + replacement + "\"");
					if (updated.equals(json)) throw new IllegalStateException("The portable manifest title was not changed.");
					bytes = updated.getBytes(StandardCharsets.UTF_8);
					replaced = true;
				}
				output.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
				output.write(bytes);
				output.closeEntry();
				input.closeEntry();
			}
			if (!replaced) throw new IllegalStateException("The portable manifest is missing.");
			return destination;
		} catch (RuntimeException | java.io.IOException error) {
			java.nio.file.Files.deleteIfExists(destination);
			throw error;
		}
	}

	private void lifecycle(String owner, String suffix, CountDownLatch ready, CountDownLatch start,
			CountDownLatch inserted, CountDownLatch readStart) {
		ready.countDown();
		await(start);
		try { ownerTransaction(owner, () -> {
			tasks.saveAndFlush(Task.restoreLocal(TASK_ID, "task-" + suffix, null, null, false,
				"Geral", "#A986FF", Task.Priority.NORMAL, ""));
			series.saveAndFlush(TaskSeries.restore(SERIES_ID, "series-" + suffix, LocalDate.of(2026, 1, 1),
				null, 2, "UTC", false));
			readingItems.saveAndFlush(ReadingItem.restore(ITEM_ID, "reading-" + suffix, "BOOK", null,
				null, "TO_READ", "", "", List.of(), false, Instant.now(), Instant.now()));
			authors.saveAndFlush(ReadingAuthor.restore(AUTHOR_ID, "author-" + suffix));
			entityManager.persist(new ReadingItemTag(ITEM_ID, 0, "tag-" + suffix));
			entityManager.persist(new ReadingItemAuthorLink(ITEM_ID, AUTHOR_ID));
			entityManager.flush();
			entityManager.clear();
			return null;
		}); } finally { inserted.countDown(); }
		await(readStart);
		ownerTransaction(owner, () -> {

			assertThat(tasks.findById(TASK_ID)).isPresent().get().extracting(Task::getTitle).isEqualTo("task-" + suffix);
			assertThat(tasks.findAll()).extracting(Task::getId).containsExactly(TASK_ID);
			Task task = tasks.findById(TASK_ID).orElseThrow();
			task.update(null, null, false, null, null, null, "updated-" + suffix);
			tasks.saveAndFlush(task);
			TaskSeries foundSeries = series.findById(SERIES_ID).orElseThrow();
			foundSeries.end();
			series.saveAndFlush(foundSeries);
			ReadingItem item = readingItems.findById(ITEM_ID).orElseThrow();
			item.update("reading-" + suffix + "-updated", "BOOK", null, null, "IN_PROGRESS", "", null,
				null, "", List.of(), false, Instant.now());
			readingItems.saveAndFlush(item);
			assertThat(entityManager.find(ReadingItemTag.class, new ReadingItemTag.Key(ITEM_ID, 0)).getTag()).isEqualTo("tag-" + suffix);
			assertThat(entityManager.find(ReadingItemAuthorLink.class, new ReadingItemAuthorLink.Key(ITEM_ID, AUTHOR_ID))).isNotNull();
			checklists.save(TASK_ID, new SaveChecklistRequest(0,
				List.of(new ChecklistItem(CHECKLIST_ID, "check-" + suffix, false, 0))));
			entityManager.flush();
			entityManager.clear();
			assertThat(checklists.get(TASK_ID).items()).containsExactly(new ChecklistItem(CHECKLIST_ID, "check-" + suffix, false, 0));
			if (owner.equals(OWNER_B)) {
				tasks.deleteById(TASK_ID);
				tasks.flush();
			}
			return null;
		});
	}

	private void seedOwners() {
		seedOwners(OWNER_A, OWNER_B);
	}

	private void seedOwners(String ownerA, String ownerB) {
		String profile = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO hub_permission_profile(id,name,normalized_name,permissions,system_profile) VALUES(?,?,?,?,FALSE)",
			profile, "Tenant test", "tenant-test-" + profile, "TASK_READ,TASK_WRITE");
		for (String owner : List.of(ownerA, ownerB)) {
			String email = owner + "@example.test";
			jdbc.update("INSERT INTO hub_user(id,display_name,email,normalized_email,password_hash,profile_id,enabled,created_at) VALUES(?,?,?,?,?,?,TRUE,CURRENT_TIMESTAMP)",
				owner, owner, email, email, "unused", profile);
			jdbc.update("INSERT INTO hub_task_user_revision(owner_id,revision) VALUES(?,0)", owner);
			jdbc.update("INSERT INTO hub_reading_user_revision(owner_id,revision) VALUES(?,0)", owner);
			jdbc.update("INSERT INTO hub_task_user_settings(owner_id,reminder_time) VALUES(?,TIME '09:00')", owner);
		}
	}

	private <T> T ownerTransaction(String owner, Supplier<T> action) {
		return ownerTransaction(owner, "TASK_READ,TASK_WRITE", action);
	}

	private <T> T ownerTransaction(String owner, String permissions, Supplier<T> action) {
		SecurityContext previous = SecurityContextHolder.getContext();
		setOwnerContext(owner, permissions);
		try { return new TransactionTemplate(transactionManager).execute(status -> action.get()); }
		finally { SecurityContextHolder.setContext(previous); }
	}

	private static void setOwnerContext(String owner) {
		setOwnerContext(owner, "TASK_READ,TASK_WRITE");
	}

	private static void setOwnerContext(String owner, String permissions) {
		var context = SecurityContextHolder.createEmptyContext();
		HubPrincipal principal = new HubPrincipal(owner, owner, owner + "@example.test", "unused", true,
			"profile", "Tenant test", permissions);
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, "unused", principal.getAuthorities()));
		SecurityContextHolder.setContext(context);
	}

	private static void await(CountDownLatch latch) {
		try { latch.await(); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
	}
}
