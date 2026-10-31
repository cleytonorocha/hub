package com.github.cleytonorocha.hub.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class OwnershipMigrationRunner implements ApplicationRunner {
	private final AuthOwnershipMigration migration;
	public OwnershipMigrationRunner(AuthOwnershipMigration migration) { this.migration = migration; }
	@Override public void run(ApplicationArguments args) { migration.migrate(); }
}
