package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.HubUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubUserRepository extends JpaRepository<HubUser, String> {
	Optional<HubUser> findByNormalizedEmail(String normalizedEmail);
	boolean existsByNormalizedEmail(String normalizedEmail);
	List<HubUser> findAllByOrderByEmailAsc();
	long countByEnabledTrueAndProfileId(String profileId);
}
