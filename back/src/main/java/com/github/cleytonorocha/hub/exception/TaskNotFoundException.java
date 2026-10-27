package com.github.cleytonorocha.hub.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class TaskNotFoundException extends RuntimeException {
	public TaskNotFoundException(String id) { super("Task not found: " + id); }
}
