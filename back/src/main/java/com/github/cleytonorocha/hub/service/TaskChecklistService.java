package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.TaskChecklistDtos.ChecklistItem;
import com.github.cleytonorocha.hub.dto.TaskChecklistDtos.ChecklistView;
import com.github.cleytonorocha.hub.dto.TaskChecklistDtos.SaveChecklistRequest;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupChecklistItem;
import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.repository.TaskRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TaskChecklistService {
	public static final int MAX_ITEMS = 20;
	public static final int MAX_TEXT_LENGTH = 200;
	private final TaskRepository tasks;
	private final JdbcTemplate jdbc;
	private final TaskCollectionRevision collectionRevision;

	public TaskChecklistService(TaskRepository tasks, JdbcTemplate jdbc, TaskCollectionRevision collectionRevision) {
		this.tasks = tasks;
		this.jdbc = jdbc;
		this.collectionRevision = collectionRevision;
	}

	@Transactional(readOnly = true)
	public ChecklistView get(String taskId) {
		Task task = requireLocalTask(taskId);
		if (task.getSeriesId() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Checklist não está disponível para ocorrências de séries semanais.");
		Long revision = jdbc.query("SELECT revision FROM hub_task_checklist_revision WHERE task_id = ?", rs -> rs.next() ? rs.getLong(1) : null, taskId);
		List<ChecklistItem> items = jdbc.query("SELECT id, item_text, checked, item_order FROM hub_task_checklist_item WHERE task_id = ? ORDER BY item_order",
			(rs, row) -> new ChecklistItem(rs.getString("id"), rs.getString("item_text"), rs.getBoolean("checked"), rs.getInt("item_order")), taskId);
		return new ChecklistView(revision == null ? 0 : revision, items);
	}

	@Transactional
	public ChecklistView save(String taskId, SaveChecklistRequest request) {
		collectionRevision.lock();
		Task task = requireLocalTask(taskId);
		if (task.getSeriesId() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Checklist não está disponível para ocorrências de séries semanais.");
		if (task.isCompleted()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Reabra a tarefa antes de editar o checklist.");
		validate(request);
		Long current = jdbc.query("SELECT revision FROM hub_task_checklist_revision WHERE task_id = ? FOR UPDATE", rs -> rs.next() ? rs.getLong(1) : null, taskId);
		long revision = current == null ? 0 : current;
		if (request.expectedRevision() != revision) throw new ResponseStatusException(HttpStatus.CONFLICT, "O checklist mudou em outra aba. Recarregue a versão salva antes de tentar novamente.");
		if (current == null) jdbc.update("INSERT INTO hub_task_checklist_revision(task_id, revision) VALUES (?, 0)", taskId);
		jdbc.update("DELETE FROM hub_task_checklist_item WHERE task_id = ?", taskId);
		for (int i = 0; i < request.items().size(); i++) {
			ChecklistItem item = request.items().get(i);
			jdbc.update("INSERT INTO hub_task_checklist_item(id, task_id, item_order, item_text, checked) VALUES (?, ?, ?, ?, ?)",
				item.id(), taskId, i, item.text().strip(), item.checked());
		}
		jdbc.update("UPDATE hub_task_checklist_revision SET revision = revision + 1 WHERE task_id = ?", taskId);
		collectionRevision.increment();
		return get(taskId);
	}

	public boolean hasPending(String taskId) {
		Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM hub_task_checklist_item WHERE task_id = ? AND checked = FALSE", Integer.class, taskId);
		return pending != null && pending > 0;
	}

	@Transactional(readOnly = true)
	public List<BackupChecklistItem> exportItems(String taskId) {
		return jdbc.query("SELECT id, item_text, checked, item_order FROM hub_task_checklist_item WHERE task_id = ? ORDER BY item_order",
			(rs, row) -> new BackupChecklistItem(rs.getString("id"), rs.getString("item_text"), rs.getBoolean("checked"), rs.getInt("item_order")), taskId);
	}

	public void restoreImported(String taskId, List<BackupChecklistItem> items) {
		if (items == null || items.isEmpty()) return;
		jdbc.update("INSERT INTO hub_task_checklist_revision(task_id, revision) VALUES (?, 1)", taskId);
		for (int i = 0; i < items.size(); i++) {
			BackupChecklistItem item = items.get(i);
			jdbc.update("INSERT INTO hub_task_checklist_item(id, task_id, item_order, item_text, checked) VALUES (?, ?, ?, ?, ?)",
				item.id(), taskId, i, item.text(), item.checked());
		}
	}

	private Task requireLocalTask(String id) {
		Task task = tasks.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "A tarefa não foi encontrada."));
		if (task.getType() != Task.Type.LOCAL) throw new ResponseStatusException(HttpStatus.CONFLICT, "Checklist está disponível somente para tarefas locais.");
		return task;
	}

	private void validate(SaveChecklistRequest request) {
		if (request == null || request.expectedRevision() < 0 || request.items() == null || request.items().size() > MAX_ITEMS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O checklist precisa ter até 20 itens e uma revisão válida.");
		Set<String> ids = new HashSet<>();
		for (int i = 0; i < request.items().size(); i++) {
			ChecklistItem item = request.items().get(i);
			try {
				if (item.id() == null || !UUID.fromString(item.id()).toString().equals(item.id())) throw new IllegalArgumentException();
			} catch (RuntimeException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O checklist contém um identificador inválido."); }
			if (!ids.add(item.id())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O checklist repete um identificador de item.");
			if (item.text() == null || item.text().isBlank() || item.text().length() > MAX_TEXT_LENGTH || item.position() != i) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confira o texto e a ordem dos itens do checklist.");
		}
	}
}
