package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.dto.TaskChecklistDtos.ChecklistView;
import com.github.cleytonorocha.hub.dto.TaskChecklistDtos.SaveChecklistRequest;
import com.github.cleytonorocha.hub.service.TaskChecklistService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/tasks/{taskId}/checklist")
public class TaskChecklistController {
	private final TaskChecklistService checklists;
	public TaskChecklistController(TaskChecklistService checklists) { this.checklists = checklists; }
	@GetMapping @PreAuthorize("hasAuthority('TASK_READ')") public ChecklistView get(@PathVariable String taskId) { return checklists.get(taskId); }
	@PutMapping @PreAuthorize("hasAuthority('TASK_WRITE')") public ChecklistView save(@PathVariable String taskId, @RequestBody SaveChecklistRequest request) { return checklists.save(taskId, request); }
}
