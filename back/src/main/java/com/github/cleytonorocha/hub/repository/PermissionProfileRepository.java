package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.PermissionProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionProfileRepository extends JpaRepository<PermissionProfile, String> {
	Optional<PermissionProfile> findByNormalizedName(String normalizedName);
	List<PermissionProfile> findAllByOrderByNameAsc();
}
