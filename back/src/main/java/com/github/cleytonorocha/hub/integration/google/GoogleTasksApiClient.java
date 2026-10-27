package com.github.cleytonorocha.hub.integration.google;

import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.Task;
import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.TaskList;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public final class GoogleTasksApiClient {
	private static final String API = "https://tasks.googleapis.com/tasks/v1";
	private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {};
	private final RestClient client;

	public GoogleTasksApiClient(RestClient.Builder builder) {
		this.client = builder.build();
	}

	public List<TaskList> listAllTaskLists(String token) {
		List<TaskList> result = new ArrayList<>();
		String pageToken = null;
		do {
			Map<String, Object> response = get(token, pageUri(API + "/users/@me/lists", pageToken));
			for (Map<String, Object> item : items(response)) {
				String id = string(item, "id");
				String title = string(item, "title");
				if (id != null && title != null && !Boolean.TRUE.equals(item.get("deleted"))) result.add(new TaskList(id, title, true));
			}
			pageToken = string(response, "nextPageToken");
		} while (pageToken != null && !pageToken.isBlank());
		return result;
	}

	public List<Task> listAllTasks(String token, String listId, String listTitle) {
		List<Task> result = new ArrayList<>();
		String pageToken = null;
		do {
			URI uri = pageUri(API + "/lists/" + path(listId) + "/tasks", pageToken, "showCompleted", "true", "showDeleted", "true", "showHidden", "true");
			Map<String, Object> response = get(token, uri);
			for (Map<String, Object> item : items(response)) {
				Task task = toTask(item, listId, listTitle);
				if (task != null) result.add(task);
			}
			pageToken = string(response, "nextPageToken");
		} while (pageToken != null && !pageToken.isBlank());
		return result;
	}

	public Task getTask(String token, String listId, String taskId, String listTitle) {
		Map<String, Object> response = get(token, URI.create(API + "/lists/" + path(listId) + "/tasks/" + path(taskId)));
		return toTask(response, listId, listTitle);
	}

	public Task createTask(String token, String listId, String title, LocalDate dueDate, String listTitle) {
		Map<String, Object> body = new HashMap<>();
		body.put("title", title);
		if (dueDate != null) body.put("due", dueDate.toString() + "T00:00:00.000Z");
		Map<String, Object> response = client.post().uri(API + "/lists/" + path(listId) + "/tasks")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).body(body).retrieve().body(MAP_TYPE);
		return toTask(response, listId, listTitle);
	}

	public Task updateTask(String token, String listId, String taskId, LocalDate dueDate, boolean completed, String etag, String listTitle) {
		Map<String, Object> body = new HashMap<>();
		body.put("due", dueDate == null ? null : dueDate.toString() + "T00:00:00.000Z");
		body.put("status", completed ? "completed" : "needsAction");
		if (!completed) body.put("completed", null);
		var request = client.patch().uri(API + "/lists/" + path(listId) + "/tasks/" + path(taskId))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		if (etag != null && !etag.isBlank()) request.header(HttpHeaders.IF_MATCH, etag);
		Map<String, Object> response = request.body(body).retrieve().body(MAP_TYPE);
		return toTask(response, listId, listTitle);
	}

	private Map<String, Object> get(String token, URI uri) {
		return client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(MAP_TYPE);
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> items(Map<String, Object> response) {
		Object values = response.get("items");
		if (!(values instanceof List<?> list)) return List.of();
		return (List<Map<String, Object>>) (List<?>) list;
	}

	private Task toTask(Map<String, Object> item, String listId, String listTitle) {
		String id = string(item, "id");
		if (id == null) return null;
		String due = string(item, "due");
		LocalDate dueDate = null;
		if (due != null && due.length() >= 10) {
			try { dueDate = LocalDate.parse(due.substring(0, 10)); } catch (RuntimeException ignored) { dueDate = null; }
		}
		return new Task(id, listId, listTitle, string(item, "title"), dueDate, "completed".equals(item.get("status")), string(item, "updated"), string(item, "etag"), Boolean.TRUE.equals(item.get("deleted")));
	}

	private URI pageUri(String base, String pageToken, String... extra) {
		var builder = UriComponentsBuilder.fromUriString(base).queryParam("maxResults", 100);
		if (pageToken != null) builder.queryParam("pageToken", pageToken);
		for (int i = 0; i + 1 < extra.length; i += 2) builder.queryParam(extra[i], extra[i + 1]);
		return builder.build().encode().toUri();
	}

	private String path(String value) {
		return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
	}

	private String string(Map<String, Object> item, String key) {
		Object value = item.get(key);
		return value instanceof String text ? text : null;
	}
}
