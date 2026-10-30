package com.github.cleytonorocha.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "hub_task_series")
public class TaskSeries {
	@Id
	@Column(length = 36, nullable = false)
	private String id;
	@Column(name = "operation_id", length = 36, nullable = false, unique = true)
	private String operationId;
	@Column(nullable = false, length = 1024)
	private String title;
	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;
	@Column(name = "due_time", columnDefinition = "TIME")
	private LocalTime dueTime;
	@Column(name = "occurrence_count", nullable = false)
	private int occurrenceCount;
	@Column(name = "time_zone_id", nullable = false, length = 64)
	private String timeZoneId;
	@Column(nullable = false)
	private boolean ended;

	protected TaskSeries() {}

	public TaskSeries(String operationId, String title, LocalDate startDate, LocalTime dueTime, int occurrenceCount, String timeZoneId) {
		this.id = UUID.randomUUID().toString();
		this.operationId = operationId;
		this.title = title;
		this.startDate = startDate;
		this.dueTime = dueTime;
		this.occurrenceCount = occurrenceCount;
		this.timeZoneId = timeZoneId;
	}

	public static TaskSeries restore(String id, String title, LocalDate startDate, LocalTime dueTime,
			int occurrenceCount, String timeZoneId, boolean ended) {
		TaskSeries result = new TaskSeries(UUID.randomUUID().toString(), title, startDate, dueTime, occurrenceCount, timeZoneId);
		result.id = id;
		result.ended = ended;
		return result;
	}

	public void end() { this.ended = true; }
	public String getId() { return id; }
	public String getOperationId() { return operationId; }
	public String getTitle() { return title; }
	public LocalDate getStartDate() { return startDate; }
	public LocalTime getDueTime() { return dueTime; }
	public int getOccurrenceCount() { return occurrenceCount; }
	public String getTimeZoneId() { return timeZoneId; }
	public boolean isEnded() { return ended; }
}
