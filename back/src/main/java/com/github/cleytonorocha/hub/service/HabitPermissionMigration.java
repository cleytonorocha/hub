package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.entity.PermissionProfile;
import com.github.cleytonorocha.hub.repository.PermissionProfileRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class HabitPermissionMigration implements ApplicationRunner {
	private static final String MIGRATION_ID = "HABITS_DEFAULT_PERMISSIONS_V1";
	private final JdbcTemplate jdbc;
	private final PermissionProfileRepository profiles;

	public HabitPermissionMigration(JdbcTemplate jdbc, PermissionProfileRepository profiles) {
		this.jdbc = jdbc;
		this.profiles = profiles;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM hub_schema_migration WHERE migration_id = ?", Boolean.class, MIGRATION_ID))) return;
		for (PermissionProfile profile : profiles.findAllByOrderByNameAsc()) {
			if (!profile.isSystemProfile()) continue;
			Set<String> permissions = new LinkedHashSet<>(java.util.Arrays.asList(profile.getPermissions().split(",")));
			permissions.add("HABITS_READ");
			permissions.add("HABITS_WRITE");
			profile.update(profile.getName(), String.join(",", permissions));
			profiles.save(profile);
		}
		jdbc.update("INSERT INTO hub_schema_migration(migration_id) VALUES (?)", MIGRATION_ID);
	}
}
