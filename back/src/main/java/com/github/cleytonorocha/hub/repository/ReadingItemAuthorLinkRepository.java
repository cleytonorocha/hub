package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.ReadingItemAuthorLink;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReadingItemAuthorLinkRepository extends JpaRepository<ReadingItemAuthorLink, ReadingItemAuthorLink.Key> {
	List<ReadingItemAuthorLink> findAllByReadingItemIdIn(Collection<String> readingItemIds);
	void deleteAllByReadingItemId(String readingItemId);
}
