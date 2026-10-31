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
import com.github.cleytonorocha.hub.security.AuthContext;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
public class GoogleTasksController {
	private final GoogleOAuthService oauth;
	private final GoogleTasksService tasks;

	public GoogleTasksController(GoogleOAuthService oauth, GoogleTasksService tasks) {
		this.oauth = oauth;
		this.tasks = tasks;
	}

	@GetMapping("/status")
	@PreAuthorize("hasAuthority('TASK_READ')")
	public ConnectionStatus status() {
		GoogleOAuthService.ConnectionState state = oauth.status(AuthContext.userId());
		String message = switch (state.status()) {
			case "CONNECTED" -> "Google Tasks conectado.";
			case "CONNECTING" -> "Aguardando autorização do Google.";
			case "REAUTH_REQUIRED" -> "Conecte novamente a conta Google para atualizar suas Tasks.";
		case "CONFIGURATION_REQUIRED" -> "Configure o ID OAuth, o segredo e a chave local para conectar o Google Tasks.";
		default -> "Conecte uma conta Google para carregar suas Tasks.";
		};
		return new ConnectionStatus(state.status(), state.configured(), state.lastSyncedAt(), message);
	}

	@PostMapping("/connect")
	@PreAuthorize("hasAuthority('TASK_WRITE')")
	public Map<String, String> connect(HttpServletRequest request) {
		return Map.of("authorizationUrl", oauth.startAuthorization(AuthContext.userId(), request.getSession(true).getId()));
	}

	@GetMapping("/oauth/callback")
	@PreAuthorize("hasAuthority('TASK_WRITE')")
	public ResponseEntity<Void> oauthCallback(@RequestParam(required = false) String state,
			@RequestParam(required = false) String code, @RequestParam(required = false) String error,
			HttpServletRequest request) {
		boolean connected = oauth.completeCallback(AuthContext.userId(), request.getSession(false) == null ? "" : request.getSession(false).getId(), state, code, error);
		return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/google-tasks?oauth=" + (connected ? "connected" : "error")).build();
	}

	@PostMapping("/disconnect")
	@PreAuthorize("hasAuthority('TASK_WRITE')")
	public ResponseEntity<Void> disconnect() { tasks.disconnect(); return ResponseEntity.noContent().build(); }

	@PostMapping("/sync")
	@PreAuthorize("hasAuthority('TASK_WRITE')")
	public SyncResult synchronize() { return tasks.synchronize(); }

	@GetMapping("/lists")
	@PreAuthorize("hasAuthority('TASK_READ')")
	public List<TaskList> lists() { return tasks.listTaskLists(); }

	@GetMapping("/tasks")
	@PreAuthorize("hasAuthority('TASK_READ')")
	public List<Task> listTasks(@RequestParam(required = false) String listId) {
		if (listId == null || listId.isBlank()) return tasks.synchronize().tasks();
		return tasks.listTasks(listId);
	}

	@PostMapping("/tasks")
	@PreAuthorize("hasAuthority('TASK_WRITE')")
	public Task create(@Valid @RequestBody GoogleCreateTaskRequest request) {
		return tasks.createTask(request.listId(), request.title(), request.dueDate());
	}

	@PatchMapping("/lists/{listId}/tasks/{taskId}")
	@PreAuthorize("hasAuthority('TASK_WRITE')")
	public Task update(@PathVariable String listId, @PathVariable String taskId, @Valid @RequestBody TaskChange change) {
		return tasks.updateTask(listId, taskId, change);
	}

	@PutMapping("/lists/{listId}/selection")
	@PreAuthorize("hasAuthority('TASK_WRITE')")
	public Map<String, Boolean> selectList(@PathVariable String listId, @Valid @RequestBody ListSelectionRequest request) {
		tasks.setListSelected(listId, request.selected());
		return Map.of("selected", request.selected());
	}

	@GetMapping("/notifications")
	@PreAuthorize("hasAuthority('TASK_READ')")
	public List<Notification> notifications(@RequestParam(defaultValue = "false") boolean unreadOnly) {
		return tasks.notifications(unreadOnly);
	}

	@PatchMapping("/notifications/{id}/read")
	@PreAuthorize("hasAuthority('TASK_WRITE')")
	public ResponseEntity<Void> markRead(@PathVariable String id) {
		tasks.markNotificationRead(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/notifications/read-all")
	@PreAuthorize("hasAuthority('TASK_WRITE')")
	public ResponseEntity<Void> markAllRead() {
		tasks.markAllNotificationsRead();
		return ResponseEntity.noContent().build();
	}
}
