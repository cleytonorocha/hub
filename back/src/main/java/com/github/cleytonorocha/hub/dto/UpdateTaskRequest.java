package com.github.cleytonorocha.hub.dto;

import com.github.cleytonorocha.hub.entity.Task;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

public record UpdateTaskRequest(
	LocalDate dueDate,
	LocalTime dueTime,
	boolean completed,
	@Size(max = 64) String category,
	@Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
	Task.Priority priority,
	@Size(max = 2000) String notes) {}
