package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.dto.CreateTaskRequest;
import com.github.cleytonorocha.hub.dto.UpdateTaskRequest;
import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.service.TaskService;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
	private final TaskService tasks;

	public TaskController(TaskService tasks) { this.tasks = tasks; }

	@GetMapping
	public List<Task> list() { return tasks.list(); }

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
