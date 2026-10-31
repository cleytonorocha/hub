package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.Habit;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.QueryHint;

public interface HabitRepository extends JpaRepository<Habit, String> {
	List<Habit> findAllByOrderByStartDateAscCreatedAtAsc();

	@org.springframework.data.jpa.repository.Query("select h from Habit h where h.id = :id")
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
	Optional<Habit> findByIdForUpdate(@Param("id") String id);
}
