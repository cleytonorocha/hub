package com.github.cleytonorocha.hub.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserPreferenceService {
	public static final int MAX_DOCUMENT_BYTES = 16 * 1024;
	private static final Set<String> ROOT_KEYS = Set.of("weeklySummaryVisible", "videoDownloaderCardVisible", "sidebarPinned", "taskDefaults", "calendarPresentations");
	private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH");
	private static final Map<String, Set<String>> PRESENTATIONS = Map.of(
		"day", Set.of("", "timeline", "ruler", "periods", "table"),
		"week", Set.of("", "columns", "list", "workweek", "focus"),
		"month", Set.of("", "grid", "weeks", "list", "focus"),
		"year", Set.of("", "matrix", "quarters", "list", "focus"));
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;

	public UserPreferenceService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

	@Transactional(readOnly = true)
	public PreferenceSnapshot get(String ownerId) { return read(ownerId); }

	/** Initialize only once while holding the owning user row. Existing data always wins. */
	@Transactional
	public PreferenceSnapshot initialize(String ownerId, Map<String, Object> legacyDocument) {
		Map<String, Object> normalized = validateComplete(legacyDocument);
		String json = serialize(normalized);
		jdbc.queryForObject("SELECT id FROM hub_user WHERE id = ? FOR UPDATE", String.class, ownerId);
		PreferenceSnapshot existing = read(ownerId);
		if (existing.initialized()) return existing;
		jdbc.update("INSERT INTO hub_user_preference (owner_id, preferences, revision, updated_at) VALUES (?, ?, 0, CURRENT_TIMESTAMP)", ownerId, json);
		return new PreferenceSnapshot(normalized, 0, true);
	}

	@Transactional
	public SaveResult save(String ownerId, long expectedRevision, Map<String, Object> input) {
		Map<String, Object> normalized = validateComplete(input);
		String json = serialize(normalized);
		int updated = jdbc.update("UPDATE hub_user_preference SET preferences = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP WHERE owner_id = ? AND revision = ?",
			json, ownerId, expectedRevision);
		if (updated == 1) return new SaveResult(new PreferenceSnapshot(normalized, expectedRevision + 1, true), true);
		return new SaveResult(read(ownerId), false);
	}

	private PreferenceSnapshot read(String ownerId) {
		return jdbc.query("SELECT preferences, revision FROM hub_user_preference WHERE owner_id = ?", rs -> {
			if (!rs.next()) return new PreferenceSnapshot(defaults(), 0, false);
			long revision = rs.getLong("revision");
			if (revision < 0) throw new PreferenceIntegrityException("A revisão armazenada das preferências é inválida.");
			String raw = rs.getString("preferences");
			if (raw == null || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
				throw new PreferenceIntegrityException("O documento armazenado de preferências excede o limite permitido.");
			}
			try {
				Map<String, Object> decoded = mapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
				return new PreferenceSnapshot(validateComplete(decoded), revision, true);
			} catch (PreferenceIntegrityException error) { throw error; }
			catch (Exception error) { throw new PreferenceIntegrityException("O documento armazenado de preferências está corrompido; ele foi preservado."); }
		}, ownerId);
	}

	private Map<String, Object> validateComplete(Map<String, Object> input) {
		if (input == null || !ROOT_KEYS.equals(input.keySet())) throw new IllegalArgumentException("O documento de preferências está incompleto ou contém campos desconhecidos.");
		Map<String, Object> result = defaults();
		for (String key : Set.of("weeklySummaryVisible", "videoDownloaderCardVisible", "sidebarPinned")) {
			Object value = input.get(key);
			if (!(value instanceof Boolean)) throw new IllegalArgumentException("Uma preferência de exibição precisa ser booleana.");
			result.put(key, value);
		}
		Object taskValue = input.get("taskDefaults");
		if (!(taskValue instanceof Map<?, ?> rawTask)) throw new IllegalArgumentException("As preferências de tarefas são inválidas.");
		Map<String, Object> task = stringMap(rawTask, Set.of("category", "color", "priority"));
		if (!task.keySet().equals(Set.of("category", "color", "priority"))) throw new IllegalArgumentException("As preferências de tarefas estão incompletas.");
		Object category = task.get("category"), color = task.get("color"), priority = task.get("priority");
		if (!(category instanceof String categoryValue) || categoryValue.isBlank() || categoryValue.strip().length() > 64) throw new IllegalArgumentException("A categoria padrão precisa ter de 1 a 64 caracteres.");
		if (!(color instanceof String colorValue) || !colorValue.matches("#[0-9A-Fa-f]{6}")) throw new IllegalArgumentException("A cor padrão é inválida.");
		if (!(priority instanceof String priorityValue) || !PRIORITIES.contains(priorityValue)) throw new IllegalArgumentException("A prioridade padrão é inválida.");
		result.put("taskDefaults", Map.of("category", categoryValue.strip(), "color", colorValue.toUpperCase(java.util.Locale.ROOT), "priority", priorityValue));

		Object calendarValue = input.get("calendarPresentations");
		if (!(calendarValue instanceof Map<?, ?> rawCalendar)) throw new IllegalArgumentException("As preferências do calendário são inválidas.");
		Map<String, Object> calendar = stringMap(rawCalendar, PRESENTATIONS.keySet());
		if (!calendar.keySet().equals(PRESENTATIONS.keySet())) throw new IllegalArgumentException("As preferências do calendário estão incompletas.");
		for (String mode : PRESENTATIONS.keySet()) {
			Object value = calendar.get(mode);
			if (!(value instanceof String presentation) || !PRESENTATIONS.get(mode).contains(presentation)) throw new IllegalArgumentException("Uma apresentação do calendário é inválida.");
		}
		result.put("calendarPresentations", calendar);
		return result;
	}

	private String serialize(Map<String, Object> normalized) {
		try {
			String json = mapper.writeValueAsString(normalized);
			if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) throw new IllegalArgumentException("O documento de preferências excede 16 KiB.");
			return json;
		} catch (IllegalArgumentException error) { throw error; }
		catch (Exception error) { throw new IllegalStateException("Não foi possível serializar as preferências.", error); }
	}

	private Map<String, Object> defaults() {
		Map<String, Object> task = new LinkedHashMap<>(); task.put("category", "Geral"); task.put("color", "#A986FF"); task.put("priority", "NORMAL");
		Map<String, Object> calendar = new LinkedHashMap<>(); calendar.put("day", ""); calendar.put("week", ""); calendar.put("month", ""); calendar.put("year", "");
		Map<String, Object> result = new LinkedHashMap<>(); result.put("weeklySummaryVisible", true); result.put("videoDownloaderCardVisible", true); result.put("sidebarPinned", false);
		result.put("taskDefaults", task); result.put("calendarPresentations", calendar); return result;
	}

	private Map<String, Object> stringMap(Map<?, ?> raw, Set<String> allowed) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : raw.entrySet()) {
			if (!(entry.getKey() instanceof String key) || !allowed.contains(key)) throw new IllegalArgumentException("As preferências contêm campos desconhecidos.");
			result.put(key, entry.getValue());
		}
		return result;
	}

	public record PreferenceSnapshot(Map<String, Object> preferences, long revision, boolean initialized) {}
	public record SaveResult(PreferenceSnapshot snapshot, boolean updated) {}
	public static class PreferenceIntegrityException extends RuntimeException {
		public PreferenceIntegrityException(String message) { super(message); }
	}
}
