package com.github.cleytonorocha.hub.dto.google;

import jakarta.validation.constraints.NotNull;

public record ListSelectionRequest(@NotNull Boolean selected) {}
