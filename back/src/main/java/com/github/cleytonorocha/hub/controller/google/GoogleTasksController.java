package com.github.cleytonorocha.hub.controller.google;

import com.github.cleytonorocha.hub.dto.google.GoogleCreateTaskRequest;
import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.ConnectionStatus;
import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.Notification;
import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.SyncResult;
import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.Task;
import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.TaskChange;
import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.TaskList;
import com.github.cleytonorocha.hub.dto.google.ListSelectionRequest;
import com.github.cleytonorocha.hub.service.google.GoogleOAuthService;
import com.github.cleytonorocha.hub.service.google.GoogleTasksService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/google-tasks")
public final class GoogleTasksController {
	private final GoogleOAuthService oauth;
	private final GoogleTasksService tasks;

	public GoogleTasksController(GoogleOAuthService oauth, GoogleTasksService tasks) {
		this.oauth = oauth;
		this.tasks = tasks;
	}

	@GetMapping("/status")
	public ConnectionStatus status() {
		GoogleOAuthService.ConnectionState state = oauth.status();
		String message = switch (state.status()) {
			case "CONNECTED" -> "Google Tasks conectado.";
			case "CONNECTING" -> "Aguardando autorização do Google.";
			case "REAUTH_REQUIRED" -> "Conecte novamente a conta Google para atualizar suas Tasks.";
		case "CONFIGURATION_REQUIRED" -> "Configure HUB_GOOGLE_CLIENT_ID para conectar o Google Tasks.";
		default -> "Conecte uma conta Google para carregar suas Tasks.";
		};
		return new ConnectionStatus(state.status(), state.configured(), state.lastSyncedAt(), message);
	}

	@PostMapping("/connect")
	public Map<String, String> connect() {
		return Map.of("authorizationUrl", oauth.startAuthorization());
	}

	@PostMapping("/sync")
	public SyncResult synchronize() { return tasks.synchronize(); }

	@GetMapping("/lists")
	public List<TaskList> lists() { return tasks.listTaskLists(); }

	@GetMapping("/tasks")
	public List<Task> listTasks(@RequestParam(required = false) String listId) {
		if (listId == null || listId.isBlank()) return tasks.synchronize().tasks();
		return tasks.listTasks(listId);
	}

	@PostMapping("/tasks")
	public Task create(@Valid @RequestBody GoogleCreateTaskRequest request) {
		return tasks.createTask(request.listId(), request.title(), request.dueDate());
	}

	@PatchMapping("/lists/{listId}/tasks/{taskId}")
	public Task update(@PathVariable String listId, @PathVariable String taskId, @Valid @RequestBody TaskChange change) {
		return tasks.updateTask(listId, taskId, change);
	}

	@PutMapping("/lists/{listId}/selection")
	public Map<String, Boolean> selectList(@PathVariable String listId, @Valid @RequestBody ListSelectionRequest request) {
		tasks.setListSelected(listId, request.selected());
		return Map.of("selected", request.selected());
	}

	@GetMapping("/notifications")
	public List<Notification> notifications(@RequestParam(defaultValue = "false") boolean unreadOnly) {
		return tasks.notifications(unreadOnly);
	}

	@PatchMapping("/notifications/{id}/read")
	public ResponseEntity<Void> markRead(@PathVariable String id) {
		tasks.markNotificationRead(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/notifications/read-all")
	public ResponseEntity<Void> markAllRead() {
		tasks.markAllNotificationsRead();
		return ResponseEntity.noContent().build();
	}
}
