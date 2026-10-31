package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.NoteDtos.CreateRequest;
import com.github.cleytonorocha.hub.dto.NoteDtos.NoteView;
import com.github.cleytonorocha.hub.dto.NoteDtos.SearchResponse;
import com.github.cleytonorocha.hub.dto.NoteDtos.UpdateRequest;
import com.github.cleytonorocha.hub.entity.Note;
import com.github.cleytonorocha.hub.entity.NoteTag;
import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.repository.NoteRepository;
import com.github.cleytonorocha.hub.repository.NoteTagRepository;
import com.github.cleytonorocha.hub.repository.TaskRepository;
import com.github.cleytonorocha.hub.security.AuthContext;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NoteService {
	public static final int MAX_NOTES = 5_000;
	public static final int MAX_TITLE_LENGTH = 512;
	public static final int MAX_BODY_LENGTH = 2_048;
	public static final int MAX_TAGS = 20;
	public static final int MAX_TAG_LENGTH = 64;
	public static final int MAX_QUERY_LENGTH = 200;
	public static final int MAX_PAGE_SIZE = 100;
	private static final Locale TAG_LOCALE = Locale.forLanguageTag("pt-BR");
	private final NoteRepository notes;
	private final NoteTagRepository tags;
	private final TaskRepository tasks;
	private final NoteCollectionRevision collectionRevision;
	private final NoteTagAssociationService associations;

	public NoteService(NoteRepository notes, NoteTagRepository tags, TaskRepository tasks,
			NoteCollectionRevision collectionRevision, NoteTagAssociationService associations) {
		this.notes = notes; this.tags = tags; this.tasks = tasks; this.collectionRevision = collectionRevision; this.associations = associations;
	}

	@Transactional(readOnly = true)
	public SearchResponse search(String query, String tag, boolean archived, int page, int size) {
		if (page < 0 || page > 1_000_000 || size < 1 || size > MAX_PAGE_SIZE) throw badRequest("A página ou o tamanho dos resultados é inválido.");
		String q = query == null ? "" : query.strip();
		if (q.length() > MAX_QUERY_LENGTH) throw badRequest("A busca excede 200 caracteres.");
		String normalized = TaskSearchNormalization.normalize(q);
		String normalizedTag = tag == null || tag.isBlank() ? null : TaskSearchNormalization.normalize(tag.strip());
		if (normalizedTag != null && normalizedTag.length() > MAX_TAG_LENGTH) throw badRequest("A etiqueta selecionada é inválida.");
		Specification<Note> specification = (root, criteria, builder) -> {
			Predicate predicate = builder.equal(root.get("archived"), archived);
			if (!normalized.isEmpty()) {
				String pattern = "%" + escapeLike(normalized) + "%";
				Expression<String> title = builder.function("HUB_NORMALIZE_SEARCH", String.class, root.get("title"));
				Expression<String> body = builder.function("HUB_NORMALIZE_SEARCH", String.class, root.get("body"));
				predicate = builder.and(predicate, builder.or(builder.like(title, pattern, '\\'), builder.like(body, pattern, '\\')));
			}
			if (normalizedTag != null) {
				Subquery<Integer> matchingTag = criteria.subquery(Integer.class);
				Root<NoteTag> tagRoot = matchingTag.from(NoteTag.class);
				matchingTag.select(builder.literal(1)).where(builder.equal(tagRoot.get("noteId"), root.get("id")),
					builder.equal(tagRoot.get("normalizedTag"), normalizedTag));
				predicate = builder.and(predicate, builder.exists(matchingTag));
			}
			return predicate;
		};
		Page<Note> result = notes.findAll(specification, PageRequest.of(page, size,
			Sort.by(Sort.Order.desc("pinned"), Sort.Order.desc("updatedAt"), Sort.Order.asc("id"))));
		List<Note> hydrated = hydrate(result.getContent());
		return new SearchResponse(hydrated.stream().map(this::toView).toList(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
	}

	@Transactional(readOnly = true)
	public NoteView get(String id) { return toView(hydrate(List.of(require(id))).getFirst()); }

	@Transactional(readOnly = true)
	public List<String> listTags(boolean archived) { return tags.findDistinctTagsByArchived(archived); }

	@Transactional
	public NoteView create(CreateRequest request) {
		collectionRevision.lock();
		List<String> cleanTags = normalizeTags(request.tags());
		String relatedTaskId = normalizeTaskId(request.relatedTaskId());
		if (relatedTaskId != null) requireReadableTask(relatedTaskId);
		if (notes.count() >= MAX_NOTES) throw new ResponseStatusException(HttpStatus.CONFLICT, "O acervo atingiu o limite de 5.000 notas.");
		Instant now = Instant.now();
		Note note = notes.saveAndFlush(new Note(request.title().strip(), request.body(), cleanTags, request.pinned(), relatedTaskId, now));
		associations.save(note);
		collectionRevision.increment();
		return toView(note);
	}

	@Transactional
	public NoteView update(String id, UpdateRequest request) {
		collectionRevision.lock();
		Note note = require(id);
		if (request.revision() == null || request.revision() != note.getRevision())
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta nota foi alterada em outra janela. Revise a versão salva antes de continuar.");
		List<String> cleanTags = normalizeTags(request.tags());
		String requestedTask = normalizeTaskId(request.relatedTaskId());
		String oldTask = note.getRelatedTaskId();
		boolean unavailable = note.isRelatedTaskUnavailable();
		if (!java.util.Objects.equals(oldTask, requestedTask)) {
			if (requestedTask != null) requireReadableTask(requestedTask);
			unavailable = false;
		} else if (requestedTask != null && request.reaffirmRelatedTask()) {
			requireReadableTask(requestedTask);
			unavailable = false;
		}
		note.update(request.title().strip(), request.body(), cleanTags, request.pinned(), request.archived(), requestedTask, unavailable, Instant.now());
		Note updated = notes.saveAndFlush(note);
		associations.save(updated);
		collectionRevision.increment();
		return toView(updated);
	}

	@Transactional
	public void delete(String id) {
		collectionRevision.lock();
		notes.delete(require(id));
		collectionRevision.increment();
	}

	public Note restoreImport(String id, String title, String body, List<String> noteTags, boolean pinned, boolean archived,
			String relatedTaskId, boolean relatedTaskUnavailable, Instant createdAt, Instant updatedAt) {
		List<String> cleanTags = normalizeTags(noteTags);
		return Note.restore(id, title, body, cleanTags, pinned, archived, relatedTaskId, relatedTaskUnavailable, createdAt, updatedAt);
	}

	public void saveImportedTags(Note note) { associations.save(note); }
	public List<Note> loadForBackup() { return hydrate(notes.findAll(Sort.by(Sort.Order.asc("id")))); }

	private Note require(String id) {
		return notes.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nota não encontrada."));
	}

	private List<Note> hydrate(Collection<Note> source) {
		if (source.isEmpty()) return List.of();
		List<Note> values = new ArrayList<>(source);
		associations.populate(values);
		return values;
	}

	private NoteView toView(Note note) {
		String taskStatus = "NONE";
		String taskTitle = null;
		String taskId = note.getRelatedTaskId();
		if (taskId != null) {
			taskStatus = "UNAVAILABLE";
			if (AuthContext.hasPermission("TASK_READ") && !note.isRelatedTaskUnavailable()) {
				Task task = tasks.findById(taskId).orElse(null);
				if (task != null) { taskStatus = "AVAILABLE"; taskTitle = task.getTitle(); }
			}
		}
		return NoteView.from(note, taskStatus, taskTitle);
	}

	private void requireReadableTask(String taskId) {
		if (!AuthContext.hasPermission("TASK_READ")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seu perfil não permite associar notas a tarefas.");
		if (tasks.findById(taskId).isEmpty()) throw badRequest("A tarefa selecionada não está disponível nesta conta.");
	}

	private List<String> normalizeTags(List<String> input) {
		if (input == null) return List.of();
		if (input.size() > MAX_TAGS) throw badRequest("Use até 20 etiquetas por nota.");
		Map<String, String> unique = new LinkedHashMap<>();
		for (String raw : input) {
			if (raw == null || raw.isBlank()) throw badRequest("As etiquetas não podem ficar vazias.");
			String value = raw.strip();
			if (value.length() > MAX_TAG_LENGTH) throw badRequest("Cada etiqueta pode ter até 64 caracteres.");
			unique.putIfAbsent(TaskSearchNormalization.normalize(value).toLowerCase(TAG_LOCALE), value);
		}
		return List.copyOf(unique.values());
	}

	private String normalizeTaskId(String value) { return value == null || value.isBlank() ? null : value.strip(); }
	private String escapeLike(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
	private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
