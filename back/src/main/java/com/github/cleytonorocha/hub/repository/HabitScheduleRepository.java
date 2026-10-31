package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.HabitSchedule;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HabitScheduleRepository extends JpaRepository<HabitSchedule, String> {
	List<HabitSchedule> findAllByHabitIdInAndEffectiveFromLessThanEqualOrderByHabitIdAscEffectiveFromAsc(Collection<String> habitIds, LocalDate date);
	List<HabitSchedule> findAllByHabitIdInOrderByHabitIdAscEffectiveFromAsc(Collection<String> habitIds);
	List<HabitSchedule> findAllByHabitIdOrderByEffectiveFromAsc(String habitId);
	Optional<HabitSchedule> findFirstByHabitIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(String habitId, LocalDate date);
	Optional<HabitSchedule> findByHabitIdAndEffectiveFrom(String habitId, LocalDate date);
}
