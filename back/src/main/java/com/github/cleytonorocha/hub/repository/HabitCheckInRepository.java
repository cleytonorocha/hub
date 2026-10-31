package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.HabitCheckIn;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HabitCheckInRepository extends JpaRepository<HabitCheckIn, String> {
	List<HabitCheckIn> findAllByHabitIdInAndCheckinDateBetweenOrderByHabitIdAscCheckinDateAsc(Collection<String> habitIds, LocalDate from, LocalDate to);
	List<HabitCheckIn> findAllByHabitIdInOrderByHabitIdAscCheckinDateAsc(Collection<String> habitIds);
	Optional<HabitCheckIn> findByHabitIdAndCheckinDate(String habitId, LocalDate date);
}
