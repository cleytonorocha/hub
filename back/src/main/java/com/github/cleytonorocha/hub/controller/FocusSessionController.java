package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.dto.FocusSessionDtos.EndRequest;
import com.github.cleytonorocha.hub.dto.FocusSessionDtos.HistoryPage;
import com.github.cleytonorocha.hub.dto.FocusSessionDtos.SessionView;
import com.github.cleytonorocha.hub.dto.FocusSessionDtos.StartRequest;
import com.github.cleytonorocha.hub.dto.FocusSessionDtos.TransitionRequest;
import com.github.cleytonorocha.hub.service.FocusSessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/focus-sessions")
public class FocusSessionController {
	private final FocusSessionService sessions;
	public FocusSessionController(FocusSessionService sessions) { this.sessions = sessions; }

	@GetMapping("/active")
	@PreAuthorize("hasAuthority('FOCUS_READ')")
	public SessionView active() { return sessions.active(); }

	@GetMapping
	@PreAuthorize("hasAuthority('FOCUS_READ')")
	public HistoryPage history(@RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
		return sessions.history(offset, limit);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('FOCUS_WRITE')")
	@ResponseStatus(HttpStatus.CREATED)
	public SessionView start(@Valid @RequestBody StartRequest request) { return sessions.start(request); }

	@PostMapping("/{id}/pause")
	@PreAuthorize("hasAuthority('FOCUS_WRITE')")
	public SessionView pause(@PathVariable String id, @Valid @RequestBody TransitionRequest request) { return sessions.pause(id, request); }

	@PostMapping("/{id}/resume")
	@PreAuthorize("hasAuthority('FOCUS_WRITE')")
	public SessionView resume(@PathVariable String id, @Valid @RequestBody TransitionRequest request) { return sessions.resume(id, request); }

	@PostMapping("/{id}/end")
	@PreAuthorize("hasAuthority('FOCUS_WRITE')")
	public SessionView end(@PathVariable String id, @Valid @RequestBody EndRequest request) { return sessions.end(id, request); }

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('FOCUS_WRITE')")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id, @RequestParam long revision) { sessions.delete(id, revision); }
}
