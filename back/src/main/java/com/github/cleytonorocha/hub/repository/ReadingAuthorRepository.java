package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.ReadingAuthor;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReadingAuthorRepository extends JpaRepository<ReadingAuthor, String> {
	List<ReadingAuthor> findAllByOrderByNormalizedNameAscIdAsc();
	boolean existsByNormalizedName(String normalizedName);
}
