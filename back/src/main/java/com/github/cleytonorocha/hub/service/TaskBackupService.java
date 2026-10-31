package com.github.cleytonorocha.hub.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupChecklistItem;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupAuthor;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupHabit;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupHabitPause;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupHabitSchedule;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupFocusSession;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupFile;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupNote;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupReadingItem;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupSeries;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupTask;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.Confirmation;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.Preview;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.NoteSummary;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.ReadingItemSample;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.TaskSample;
import com.github.cleytonorocha.hub.entity.ReadingItem;
import com.github.cleytonorocha.hub.entity.ReadingAuthor;
import com.github.cleytonorocha.hub.entity.Note;
import com.github.cleytonorocha.hub.entity.Habit;
import com.github.cleytonorocha.hub.entity.HabitCheckIn;
import com.github.cleytonorocha.hub.entity.HabitPause;
import com.github.cleytonorocha.hub.entity.HabitSchedule;
import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.entity.TaskSeries;
import com.github.cleytonorocha.hub.repository.ReadingItemRepository;
import com.github.cleytonorocha.hub.repository.ReadingAuthorRepository;
import com.github.cleytonorocha.hub.repository.HabitCheckInRepository;
import com.github.cleytonorocha.hub.repository.HabitPauseRepository;
import com.github.cleytonorocha.hub.repository.HabitRepository;
import com.github.cleytonorocha.hub.repository.HabitScheduleRepository;
import com.github.cleytonorocha.hub.repository.FocusSessionRepository;
import com.github.cleytonorocha.hub.repository.NoteRepository;
import com.github.cleytonorocha.hub.repository.TaskRepository;
import com.github.cleytonorocha.hub.repository.TaskSeriesRepository;
import com.github.cleytonorocha.hub.security.AuthContext;
import com.github.cleytonorocha.hub.service.ReadingCoverStorageService.StoredCover;

