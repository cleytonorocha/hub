package com.github.cleytonorocha.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "hub_task")
public class Task {
	@Id
	@Column(length = 36, nullable = false)
	private String id;

	@Column(nullable = false, length = 1024)
	private String title;

	@Column(name = "due_date")
	private LocalDate dueDate;

	@Column(name = "due_time", columnDefinition = "TIME")
	private LocalTime dueTime;

	@Column(nullable = false)
	private boolean completed;

	@Column(nullable = false, length = 64)
	private String category;

	@Column(nullable = false, length = 7)
	private String color;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Priority priority;

	@Column(nullable = false, length = 2000)
	private String notes;

	@Enumerated(EnumType.STRING)
	@Column(name = "task_type", nullable = false, length = 16)
	private Type type;

	protected Task() {}

	public Task(String title, LocalDate dueDate, Type type) {
		this(title, dueDate, null, type, null, null, null, null);
	}

	public Task(String title, LocalDate dueDate, Type type, String category, String color, Priority priority, String notes) {
		this(title, dueDate, null, type, category, color, priority, notes);
	}

	public Task(String title, LocalDate dueDate, LocalTime dueTime, Type type, String category, String color, Priority priority, String notes) {
		this.id = UUID.randomUUID().toString();
		this.title = title;
		this.dueDate = dueDate;
		this.dueTime = dueTime;
		this.type = type == null ? Type.LOCAL : type;
		this.category = category == null || category.isBlank() ? "Geral" : category.strip();
		this.color = color == null ? "#A986FF" : color;
		this.priority = priority == null ? Priority.NORMAL : priority;
		this.notes = notes == null ? "" : notes;
	}

	public String getId() { return id; }
	public String getTitle() { return title; }
	public LocalDate getDueDate() { return dueDate; }
	public LocalTime getDueTime() { return dueTime; }
	public boolean isCompleted() { return completed; }
	public String getCategory() { return category; }
	public String getColor() { return color; }
	public Priority getPriority() { return priority; }
	public String getNotes() { return notes; }
	public Type getType() { return type; }

	public void update(LocalDate dueDate, LocalTime dueTime, boolean completed, String category, String color, Priority priority, String notes) {
		this.dueDate = dueDate;
		this.dueTime = dueTime;
		this.completed = completed;
		if (category != null) this.category = category.isBlank() ? "Geral" : category.strip();
		if (color != null) this.color = color;
		if (priority != null) this.priority = priority;
		if (notes != null) this.notes = notes;
	}

	public enum Type { LOCAL, GOOGLE }
	public enum Priority { LOW, NORMAL, HIGH }
}
