package com.github.cleytonorocha.hub.service;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuthOwnershipMigration implements ApplicationRunner {
	public static final String VERSION = "AUTH_OWNER_INTEGRITY_V1";
	private static final List<String> OWNER_TABLES = List.of("hub_task", "hub_task_series", "hub_reading_item", "hub_reading_author",
		"hub_reading_item_author", "hub_reading_item_tag", "hub_task_checklist_revision", "hub_task_checklist_item",
		"hub_task_collection_state", "hub_reading_collection_state", "hub_task_settings", "google_connection",
		"google_task_list_state", "google_task_state", "hub_task_notification", "hub_task_user_revision", "hub_reading_user_revision");
	private final JdbcTemplate jdbc;
	private volatile boolean ready;
	public AuthOwnershipMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override public void run(ApplicationArguments args) {
		Long users = jdbc.queryForObject("SELECT COUNT(*) FROM hub_user", Long.class);
		if (users == null || users == 0) { ready = false; return; }
		migrate();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void migrate() {
		if (applied()) { ready = true; return; }
		Long users = jdbc.queryForObject("SELECT COUNT(*) FROM hub_user", Long.class);
		if (users == null || users == 0) { ready = false; return; }
		for (String table : OWNER_TABLES) {
			Long unassigned = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE owner_id IS NULL", Long.class);
			if (unassigned != null && unassigned > 0) throw new IllegalStateException("A migração de propriedade está incompleta em " + table + ".");
		}
		dropLegacyGlobalAuthorConstraint();
		for (String table : OWNER_TABLES) jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN owner_id SET NOT NULL");
		addConstraint("hub_task", "uq_hub_task_owner_id_constraint", "UNIQUE (owner_id, id)");
		addConstraint("hub_task_series", "uq_hub_task_series_owner_id_constraint", "UNIQUE (owner_id, id)");
		addConstraint("hub_reading_item", "uq_hub_reading_item_owner_id_constraint", "UNIQUE (owner_id, id)");
		addConstraint("hub_reading_author", "uq_hub_reading_author_owner_id_constraint", "UNIQUE (owner_id, id)");
		addConstraint("hub_task", "fk_hub_task_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_task_series", "fk_hub_task_series_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_task", "fk_hub_task_series_link_owner", "FOREIGN KEY (owner_id, series_id) REFERENCES hub_task_series(owner_id, id)");
		addConstraint("hub_reading_item", "fk_hub_reading_item_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_reading_author", "fk_hub_reading_author_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_task_user_revision", "fk_hub_task_user_revision_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_reading_user_revision", "fk_hub_reading_user_revision_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_reading_item_author", "fk_reading_item_author_item_owner", "FOREIGN KEY (owner_id, reading_item_id) REFERENCES hub_reading_item(owner_id, id)");
		addConstraint("hub_reading_item_author", "fk_reading_item_author_author_owner", "FOREIGN KEY (owner_id, author_id) REFERENCES hub_reading_author(owner_id, id)");
		addConstraint("hub_reading_item_tag", "fk_reading_item_tag_item_owner", "FOREIGN KEY (owner_id, reading_item_id) REFERENCES hub_reading_item(owner_id, id)");
		addConstraint("hub_task_checklist_revision", "fk_checklist_revision_task_owner", "FOREIGN KEY (owner_id, task_id) REFERENCES hub_task(owner_id, id)");
		addConstraint("hub_task_checklist_item", "fk_checklist_item_task_owner", "FOREIGN KEY (owner_id, task_id) REFERENCES hub_task(owner_id, id)");
		for (String table : List.of("hub_task_collection_state", "hub_reading_collection_state", "hub_task_settings", "google_connection", "google_task_list_state", "google_task_state", "hub_task_notification"))
			addConstraint(table, "fk_" + table + "_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		jdbc.update("INSERT INTO hub_schema_migration(migration_id) VALUES (?)", VERSION);
		ready = true;
	}

	public boolean isReady() { return ready || applied(); }
	public void requireReady() {
		if (!isReady()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
			"A atualização de propriedade dos dados precisa ser concluída. Reinicie o backend para tentar novamente.");
	}

	private boolean applied() {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM hub_schema_migration WHERE migration_id = ?", Integer.class, VERSION);
		return count != null && count > 0;
	}
	private void addConstraint(String table, String name, String definition) {
		jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT IF NOT EXISTS " + name + " " + definition);
	}
	private void dropLegacyGlobalAuthorConstraint() {
		List<String> constraints = jdbc.query("SELECT tc.constraint_name FROM information_schema.table_constraints tc "
			+ "JOIN information_schema.key_column_usage kcu ON tc.constraint_catalog = kcu.constraint_catalog "
			+ "AND tc.constraint_schema = kcu.constraint_schema AND tc.constraint_name = kcu.constraint_name "
			+ "WHERE UPPER(tc.table_name) = 'HUB_READING_AUTHOR' AND UPPER(tc.constraint_type) = 'UNIQUE' "
			+ "GROUP BY tc.constraint_name HAVING COUNT(*) = 1 AND MAX(UPPER(kcu.column_name)) = 'NORMALIZED_NAME'",
			(rs, row) -> rs.getString(1));
		for (String name : constraints) jdbc.execute("ALTER TABLE hub_reading_author DROP CONSTRAINT IF EXISTS \"" + name.replace("\"", "\"\"") + "\"");
	}
}
