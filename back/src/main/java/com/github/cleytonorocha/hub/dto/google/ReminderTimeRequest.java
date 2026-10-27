package com.github.cleytonorocha.hub.dto.google;

import jakarta.validation.constraints.NotBlank;

public record ReminderTimeRequest(@NotBlank String reminderTime) {}
