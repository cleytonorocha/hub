package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.dto.CreateTaskRequest;
import com.github.cleytonorocha.hub.dto.TaskSearchResponse;
import com.github.cleytonorocha.hub.dto.UpdateTaskRequest;
import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.service.TaskService;
import com.github.cleytonorocha.hub.service.TaskSearchService;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
	private final TaskService tasks;
	private final TaskSearchService search;

	public TaskController(TaskService tasks, TaskSearchService search) {
		this.tasks = tasks;
		this.search = search;
	}

	@GetMapping
	public List<Task> list() { return tasks.list(); }

	@GetMapping("/search")
	public TaskSearchResponse search(
			@RequestParam(defaultValue = "") String q,
			@RequestParam(defaultValue = "all") String status,
			@RequestParam(defaultValue = "") String category,
			@RequestParam(defaultValue = "") String priority,
			@RequestParam(defaultValue = "all") String period,
			@RequestParam(required = false) String startDate,
			@RequestParam(required = false) String endDate,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		validateSearch(q, status, category, priority, period, startDate, endDate, page, size);
		return search.search(q, status, category, priority, period, startDate, endDate, page, size);
	}

	private static void validateSearch(String q, String status, String category, String priority,
			String period, String startDate, String endDate, int page, int size) {
		if (q.length() > TaskSearchService.MAX_QUERY_LENGTH) badRequest("O termo de busca excede 200 caracteres.");
		if (!List.of("all", "pending", "completed").contains(status)) badRequest("Estado de busca inválido.");
		if (!category.isBlank() && category.length() > TaskSearchService.MAX_CATEGORY_LENGTH) badRequest("Categoria inválida.");
		if (!priority.isBlank() && !List.of("LOW", "NORMAL", "HIGH").contains(priority)) badRequest("Prioridade inválida.");
		if (!List.of("all", "range", "undated").contains(period)) badRequest("Período inválido.");
		if (page < 0 || page > 1_000_000) badRequest("Página inválida.");
		if (size < 1 || size > TaskSearchService.MAX_PAGE_SIZE) badRequest("Tamanho de página inválido.");
		if ("range".equals(period)) {
			LocalDate start = parseDate(startDate);
			LocalDate end = parseDate(endDate);
			if (start != null && end != null && start.isAfter(end)) badRequest("A data inicial não pode ser posterior à data final.");
		}
	}

	private static LocalDate parseDate(String value) {
		if (value == null || value.isBlank()) return null;
		try { return LocalDate.parse(value); }
		catch (RuntimeException exception) { badRequest("Data de busca inválida."); return null; }
	}

	private static void badRequest(String message) {
		throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Task create(@Valid @RequestBody CreateTaskRequest request) {
		return tasks.create(request);
	}

	@PatchMapping("/{id}")
	public Task update(@PathVariable String id, @Valid @RequestBody UpdateTaskRequest request) {
		return tasks.update(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		tasks.delete(id);
	}
}
