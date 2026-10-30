package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.Task;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface TaskRepository extends JpaRepository<Task, String>, JpaSpecificationExecutor<Task> {
	List<Task> findAllByTypeOrderByIdAsc(Task.Type type);
	List<Task> findAllBySeriesIdOrderBySeriesPositionAsc(String seriesId);
	List<Task> findAllBySeriesIdAndCompletedFalseAndDueDateGreaterThan(String seriesId, java.time.LocalDate dueDate);
	long countBySeriesId(String seriesId);

	@Query("select distinct task.category from Task task order by task.category")
	List<String> findDistinctCategories();
}
