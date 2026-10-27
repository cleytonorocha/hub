package com.github.cleytonorocha.hub.dto.google;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record GoogleCreateTaskRequest(@NotBlank @Size(max = 1024) String title, @NotBlank String listId, LocalDate dueDate) {}
