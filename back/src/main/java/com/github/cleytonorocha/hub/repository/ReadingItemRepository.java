package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.ReadingItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ReadingItemRepository extends JpaRepository<ReadingItem, String>, JpaSpecificationExecutor<ReadingItem> {
	List<ReadingItem> findAllByOrderByIdAsc();

	@org.springframework.data.jpa.repository.EntityGraph(attributePaths = "tags")
	List<ReadingItem> findAllWithTagsByIdIn(Collection<String> ids);

	@org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"tags", "authors"})
	List<ReadingItem> findAllWithTagsAndAuthorsByIdIn(Collection<String> ids);
}