import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskBackupService {
	public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
	public static final int MAX_MANIFEST_BYTES = 90 * 1024 * 1024;
	public static final long MAX_PACKAGE_BYTES = 100L * 1024 * 1024;
	public static final long MAX_PACKAGE_EXPANDED_BYTES = 100L * 1024 * 1024;
	public static final int MAX_TASKS = 5_000;
	public static final int MAX_READING_ITEMS = 5_000;
	public static final int MAX_HABITS = 5_000;
	public static final int MAX_FOCUS_SESSIONS = 50_000;
	public static final int MAX_NOTES = 5_000;
	private static final int FORMAT_VERSION = 11;
	private static final int LEGACY_FORMAT_VERSION = 4;
	private static final Set<String> ROOT_FIELDS_V1 = Set.of("formatVersion", "generatedAt", "tasks");
	private static final Set<String> ROOT_FIELDS_V2_V3 = Set.of("formatVersion", "generatedAt", "tasks", "series");
	private static final Set<String> ROOT_FIELDS_V4 = Set.of("formatVersion", "generatedAt", "tasks", "series", "readingItems");
	private static final Set<String> ROOT_FIELDS_V5 = ROOT_FIELDS_V4;
	private static final Set<String> ROOT_FIELDS_V7 = Set.of("formatVersion", "generatedAt", "tasks", "series", "readingItems", "authors");
	private static final Set<String> ROOT_FIELDS_V8 = Set.of("formatVersion", "generatedAt", "tasks", "series", "readingItems", "authors", "habits");
	private static final Set<String> ROOT_FIELDS_V9 = Set.of("formatVersion", "generatedAt", "tasks", "series", "readingItems", "authors", "habits", "focusSessions");
	private static final Set<String> ROOT_FIELDS_V10 = Set.of("formatVersion", "generatedAt", "tasks", "series", "readingItems", "authors", "habits", "focusSessions", "notes");
	private static final Set<String> ROOT_FIELDS_V11 = Set.of("formatVersion", "generatedAt", "tasks", "series", "readingItems", "authors", "habits", "focusSessions", "notes", "omittedSections");
	private static final List<String> OMITTABLE_SECTION_ORDER = List.of("habits", "focusSessions", "notes");
	private static final Set<String> OMITTABLE_SECTIONS = Set.copyOf(OMITTABLE_SECTION_ORDER);
	private static final Set<String> HABIT_FIELDS = Set.of("id", "name", "startDate", "createdAt", "updatedAt", "schedules", "pauses", "checkIns");
	private static final Set<String> HABIT_SCHEDULE_FIELDS = Set.of("effectiveFrom", "weekdaysMask");
	private static final Set<String> HABIT_PAUSE_FIELDS = Set.of("pausedOn", "resumedOn");
	private static final Set<String> FOCUS_SESSION_FIELDS = Set.of("id", "objective", "plannedSeconds", "focusedSeconds", "state", "startedAt", "resumedAt", "pausedAt", "endedAt", "endReason", "taskId");
	private static final Set<String> NOTE_FIELDS = Set.of("id", "title", "body", "tags", "pinned", "archived", "relatedTaskId", "relatedTaskUnavailable", "createdAt", "updatedAt");
	private static final Set<String> TASK_FIELDS_V1 = Set.of("id", "title", "dueDate", "dueTime", "completed", "type", "category", "color", "priority", "notes");
	private static final Set<String> TASK_FIELDS_V2 = Set.of("id", "title", "dueDate", "dueTime", "completed", "type", "category", "color", "priority", "notes", "seriesId", "seriesPosition");
	private static final Set<String> TASK_FIELDS = Set.of("id", "title", "dueDate", "dueTime", "completed", "type", "category", "color", "priority", "notes", "seriesId", "seriesPosition", "checklist");
	private static final Set<String> SERIES_FIELDS = Set.of("id", "title", "startDate", "dueTime", "occurrenceCount", "timeZoneId", "ended");
	private static final Set<String> READING_FIELDS = Set.of("id", "title", "type", "authorOrSource", "url", "status", "progressMarker", "note", "tags", "archived", "createdAt", "updatedAt");
	private static final Set<String> READING_FIELDS_V5 = Set.of("id", "title", "type", "authorOrSource", "url", "status", "progressMarker", "note", "tags", "archived", "createdAt", "updatedAt", "coverSha256", "coverFormat", "coverSize");
	private static final Set<String> READING_FIELDS_V6 = Set.of("id", "title", "type", "authorOrSource", "url", "status", "progressMarker", "totalPages", "currentPage", "note", "tags", "archived", "createdAt", "updatedAt", "coverSha256", "coverFormat", "coverSize");
	private static final Set<String> READING_FIELDS_V6_NO_COVER = Set.of("id", "title", "type", "authorOrSource", "url", "status", "progressMarker", "totalPages", "currentPage", "note", "tags", "archived", "createdAt", "updatedAt");
	private static final Set<String> READING_FIELDS_V7 = Set.of("id", "title", "type", "authorOrSource", "url", "status", "progressMarker", "totalPages", "currentPage", "note", "tags", "archived", "createdAt", "updatedAt", "coverSha256", "coverFormat", "coverSize", "coverSourceUrl", "authorIds");
	private static final Set<String> READING_FIELDS_V7_LOCAL_COVER = Set.of("id", "title", "type", "authorOrSource", "url", "status", "progressMarker", "totalPages", "currentPage", "note", "tags", "archived", "createdAt", "updatedAt", "coverSha256", "coverFormat", "coverSize", "authorIds");
	private static final Set<String> READING_FIELDS_V7_NO_COVER = Set.of("id", "title", "type", "authorOrSource", "url", "status", "progressMarker", "totalPages", "currentPage", "note", "tags", "archived", "createdAt", "updatedAt", "authorIds");
	private final TaskRepository tasks;
	private final TaskSeriesRepository series;
	private final ReadingItemRepository readingItems;
	private final ReadingAuthorRepository authors;
	private final TaskChecklistService checklists;
	private final TaskCollectionRevision collectionRevision;
	private final ReadingCollectionRevision readingCollectionRevision;
	private final TaskImportPreviewStore previews;
	private final ObjectMapper mapper;
	private final ReadingCoverStorageService covers;
	private final ReadingItemAssociationService readingAssociations;
	private final HabitRepository habits;
	private final HabitScheduleRepository habitSchedules;
	private final HabitPauseRepository habitPauses;
	private final HabitCheckInRepository habitCheckIns;
	private final HabitCollectionRevision habitCollectionRevision;
	private final FocusSessionRepository focusSessions;
	private final NoteRepository notes;
	private final NoteService noteService;
	private final NoteCollectionRevision noteCollectionRevision;

	public TaskBackupService(TaskRepository tasks, TaskSeriesRepository series, ReadingItemRepository readingItems,
		TaskChecklistService checklists, TaskCollectionRevision collectionRevision, ReadingCollectionRevision readingCollectionRevision,
		TaskImportPreviewStore previews, ObjectMapper mapper, ReadingCoverStorageService covers, ReadingAuthorRepository authors,
		ReadingItemAssociationService readingAssociations, HabitRepository habits, HabitScheduleRepository habitSchedules,
		HabitPauseRepository habitPauses, HabitCheckInRepository habitCheckIns, HabitCollectionRevision habitCollectionRevision,
		FocusSessionRepository focusSessions, NoteRepository notes, NoteService noteService, NoteCollectionRevision noteCollectionRevision) {
		this.tasks = tasks;
		this.series = series;
		this.readingItems = readingItems;
		this.authors = authors;
		this.checklists = checklists;
		this.collectionRevision = collectionRevision;
		this.readingCollectionRevision = readingCollectionRevision;
		this.previews = previews;
		this.mapper = mapper;
		this.covers = covers;
		this.readingAssociations = readingAssociations;
		this.habits = habits;
		this.habitSchedules = habitSchedules;
		this.habitPauses = habitPauses;
		this.habitCheckIns = habitCheckIns;
		this.habitCollectionRevision = habitCollectionRevision;
		this.focusSessions = focusSessions;
		this.notes = notes;
		this.noteService = noteService;
		this.noteCollectionRevision = noteCollectionRevision;
	}

	@Transactional
	public PortableCopy exportPortableCopy() {
		if (!AuthContext.hasPermission("TASK_READ") || !AuthContext.hasPermission("READING_READ"))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "É necessário acesso de leitura em todas as áreas base para exportar a cópia.");
		collectionRevision.lock();
		readingCollectionRevision.lock();
		habitCollectionRevision.lock();
		noteCollectionRevision.lock();
		String ownerId = AuthContext.userId();
		Instant snapshotAt = Instant.now();
		List<String> omittedSections = new ArrayList<>();
		List<BackupFocusSession> portableFocusSessions = List.of();
		if (AuthContext.hasPermission("FOCUS_READ")) {
			focusSessions.lockOwnerSlot(ownerId);
			List<com.github.cleytonorocha.hub.dto.FocusSessionDtos.SessionView> localFocusSessions = focusSessions.allForCopy(ownerId);
			if (localFocusSessions.size() > MAX_FOCUS_SESSIONS) throw tooLarge("A cópia excede o limite de 50.000 sessões de foco.");
			portableFocusSessions = localFocusSessions.stream().map(session -> toBackupFocusSession(session, snapshotAt)).toList();
		} else omittedSections.add("focusSessions");
		List<Task> localTasks = tasks.findAllByTypeOrderByIdAsc(Task.Type.LOCAL);
		if (localTasks.size() > MAX_TASKS) throw tooLarge("A cópia excede o limite de 5.000 tarefas.");
		List<BackupSeries> localSeries = series.findAll().stream().map(this::toBackupSeries).toList();
		List<ReadingItem> localReadingItems = readingItems.findAllByOrderByIdAsc();
		if (localReadingItems.size() > MAX_READING_ITEMS) throw tooLarge("A cópia excede o limite de 5.000 itens de leitura.");
		localReadingItems = loadReadingItemsWithTags(localReadingItems);
		List<BackupReadingItem> portableReadings = localReadingItems.stream().map(item -> toBackupReadingItem(item, true, ownerId)).toList();
		List<BackupAuthor> portableAuthors = authors.findAllByOrderByNormalizedNameAscIdAsc().stream()
			.map(author -> new BackupAuthor(author.getId(), author.getName())).toList();
		List<BackupHabit> portableHabits = List.of();
		if (AuthContext.hasPermission("HABITS_READ")) portableHabits = exportHabits();
		else omittedSections.add("habits");
		List<BackupNote> portableNotes = List.of();
		if (AuthContext.hasPermission("NOTES_READ")) portableNotes = exportNotes(localTasks);
		else omittedSections.add("notes");
		omittedSections.sort(java.util.Comparator.comparingInt(OMITTABLE_SECTION_ORDER::indexOf));
		BackupFile file = new BackupFile(FORMAT_VERSION, Instant.now(), localTasks.stream().map(this::toBackupTask).toList(),
			localSeries, portableReadings, portableAuthors, portableHabits, portableFocusSessions, portableNotes, omittedSections);
		byte[] manifest;
		try { manifest = mapper.writeValueAsBytes(file); }
		catch (Exception error) { throw new IllegalStateException("Não foi possível preparar a cópia portátil do Hub."); }
		if (manifest.length > MAX_MANIFEST_BYTES) throw tooLarge("O manifesto da cópia excede 90 MiB e não foi exportado.");
		Path archive = null;
		try {
			archive = Files.createTempFile("hub-portable-copy-", ".zip");
			Map<String, ReadingItem> readingById = new LinkedHashMap<>();
			for (ReadingItem item : localReadingItems) readingById.put(item.getId(), item);
			long expandedSize = manifest.length;
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
				putZipEntry(zip, "manifest.json", manifest);
				for (BackupReadingItem item : portableReadings) {
					if (item.coverSha256() == null) continue;
					ReadingItem reading = readingById.get(item.id());
					byte[] image = covers.read(ownerId, reading.getCoverImageName(), false);
					if (image.length != item.coverSize() || !sha256(image).equals(item.coverSha256())) {
						throw new IllegalStateException("Uma imagem de capa mudou durante a exportação.");
					}
					expandedSize += image.length;
					if (expandedSize > MAX_PACKAGE_EXPANDED_BYTES) throw tooLarge("As imagens excedem 100 MiB descompactadas; nenhuma cópia foi exportada.");
					putZipEntry(zip, assetPath(item.id(), item.coverFormat()), image);
					if (Files.size(archive) > MAX_PACKAGE_BYTES) throw tooLarge("A cópia excede o limite de 100 MiB e não foi exportada.");
				}
			}
			long length = Files.size(archive);
			if (length > MAX_PACKAGE_BYTES) throw tooLarge("A cópia excede o limite de 100 MiB e não foi exportada.");
			return PortableCopy.zip(archive, length);
		} catch (ResponseStatusException error) {
			deleteTemporary(archive);
			throw error;
		} catch (Exception error) {
			deleteTemporary(archive);
			throw new IllegalStateException("Não foi possível preparar a cópia portátil completa do Hub.", error);
		}
	}

	@Transactional
	public Preview preview(byte[] content) {
		DecodedBackup decoded = decode(content);
		try { return preview(decoded); }
		finally { decoded.cleanup(); }
	}

	@Transactional
	public Preview preview(Path content) {
		DecodedBackup decoded = decode(content);
		try { return preview(decoded); }
		finally { decoded.cleanup(); }
	}

	private Preview preview(DecodedBackup decoded) {
		requireTaskImportPermission(decoded);
		requireReadingImportPermission(decoded);
		requireHabitImportPermission(decoded);
		requireFocusImportPermission(decoded);
		requireNoteImportPermission(decoded);
		long taskRevision = collectionRevision.lock();
		long readingRevision = readingCollectionRevision.lock();
		long habitRevision = habitCollectionRevision.lock();
		long noteRevision = noteCollectionRevision.lock();
		List<Task> localTasks = tasks.findAllByTypeOrderByIdAsc(Task.Type.LOCAL);
		TaskBackupClassification taskClassification = classifyTasks(decoded, localTasks);
		ReadingBackupClassification readingClassification = classifyReadingItems(decoded, loadReadingItemsWithTags(readingItems.findAllByOrderByIdAsc()));
		HabitBackupClassification habitClassification = classifyHabits(decoded, habits.findAllByOrderByStartDateAscCreatedAtAsc());
		FocusBackupClassification focusClassification = classifyFocusSessions(decoded, focusSessions.allForCopy(AuthContext.userId()), currentActiveId());
		NoteBackupClassification noteClassification = classifyNotes(decoded, taskClassification, noteService.loadForBackup());
		return issuePreview(decoded, taskRevision, readingRevision, habitRevision, noteRevision, taskClassification, readingClassification, habitClassification, focusClassification, noteClassification);
	}

	@Transactional
	public Confirmation confirm(byte[] content, String token) {
		DecodedBackup decoded = decode(content);
		try { return confirm(decoded, token); }
		finally { decoded.cleanup(); }
	}

	@Transactional
	public Confirmation confirm(Path content, String token) {
		DecodedBackup decoded = decode(content);
		try { return confirm(decoded, token); }
		finally { decoded.cleanup(); }
	}

	private Confirmation confirm(DecodedBackup decoded, String token) {
		requireTaskImportPermission(decoded);
		requireReadingImportPermission(decoded);
		requireHabitImportPermission(decoded);
		requireFocusImportPermission(decoded);
		requireNoteImportPermission(decoded);
		TaskImportPreviewStore.Entry entry = previews.get(token, AuthContext.userId());
		if (entry == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "A prévia expirou. Revise o arquivo novamente.");
		if (!entry.digest().equals(decoded.digest())) throw invalid("O arquivo mudou depois da prévia. Revise-o novamente.");
		if (!entry.classification().tasks().conflictIds().isEmpty() || !entry.classification().readingItems().conflictIds().isEmpty()
				|| !entry.classification().habits().conflictIds().isEmpty() || !entry.classification().focusSessions().conflictIds().isEmpty()
				|| !entry.classification().notes().conflictIds().isEmpty() || entry.classification().focusSessions().activeSlotConflict()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Resolva os conflitos mostrados na prévia antes de importar.");

		long taskRevision = collectionRevision.lock();
		long readingRevision = readingCollectionRevision.lock();
		long habitRevision = habitCollectionRevision.lock();
		long noteRevision = noteCollectionRevision.lock();
		String ownerId = AuthContext.userId();
		focusSessions.lockOwnerSlot(ownerId);
		List<Task> localTasks = tasks.findAllByTypeOrderByIdAsc(Task.Type.LOCAL);
		TaskBackupClassification currentTasks = classifyTasks(decoded, localTasks);
		ReadingBackupClassification currentReading = classifyReadingItems(decoded, loadReadingItemsWithTags(readingItems.findAllByOrderByIdAsc()));
		HabitBackupClassification currentHabits = classifyHabits(decoded, habits.findAllByOrderByStartDateAscCreatedAtAsc());
		FocusBackupClassification currentFocus = classifyFocusSessions(decoded, focusSessions.allForCopy(ownerId), currentActiveId());
		NoteBackupClassification currentNotes = classifyNotes(decoded, currentTasks, noteService.loadForBackup());
		PortableCopyClassificationSnapshot currentSnapshot = snapshot(currentTasks, currentReading, currentHabits, currentFocus, currentNotes);
		if (taskRevision != entry.taskRevision() || readingRevision != entry.readingRevision() || habitRevision != entry.habitRevision() || noteRevision != entry.noteRevision()
				|| !currentSnapshot.equals(entry.classification())) {
			Preview refreshed = issuePreview(decoded, taskRevision, readingRevision, habitRevision, noteRevision, currentTasks, currentReading, currentHabits, currentFocus, currentNotes);
			return new Confirmation("REVIEW_REQUIRED", refreshed, 0, 0, 0, 0, 0, 0);
		}
		if (!currentTasks.conflictIds().isEmpty() || !currentReading.conflictIds().isEmpty() || !currentHabits.conflictIds().isEmpty()
				|| !currentFocus.conflictIds().isEmpty() || currentFocus.activeSlotConflict() || !currentNotes.conflictIds().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A cópia contém conflitos. Revise a prévia atualizada.");
		if (notes.count() + currentNotes.newNotes().size() > MAX_NOTES) throw new ResponseStatusException(HttpStatus.CONFLICT, "O acervo atingiria o limite de 5.000 notas. Remova notas antes de importar esta cópia.");
		boolean missingSeries = decoded.file().series() != null && decoded.file().series().stream().anyMatch(item -> series.findById(item.id()).isEmpty());
		Set<String> localAuthorIds = authors.findAll().stream().map(ReadingAuthor::getId).collect(java.util.stream.Collectors.toSet());
		List<BackupAuthor> newAuthors = decoded.file().authors() == null ? List.of() : decoded.file().authors().stream()
			.filter(author -> !localAuthorIds.contains(author.id())).toList();
		if (currentTasks.newTasks().isEmpty() && !missingSeries && currentReading.newItems().isEmpty() && newAuthors.isEmpty()
				&& currentHabits.newHabits().isEmpty() && currentFocus.newSessions().isEmpty() && currentNotes.newNotes().isEmpty()) {
			removePreviewAfterCommit(token);
			return new Confirmation("IMPORTED", null, 0, currentTasks.identicalIds().size(), currentTasks.repeatedIdenticalIds(),
				0, currentReading.identicalIds().size(), currentReading.repeatedIdenticalIds(), 0, newAuthors.size(),
				0, currentHabits.identicalIds().size(), currentHabits.repeatedIdenticalIds(), 0, currentFocus.identicalIds().size(),
				0, currentNotes.identicalIds().size(), currentNotes.repeatedIdenticalIds());
		}

		List<TaskSeries> newSeries = (decoded.file().series() == null ? List.<BackupSeries>of() : decoded.file().series()).stream()
			.filter(incoming -> series.findById(incoming.id()).isEmpty()).map(this::restoreSeries).toList();
		List<String> newlyStoredCovers = new ArrayList<>();
		try {
			Map<String, String> coverNames = new LinkedHashMap<>();
			Map<String, String> coverDigests = new LinkedHashMap<>();
			for (BackupReadingItem item : currentReading.newItems()) {
				Path source = decoded.coverAssets().get(item.id());
				if (source == null) continue;
				StoredCover stored = covers.storePortable(ownerId, Files.readAllBytes(source), item.coverFormat());
				newlyStoredCovers.add(stored.name());
				coverNames.put(item.id(), stored.name());
				coverDigests.put(item.id(), stored.sha256());
			}
			series.saveAll(newSeries);
			series.flush();
			authors.saveAll(newAuthors.stream().map(author -> ReadingAuthor.restore(author.id(), author.name())).toList());
			authors.flush();
			List<Task> additions = currentTasks.newTasks().stream().map(this::restoreTask).toList();
			tasks.saveAll(additions);
			tasks.flush();
			for (Task task : additions) checklists.restoreImported(task.getId(), decoded.uniqueTasks().get(task.getId()).checklist());
			List<ReadingItem> readingAdditions = currentReading.newItems().stream()
				.map(item -> restoreReadingItem(item, coverNames.get(item.id()), coverDigests.get(item.id()))).toList();
			readingItems.saveAll(readingAdditions);
			readingItems.flush();
			for (ReadingItem item : readingAdditions) readingAssociations.save(item);
			List<Habit> habitAdditions = currentHabits.newHabits().stream().map(this::restoreHabit).toList();
			habits.saveAll(habitAdditions);
			habits.flush();
			for (Habit habit : habitAdditions) {
				BackupHabit backup = decoded.uniqueHabits().get(habit.getId());
				habitSchedules.saveAll(backup.schedules().stream().map(item -> new HabitSchedule(habit.getId(), item.effectiveFrom(), item.weekdaysMask())).toList());
				habitPauses.saveAll(backup.pauses().stream().map(item -> restorePause(habit.getId(), item)).toList());
				habitCheckIns.saveAll(backup.checkIns().stream().map(date -> new HabitCheckIn(habit.getId(), date, Instant.now())).toList());
			}
			for (BackupFocusSession session : currentFocus.newSessions()) focusSessions.restore(ownerId, session);
			List<Note> noteAdditions = currentNotes.newNotes().stream().map(item -> noteService.restoreImport(item.id(), item.title(), item.body(), item.tags(),
				item.pinned(), item.archived(), item.relatedTaskId(), item.relatedTaskUnavailable(), item.createdAt(), item.updatedAt())).toList();
			notes.saveAll(noteAdditions);
			notes.flush();
			for (Note note : noteAdditions) noteService.saveImportedTags(note);
			if (!newlyStoredCovers.isEmpty()) cleanupImportedCoversOnRollback(ownerId, newlyStoredCovers);
			if (!additions.isEmpty() || !newSeries.isEmpty()) collectionRevision.increment();
			if (!readingAdditions.isEmpty() || !newAuthors.isEmpty()) readingCollectionRevision.increment();
			if (!habitAdditions.isEmpty()) habitCollectionRevision.increment();
			if (!noteAdditions.isEmpty()) noteCollectionRevision.increment();
			removePreviewAfterCommit(token);
			return new Confirmation("IMPORTED", null, additions.size(), currentTasks.identicalIds().size(), currentTasks.repeatedIdenticalIds(),
				readingAdditions.size(), currentReading.identicalIds().size(), currentReading.repeatedIdenticalIds(), newlyStoredCovers.size(), newAuthors.size(),
				habitAdditions.size(), currentHabits.identicalIds().size(), currentHabits.repeatedIdenticalIds(),
				currentFocus.newSessions().size(), currentFocus.identicalIds().size(), noteAdditions.size(), currentNotes.identicalIds().size(), currentNotes.repeatedIdenticalIds());
		} catch (RuntimeException | IOException error) {
			newlyStoredCovers.forEach(name -> covers.delete(ownerId, name));
			if (error instanceof RuntimeException runtime) throw runtime;
			throw new IllegalStateException("Não foi possível preparar as imagens da cópia.", error);
		}
	}

	private Preview issuePreview(DecodedBackup decoded, long taskRevision, long readingRevision, long habitRevision, long noteRevision,
			TaskBackupClassification taskClassification, ReadingBackupClassification readingClassification, HabitBackupClassification habitClassification,
			FocusBackupClassification focusClassification, NoteBackupClassification noteClassification) {
		List<TaskSample> samples = taskClassification.newTasks().stream().limit(10)
			.map(task -> new TaskSample(task.id(), task.title(), task.dueDate())).toList();
		List<TaskSample> conflictSamples = taskClassification.conflictIds().stream().limit(20)
			.map(decoded.uniqueTasks()::get)
			.filter(task -> task != null)
			.map(task -> new TaskSample(task.id(), task.title(), task.dueDate())).toList();
		List<ReadingItemSample> readingSamples = readingClassification.newItems().stream().limit(10)
			.map(item -> new ReadingItemSample(item.id(), item.title(), item.type(), item.archived())).toList();
		List<ReadingItemSample> readingConflictSamples = readingClassification.conflictIds().stream().limit(20)
			.map(decoded.uniqueReadingItems()::get)
			.filter(item -> item != null)
			.map(item -> new ReadingItemSample(item.id(), item.title(), item.type(), item.archived())).toList();
		boolean hasConflicts = !taskClassification.conflictIds().isEmpty() || !readingClassification.conflictIds().isEmpty()
			|| !habitClassification.conflictIds().isEmpty() || !focusClassification.conflictIds().isEmpty() || focusClassification.activeSlotConflict()
			|| !noteClassification.conflictIds().isEmpty();
		List<ReadingAuthor> localAuthors = authors.findAll();
		Map<String, ReadingAuthor> localAuthorsById = new LinkedHashMap<>();
		Map<String, ReadingAuthor> localAuthorsByName = new LinkedHashMap<>();
		for (ReadingAuthor author : localAuthors) { localAuthorsById.put(author.getId(), author); localAuthorsByName.put(author.getNormalizedName(), author); }
		List<BackupAuthor> newAuthors = decoded.file().authors() == null ? List.of() : decoded.file().authors().stream().filter(author -> !localAuthorsById.containsKey(author.id())).toList();
		int conflictingAuthors = decoded.file().authors() == null ? 0 : (int) decoded.file().authors().stream().filter(author -> {
			ReadingAuthor localById = localAuthorsById.get(author.id());
			ReadingAuthor localByName = localAuthorsByName.get(ReadingAuthor.normalize(author.name()));
			return (localById != null && !localById.getName().equals(author.name())) || (localByName != null && !localByName.getId().equals(author.id()));
		}).count();
		boolean hasNewItems = !taskClassification.newTasks().isEmpty() || !readingClassification.newItems().isEmpty() || !newAuthors.isEmpty()
			|| !habitClassification.newHabits().isEmpty() || !focusClassification.newSessions().isEmpty() || !noteClassification.newNotes().isEmpty();
		boolean noteLimitExceeded = notes.count() + noteClassification.newNotes().size() > MAX_NOTES;
		int newCovers = (int) readingClassification.newItems().stream().filter(item -> item.coverSha256() != null).count();
		int identicalCovers = (int) readingClassification.identicalIds().stream()
			.map(decoded.uniqueReadingItems()::get).filter(item -> item != null && item.coverSha256() != null).count();
		boolean hasNewSeries = decoded.file().series() != null && decoded.file().series().stream().anyMatch(item -> series.findById(item.id()).isEmpty());
		Preview withoutToken = new Preview("", decoded.file().formatVersion(), decoded.file().generatedAt(),
			decoded.file().tasks().size(), taskClassification.newTasks().size(), taskClassification.identicalIds().size(),
			taskClassification.conflictIds().size(), taskClassification.repeatedIdenticalIds(), samples,
			conflictSamples, taskClassification.conflictIds().stream().limit(20).toList(),
			decoded.file().readingItems().size(), readingClassification.newItems().size(), readingClassification.identicalIds().size(),
			readingClassification.conflictIds().size(), readingClassification.repeatedIdenticalIds(), readingSamples,
			readingConflictSamples, readingClassification.conflictIds().stream().limit(20).toList(), !hasConflicts && !noteLimitExceeded && (hasNewItems || hasNewSeries),
			decoded.file().formatVersion() >= 5 ? (int) decoded.file().readingItems().stream().filter(item -> item.coverSha256() != null).count() : 0,
			newCovers, identicalCovers, decoded.file().authors() == null ? 0 : decoded.file().authors().size(), newAuthors.size(), conflictingAuthors,
			decoded.file().habits().size(), habitClassification.newHabits().size(), habitClassification.identicalIds().size(),
			habitClassification.conflictIds().size(), habitClassification.repeatedIdenticalIds(), decoded.file().focusSessions().size(),
			focusClassification.newSessions().size(), focusClassification.identicalIds().size(), focusClassification.conflictIds().size(), focusClassification.activeSlotConflict(),
			new NoteSummary(decoded.file().notes().size(), noteClassification.newNotes().size(), noteClassification.identicalIds().size(),
				noteClassification.conflictIds().size(), noteClassification.repeatedIdenticalIds(),
				(int) noteClassification.newNotes().stream().filter(BackupNote::relatedTaskUnavailable).count(), noteLimitExceeded),
			decoded.file().omittedSections());
		String token = previews.put(AuthContext.userId(), decoded.digest(), taskRevision, readingRevision, habitRevision, noteRevision,
			snapshot(taskClassification, readingClassification, habitClassification, focusClassification, noteClassification));
		return new Preview(token, withoutToken.formatVersion(), withoutToken.generatedAt(), withoutToken.totalTasks(),
			withoutToken.newTasks(), withoutToken.identicalTasks(), withoutToken.conflictingTasks(),
			withoutToken.repeatedIdenticalIds(), withoutToken.samples(), withoutToken.conflicts(), withoutToken.conflictIds(),
			withoutToken.totalReadingItems(), withoutToken.newReadingItems(), withoutToken.identicalReadingItems(),
			withoutToken.conflictingReadingItems(), withoutToken.repeatedIdenticalReadingIds(), withoutToken.readingSamples(),
			withoutToken.readingConflicts(), withoutToken.readingConflictIds(), withoutToken.canImport(), withoutToken.totalCovers(),
			withoutToken.newCovers(), withoutToken.identicalCovers(), withoutToken.totalAuthors(), withoutToken.newAuthors(), withoutToken.conflictingAuthors(),
			withoutToken.totalHabits(), withoutToken.newHabits(), withoutToken.identicalHabits(), withoutToken.conflictingHabits(), withoutToken.repeatedIdenticalHabitIds(),
			withoutToken.totalFocusSessions(), withoutToken.newFocusSessions(), withoutToken.identicalFocusSessions(), withoutToken.conflictingFocusSessions(), withoutToken.focusSlotConflict(), withoutToken.notes(), withoutToken.omittedSections());
	}

	private PortableCopyClassificationSnapshot snapshot(TaskBackupClassification tasks, ReadingBackupClassification readings, HabitBackupClassification habits,
			FocusBackupClassification focus, NoteBackupClassification notes) {
		return new PortableCopyClassificationSnapshot(
			new TaskBackupClassificationSnapshot(tasks.newTasks().stream().map(BackupTask::id).toList(),
				tasks.identicalIds(), tasks.conflictIds(), tasks.repeatedIdenticalIds()),
			new ReadingBackupClassificationSnapshot(readings.newItems().stream().map(BackupReadingItem::id).toList(),
				readings.identicalIds(), readings.conflictIds(), readings.repeatedIdenticalIds()),
			new HabitBackupClassificationSnapshot(habits.newHabits().stream().map(BackupHabit::id).toList(),
				habits.identicalIds(), habits.conflictIds(), habits.repeatedIdenticalIds()),
			new FocusBackupClassificationSnapshot(focus.newSessions().stream().map(BackupFocusSession::id).toList(),
				focus.identicalIds(), focus.conflictIds(), focus.repeatedIdenticalIds(), focus.activeSlotConflict()),
			new NoteBackupClassificationSnapshot(notes.newNotes().stream().map(BackupNote::id).toList(), notes.identicalIds(), notes.conflictIds(), notes.repeatedIdenticalIds()));
	}

	private FocusBackupClassification classifyFocusSessions(DecodedBackup decoded,
			List<com.github.cleytonorocha.hub.dto.FocusSessionDtos.SessionView> existingSessions, String activeId) {
		Map<String, com.github.cleytonorocha.hub.dto.FocusSessionDtos.SessionView> localById = new LinkedHashMap<>();
		for (var existing : existingSessions) localById.put(existing.id(), existing);
		List<BackupFocusSession> newSessions = new ArrayList<>();
		List<String> identicalIds = new ArrayList<>();
		Set<String> conflicts = new LinkedHashSet<>(decoded.internalFocusConflicts());
		for (BackupFocusSession incoming : decoded.uniqueFocusSessions().values()) {
			if (conflicts.contains(incoming.id())) continue;
			var local = localById.get(incoming.id());
			if (local == null) newSessions.add(incoming);
			else if (("ENDED".equals(local.state()) && "ENDED".equals(incoming.state())
					|| "PAUSED".equals(local.state()) && "PAUSED".equals(incoming.state()))
					&& samePersistedFocusSession(local, incoming)) identicalIds.add(incoming.id());
			else if (isCurrentActiveSnapshot(local, incoming)) identicalIds.add(incoming.id());
			else conflicts.add(incoming.id());
		}
		boolean hasNewOpenSession = newSessions.stream().anyMatch(item -> !"ENDED".equals(item.state()));
		boolean slotConflict = hasNewOpenSession && activeId != null;
		return new FocusBackupClassification(newSessions, identicalIds, conflicts.stream().sorted().toList(),
			decoded.repeatedIdenticalFocusIds(), slotConflict);
	}

	private boolean samePersistedFocusSession(com.github.cleytonorocha.hub.dto.FocusSessionDtos.SessionView local,
			BackupFocusSession incoming) {
		return local.id().equals(incoming.id()) && local.objective().equals(incoming.objective())
			&& local.plannedSeconds() == incoming.plannedSeconds() && local.focusedSeconds() == incoming.focusedSeconds()
			&& local.state().equals(incoming.state()) && java.util.Objects.equals(local.startedAt(), incoming.startedAt())
			&& java.util.Objects.equals(local.resumedAt(), incoming.resumedAt())
			&& java.util.Objects.equals(local.pausedAt(), incoming.pausedAt())
			&& java.util.Objects.equals(local.endedAt(), incoming.endedAt())
			&& java.util.Objects.equals(local.endReason(), incoming.endReason())
			&& java.util.Objects.equals(local.taskId(), incoming.taskId());
	}

	private boolean isCurrentActiveSnapshot(com.github.cleytonorocha.hub.dto.FocusSessionDtos.SessionView local,
			BackupFocusSession incoming) {
		if (!"ACTIVE".equals(local.state()) || !"PAUSED".equals(incoming.state()) || local.resumedAt() == null
				|| incoming.pausedAt() == null || incoming.resumedAt() != null || incoming.endedAt() != null || incoming.endReason() != null
				|| !local.id().equals(incoming.id()) || !local.objective().equals(incoming.objective())
				|| local.plannedSeconds() != incoming.plannedSeconds()
				|| !java.util.Objects.equals(local.taskId(), incoming.taskId())
				|| !java.util.Objects.equals(local.startedAt(), incoming.startedAt())
				|| incoming.pausedAt().isBefore(local.resumedAt())) return false;
		long elapsed = Math.max(0, java.time.Duration.between(local.resumedAt(), incoming.pausedAt()).getSeconds());
		return (long) local.focusedSeconds() + elapsed == incoming.focusedSeconds();
	}

	private String currentActiveId() {
		var active = focusSessions.active(AuthContext.userId());
		return active == null ? null : active.id();
	}

	private void requireTaskImportPermission(DecodedBackup decoded) {
		if ((!decoded.file().tasks().isEmpty() || (decoded.file().series() != null && !decoded.file().series().isEmpty()))
				&& !AuthContext.hasPermission("TASK_WRITE"))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seu perfil não pode importar tarefas.");
	}

	private void requireReadingImportPermission(DecodedBackup decoded) {
		if ((!decoded.file().readingItems().isEmpty() || (decoded.file().authors() != null && !decoded.file().authors().isEmpty()))
				&& !AuthContext.hasPermission("READING_WRITE"))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seu perfil não pode importar itens de leitura.");
	}

	private void requireFocusImportPermission(DecodedBackup decoded) {
		if (!decoded.file().focusSessions().isEmpty()) {
			if (!AuthContext.hasPermission("FOCUS_WRITE"))
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seu perfil não pode importar sessões de foco.");
			if (decoded.file().focusSessions().stream().anyMatch(session -> session.taskId() != null) && !AuthContext.hasPermission("TASK_READ"))
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seu perfil não pode verificar os vínculos de tarefas das sessões de foco.");
		}
	}

	private void requireHabitImportPermission(DecodedBackup decoded) {
		if (!decoded.file().habits().isEmpty() && !AuthContext.hasPermission("HABITS_WRITE"))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seu perfil não pode importar hábitos.");
	}

	private void requireNoteImportPermission(DecodedBackup decoded) {
		if (!decoded.file().notes().isEmpty()) {
			if (!AuthContext.hasPermission("NOTES_WRITE")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seu perfil não pode importar notas.");
			if (decoded.file().notes().stream().anyMatch(note -> note.relatedTaskId() != null) && !AuthContext.hasPermission("TASK_READ"))
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seu perfil não pode verificar os vínculos de tarefas das notas.");
		}
	}

	private TaskBackupClassification classifyTasks(DecodedBackup decoded, List<Task> localTasks) {
		Map<String, BackupTask> localById = new LinkedHashMap<>();
		for (Task task : localTasks) localById.put(task.getId(), toBackupTask(task));
		List<BackupTask> newTasks = new ArrayList<>();
		List<String> identicalIds = new ArrayList<>();
		Set<String> conflicts = new LinkedHashSet<>(decoded.internalConflicts());
		for (BackupSeries incoming : decoded.file().series() == null ? List.<BackupSeries>of() : decoded.file().series()) {
			TaskSeries existing = series.findById(incoming.id()).orElse(null);
			if (existing != null && !toBackupSeries(existing).equals(incoming)) conflicts.add(incoming.id());
		}
		for (BackupTask incoming : decoded.uniqueTasks().values()) {
			if (conflicts.contains(incoming.id())) continue;
			BackupTask local = localById.get(incoming.id());
			if (local == null) newTasks.add(incoming);
			else if (local.equals(incoming)) identicalIds.add(incoming.id());
			else conflicts.add(incoming.id());
		}
		return new TaskBackupClassification(newTasks, identicalIds, conflicts.stream().sorted().toList(), decoded.repeatedIdenticalIds());
	}

	private ReadingBackupClassification classifyReadingItems(DecodedBackup decoded, List<ReadingItem> existingItems) {
		Map<String, BackupReadingItem> localById = new LinkedHashMap<>();
		String ownerId = AuthContext.userId();
		for (ReadingItem item : existingItems) localById.put(item.getId(), toBackupReadingItem(item, true, ownerId));
		List<BackupReadingItem> newItems = new ArrayList<>();
		List<String> identicalIds = new ArrayList<>();
		Set<String> conflicts = new LinkedHashSet<>(decoded.internalReadingConflicts());
		Map<String, ReadingAuthor> existingAuthors = new LinkedHashMap<>();
		Map<String, ReadingAuthor> existingAuthorsByName = new LinkedHashMap<>();
		for (ReadingAuthor author : authors.findAll()) {
			existingAuthors.put(author.getId(), author);
			existingAuthorsByName.put(author.getNormalizedName(), author);
		}
		Set<String> authorConflicts = new LinkedHashSet<>();
		for (BackupAuthor incoming : decoded.file().authors() == null ? List.<BackupAuthor>of() : decoded.file().authors()) {
			ReadingAuthor byId = existingAuthors.get(incoming.id());
			ReadingAuthor byName = existingAuthorsByName.get(ReadingAuthor.normalize(incoming.name()));
			if ((byId != null && !byId.getName().equals(incoming.name())) || (byName != null && !byName.getId().equals(incoming.id()))) authorConflicts.add(incoming.id());
		}
		conflicts.addAll(authorConflicts);
		for (BackupReadingItem incoming : decoded.uniqueReadingItems().values()) {
			if (conflicts.contains(incoming.id())) continue;
			if (incoming.authorIds() != null && incoming.authorIds().stream().anyMatch(authorConflicts::contains)) { conflicts.add(incoming.id()); continue; }
			BackupReadingItem local = localById.get(incoming.id());
			if (local == null) newItems.add(incoming);
			else if (sameReadingItem(local, incoming, decoded.file().formatVersion() >= 5, decoded.file().formatVersion() >= 7)) identicalIds.add(incoming.id());
			else conflicts.add(incoming.id());
		}
		return new ReadingBackupClassification(newItems, identicalIds, conflicts.stream().sorted().toList(), decoded.repeatedIdenticalReadingIds());
	}

	private HabitBackupClassification classifyHabits(DecodedBackup decoded, List<Habit> existingHabits) {
		Map<String, BackupHabit> localById = new LinkedHashMap<>();
		for (BackupHabit habit : toBackupHabits(existingHabits)) localById.put(habit.id(), habit);
		List<BackupHabit> newHabits = new ArrayList<>();
		List<String> identicalIds = new ArrayList<>();
		Set<String> conflicts = new LinkedHashSet<>(decoded.internalHabitConflicts());
		for (BackupHabit incoming : decoded.uniqueHabits().values()) {
			if (conflicts.contains(incoming.id())) continue;
			BackupHabit local = localById.get(incoming.id());
			if (local == null) newHabits.add(incoming);
			else if (local.equals(incoming)) identicalIds.add(incoming.id());
			else conflicts.add(incoming.id());
		}
		return new HabitBackupClassification(newHabits, identicalIds, conflicts.stream().sorted().toList(), decoded.repeatedIdenticalHabitIds());
	}

	private NoteBackupClassification classifyNotes(DecodedBackup decoded, TaskBackupClassification taskClassification, List<Note> existingNotes) {
		Set<String> importableTaskIds = new LinkedHashSet<>();
		importableTaskIds.addAll(tasks.findAll().stream().map(Task::getId).toList());
		importableTaskIds.addAll(taskClassification.newTasks().stream().map(BackupTask::id).toList());
		Map<String, BackupNote> localById = new LinkedHashMap<>();
		for (Note note : existingNotes) localById.put(note.getId(), toBackupNote(note, importableTaskIds));
		List<BackupNote> newNotes = new ArrayList<>();
		List<String> identicalIds = new ArrayList<>();
		Set<String> conflicts = new LinkedHashSet<>(decoded.internalNoteConflicts());
		for (BackupNote raw : decoded.uniqueNotes().values()) {
			if (conflicts.contains(raw.id())) continue;
			BackupNote incoming = normalizeNoteLink(raw, importableTaskIds);
			BackupNote local = localById.get(incoming.id());
			if (local == null) newNotes.add(incoming);
			else if (local.equals(incoming)) identicalIds.add(incoming.id());
			else conflicts.add(incoming.id());
		}
		return new NoteBackupClassification(newNotes, identicalIds, conflicts.stream().sorted().toList(), decoded.repeatedIdenticalNoteIds());
	}

	private List<BackupNote> exportNotes(List<Task> localTasks) {
		List<Note> source = noteService.loadForBackup();
		if (source.size() > MAX_NOTES) throw tooLarge("A cópia excede o limite de 5.000 notas.");
		Set<String> taskIds = localTasks.stream().map(Task::getId).collect(java.util.stream.Collectors.toSet());
		return source.stream().map(note -> toBackupNote(note, taskIds)).toList();
	}

	private BackupNote toBackupNote(Note note, Set<String> taskIdsInCopy) {
		String taskId = note.getRelatedTaskId();
		boolean unavailable = taskId != null && (taskIdsInCopy == null || !taskIdsInCopy.contains(taskId));
		return new BackupNote(note.getId(), note.getTitle(), note.getBody(), note.getTags(), note.isPinned(), note.isArchived(), taskId, unavailable,
			note.getCreatedAt().truncatedTo(ChronoUnit.MICROS), note.getUpdatedAt().truncatedTo(ChronoUnit.MICROS));
	}

	private BackupNote normalizeNoteLink(BackupNote note, Set<String> importableTaskIds) {
		boolean unavailable = note.relatedTaskId() != null && !importableTaskIds.contains(note.relatedTaskId());
		return new BackupNote(note.id(), note.title(), note.body(), note.tags(), note.pinned(), note.archived(), note.relatedTaskId(), unavailable,
			note.createdAt(), note.updatedAt());
	}

	private boolean sameReadingItem(BackupReadingItem left, BackupReadingItem right, boolean compareCover, boolean compareAuthors) {
		return left.id().equals(right.id()) && left.title().equals(right.title()) && left.type().equals(right.type())
			&& java.util.Objects.equals(left.authorOrSource(), right.authorOrSource()) && java.util.Objects.equals(left.url(), right.url())
			&& left.status().equals(right.status()) && left.progressMarker().equals(right.progressMarker())
			&& java.util.Objects.equals(left.totalPages(), right.totalPages()) && java.util.Objects.equals(left.currentPage(), right.currentPage())
			&& left.note().equals(right.note()) && left.archived() == right.archived()
			&& left.createdAt().equals(right.createdAt()) && left.updatedAt().equals(right.updatedAt())
			&& (!compareAuthors || (java.util.Objects.equals(left.coverSourceUrl(), right.coverSourceUrl())
				&& left.authorIds().stream().sorted().toList().equals(right.authorIds().stream().sorted().toList())))
			&& (!compareCover || (java.util.Objects.equals(left.coverSha256(), right.coverSha256())
				&& java.util.Objects.equals(left.coverFormat(), right.coverFormat())
				&& java.util.Objects.equals(left.coverSize(), right.coverSize())))
			&& left.tags().stream().map(value -> value.toLowerCase(java.util.Locale.forLanguageTag("pt-BR"))).sorted().toList()
				.equals(right.tags().stream().map(value -> value.toLowerCase(java.util.Locale.forLanguageTag("pt-BR"))).sorted().toList());
	}

	private DecodedBackup decode(byte[] content) {
		if (content == null || content.length == 0) throw invalid("O arquivo está vazio.");
		return decodeJson(content, false, Map.of(), null, digest(content));
	}

	private DecodedBackup decode(Path path) {
		try {
			long size = Files.size(path);
			if (size == 0) throw invalid("O arquivo está vazio.");
			if (size > MAX_PACKAGE_BYTES) throw tooLarge("A cópia excede o limite de 100 MiB.");
			byte[] signature = new byte[2];
			try (InputStream input = Files.newInputStream(path)) {
				if (input.read(signature) != 2) throw invalid("O arquivo da cópia está incompleto.");
			}
			if (signature[0] == 'P' && signature[1] == 'K') return decodeZip(path);
			if (size > MAX_FILE_BYTES) throw tooLarge("O arquivo JSON excede o limite de 5 MiB.");
			return decode(Files.readAllBytes(path));
		} catch (ResponseStatusException error) {
			throw error;
		} catch (IOException error) {
			throw invalid("Não foi possível ler a cópia selecionada.");
		}
	}

	private DecodedBackup decodeZip(Path archive) {
		Path staging = null;
		try {
			staging = Files.createTempDirectory("hub-copy-assets-");
			Map<String, Path> entries = new LinkedHashMap<>();
			byte[] manifest = null;
			long expanded = 0;
			int entryCount = 0;
			try (ZipFile zip = new ZipFile(archive.toFile())) {
				var enumeration = zip.entries();
				while (enumeration.hasMoreElements()) {
					ZipEntry entry = enumeration.nextElement();
					entryCount++;
					if (entryCount > MAX_READING_ITEMS + 1 || entry.isDirectory()) throw invalid("O pacote contém entradas demais ou pastas inesperadas.");
					String name = entry.getName();
					if (name.equals("manifest.json")) {
						if (manifest != null) throw invalid("O pacote repete o manifesto.");
						manifest = readEntry(zip, entry, MAX_MANIFEST_BYTES);
						expanded += manifest.length;
					} else if (name.matches("assets/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)")) {
						if (entries.containsKey(name)) throw invalid("O pacote repete um arquivo de imagem.");
						byte[] bytes = readEntry(zip, entry, ReadingCoverStorageService.MAX_FILE_BYTES);
						expanded += bytes.length;
						if (expanded > MAX_PACKAGE_EXPANDED_BYTES) throw tooLarge("O conteúdo descompactado excede 100 MiB.");
						Path staged = staging.resolve(UUID.randomUUID().toString());
						Files.write(staged, bytes);
						entries.put(name, staged);
					} else {
						throw invalid("O pacote contém um caminho ou arquivo não permitido.");
					}
					if (expanded > MAX_PACKAGE_EXPANDED_BYTES) throw tooLarge("O conteúdo descompactado excede 100 MiB.");
				}
			}
			if (manifest == null) throw invalid("O pacote não contém manifest.json.");
			return decodeJson(manifest, true, entries, staging, digest(archive));
		} catch (ResponseStatusException error) {
			deleteTree(staging);
			throw error;
		} catch (Exception error) {
			deleteTree(staging);
			throw invalid("O pacote está corrompido ou não é uma cópia portátil válida.");
		}
	}

	private DecodedBackup decodeJson(byte[] content, boolean packaged, Map<String, Path> archiveEntries, Path staging, String wholeDigest) {
		if (content == null || content.length == 0) throw invalid("O arquivo está vazio.");
		if (content.length > (packaged ? MAX_MANIFEST_BYTES : MAX_FILE_BYTES)) throw tooLarge(packaged ? "O manifesto da cópia excede o limite de 90 MiB." : "O arquivo excede o limite de 5 MiB.");
		try {
			TokenStreamFactory strictFactory = mapper.tokenStreamFactory().rebuild()
				.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.build();
			JsonNode root;
			try (JsonParser parser = strictFactory.createParser(content)) {
				root = mapper.readTree(parser);
			}
			if (root == null || !root.isObject() || !root.has("formatVersion") || !root.get("formatVersion").isIntegralNumber()) throw invalid("O arquivo não tem a estrutura esperada da cópia do Hub.");
			int inputVersion = root.get("formatVersion").intValue();
			if (inputVersion < 1 || inputVersion > (packaged ? FORMAT_VERSION : LEGACY_FORMAT_VERSION)) throw invalid("Esta versão da cópia não é compatível.");
			if (packaged && inputVersion < 5) throw invalid("O pacote ZIP precisa usar a versão 5 ou posterior.");
			Set<String> expectedRootFields = inputVersion == 1 ? ROOT_FIELDS_V1 : inputVersion <= 3 ? ROOT_FIELDS_V2_V3
						: inputVersion == 4 ? ROOT_FIELDS_V4 : inputVersion == 8 ? ROOT_FIELDS_V8 : inputVersion == 9 ? ROOT_FIELDS_V9
						: inputVersion == 10 ? ROOT_FIELDS_V10 : inputVersion == 11 ? ROOT_FIELDS_V11
				: inputVersion >= 7 ? ROOT_FIELDS_V7 : ROOT_FIELDS_V5;
			if (!fieldNames(root).equals(expectedRootFields)) throw invalid("O arquivo não tem a estrutura esperada da cópia do Hub.");
			if (!root.get("generatedAt").isTextual()) throw invalid("A versão ou a data da cópia está inválida.");
			JsonNode taskNodes = root.get("tasks");
			if (taskNodes == null || !taskNodes.isArray()) throw invalid("A lista de tarefas do arquivo é inválida.");
			if (taskNodes.size() > MAX_TASKS) throw tooLarge("O arquivo excede o limite de 5.000 tarefas.");
			for (JsonNode taskNode : taskNodes) {
				Set<String> expectedTaskFields = inputVersion == 1 ? TASK_FIELDS_V1 : inputVersion == 2 ? TASK_FIELDS_V2 : TASK_FIELDS;
				if (!taskNode.isObject() || !fieldNames(taskNode).equals(expectedTaskFields)) throw invalid("Uma tarefa do arquivo tem campos inválidos.");
				if (!taskNode.get("id").isTextual() || !taskNode.get("title").isTextual()
					|| !isTextOrNull(taskNode.get("dueDate")) || !isTextOrNull(taskNode.get("dueTime"))
					|| !taskNode.get("completed").isBoolean() || !taskNode.get("type").isTextual()
					|| !taskNode.get("category").isTextual() || !taskNode.get("color").isTextual()
					|| !taskNode.get("priority").isTextual() || !taskNode.get("notes").isTextual()) {
					throw invalid("Uma tarefa do arquivo tem valores com tipo inválido.");
				}
				if (inputVersion >= 2 && (!isTextOrNull(taskNode.get("seriesId"))
					|| !(taskNode.get("seriesPosition").isNull() || taskNode.get("seriesPosition").isIntegralNumber()))) throw invalid("Uma ocorrência contém vínculo de série inválido.");
				if (inputVersion >= 3) {
					JsonNode items = taskNode.get("checklist");
					if (items == null || !items.isArray() || items.size() > TaskChecklistService.MAX_ITEMS) throw invalid("A lista de checklist é inválida.");
					for (JsonNode item : items) if (!item.isObject() || !fieldNames(item).equals(Set.of("id", "text", "checked", "position"))
						|| !item.get("id").isTextual() || !item.get("text").isTextual() || !item.get("checked").isBoolean() || !item.get("position").isIntegralNumber()) throw invalid("Um item de checklist é inválido.");
				}
			}
			if (inputVersion >= 2) {
				JsonNode seriesNodes = root.get("series");
				if (seriesNodes == null || !seriesNodes.isArray() || seriesNodes.size() > MAX_TASKS) throw invalid("A lista de séries da cópia é inválida.");
				for (JsonNode node : seriesNodes) if (!node.isObject() || !fieldNames(node).equals(SERIES_FIELDS)) throw invalid("Uma série do arquivo tem campos inválidos.");
			}
			if (inputVersion >= 4) {
				JsonNode readingNodes = root.get("readingItems");
				if (readingNodes == null || !readingNodes.isArray() || readingNodes.size() > MAX_READING_ITEMS) throw tooLarge("A lista excede o limite de 5.000 itens de leitura.");
				for (JsonNode node : readingNodes) {
					Set<String> fields = node.isObject() ? fieldNames(node) : Set.of();
					boolean validReadingFields = inputVersion >= 7
						? fields.equals(READING_FIELDS_V7) || fields.equals(READING_FIELDS_V7_LOCAL_COVER) || fields.equals(READING_FIELDS_V7_NO_COVER)
						: inputVersion >= 6
						? fields.equals(READING_FIELDS_V6) || fields.equals(READING_FIELDS_V6_NO_COVER)
						: inputVersion == 5 ? fields.equals(READING_FIELDS_V5) || fields.equals(READING_FIELDS)
						: fields.equals(READING_FIELDS);
					if (!node.isObject() || !validReadingFields) throw invalid("Um item de leitura tem campos inválidos.");
					if (!node.get("id").isTextual() || !node.get("title").isTextual() || !node.get("type").isTextual()
						|| !isTextOrNull(node.get("authorOrSource")) || !isTextOrNull(node.get("url"))
						|| !node.get("status").isTextual() || !node.get("progressMarker").isTextual()
						|| !node.get("note").isTextual() || !node.get("tags").isArray() || !node.get("archived").isBoolean()
						|| !node.get("createdAt").isTextual() || !node.get("updatedAt").isTextual()
						|| (inputVersion >= 6 && ((!isIntegerOrNull(node.get("totalPages"))) || (!isIntegerOrNull(node.get("currentPage")))))
						|| (inputVersion >= 5 && ((node.has("coverSha256") && !isTextOrNull(node.get("coverSha256")))
							|| (node.has("coverFormat") && !isTextOrNull(node.get("coverFormat")))
							|| (node.has("coverSize") && !(node.get("coverSize").isNull() || node.get("coverSize").isIntegralNumber()))))) throw invalid("Um item de leitura tem valores com tipo inválido.");
					if (node.get("tags").size() > ReadingItemService.MAX_TAGS) throw invalid("Um item de leitura excede o limite de etiquetas.");
					for (JsonNode tag : node.get("tags")) if (!tag.isTextual()) throw invalid("Uma etiqueta do arquivo é inválida.");
					if (inputVersion >= 7) {
						JsonNode authorIds = node.get("authorIds");
						if (authorIds == null || !authorIds.isArray() || authorIds.size() > 20) throw invalid("Um item excede o limite de autores.");
						for (JsonNode authorId : authorIds) if (!authorId.isTextual()) throw invalid("O vínculo de autor é inválido.");
						if (node.has("coverSourceUrl") && !isTextOrNull(node.get("coverSourceUrl"))) throw invalid("O link de origem da capa é inválido.");
					}
				}
			}
			if (inputVersion >= 7) {
				JsonNode authorNodes = root.get("authors");
				if (authorNodes == null || !authorNodes.isArray() || authorNodes.size() > MAX_READING_ITEMS) throw invalid("O catálogo de autores da cópia é inválido.");
				for (JsonNode author : authorNodes) if (!author.isObject() || !fieldNames(author).equals(Set.of("id", "name"))
					|| !author.has("id") || !author.get("id").isTextual() || !author.has("name") || !author.get("name").isTextual()) throw invalid("Um autor do arquivo tem campos inválidos.");
			}
			if (inputVersion >= 8) {
				JsonNode habitNodes = root.get("habits");
				if (habitNodes == null || !habitNodes.isArray() || habitNodes.size() > MAX_HABITS) throw tooLarge("A lista excede o limite de 5.000 hábitos.");
				for (JsonNode node : habitNodes) {
					if (!node.isObject() || !fieldNames(node).equals(HABIT_FIELDS)
						|| !node.get("id").isTextual() || !node.get("name").isTextual() || !isTextOrNull(node.get("startDate"))
						|| !node.get("createdAt").isTextual() || !node.get("updatedAt").isTextual()
						|| !node.get("schedules").isArray() || !node.get("pauses").isArray() || !node.get("checkIns").isArray()) throw invalid("Um hábito da cópia está inválido.");
					for (JsonNode schedule : node.get("schedules")) if (!schedule.isObject() || !fieldNames(schedule).equals(HABIT_SCHEDULE_FIELDS)
						|| !isTextOrNull(schedule.get("effectiveFrom")) || !schedule.get("weekdaysMask").isIntegralNumber()) throw invalid("Uma agenda de hábito da cópia está inválida.");
					for (JsonNode pause : node.get("pauses")) if (!pause.isObject() || !fieldNames(pause).equals(HABIT_PAUSE_FIELDS)
						|| !isTextOrNull(pause.get("pausedOn")) || !isTextOrNull(pause.get("resumedOn"))) throw invalid("Uma pausa de hábito da cópia está inválida.");
					for (JsonNode checkIn : node.get("checkIns")) if (!checkIn.isTextual()) throw invalid("Um registro de hábito da cópia está inválido.");
				}
			}
			if (inputVersion >= 9) {
				JsonNode focusNodes = root.get("focusSessions");
				if (focusNodes == null || !focusNodes.isArray() || focusNodes.size() > MAX_FOCUS_SESSIONS) throw tooLarge("A cópia excede o limite de 50.000 sessões de foco.");
				for (JsonNode node : focusNodes) {
					if (!node.isObject() || !fieldNames(node).equals(FOCUS_SESSION_FIELDS)
						|| !node.get("id").isTextual() || !node.get("objective").isTextual()
						|| !node.get("plannedSeconds").isIntegralNumber() || !node.get("focusedSeconds").isIntegralNumber()
						|| !node.get("state").isTextual() || !isTextOrNull(node.get("startedAt"))
						|| !isTextOrNull(node.get("resumedAt")) || !isTextOrNull(node.get("pausedAt"))
						|| !isTextOrNull(node.get("endedAt")) || !isTextOrNull(node.get("endReason")) || !isTextOrNull(node.get("taskId")))
						throw invalid("Uma sessão de foco da cópia está inválida.");
				}
			}
			if (inputVersion >= 10) {
				JsonNode noteNodes = root.get("notes");
				if (noteNodes == null || !noteNodes.isArray() || noteNodes.size() > MAX_NOTES) throw tooLarge("A cópia excede o limite de 5.000 notas.");
				for (JsonNode node : noteNodes) {
					if (!node.isObject() || !fieldNames(node).equals(NOTE_FIELDS)
						|| !node.get("id").isTextual() || !node.get("title").isTextual() || !node.get("body").isTextual()
						|| !node.get("tags").isArray() || !node.get("pinned").isBoolean() || !node.get("archived").isBoolean()
						|| !isTextOrNull(node.get("relatedTaskId")) || !node.get("relatedTaskUnavailable").isBoolean()
						|| !node.get("createdAt").isTextual() || !node.get("updatedAt").isTextual()) throw invalid("Uma nota da cópia está inválida.");
					if (node.get("tags").size() > NoteService.MAX_TAGS) throw invalid("Uma nota excede o limite de etiquetas.");
					for (JsonNode tag : node.get("tags")) if (!tag.isTextual()) throw invalid("Uma etiqueta da nota é inválida.");
				}
			}
			if (inputVersion >= 11) {
				JsonNode omitted = root.get("omittedSections");
				if (omitted == null || !omitted.isArray() || omitted.size() > OMITTABLE_SECTIONS.size()) throw invalid("A lista de áreas omitidas é inválida.");
				Set<String> unique = new LinkedHashSet<>();
				for (JsonNode section : omitted) {
					if (!section.isTextual() || !OMITTABLE_SECTIONS.contains(section.asText()) || !unique.add(section.asText()))
						throw invalid("A lista de áreas omitidas contém um valor inválido ou repetido.");
				}
			}
			BackupFile file = mapper.treeToValue(root, BackupFile.class);
			if (inputVersion < 4) file = new BackupFile(file.formatVersion(), file.generatedAt(), file.tasks(), file.series(), List.of(), List.of(), List.of());
			validateFile(file);
			file = new BackupFile(file.formatVersion(), file.generatedAt(), file.tasks(), file.series(), normalizeReadingItems(file.readingItems(), inputVersion),
				inputVersion >= 7 ? file.authors() : List.of(), inputVersion >= 8 ? normalizeHabits(file.habits()) : List.of(),
				inputVersion >= 9 ? normalizeFocusSessions(file.focusSessions(), file.generatedAt()) : List.of(),
				inputVersion >= 10 ? normalizeNotes(file.notes()) : List.of(),
				inputVersion >= 11 ? file.omittedSections() : List.of());
			Map<String, BackupTask> unique = new LinkedHashMap<>();
			Set<String> internalConflicts = new LinkedHashSet<>();
			int repeatedIdenticalIds = 0;
			for (BackupTask task : file.tasks()) {
				BackupTask normalized = normalize(task, inputVersion);
				BackupTask previous = unique.putIfAbsent(normalized.id(), normalized);
				if (previous != null) {
					if (previous.equals(normalized)) repeatedIdenticalIds++;
					else internalConflicts.add(normalized.id());
				}
			}
			Map<String, BackupReadingItem> uniqueReadingItems = new LinkedHashMap<>();
			Set<String> internalReadingConflicts = new LinkedHashSet<>();
			int repeatedIdenticalReadingIds = 0;
			for (BackupReadingItem item : file.readingItems()) {
				BackupReadingItem normalized = normalizeReadingItem(item, inputVersion);
				BackupReadingItem previous = uniqueReadingItems.putIfAbsent(normalized.id(), normalized);
				if (previous != null) {
					if (sameReadingItem(previous, normalized, inputVersion >= 5, inputVersion >= 7)) repeatedIdenticalReadingIds++;
					else internalReadingConflicts.add(normalized.id());
				}
			}
			Map<String, Path> coverAssets = validateAndMapAssets(file, inputVersion, archiveEntries);
			Map<String, BackupHabit> uniqueHabits = new LinkedHashMap<>();
			Set<String> internalHabitConflicts = new LinkedHashSet<>();
			int repeatedIdenticalHabitIds = 0;
			for (BackupHabit habit : file.habits()) {
				BackupHabit previous = uniqueHabits.putIfAbsent(habit.id(), habit);
				if (previous != null) {
					if (previous.equals(habit)) repeatedIdenticalHabitIds++;
					else internalHabitConflicts.add(habit.id());
				}
			}
			Map<String, BackupFocusSession> uniqueFocus = new LinkedHashMap<>();
			Set<String> internalFocusConflicts = new LinkedHashSet<>();
			int repeatedIdenticalFocusIds = 0;
			for (BackupFocusSession session : file.focusSessions()) {
				BackupFocusSession previous = uniqueFocus.putIfAbsent(session.id(), session);
				if (previous != null) {
					if (previous.equals(session)) repeatedIdenticalFocusIds++;
					else internalFocusConflicts.add(session.id());
				}
			}
			if (uniqueFocus.values().stream().filter(session -> !"ENDED".equals(session.state())).count() > 1)
				throw invalid("A cópia não pode conter mais de uma sessão ativa ou pausada.");
			Map<String, BackupNote> uniqueNotes = new LinkedHashMap<>();
			Set<String> internalNoteConflicts = new LinkedHashSet<>();
			int repeatedIdenticalNoteIds = 0;
			for (BackupNote note : file.notes()) {
				BackupNote previous = uniqueNotes.putIfAbsent(note.id(), note);
				if (previous != null) {
					if (previous.equals(note)) repeatedIdenticalNoteIds++;
					else internalNoteConflicts.add(note.id());
				}
			}
			return new DecodedBackup(file, wholeDigest, unique, internalConflicts, repeatedIdenticalIds,
				uniqueReadingItems, internalReadingConflicts, repeatedIdenticalReadingIds, uniqueHabits, internalHabitConflicts,
				repeatedIdenticalHabitIds, uniqueFocus, internalFocusConflicts, repeatedIdenticalFocusIds, uniqueNotes, internalNoteConflicts,
				repeatedIdenticalNoteIds, coverAssets, staging);
		} catch (ResponseStatusException error) {
			throw error;
		} catch (Exception error) {
			throw invalid("Não foi possível ler o arquivo JSON. Escolha uma cópia válida do Hub.");
		}
	}

	private void validateFile(BackupFile file) {
		if (file == null || file.formatVersion() < 1 || file.formatVersion() > FORMAT_VERSION) throw invalid("Esta versão da cópia não é compatível.");
		if (file.generatedAt() == null || file.tasks() == null) throw invalid("O arquivo está incompleto.");
		if (file.tasks().size() > MAX_TASKS) throw tooLarge("O arquivo excede o limite de 5.000 tarefas.");
		for (BackupTask task : file.tasks()) {
			if (task == null) throw invalid("O arquivo contém uma tarefa inválida.");
			try {
				if (task.id() == null || !UUID.fromString(task.id()).toString().equals(task.id())) throw invalid("O arquivo contém um identificador de tarefa inválido.");
			} catch (IllegalArgumentException error) { throw invalid("O arquivo contém um identificador de tarefa inválido."); }
			if (task.title() == null || task.title().isBlank() || task.title().length() > 1024) throw invalid("O arquivo contém um título inválido.");
			if (!"LOCAL".equals(task.type())) throw invalid("A cópia pode conter somente tarefas locais.");
			if (task.category() == null || task.category().isBlank() || task.category().length() > 64) throw invalid("O arquivo contém uma categoria inválida.");
			if (task.color() == null || !task.color().matches("^#[0-9A-Fa-f]{6}$")) throw invalid("O arquivo contém uma cor inválida.");
			if (task.priority() == null) throw invalid("O arquivo contém uma prioridade inválida.");
			if (task.notes() == null || task.notes().length() > 2000) throw invalid("O arquivo contém notas inválidas.");
			if (file.formatVersion() >= 2 && ((task.seriesId() == null) != (task.seriesPosition() == null))) throw invalid("Uma ocorrência contém vínculo de série incompleto.");
			if (file.formatVersion() >= 3) validateChecklist(task.checklist());
			if (file.formatVersion() >= 3 && task.seriesId() != null && !task.checklist().isEmpty()) throw invalid("Ocorrências de séries não podem conter checklist.");
		}
		if (file.formatVersion() >= 2) validateSeries(file);
		if (file.formatVersion() >= 4) {
			if (file.readingItems() == null || file.readingItems().size() > MAX_READING_ITEMS) throw tooLarge("A cópia excede o limite de 5.000 itens de leitura.");
			for (BackupReadingItem item : file.readingItems()) validateReadingItem(item);
		}
		if (file.formatVersion() >= 7) {
			if (file.authors() == null || file.authors().size() > MAX_READING_ITEMS) throw invalid("O catálogo de autores da cópia é inválido.");
			Set<String> authorIds = new LinkedHashSet<>();
			Set<String> authorNames = new LinkedHashSet<>();
			for (BackupAuthor author : file.authors()) {
				try { if (author == null || author.id() == null || !UUID.fromString(author.id()).toString().equals(author.id())) throw invalid("O arquivo contém um identificador de autor inválido."); }
				catch (RuntimeException error) { throw invalid("O arquivo contém um identificador de autor inválido."); }
				if (author.name() == null || author.name().isBlank() || author.name().strip().length() > 160) throw invalid("O arquivo contém um nome de autor inválido.");
				if (!authorIds.add(author.id()) || !authorNames.add(ReadingAuthor.normalize(author.name()))) throw invalid("O arquivo repete um autor.");
			}
			for (BackupReadingItem item : file.readingItems()) {
				if (item.authorIds() == null || item.authorIds().size() > 20 || new LinkedHashSet<>(item.authorIds()).size() != item.authorIds().size()) throw invalid("Um item tem vínculos de autor inválidos.");
				if (!"BOOK".equals(item.type()) && !item.authorIds().isEmpty()) throw invalid("Somente livros podem ter autores do catálogo.");
				for (String authorId : item.authorIds()) if (!authorIds.contains(authorId)) throw invalid("Um livro aponta para um autor inexistente no arquivo.");
				if (item.coverSourceUrl() != null && item.coverSourceUrl().length() > 2048) throw invalid("O link de origem da capa excede o limite.");
				if (item.coverSourceUrl() != null) validateCoverSourceUrl(item.coverSourceUrl());
				if (item.coverSourceUrl() != null && item.coverSha256() == null) throw invalid("A origem da capa exige uma imagem no pacote.");
			}
		}
		if (file.formatVersion() >= 8) {
			if (file.habits() == null || file.habits().size() > MAX_HABITS) throw tooLarge("A cópia excede o limite de 5.000 hábitos.");
			Set<String> ids = new LinkedHashSet<>();
			for (BackupHabit habit : file.habits()) {
				validateHabit(habit);
				if (!ids.add(habit.id())) throw invalid("O arquivo repete um hábito.");
			}
		}
		if (file.formatVersion() >= 9) {
			if (file.focusSessions() == null || file.focusSessions().size() > MAX_FOCUS_SESSIONS) throw tooLarge("A cópia excede o limite de 50.000 sessões de foco.");
			Set<String> ids = new LinkedHashSet<>();
			int openSessions = 0;
			for (BackupFocusSession session : file.focusSessions()) {
				validateFocusSession(session, file.generatedAt());
				if (!ids.add(session.id())) throw invalid("O arquivo repete uma sessão de foco.");
				if (!"ENDED".equals(session.state())) openSessions++;
			}
			if (openSessions > 1) throw invalid("A cópia não pode conter mais de uma sessão ativa ou pausada.");
		}
		if (file.formatVersion() >= 10) {
			if (file.notes() == null || file.notes().size() > MAX_NOTES) throw tooLarge("A cópia excede o limite de 5.000 notas.");
			Set<String> ids = new LinkedHashSet<>();
			for (BackupNote note : file.notes()) {
				validateNote(note, file.generatedAt());
				if (!ids.add(note.id())) throw invalid("O arquivo repete uma nota.");
			}
		}
		if (file.formatVersion() >= 11) {
			if (file.omittedSections() == null || file.omittedSections().size() > OMITTABLE_SECTIONS.size()
					|| new LinkedHashSet<>(file.omittedSections()).size() != file.omittedSections().size()
					|| !OMITTABLE_SECTIONS.containsAll(file.omittedSections())) throw invalid("A lista de áreas omitidas é inválida.");
			for (String section : file.omittedSections()) {
				boolean containsData = switch (section) {
					case "habits" -> !file.habits().isEmpty();
					case "focusSessions" -> !file.focusSessions().isEmpty();
					case "notes" -> !file.notes().isEmpty();
					default -> true;
				};
				if (containsData) throw invalid("Uma área marcada como omitida contém registros.");
			}
		}
	}

	private void validateNote(BackupNote note, Instant generatedAt) {
		try { if (note == null || note.id() == null || !UUID.fromString(note.id()).toString().equals(note.id())) throw invalid("O arquivo contém um identificador de nota inválido."); }
		catch (RuntimeException error) { throw invalid("O arquivo contém um identificador de nota inválido."); }
		if (note.title() == null || note.title().isBlank() || note.title().strip().length() > NoteService.MAX_TITLE_LENGTH
				|| note.body() == null || note.body().length() > NoteService.MAX_BODY_LENGTH || note.tags() == null
				|| note.tags().size() > NoteService.MAX_TAGS || note.createdAt() == null || note.updatedAt() == null
				|| note.createdAt().isAfter(generatedAt) || note.updatedAt().isBefore(note.createdAt()) || note.updatedAt().isAfter(generatedAt)) throw invalid("A cópia contém dados de nota inválidos.");
		Set<String> tags = new LinkedHashSet<>();
		for (String tag : note.tags()) {
			if (tag == null || tag.isBlank() || tag.strip().length() > NoteService.MAX_TAG_LENGTH
					|| !tags.add(TaskSearchNormalization.normalize(tag.strip()).toLowerCase(java.util.Locale.forLanguageTag("pt-BR"))))
				throw invalid("A cópia contém etiquetas de nota inválidas ou repetidas.");
		}
		if (note.relatedTaskId() != null) {
			try { if (!UUID.fromString(note.relatedTaskId()).toString().equals(note.relatedTaskId())) throw invalid("O vínculo de tarefa de uma nota é inválido."); }
			catch (RuntimeException error) { throw invalid("O vínculo de tarefa de uma nota é inválido."); }
		} else if (note.relatedTaskUnavailable()) throw invalid("Uma nota sem tarefa não pode marcar o vínculo como indisponível.");
	}

	private void validateFocusSession(BackupFocusSession session, Instant generatedAt) {
		try { if (session == null || session.id() == null || !UUID.fromString(session.id()).toString().equals(session.id())) throw invalid("O arquivo contém um identificador de sessão inválido."); }
		catch (RuntimeException error) { throw invalid("O arquivo contém um identificador de sessão inválido."); }
		if (session.objective() == null || session.objective().isBlank() || session.objective().strip().length() > 240
				|| session.plannedSeconds() < 60 || session.plannedSeconds() > 43_200 || session.focusedSeconds() < 0
				|| session.startedAt() == null || generatedAt == null || session.startedAt().isAfter(generatedAt))
			throw invalid("A cópia contém dados de sessão inválidos.");
		if (session.taskId() != null) {
			try { if (!UUID.fromString(session.taskId()).toString().equals(session.taskId())) throw invalid("O vínculo de tarefa da sessão é inválido."); }
			catch (RuntimeException error) { throw invalid("O vínculo de tarefa da sessão é inválido."); }
		}
		if ("ACTIVE".equals(session.state())) {
			if (session.resumedAt() == null || session.resumedAt().isBefore(session.startedAt()) || session.resumedAt().isAfter(generatedAt)
					|| session.endedAt() != null || session.endReason() != null) throw invalid("O estado ativo da sessão na cópia é inválido.");
		} else if ("PAUSED".equals(session.state())) {
			if (session.resumedAt() != null || session.pausedAt() == null || session.pausedAt().isBefore(session.startedAt())
					|| session.pausedAt().isAfter(generatedAt) || session.endedAt() != null || session.endReason() != null)
				throw invalid("O estado pausado da sessão na cópia é inválido.");
		} else if ("ENDED".equals(session.state())) {
			if (session.resumedAt() != null || session.endedAt() == null || session.endedAt().isBefore(session.startedAt())
				|| session.endedAt().isAfter(generatedAt) || session.endReason() == null || !Set.of("NORMAL", "EARLY", "ADJUSTED").contains(session.endReason()))
				throw invalid("O encerramento da sessão na cópia é inválido.");
		} else throw invalid("O estado da sessão na cópia é inválido.");
	}

	private List<BackupFocusSession> normalizeFocusSessions(List<BackupFocusSession> sessions, Instant generatedAt) {
		if (sessions == null) return List.of();
		return sessions.stream().map(session -> {
			if (!"ACTIVE".equals(session.state())) return session;
			long extra = java.time.Duration.between(session.resumedAt(), generatedAt).getSeconds();
			long focused = Math.addExact((long) session.focusedSeconds(), Math.max(0, extra));
			if (focused > Integer.MAX_VALUE) throw invalid("O tempo registrado da sessão excede o limite suportado.");
			return new BackupFocusSession(session.id(), session.objective(), session.plannedSeconds(), (int) focused,
				"PAUSED", session.startedAt(), null, generatedAt, null, null, session.taskId());
		}).toList();
	}

	private void validateHabit(BackupHabit habit) {
		try { if (habit == null || habit.id() == null || !UUID.fromString(habit.id()).toString().equals(habit.id())) throw invalid("O arquivo contém um identificador de hábito inválido."); }
		catch (RuntimeException error) { throw invalid("O arquivo contém um identificador de hábito inválido."); }
		if (habit.name() == null || habit.name().isBlank() || habit.name().strip().length() > 120 || habit.startDate() == null
				|| habit.createdAt() == null || habit.updatedAt() == null || habit.updatedAt().isBefore(habit.createdAt())) throw invalid("O arquivo contém dados de hábito inválidos.");
		if (habit.schedules() == null || habit.schedules().isEmpty() || habit.schedules().size() > 10_000
				|| habit.pauses() == null || habit.pauses().size() > 10_000 || habit.checkIns() == null || habit.checkIns().size() > 100_000)
			throw invalid("O arquivo contém histórico de hábito inválido ou longo demais.");
		LocalDate previousSchedule = null;
		for (BackupHabitSchedule schedule : habit.schedules()) {
			if (schedule.effectiveFrom() == null || schedule.effectiveFrom().isBefore(habit.startDate())
					|| schedule.weekdaysMask() < 1 || schedule.weekdaysMask() > 127
					|| (previousSchedule != null && !schedule.effectiveFrom().isAfter(previousSchedule))) throw invalid("O arquivo contém uma agenda de hábito inválida.");
			previousSchedule = schedule.effectiveFrom();
		}
		LocalDate previousPauseEnd = null;
		for (BackupHabitPause pause : habit.pauses()) {
			if (pause.pausedOn() == null || (pause.resumedOn() != null && pause.resumedOn().isBefore(pause.pausedOn()))
					|| (previousPauseEnd != null && pause.pausedOn().isBefore(previousPauseEnd))) throw invalid("O arquivo contém um intervalo de pausa inválido.");
			if (pause.resumedOn() == null && pause != habit.pauses().getLast()) throw invalid("O arquivo contém mais de uma pausa ativa.");
			previousPauseEnd = pause.resumedOn();
		}
		Set<LocalDate> checkIns = new LinkedHashSet<>();
		for (LocalDate date : habit.checkIns()) {
			if (date == null || !checkIns.add(date)) throw invalid("O arquivo repete um registro de hábito.");
			if (date.isBefore(habit.startDate())) throw invalid("O arquivo contém um registro anterior ao início do hábito.");
			BackupHabitSchedule effectiveSchedule = habit.schedules().stream()
				.filter(schedule -> !schedule.effectiveFrom().isAfter(date))
				.max(java.util.Comparator.comparing(BackupHabitSchedule::effectiveFrom)).orElse(null);
			if (effectiveSchedule == null || (effectiveSchedule.weekdaysMask() & (1 << (date.getDayOfWeek().getValue() - 1))) == 0)
				throw invalid("O arquivo contém um registro em um dia que não estava previsto.");
			boolean paused = habit.pauses().stream().anyMatch(pause -> !date.isBefore(pause.pausedOn())
				&& (pause.resumedOn() == null || date.isBefore(pause.resumedOn())) && !date.equals(pause.pausedOn()));
			if (paused) throw invalid("O arquivo contém um registro durante uma pausa do hábito.");
		}
	}

	private void validateReadingItem(BackupReadingItem item) {
		try { if (item == null || item.id() == null || !UUID.fromString(item.id()).toString().equals(item.id())) throw invalid("O arquivo contém um identificador de conteúdo inválido."); }
		catch (RuntimeException error) { throw invalid("O arquivo contém um identificador de conteúdo inválido."); }
		if (item.title() == null || item.title().isBlank() || item.title().length() > 1024) throw invalid("O arquivo contém um título de conteúdo inválido.");
		if (!Set.of("BOOK", "ARTICLE", "VIDEO").contains(item.type())) throw invalid("O arquivo contém um tipo de conteúdo inválido.");
		if (item.authorOrSource() != null && item.authorOrSource().length() > 512) throw invalid("O arquivo contém uma origem inválida.");
		if (item.url() != null) validateBackupUrl(item.url());
		if (!Set.of("TO_READ", "IN_PROGRESS", "COMPLETED").contains(item.status())) throw invalid("O arquivo contém um estado de leitura inválido.");
		if (item.progressMarker() == null || item.progressMarker().length() > 512) throw invalid("O arquivo contém um marcador de progresso inválido.");
		if (item.note() == null || item.note().length() > 2000) throw invalid("O arquivo contém uma nota inválida.");
		if (item.tags() == null || item.tags().size() > ReadingItemService.MAX_TAGS) throw invalid("O arquivo excede o limite de etiquetas.");
		for (String tag : item.tags()) if (tag == null || tag.isBlank() || tag.strip().length() > ReadingItemService.MAX_TAG_LENGTH) throw invalid("O arquivo contém uma etiqueta inválida.");
		if (item.createdAt() == null || item.updatedAt() == null || item.updatedAt().isBefore(item.createdAt())) throw invalid("O arquivo contém datas de conteúdo inválidas.");
		if ((item.totalPages() == null && item.currentPage() != null)
			|| (item.totalPages() != null && (!"BOOK".equals(item.type()) || item.totalPages() < 1 || item.totalPages() > ReadingItemService.MAX_TOTAL_PAGES
				|| (item.currentPage() != null && (item.currentPage() < 0 || item.currentPage() > item.totalPages()))))) throw invalid("O arquivo contém um progresso por páginas inválido.");
		if ((item.coverSha256() == null) != (item.coverFormat() == null) || (item.coverSha256() == null) != (item.coverSize() == null)) throw invalid("O manifesto tem uma referência de imagem incompleta.");
		if (item.coverSha256() != null && (!item.coverSha256().matches("[0-9a-f]{64}") || !Set.of("jpg", "png").contains(item.coverFormat())
			|| item.coverSize() < 1 || item.coverSize() > ReadingCoverStorageService.MAX_FILE_BYTES)) throw invalid("O manifesto tem uma imagem inválida.");
	}

	private List<BackupReadingItem> normalizeReadingItems(List<BackupReadingItem> items, int version) {
		return (items == null ? List.<BackupReadingItem>of() : items).stream().map(item -> normalizeReadingItem(item, version)).toList();
	}

	private BackupReadingItem normalizeReadingItem(BackupReadingItem item, int version) {
		List<String> tags = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (String tag : item.tags()) if (seen.add(tag.strip().toLowerCase(java.util.Locale.forLanguageTag("pt-BR")))) tags.add(tag.strip());
		Integer totalPages = version >= 6 ? item.totalPages() : null;
		Integer currentPage = version >= 6 && totalPages != null ? (item.currentPage() == null ? 0 : item.currentPage()) : null;
		return new BackupReadingItem(item.id(), item.title(), item.type(), item.authorOrSource(), item.url(), item.status(),
			item.progressMarker(), totalPages, currentPage,
			item.note(), tags, item.archived(), item.createdAt().truncatedTo(ChronoUnit.MICROS), item.updatedAt().truncatedTo(ChronoUnit.MICROS),
			version >= 5 ? item.coverSha256() : null, version >= 5 ? item.coverFormat() : null,
			version >= 5 ? item.coverSize() : null, version >= 7 ? item.coverSourceUrl() : null,
			version >= 7 && item.authorIds() != null ? item.authorIds() : List.of());
	}

	private List<BackupHabit> normalizeHabits(List<BackupHabit> habits) {
		return (habits == null ? List.<BackupHabit>of() : habits).stream().map(item -> new BackupHabit(item.id(), item.name().strip(),
			item.startDate(), item.createdAt().truncatedTo(ChronoUnit.MICROS), item.updatedAt().truncatedTo(ChronoUnit.MICROS),
			item.schedules().stream().sorted(java.util.Comparator.comparing(BackupHabitSchedule::effectiveFrom)).toList(),
			item.pauses().stream().sorted(java.util.Comparator.comparing(BackupHabitPause::pausedOn)).toList(),
			item.checkIns().stream().sorted().toList())).toList();
	}

	private List<BackupNote> normalizeNotes(List<BackupNote> notes) {
		return (notes == null ? List.<BackupNote>of() : notes).stream().map(note -> {
			List<String> cleanTags = new ArrayList<>();
			Set<String> seen = new LinkedHashSet<>();
			for (String tag : note.tags()) if (seen.add(TaskSearchNormalization.normalize(tag.strip()).toLowerCase(java.util.Locale.forLanguageTag("pt-BR")))) cleanTags.add(tag.strip());
			return new BackupNote(note.id(), note.title().strip(), note.body(), cleanTags, note.pinned(), note.archived(), note.relatedTaskId(), note.relatedTaskUnavailable(),
				note.createdAt().truncatedTo(ChronoUnit.MICROS), note.updatedAt().truncatedTo(ChronoUnit.MICROS));
		}).toList();
	}

	private void validateBackupUrl(String value) {
		if (value.length() > 2048) throw invalid("O arquivo contém um endereço web longo demais.");
		URI uri;
		try {
			uri = URI.create(value);
		} catch (IllegalArgumentException error) { throw invalid("O arquivo contém um endereço web inválido."); }
		if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
			|| !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) throw invalid("O arquivo contém um endereço web inválido.");
	}

	private void validateCoverSourceUrl(String value) {
		try {
			URI uri = URI.create(value);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
			if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
				|| (uri.getPort() != -1 && uri.getPort() != 443)
				|| !(host.equals("books.google.com") || host.endsWith(".googleusercontent.com") || host.endsWith(".gstatic.com")))
				throw invalid("O arquivo contém uma origem de capa não permitida.");
		} catch (IllegalArgumentException error) {
			throw invalid("O arquivo contém uma origem de capa inválida.");
		}
	}

	private void validateChecklist(List<BackupChecklistItem> items) {
		if (items == null || items.size() > TaskChecklistService.MAX_ITEMS) throw invalid("O checklist excede 20 itens.");
		Set<String> ids = new LinkedHashSet<>();
		for (int i = 0; i < items.size(); i++) {
			BackupChecklistItem item = items.get(i);
			try { if (item.id() == null || !UUID.fromString(item.id()).toString().equals(item.id())) throw invalid("O checklist contém um identificador inválido."); }
			catch (RuntimeException error) { throw invalid("O checklist contém um identificador inválido."); }
			if (!ids.add(item.id()) || item.text() == null || item.text().isBlank() || item.text().length() > TaskChecklistService.MAX_TEXT_LENGTH || item.position() != i) throw invalid("O checklist contém texto, identidade ou ordem inválida.");
		}
	}

	private void validateSeries(BackupFile file) {
		Map<String, BackupSeries> byId = new LinkedHashMap<>();
		for (BackupSeries item : file.series() == null ? List.<BackupSeries>of() : file.series()) {
			try { if (item.id() == null || !UUID.fromString(item.id()).toString().equals(item.id())) throw invalid("O arquivo contém um identificador de série inválido."); }
			catch (RuntimeException error) { throw invalid("O arquivo contém um identificador de série inválido."); }
			if (item.title() == null || item.title().isBlank() || item.title().length() > 1024 || item.startDate() == null
				|| item.occurrenceCount() < 2 || item.occurrenceCount() > 52 || item.timeZoneId() == null || item.timeZoneId().isBlank()) throw invalid("O arquivo contém uma série inválida.");
			try { java.time.ZoneId.of(item.timeZoneId()); } catch (RuntimeException error) { throw invalid("O fuso horário de uma série é inválido."); }
			if (byId.putIfAbsent(item.id(), item) != null) throw invalid("O arquivo repete o identificador de uma série.");
		}
		Map<String, Set<Integer>> positions = new LinkedHashMap<>();
		for (BackupTask task : file.tasks()) {
			if (task.seriesId() == null) continue;
			BackupSeries parent = byId.get(task.seriesId());
			if (parent == null || task.seriesPosition() == null || task.seriesPosition() < 1 || task.seriesPosition() > parent.occurrenceCount()) throw invalid("Uma ocorrência aponta para uma série inexistente ou posição inválida.");
			if (!positions.computeIfAbsent(task.seriesId(), ignored -> new LinkedHashSet<>()).add(task.seriesPosition())) throw invalid("O arquivo repete uma posição de ocorrência.");
		}
	}

	private BackupTask normalize(BackupTask task, int version) {
		return new BackupTask(task.id(), task.title(), task.dueDate(), task.dueTime(), task.completed(), task.type(),
			task.category(), task.color(), task.priority(), task.notes(), version >= 2 ? task.seriesId() : null,
			version >= 2 ? task.seriesPosition() : null, version >= 3 && task.checklist() != null ? task.checklist() : List.of());
	}

	private Set<String> fieldNames(JsonNode node) {
		return new LinkedHashSet<>(node.propertyNames());
	}

	private boolean isTextOrNull(JsonNode node) { return node != null && (node.isNull() || node.isTextual()); }
	private boolean isIntegerOrNull(JsonNode node) { return node != null && (node.isNull() || (node.isIntegralNumber() && node.canConvertToInt())); }

	private BackupTask toBackupTask(Task task) {
		return new BackupTask(task.getId(), task.getTitle(), task.getDueDate(), task.getDueTime(), task.isCompleted(),
			task.getType().name(), task.getCategory(), task.getColor(), task.getPriority(), task.getNotes(), task.getSeriesId(), task.getSeriesPosition(), checklists.exportItems(task.getId()));
	}

	private BackupFocusSession toBackupFocusSession(com.github.cleytonorocha.hub.dto.FocusSessionDtos.SessionView session, Instant snapshotAt) {
		String state = session.state();
		int focusedSeconds = session.focusedSeconds();
		Instant resumedAt = session.resumedAt();
		Instant pausedAt = session.pausedAt();
		if ("ACTIVE".equals(state)) {
			long elapsed = Math.max(0, java.time.Duration.between(resumedAt, snapshotAt).getSeconds());
			long total = (long) focusedSeconds + elapsed;
			focusedSeconds = (int) Math.min(Integer.MAX_VALUE, total);
			state = "PAUSED";
			resumedAt = null;
			pausedAt = snapshotAt;
		}
		return new BackupFocusSession(session.id(), session.objective(), session.plannedSeconds(), focusedSeconds, state,
			session.startedAt(), resumedAt, pausedAt, session.endedAt(), session.endReason(), session.taskId());
	}

	private BackupSeries toBackupSeries(TaskSeries item) {
		return new BackupSeries(item.getId(), item.getTitle(), item.getStartDate(), item.getDueTime(), item.getOccurrenceCount(), item.getTimeZoneId(), item.isEnded());
	}

	private BackupReadingItem toBackupReadingItem(ReadingItem item, boolean includeCover, String ownerId) {
		String coverSha256 = includeCover ? item.getCoverImageSha256() : null;
		if (includeCover && ((item.getCoverImageName() == null) != (coverSha256 == null))) throw new IllegalStateException("A referência e o hash da imagem de capa estão inconsistentes.");
		String coverFormat = includeCover && item.getCoverImageName() != null ? item.getCoverImageName().substring(item.getCoverImageName().lastIndexOf('.') + 1) : null;
		Long coverSize = null;
		if (includeCover && item.getCoverImageName() != null) {
			try { coverSize = covers.size(ownerId, item.getCoverImageName()); }
			catch (IOException error) { throw new IllegalStateException("Uma imagem de capa não está disponível.", error); }
			if (coverSha256 == null || !coverSha256.matches("[0-9a-f]{64}")) throw new IllegalStateException("O hash da imagem de capa está inválido.");
		}
		return new BackupReadingItem(item.getId(), item.getTitle(), item.getType(), item.getAuthorOrSource(), item.getUrl(),
			item.getStatus(), item.getProgressMarker(), item.getTotalPages(), item.getCurrentPage(), item.getNote(), item.getTags(), item.isArchived(), item.getCreatedAt(), item.getUpdatedAt(),
			coverSha256, coverFormat, coverSize, item.getCoverSourceUrl(), item.getAuthors().stream().map(ReadingAuthor::getId).sorted().toList());
	}

	private List<BackupHabit> exportHabits() {
		List<Habit> local = habits.findAllByOrderByStartDateAscCreatedAtAsc();
		if (local.size() > MAX_HABITS) throw tooLarge("A cópia excede o limite de 5.000 hábitos.");
		return toBackupHabits(local);
	}

	private List<BackupHabit> toBackupHabits(List<Habit> source) {
		if (source.isEmpty()) return List.of();
		List<String> ids = source.stream().map(Habit::getId).toList();
		Map<String, List<HabitSchedule>> schedulesByHabit = new LinkedHashMap<>();
		for (HabitSchedule item : habitSchedules.findAllByHabitIdInOrderByHabitIdAscEffectiveFromAsc(ids))
			schedulesByHabit.computeIfAbsent(item.getHabitId(), ignored -> new ArrayList<>()).add(item);
		Map<String, List<HabitPause>> pausesByHabit = new LinkedHashMap<>();
		for (HabitPause item : habitPauses.findAllByHabitIdInOrderByHabitIdAscPausedOnAsc(ids))
			pausesByHabit.computeIfAbsent(item.getHabitId(), ignored -> new ArrayList<>()).add(item);
		Map<String, List<HabitCheckIn>> checkInsByHabit = new LinkedHashMap<>();
		for (HabitCheckIn item : habitCheckIns.findAllByHabitIdInOrderByHabitIdAscCheckinDateAsc(ids))
			checkInsByHabit.computeIfAbsent(item.getHabitId(), ignored -> new ArrayList<>()).add(item);
		return source.stream().map(habit -> new BackupHabit(habit.getId(), habit.getName(), habit.getStartDate(),
			habit.getCreatedAt().truncatedTo(ChronoUnit.MICROS), habit.getUpdatedAt().truncatedTo(ChronoUnit.MICROS),
			schedulesByHabit.getOrDefault(habit.getId(), List.of()).stream()
				.map(item -> new BackupHabitSchedule(item.getEffectiveFrom(), item.getWeekdaysMask())).toList(),
			pausesByHabit.getOrDefault(habit.getId(), List.of()).stream()
				.map(item -> new BackupHabitPause(item.getPausedOn(), item.getResumedOn())).toList(),
			checkInsByHabit.getOrDefault(habit.getId(), List.of()).stream().map(HabitCheckIn::getCheckinDate).toList())).toList();
	}

	private List<ReadingItem> loadReadingItemsWithTags(List<ReadingItem> source) {
		if (source.isEmpty()) return List.of();
		Map<String, ReadingItem> hydrated = new LinkedHashMap<>();
		List<ReadingItem> related = readingItems.findAllByIdIn(source.stream().map(ReadingItem::getId).toList());
		readingAssociations.populate(related);
		for (ReadingItem item : related) hydrated.put(item.getId(), item);
		return source.stream().map(item -> hydrated.get(item.getId())).toList();
	}

	private Task restoreTask(BackupTask task) {
		Task restored = Task.restoreLocal(task.id(), task.title(), task.dueDate(), task.dueTime(), task.completed(),
			task.category(), task.color(), task.priority() == null ? null : task.priority().name(), task.notes(), task.seriesId(), task.seriesPosition());
		return restored;
	}

	private TaskSeries restoreSeries(BackupSeries item) {
		return TaskSeries.restore(item.id(), item.title(), item.startDate(), item.dueTime(), item.occurrenceCount(), item.timeZoneId(), item.ended());
	}

	private Habit restoreHabit(BackupHabit item) {
		return Habit.restore(item.id(), item.name(), item.startDate(), item.createdAt(), item.updatedAt());
	}

	private HabitPause restorePause(String habitId, BackupHabitPause item) {
		HabitPause pause = new HabitPause(habitId, item.pausedOn());
		if (item.resumedOn() != null) pause.resume(item.resumedOn());
		return pause;
	}

	private ReadingItem restoreReadingItem(BackupReadingItem item, String coverImageName, String coverImageSha256) {
		ReadingItem restored = ReadingItem.restore(item.id(), item.title(), item.type(), item.authorOrSource(), item.url(), item.status(),
			item.progressMarker(), item.totalPages(), item.currentPage(), item.note(), item.tags(), item.archived(), item.createdAt(), item.updatedAt(), coverImageName, coverImageSha256);
		restored.restoreCoverSourceUrl(item.coverSourceUrl());
		restored.replaceAuthors(item.authorIds() == null || item.authorIds().isEmpty() ? Set.of() : new LinkedHashSet<>(authors.findAllById(item.authorIds())), item.updatedAt());
		return restored;
	}

	private String digest(byte[] content) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.", impossible); }
	}

	private String digest(Path path) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
				input.transferTo(java.io.OutputStream.nullOutputStream());
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException error) {
			throw new IllegalStateException("Não foi possível verificar a cópia.", error);
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 indisponível.", impossible);
		}
	}

	private String sha256(byte[] bytes) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.", impossible); }
	}

	private Map<String, Path> validateAndMapAssets(BackupFile file, int version, Map<String, Path> entries) {
		if (version < 5) {
			if (!entries.isEmpty()) throw invalid("Uma cópia antiga não pode conter arquivos de imagem.");
			return Map.of();
		}
		Map<String, Path> byItem = new LinkedHashMap<>();
		Set<String> referenced = new LinkedHashSet<>();
		for (BackupReadingItem item : file.readingItems()) {
			if (item.coverSha256() == null) continue;
			String asset = assetPath(item.id(), item.coverFormat());
			Path path = entries.get(asset);
			if (path == null) throw invalid("Uma imagem declarada no manifesto está ausente.");
			try {
				byte[] bytes = Files.readAllBytes(path);
				if (bytes.length != item.coverSize() || !sha256(bytes).equals(item.coverSha256())
					|| !covers.validate(bytes).equals(item.coverFormat())) throw invalid("Uma imagem do pacote não corresponde ao manifesto.");
			} catch (IOException error) {
				throw invalid("Não foi possível validar uma imagem do pacote.");
			}
			referenced.add(asset);
			byItem.put(item.id(), path);
		}
		if (referenced.size() != entries.size()) throw invalid("O pacote contém imagens não referenciadas.");
		return Map.copyOf(byItem);
	}

	private static String assetPath(String id, String format) { return "assets/" + id + "." + format; }

	private byte[] readEntry(ZipFile zip, ZipEntry entry, long maximum) throws IOException {
		try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) >= 0) {
				if ((long) output.size() + count > maximum) throw tooLarge("Uma entrada do pacote excede o limite permitido.");
				output.write(buffer, 0, count);
			}
			return output.toByteArray();
		}
	}

	private static void putZipEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		entry.setTime(0L);
		zip.putNextEntry(entry);
		zip.write(bytes);
		zip.closeEntry();
	}

	private static void deleteTemporary(Path file) {
		if (file == null) return;
		try { Files.deleteIfExists(file); } catch (IOException ignored) { }
	}

	private static void deleteTree(Path directory) {
		if (directory == null || !Files.exists(directory)) return;
		try (var paths = Files.walk(directory)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(TaskBackupService::deleteTemporary);
		} catch (IOException ignored) { }
	}

	private void cleanupImportedCoversOnRollback(String ownerId, List<String> names) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) names.forEach(name -> covers.delete(ownerId, name));
			}
		});
	}

	private void removePreviewAfterCommit(String token) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			previews.remove(token);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override public void afterCommit() { previews.remove(token); }
		});
	}

	private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
	private ResponseStatusException tooLarge(String message) { return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, message); }

	private record DecodedBackup(BackupFile file, String digest, Map<String, BackupTask> uniqueTasks,
		Set<String> internalConflicts, int repeatedIdenticalIds, Map<String, BackupReadingItem> uniqueReadingItems,
		Set<String> internalReadingConflicts, int repeatedIdenticalReadingIds, Map<String, BackupHabit> uniqueHabits,
		Set<String> internalHabitConflicts, int repeatedIdenticalHabitIds, Map<String, BackupFocusSession> uniqueFocusSessions,
		Set<String> internalFocusConflicts, int repeatedIdenticalFocusIds, Map<String, BackupNote> uniqueNotes,
		Set<String> internalNoteConflicts, int repeatedIdenticalNoteIds, Map<String, Path> coverAssets, Path staging) {
		private void cleanup() { deleteTree(staging); }
	}

	public record PortableCopy(byte[] bytes, Path archivePath, long size, boolean zipped) {
		private static PortableCopy json(byte[] bytes) { return new PortableCopy(bytes, null, bytes.length, false); }
		private static PortableCopy zip(Path path, long size) { return new PortableCopy(null, path, size, true); }
	}

}
