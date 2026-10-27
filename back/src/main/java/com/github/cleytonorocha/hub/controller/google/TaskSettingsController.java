package com.github.cleytonorocha.hub.controller.google;

import com.github.cleytonorocha.hub.dto.google.GoogleTaskDtos.ReminderSettings;
import com.github.cleytonorocha.hub.dto.google.ReminderTimeRequest;
import com.github.cleytonorocha.hub.service.google.GoogleTasksService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/tasks")
public final class TaskSettingsController {
	private final GoogleTasksService tasks;

	public TaskSettingsController(GoogleTasksService tasks) { this.tasks = tasks; }

	@GetMapping
	public ReminderSettings get() { return tasks.reminderSettings(); }

	@PutMapping
	public ReminderSettings save(@Valid @RequestBody ReminderTimeRequest request) {
		return tasks.saveReminderSettings(request.reminderTime());
	}
}
