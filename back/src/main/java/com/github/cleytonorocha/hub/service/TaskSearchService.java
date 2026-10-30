package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.TaskSearchResponse;
import com.github.cleytonorocha.hub.entity.Task;
import com.github.cleytonorocha.hub.repository.TaskRepository;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

@Service
public class TaskSearchService {
	public static final int MAX_PAGE_SIZE = 100;
	public static final int MAX_QUERY_LENGTH = 200;
	public static final int MAX_CATEGORY_LENGTH = 64;

	private final TaskRepository tasks;

	public TaskSearchService(TaskRepository tasks) { this.tasks = tasks; }

	@Transactional(readOnly = true)
	public TaskSearchResponse search(String query, String status, String category, String priority,
			String period, String startDate, String endDate, int page, int size) {
		String normalizedQuery = TaskSearchNormalization.normalize(query == null ? "" : query.strip());
		Specification<Task> specification = (root, criteria, builder) -> {
			Predicate predicate = builder.conjunction();
			if (!normalizedQuery.isEmpty()) {
				String pattern = "%" + escapeLike(normalizedQuery) + "%";
				Predicate title = builder.like(normalize(builder, root.get("title")), pattern, '\\');
				Predicate taskCategory = builder.like(normalize(builder, root.get("category")), pattern, '\\');
				Predicate notes = builder.like(normalize(builder, root.get("notes")), pattern, '\\');
				predicate = builder.and(predicate, builder.or(title, taskCategory, notes));
			}
			if ("pending".equals(status)) predicate = builder.and(predicate, builder.isFalse(root.get("completed")));
			if ("completed".equals(status)) predicate = builder.and(predicate, builder.isTrue(root.get("completed")));
			if (StringUtils.hasText(category)) predicate = builder.and(predicate, builder.equal(root.get("category"), category));
			if (StringUtils.hasText(priority)) predicate = builder.and(predicate, builder.equal(root.get("priority"), Task.Priority.valueOf(priority)));
			if ("undated".equals(period)) predicate = builder.and(predicate, builder.isNull(root.get("dueDate")));
			if ("range".equals(period)) {
				if (startDate != null) predicate = builder.and(predicate, builder.greaterThanOrEqualTo(root.<LocalDate>get("dueDate"), LocalDate.parse(startDate)));
				if (endDate != null) predicate = builder.and(predicate, builder.lessThanOrEqualTo(root.<LocalDate>get("dueDate"), LocalDate.parse(endDate)));
				predicate = builder.and(predicate, builder.isNotNull(root.get("dueDate")));
			}
			return predicate;
		};

		Sort sort = Sort.by(
			Sort.Order.asc("completed"),
			Sort.Order.asc("dueDate").nullsLast(),
			Sort.Order.asc("dueTime").nullsLast(),
			Sort.Order.asc("title"),
			Sort.Order.asc("id"));
		Page<Task> result = tasks.findAll(specification, PageRequest.of(page, size, sort));
		return new TaskSearchResponse(result.getContent(), result.getNumber(), result.getSize(),
			result.getTotalElements(), result.getTotalPages(), tasks.findDistinctCategories());
	}

	private static Expression<String> normalize(CriteriaBuilder builder, Expression<String> value) {
		return builder.function("HUB_NORMALIZE_SEARCH", String.class, value);
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
