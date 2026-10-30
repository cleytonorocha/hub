package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.CreateTaskRequest;
import com.github.cleytonorocha.hub.dto.UpdateTaskRequest;
import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.exception.TaskNotFoundException;
import com.github.cleytonorocha.hub.repository.TaskRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {
	private final TaskRepository tasks;
	private final TaskCollectionRevision collectionRevision;
	private final TaskChecklistService checklists;

	public TaskService(TaskRepository tasks, TaskCollectionRevision collectionRevision, TaskChecklistService checklists) {
		this.tasks = tasks;
		this.collectionRevision = collectionRevision;
		this.checklists = checklists;
	}

	public List<Task> list() { return tasks.findAll(); }

	@Transactional
	public Task create(CreateTaskRequest request) {
		collectionRevision.lock();
		Task created = tasks.save(new Task(request.title().trim(), request.dueDate(), request.dueTime(), request.type(), request.category(), request.color(), request.priority(), request.notes()));
		collectionRevision.increment();
		return created;
	}

	@Transactional
	public Task update(String id, UpdateTaskRequest request) {
		collectionRevision.lock();
		Task task = tasks.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
		if (!task.isCompleted() && request.completed() && checklists.hasPending(id)
				&& !Boolean.TRUE.equals(request.confirmPendingChecklist())) {
			throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
				"Confirme explicitamente a conclusão com itens pendentes.");
		}
		task.update(request.dueDate(), request.dueTime(), request.completed(), request.category(), request.color(), request.priority(), request.notes());
		Task updated = tasks.save(task);
		collectionRevision.increment();
		return updated;
	}

	@Transactional
	public void delete(String id) {
		collectionRevision.lock();
		Task task = tasks.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
		tasks.delete(task);
		collectionRevision.increment();
	}
}
