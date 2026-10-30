package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.dto.TaskSeriesDtos.ClosePreview;
import com.github.cleytonorocha.hub.dto.TaskSeriesDtos.CloseRequest;
import com.github.cleytonorocha.hub.dto.TaskSeriesDtos.CloseResult;
import com.github.cleytonorocha.hub.dto.TaskSeriesDtos.CreateSeriesRequest;
import com.github.cleytonorocha.hub.dto.TaskSeriesDtos.SeriesView;
import com.github.cleytonorocha.hub.service.TaskSeriesService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/task-series")
public class TaskSeriesController {
	private final TaskSeriesService series;

	public TaskSeriesController(TaskSeriesService series) { this.series = series; }

	@GetMapping
	@PreAuthorize("hasAuthority('TASK_READ')")
	public List<SeriesView> list() { return series.list(); }

	@PostMapping
	@PreAuthorize("hasAuthority('TASK_WRITE')")
	@ResponseStatus(HttpStatus.CREATED)
	public SeriesView create(@Valid @RequestBody CreateSeriesRequest request) { return series.create(request); }

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('TASK_READ')")
	public SeriesView get(@PathVariable String id) { return series.get(id); }

	@GetMapping("/{id}/close-preview")
	@PreAuthorize("hasAuthority('TASK_WRITE')")
	public ClosePreview previewClose(@PathVariable String id, @RequestParam String timeZoneId) {
		return series.previewClose(id, timeZoneId);
	}

	@PostMapping("/{id}/close")
	@PreAuthorize("hasAuthority('TASK_WRITE')")
	public CloseResult close(@PathVariable String id, @Valid @RequestBody CloseRequest request) {
		return series.close(id, request);
	}
}
