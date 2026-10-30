package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.ReadingItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ReadingItemRepository extends JpaRepository<ReadingItem, String>, JpaSpecificationExecutor<ReadingItem> {
	List<ReadingItem> findAllByOrderByIdAsc();

	List<ReadingItem> findAllByIdIn(Collection<String> ids);
}
