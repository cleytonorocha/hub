package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.HabitDtos.CreateHabitRequest;
import com.github.cleytonorocha.hub.dto.HabitDtos.DayView;
import com.github.cleytonorocha.hub.dto.HabitDtos.HabitSummary;
import com.github.cleytonorocha.hub.dto.HabitDtos.HabitWeek;
import com.github.cleytonorocha.hub.dto.HabitDtos.MutationResult;
import com.github.cleytonorocha.hub.dto.HabitDtos.UpdateHabitRequest;
import com.github.cleytonorocha.hub.dto.HabitDtos.WeekView;
import com.github.cleytonorocha.hub.entity.Habit;
import com.github.cleytonorocha.hub.entity.HabitCheckIn;
import com.github.cleytonorocha.hub.entity.HabitPause;
import com.github.cleytonorocha.hub.entity.HabitSchedule;
import com.github.cleytonorocha.hub.repository.HabitCheckInRepository;
import com.github.cleytonorocha.hub.repository.HabitPauseRepository;
import com.github.cleytonorocha.hub.repository.HabitRepository;
import com.github.cleytonorocha.hub.repository.HabitScheduleRepository;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HabitService {
	private final HabitRepository habits;
	private final HabitScheduleRepository schedules;
	private final HabitPauseRepository pauses;
	private final HabitCheckInRepository checkIns;
	private final HabitCollectionRevision collectionRevision;

	public HabitService(HabitRepository habits, HabitScheduleRepository schedules, HabitPauseRepository pauses,
			HabitCheckInRepository checkIns, HabitCollectionRevision collectionRevision) {
		this.habits = habits;
		this.schedules = schedules;
		this.pauses = pauses;
		this.checkIns = checkIns;
		this.collectionRevision = collectionRevision;
	}

	@Transactional(readOnly = true)
	public WeekView week(LocalDate weekStart, String requestedZone) {
		ZoneId zone = zone(requestedZone);
		LocalDate today = LocalDate.now(zone);
		if (weekStart == null || weekStart.getDayOfWeek() != java.time.DayOfWeek.MONDAY || weekStart.isAfter(today))
			throw badRequest("Escolha uma semana iniciada na segunda-feira que não esteja no futuro.");
		LocalDate weekEnd = weekStart.plusDays(6);
		List<Habit> allHabits = habits.findAllByOrderByStartDateAscCreatedAtAsc();
		if (allHabits.isEmpty()) return new WeekView(weekStart, weekEnd, today, zone.getId(), List.of(), 0, 0);
		List<String> ids = allHabits.stream().map(Habit::getId).toList();
		Map<String, List<HabitSchedule>> scheduleByHabit = groupSchedules(schedules.findAllByHabitIdInOrderByHabitIdAscEffectiveFromAsc(ids));
		Map<String, List<HabitPause>> pausesByHabit = groupPauses(pauses.findAllByHabitIdInOrderByHabitIdAscPausedOnAsc(ids));
		Map<String, Set<LocalDate>> checkInsByHabit = groupCheckIns(checkIns.findAllByHabitIdInAndCheckinDateBetweenOrderByHabitIdAscCheckinDateAsc(ids, weekStart, weekEnd));
		List<HabitWeek> views = allHabits.stream().map(habit -> week(habit, weekStart, weekEnd, today,
			scheduleByHabit.getOrDefault(habit.getId(), List.of()), pausesByHabit.getOrDefault(habit.getId(), List.of()),
			checkInsByHabit.getOrDefault(habit.getId(), Set.of()))).toList();
		int completed = views.stream().mapToInt(HabitWeek::completed).sum();
		int eligible = views.stream().mapToInt(HabitWeek::eligible).sum();
		return new WeekView(weekStart, weekEnd, today, zone.getId(), views, completed, eligible);
	}

	@Transactional
	public HabitSummary create(CreateHabitRequest request) {
		int mask = weekdaysMask(request.weekdays());
		collectionRevision.lock();
		String name = cleanName(request.name());
		Instant now = Instant.now();
		Habit habit = habits.saveAndFlush(new Habit(name, request.startDate(), now));
		schedules.saveAndFlush(new HabitSchedule(habit.getId(), request.startDate(), mask));
		collectionRevision.increment();
		return HabitSummary.from(habit);
	}

	@Transactional
	public MutationResult update(String id, UpdateHabitRequest request, String requestedZone) {
		ZoneId zone = zone(requestedZone);
		LocalDate today = LocalDate.now(zone);
		int mask = weekdaysMask(request.weekdays());
		collectionRevision.lock();
		Habit habit = requireForUpdate(id);
		if (request.revision() != habit.getRevision()) throw conflict("Este hábito mudou. Atualize os dados antes de salvar.");
		LocalDate requestedStart = request.startDate();
		boolean startDateChanged = !habit.getStartDate().equals(requestedStart);
		if (startDateChanged && (!habit.getStartDate().isAfter(today) || requestedStart.isBefore(today)))
			throw conflict("A data de início só pode mudar antes do hábito começar e não pode criar dias passados.");
		LocalDate effectiveFrom;
		if (startDateChanged) effectiveFrom = requestedStart;
		else {
			LocalDate frequencyDate = checkIns.findByHabitIdAndCheckinDate(id, today).isPresent() ? today.plusDays(1) : today;
			effectiveFrom = habit.getStartDate().isAfter(frequencyDate) ? habit.getStartDate() : frequencyDate;
		}
		HabitSchedule existing = schedules.findByHabitIdAndEffectiveFrom(id, effectiveFrom).orElse(null);
		int currentMask = schedules.findFirstByHabitIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(id, effectiveFrom)
			.map(HabitSchedule::getWeekdaysMask).orElseGet(() -> schedules.findAllByHabitIdOrderByEffectiveFromAsc(id).stream()
				.findFirst().map(HabitSchedule::getWeekdaysMask).orElse(0));
		boolean scheduleChanged = currentMask != mask;
		boolean changed = scheduleChanged || startDateChanged || !habit.getName().equals(cleanName(request.name()));
		if (!changed) return new MutationResult(id, habit.getRevision(), "UNCHANGED");
		habit.update(cleanName(request.name()), requestedStart, Instant.now());
		habits.saveAndFlush(habit);
		if (startDateChanged) {
			List<HabitSchedule> history = schedules.findAllByHabitIdOrderByEffectiveFromAsc(id);
			history.forEach(schedules::delete);
			schedules.flush();
			schedules.saveAndFlush(new HabitSchedule(id, requestedStart, mask));
		} else if (scheduleChanged) {
			if (existing != null) { schedules.delete(existing); schedules.flush(); }
			schedules.saveAndFlush(new HabitSchedule(id, effectiveFrom, mask));
		}
		collectionRevision.increment();
		return new MutationResult(id, habit.getRevision(), "UPDATED");
	}

	@Transactional
	public MutationResult mark(String id, LocalDate date, String requestedZone) {
		ZoneId zone = zone(requestedZone);
		LocalDate today = LocalDate.now(zone);
		if (date == null || date.isAfter(today)) throw badRequest("Não é possível registrar um dia futuro.");
		collectionRevision.lock();
		Habit habit = requireForUpdate(id);
		if (checkIns.findByHabitIdAndCheckinDate(id, date).isEmpty()) {
			List<HabitSchedule> history = schedules.findAllByHabitIdOrderByEffectiveFromAsc(id);
			List<HabitPause> pauseHistory = pauses.findAllByHabitIdOrderByPausedOnAsc(id);
			if (!eligible(habit, date, history, pauseHistory, Set.of())) throw conflict("Esse dia não estava previsto para o hábito.");
			checkIns.saveAndFlush(new HabitCheckIn(id, date, Instant.now()));
			collectionRevision.increment();
		}
		return new MutationResult(id, habit.getRevision(), "MARKED");
	}

	@Transactional
	public MutationResult unmark(String id, LocalDate date, String requestedZone) {
		ZoneId zone = zone(requestedZone);
		LocalDate today = LocalDate.now(zone);
		if (date == null || date.isAfter(today)) throw badRequest("Não é possível remover um registro de um dia futuro.");
		collectionRevision.lock();
		Habit habit = requireForUpdate(id);
		HabitCheckIn existing = checkIns.findByHabitIdAndCheckinDate(id, date).orElse(null);
		if (existing != null) {
			checkIns.delete(existing);
			collectionRevision.increment();
		}
		return new MutationResult(id, habit.getRevision(), "UNMARKED");
	}

	@Transactional
	public MutationResult pause(String id, String requestedZone) {
		ZoneId zone = zone(requestedZone);
		LocalDate today = LocalDate.now(zone);
		collectionRevision.lock();
		Habit habit = requireForUpdate(id);
		if (pauses.findFirstByHabitIdAndResumedOnIsNullOrderByPausedOnDesc(id).isEmpty()) {
			pauses.saveAndFlush(new HabitPause(id, today));
			collectionRevision.increment();
			return new MutationResult(id, habit.getRevision(), "PAUSED");
		}
		return new MutationResult(id, habit.getRevision(), "UNCHANGED");
	}

	@Transactional
	public MutationResult resume(String id, String requestedZone) {
		ZoneId zone = zone(requestedZone);
		LocalDate today = LocalDate.now(zone);
		collectionRevision.lock();
		Habit habit = requireForUpdate(id);
		HabitPause pause = pauses.findFirstByHabitIdAndResumedOnIsNullOrderByPausedOnDesc(id).orElse(null);
		if (pause == null) return new MutationResult(id, habit.getRevision(), "UNCHANGED");
		pause.resume(today);
		pauses.saveAndFlush(pause);
		collectionRevision.increment();
		return new MutationResult(id, habit.getRevision(), "RESUMED");
	}

	private HabitWeek week(Habit habit, LocalDate weekStart, LocalDate weekEnd, LocalDate today,
			List<HabitSchedule> scheduleHistory, List<HabitPause> pauseHistory, Set<LocalDate> marked) {
		List<DayView> days = new ArrayList<>(7);
		int completed = 0;
		int eligible = 0;
		for (int i = 0; i < 7; i++) {
			LocalDate date = weekStart.plusDays(i);
			boolean checked = marked.contains(date);
			boolean scheduled = eligible(habit, date, scheduleHistory, pauseHistory, marked);
			String status;
			if (checked) { status = "DONE"; completed++; }
			else if (date.isBefore(habit.getStartDate())) status = "BEFORE_START";
			else if (isPaused(date, pauseHistory)) status = "PAUSED";
			else if (!isScheduled(date, scheduleHistory)) status = "NOT_SCHEDULED";
			else if (date.isAfter(today)) status = "FUTURE";
			else if (date.equals(today)) status = "PENDING_TODAY";
			else status = "NOT_RECORDED";
			if (scheduled) eligible++;
			days.add(new DayView(date, status, checked, scheduled));
		}
		boolean paused = pauseHistory.stream().anyMatch(p -> p.getResumedOn() == null);
		LocalDate configuredFor = marked.contains(today) ? today.plusDays(1) : today;
		int currentMask = scheduleHistory.stream().filter(s -> !s.getEffectiveFrom().isAfter(configuredFor))
			.max(Comparator.comparing(HabitSchedule::getEffectiveFrom)).map(HabitSchedule::getWeekdaysMask)
			.orElseGet(() -> scheduleHistory.stream().findFirst().map(HabitSchedule::getWeekdaysMask).orElse(0));
		return new HabitWeek(habit.getId(), habit.getName(), habit.getStartDate(), habit.getRevision(), currentMask,
			paused, today.isBefore(habit.getStartDate()), days, completed, eligible);
	}

	private boolean eligible(Habit habit, LocalDate date, List<HabitSchedule> history, List<HabitPause> pauseHistory, Set<LocalDate> marked) {
		if (date.isBefore(habit.getStartDate())) return false;
		return isScheduled(date, history) && (!isPaused(date, pauseHistory) || marked.contains(date));
	}

	private boolean isScheduled(LocalDate date, List<HabitSchedule> history) {
		HabitSchedule schedule = history.stream().filter(value -> !value.getEffectiveFrom().isAfter(date))
			.max(Comparator.comparing(HabitSchedule::getEffectiveFrom)).orElse(null);
		return schedule != null && (schedule.getWeekdaysMask() & (1 << (date.getDayOfWeek().getValue() - 1))) != 0;
	}

	private boolean isPaused(LocalDate date, List<HabitPause> history) {
		return history.stream().anyMatch(p -> !date.isBefore(p.getPausedOn()) && (p.getResumedOn() == null || date.isBefore(p.getResumedOn())));
	}

	private Habit requireForUpdate(String id) { return habits.findByIdForUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "O hábito não foi encontrado.")); }

	private static Map<String, List<HabitSchedule>> groupSchedules(List<HabitSchedule> rows) {
		Map<String, List<HabitSchedule>> result = new HashMap<>();
		for (HabitSchedule row : rows) result.computeIfAbsent(row.getHabitId(), ignored -> new ArrayList<>()).add(row);
		return result;
	}
	private static Map<String, List<HabitPause>> groupPauses(List<HabitPause> rows) {
		Map<String, List<HabitPause>> result = new HashMap<>();
		for (HabitPause row : rows) result.computeIfAbsent(row.getHabitId(), ignored -> new ArrayList<>()).add(row);
		return result;
	}
	private static Map<String, Set<LocalDate>> groupCheckIns(List<HabitCheckIn> rows) {
		Map<String, Set<LocalDate>> result = new HashMap<>();
		for (HabitCheckIn row : rows) result.computeIfAbsent(row.getHabitId(), ignored -> new HashSet<>()).add(row.getCheckinDate());
		return result;
	}

	private static String cleanName(String name) {
		String result = name == null ? "" : name.strip();
		if (result.isBlank() || result.length() > 120) throw badRequest("Informe um nome de hábito com até 120 caracteres.");
		return result;
	}
	private static int weekdaysMask(Collection<Integer> weekdays) {
		if (weekdays == null || weekdays.isEmpty() || weekdays.size() > 7) throw badRequest("Selecione ao menos um dia da semana.");
		int mask = 0;
		for (Integer day : weekdays) {
			if (day == null || day < 1 || day > 7) throw badRequest("Um dos dias da semana é inválido.");
			mask |= 1 << (day - 1);
		}
		if (mask == 0) throw badRequest("Selecione ao menos um dia da semana.");
		return mask;
	}
	private static ZoneId zone(String value) {
		if (value == null || !ZoneId.getAvailableZoneIds().contains(value)) throw badRequest("O fuso horário local é inválido.");
		try { return ZoneId.of(value); }
		catch (DateTimeException error) { throw badRequest("O fuso horário local é inválido."); }
	}
	private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
	private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
