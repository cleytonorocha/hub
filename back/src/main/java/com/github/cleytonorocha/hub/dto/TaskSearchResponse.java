package com.github.cleytonorocha.hub.dto;

import com.github.cleytonorocha.hub.entity.Task;
import java.util.List;

public record TaskSearchResponse(
	List<Task> content,
	int page,
	int size,
	long totalElements,
	int totalPages,
	List<String> categories) {}
