package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.TaskSeriesDtos.ClosePreview;
import com.github.cleytonorocha.hub.dto.TaskSeriesDtos.CloseRequest;
import com.github.cleytonorocha.hub.dto.TaskSeriesDtos.CloseResult;
import com.github.cleytonorocha.hub.dto.TaskSeriesDtos.CreateSeriesRequest;
import com.github.cleytonorocha.hub.dto.TaskSeriesDtos.SeriesView;
import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.entity.TaskSeries;
import com.github.cleytonorocha.hub.repository.TaskRepository;
import com.github.cleytonorocha.hub.repository.TaskSeriesRepository;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TaskSeriesService {
	private final TaskRepository tasks;
	private final TaskSeriesRepository series;
	private final TaskCollectionRevision collectionRevision;

	public TaskSeriesService(TaskRepository tasks, TaskSeriesRepository series, TaskCollectionRevision collectionRevision) {
		this.tasks = tasks;
		this.series = series;
		this.collectionRevision = collectionRevision;
	}

	@Transactional
	public SeriesView create(CreateSeriesRequest request) {
		ZoneId zone = zone(request.timeZoneId());
		String operationId = canonicalUuid(request.operationId());
		long revision = collectionRevision.lock();
		TaskSeries prior = series.findByOperationId(operationId).orElse(null);
		if (prior != null) {
			if (!matches(prior, request)) throw conflict("A chave desta operação já foi usada com outros dados.");
			return view(prior);
		}
		TaskSeries created = series.save(new TaskSeries(operationId, request.title().strip(), request.startDate(),
			request.dueTime(), request.occurrenceCount(), zone.getId()));
		series.flush();
		List<Task> occurrences = java.util.stream.IntStream.range(0, request.occurrenceCount())
			.mapToObj(position -> {
				Task task = new Task(request.title().strip(), request.startDate().plusWeeks(position), request.dueTime(),
					Task.Type.LOCAL, request.category(), request.color(), request.priority(), request.notes());
				task.assignToSeries(created.getId(), position + 1);
				return task;
			}).toList();
		tasks.saveAll(occurrences);
		tasks.flush();
		collectionRevision.increment();
		return new SeriesView(created, occurrences);
	}

	@Transactional(readOnly = true)
	public List<SeriesView> list() { return series.findAll().stream().map(this::view).toList(); }

	@Transactional(readOnly = true)
	public SeriesView get(String id) { return view(require(id)); }

	@Transactional
	public ClosePreview previewClose(String id, String timeZoneId) {
		long revision = collectionRevision.lock();
		TaskSeries current = require(id);
		ZoneId zone = zone(timeZoneId);
		LocalDate today = LocalDate.now(zone);
		return new ClosePreview(id, current.isEnded() ? 0 : pendingFutureCount(id, today), today, zone.getId(), revision);
	}

	@Transactional
	public CloseResult close(String id, CloseRequest request) {
		ZoneId zone = zone(request.timeZoneId());
		LocalDate today = LocalDate.now(zone);
		if (!today.equals(request.today())) throw conflict("O dia local mudou. Atualize a revisão da série antes de confirmar.");
		long revision = collectionRevision.lock();
		LocalDate lockedToday = LocalDate.now(zone);
		if (!lockedToday.equals(request.today())) throw conflict("O dia local mudou. Atualize a revisão da série antes de confirmar.");
		TaskSeries current = require(id);
		if (current.isEnded()) return new CloseResult("ALREADY_ENDED", 0, (int) tasks.countBySeriesId(id), lockedToday);
		if (revision != request.expectedRevision()) throw conflict("As tarefas mudaram desde a revisão. Atualize a quantidade antes de confirmar.");
		List<Task> removing = tasks.findAllBySeriesIdAndCompletedFalseAndDueDateGreaterThan(id, lockedToday);
		int removed = removing.size();
		if (removed > 0) tasks.deleteAll(removing);
		current.end();
		int preserved = (int) tasks.countBySeriesId(id);
		collectionRevision.increment();
		return new CloseResult("ENDED", removed, preserved, lockedToday);
	}

	private int pendingFutureCount(String id, LocalDate today) {
		return tasks.findAllBySeriesIdAndCompletedFalseAndDueDateGreaterThan(id, today).size();
	}

	private SeriesView view(TaskSeries item) {
		return new SeriesView(item, tasks.findAllBySeriesIdOrderBySeriesPositionAsc(item.getId()));
	}

	private TaskSeries require(String id) {
		return series.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "A série não foi encontrada."));
	}

	private static boolean matches(TaskSeries series, CreateSeriesRequest request) {
		return series.getTitle().equals(request.title().strip()) && series.getStartDate().equals(request.startDate())
			&& java.util.Objects.equals(series.getDueTime(), request.dueTime())
			&& series.getOccurrenceCount() == request.occurrenceCount()
			&& series.getTimeZoneId().equals(zone(request.timeZoneId()).getId());
	}

	private static String canonicalUuid(String value) {
		try {
			String canonical = UUID.fromString(value).toString();
			if (!canonical.equalsIgnoreCase(value)) throw new IllegalArgumentException();
			return canonical;
		} catch (RuntimeException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A chave da operação é inválida."); }
	}

	private static ZoneId zone(String id) {
		try { return ZoneId.of(id); }
		catch (DateTimeException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O fuso horário local é inválido."); }
	}

	private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
