package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.ReadingItemTag;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReadingItemTagRepository extends JpaRepository<ReadingItemTag, ReadingItemTag.Key> {
	List<ReadingItemTag> findAllByReadingItemIdInOrderByReadingItemIdAscTagOrderAsc(Collection<String> readingItemIds);
	void deleteAllByReadingItemId(String readingItemId);
}
