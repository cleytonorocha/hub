package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.HabitPause;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HabitPauseRepository extends JpaRepository<HabitPause, String> {
	List<HabitPause> findAllByHabitIdInOrderByHabitIdAscPausedOnAsc(Collection<String> habitIds);
	List<HabitPause> findAllByHabitIdOrderByPausedOnAsc(String habitId);
	Optional<HabitPause> findFirstByHabitIdAndResumedOnIsNullOrderByPausedOnDesc(String habitId);
}
