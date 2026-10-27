package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.CreateTaskRequest;
import com.github.cleytonorocha.hub.dto.UpdateTaskRequest;
import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.exception.TaskNotFoundException;
import com.github.cleytonorocha.hub.repository.TaskRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TaskService {
	private final TaskRepository tasks;

	public TaskService(TaskRepository tasks) { this.tasks = tasks; }

	public List<Task> list() { return tasks.findAll(); }

	public Task create(CreateTaskRequest request) {
		return tasks.save(new Task(request.title().trim(), request.dueDate(), request.dueTime(), request.type(), request.category(), request.color(), request.priority(), request.notes()));
	}

	public Task update(String id, UpdateTaskRequest request) {
		Task task = tasks.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
		task.update(request.dueDate(), request.dueTime(), request.completed(), request.category(), request.color(), request.priority(), request.notes());
		return tasks.save(task);
	}

	public void delete(String id) {
		Task task = tasks.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
		tasks.delete(task);
	}
}
