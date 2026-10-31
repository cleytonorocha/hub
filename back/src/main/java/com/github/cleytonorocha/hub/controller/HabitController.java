package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.dto.HabitDtos.CreateHabitRequest;
import com.github.cleytonorocha.hub.dto.HabitDtos.HabitSummary;
import com.github.cleytonorocha.hub.dto.HabitDtos.MutationResult;
import com.github.cleytonorocha.hub.dto.HabitDtos.UpdateHabitRequest;
import com.github.cleytonorocha.hub.dto.HabitDtos.WeekView;
import com.github.cleytonorocha.hub.service.HabitService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/habits")
public class HabitController {
	private final HabitService habits;

	public HabitController(HabitService habits) { this.habits = habits; }

	@GetMapping
	@PreAuthorize("hasAuthority('HABITS_READ')")
	public WeekView week(@RequestParam LocalDate weekStart, @RequestParam String timeZoneId) {
		return habits.week(weekStart, timeZoneId);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('HABITS_WRITE')")
	@ResponseStatus(HttpStatus.CREATED)
	public HabitSummary create(@Valid @RequestBody CreateHabitRequest request) { return habits.create(request); }

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('HABITS_WRITE')")
	public MutationResult update(@PathVariable String id, @Valid @RequestBody UpdateHabitRequest request,
			@RequestParam String timeZoneId) { return habits.update(id, request, timeZoneId); }

	@PutMapping("/{id}/check-ins/{date}")
	@PreAuthorize("hasAuthority('HABITS_WRITE')")
	public MutationResult mark(@PathVariable String id, @PathVariable LocalDate date, @RequestParam String timeZoneId) {
		return habits.mark(id, date, timeZoneId);
	}

	@DeleteMapping("/{id}/check-ins/{date}")
	@PreAuthorize("hasAuthority('HABITS_WRITE')")
	public MutationResult unmark(@PathVariable String id, @PathVariable LocalDate date, @RequestParam String timeZoneId) {
		return habits.unmark(id, date, timeZoneId);
	}

	@PostMapping("/{id}/pause")
	@PreAuthorize("hasAuthority('HABITS_WRITE')")
	public MutationResult pause(@PathVariable String id, @RequestParam String timeZoneId) { return habits.pause(id, timeZoneId); }

	@PostMapping("/{id}/resume")
	@PreAuthorize("hasAuthority('HABITS_WRITE')")
	public MutationResult resume(@PathVariable String id, @RequestParam String timeZoneId) { return habits.resume(id, timeZoneId); }
}
