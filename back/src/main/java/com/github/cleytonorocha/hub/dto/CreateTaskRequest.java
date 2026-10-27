package com.github.cleytonorocha.hub.dto;

import com.github.cleytonorocha.hub.entity.Task;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

public record CreateTaskRequest(
	@NotBlank @Size(max = 1024) String title,
	LocalDate dueDate,
	LocalTime dueTime,
	Task.Type type,
	@Size(max = 64) String category,
	@Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
	Task.Priority priority,
	@Size(max = 2000) String notes) {}
