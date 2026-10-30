package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.TaskSeries;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskSeriesRepository extends JpaRepository<TaskSeries, String> {
	Optional<TaskSeries> findByOperationId(String operationId);
}
