package com.github.cleytonorocha.hub;

import com.github.cleytonorocha.hub.service.AuthOwnershipMigration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests V2 against an H2 copy shaped like a V1 database. */
@SpringBootTest(properties = {
	"spring.sql.init.mode=always",
	"spring.sql.init.schema-locations=classpath:schema.sql",
	"spring.jpa.hibernate.ddl-auto=none"
})
class OwnershipMigrationTests {
	private static final String ADMIN = "legacy-admin";
	private static final String OTHER = "other-user";
	private static final String TASK_ID = "legacy-task";
	private static final String SERIES_ID = "legacy-series";
	private static final String ITEM_ID = "legacy-item";
	private static final String AUTHOR_ID = "legacy-author";
	private static final String LIST_ID = "primary-list";
	private static final String GOOGLE_TASK_ID = "primary-task";
	private static final String NOTIFICATION_ID = "legacy-notification";
	private static final String OPERATION_ID = "legacy-operation";
	private static final String CHECKLIST_ID = "legacy-checklist-item";
	private static final String NOTE_ID = "legacy-note-v3";
	private static final String HABIT_ID = "legacy-habit-v3";
	private static final String FOCUS_ID = "legacy-focus-v3";
	private static final String TEST_DATABASE_URL = "jdbc:h2:mem:ownership-migration-" + UUID.randomUUID()
		+ ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
	private static final Map<String, List<String>> NEW_PRIMARY_KEYS = Map.ofEntries(
		Map.entry("hub_task", List.of("OWNER_ID", "ID")),
		Map.entry("hub_task_series", List.of("OWNER_ID", "ID")),
		Map.entry("hub_reading_item", List.of("OWNER_ID", "ID")),
		Map.entry("hub_reading_author", List.of("OWNER_ID", "ID")),
		Map.entry("hub_reading_item_author", List.of("OWNER_ID", "READING_ITEM_ID", "AUTHOR_ID")),
		Map.entry("hub_reading_item_tag", List.of("OWNER_ID", "READING_ITEM_ID", "TAG_ORDER")),
		Map.entry("hub_task_checklist_revision", List.of("OWNER_ID", "TASK_ID")),
		Map.entry("hub_task_checklist_item", List.of("OWNER_ID", "ID")),
		Map.entry("google_task_list_state", List.of("OWNER_ID", "CONNECTION_ID", "TASK_LIST_ID")),
		Map.entry("google_task_state", List.of("OWNER_ID", "CONNECTION_ID", "TASK_LIST_ID", "TASK_ID")),
		Map.entry("hub_task_notification", List.of("OWNER_ID", "NOTIFICATION_ID")));
	private static final Map<String, List<String>> LEGACY_PRIMARY_KEYS = Map.ofEntries(
		Map.entry("hub_task", List.of("ID")),
		Map.entry("hub_task_series", List.of("ID")),
		Map.entry("hub_reading_item", List.of("ID")),
		Map.entry("hub_reading_author", List.of("ID")),
		Map.entry("hub_reading_item_author", List.of("READING_ITEM_ID", "AUTHOR_ID")),
		Map.entry("hub_reading_item_tag", List.of("READING_ITEM_ID", "TAG_ORDER")),
		Map.entry("hub_task_checklist_revision", List.of("TASK_ID")),
		Map.entry("hub_task_checklist_item", List.of("ID")),
		Map.entry("google_task_list_state", List.of("CONNECTION_ID", "TASK_LIST_ID")),
		Map.entry("google_task_state", List.of("CONNECTION_ID", "TASK_LIST_ID", "TASK_ID")),
		Map.entry("hub_task_notification", List.of("NOTIFICATION_ID")));

	@DynamicPropertySource
	static void isolatedDatabase(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
	}

	@Autowired JdbcTemplate jdbc;
	@Autowired AuthOwnershipMigration migration;

	@Test
	void upgradesV1SchemaPreservesLegacyDataAndResumesAfterPartialDdl() {
		seedUsersAndV1Data();
		seedV3Data();
		downgradeKeysToV1Shape();
		downgradeV3KeysToLegacyShape();
		assertThat(foreignKeyExists("fk_hub_note_tag_note")).isTrue();
		assertThat(primaryKey("hub_note")).containsExactly("ID");
		assertThat(primaryKey("hub_habit")).containsExactly("ID");
		jdbc.update("INSERT INTO hub_schema_migration(migration_id) VALUES (?)", AuthOwnershipMigration.V1);

		// Simulate a process stopping after dropping old constraints and converting
		// some keys, but before writing the V2 marker.
		dropMigrationForeignKeys();
		convertPrimaryKey("hub_task", NEW_PRIMARY_KEYS.get("hub_task"));
		convertPrimaryKey("hub_task_series", NEW_PRIMARY_KEYS.get("hub_task_series"));
		convertPrimaryKey("hub_reading_item", NEW_PRIMARY_KEYS.get("hub_reading_item"));

		migration.migrate();

		for (var entry : NEW_PRIMARY_KEYS.entrySet()) assertThat(primaryKey(entry.getKey())).as(entry.getKey()).containsExactlyElementsOf(entry.getValue());
		System.out.println("MIGRATION_TEST_SERIES_UNIQUES=" + jdbc.query("SELECT tc.constraint_name || ':' || kcu.column_name FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu ON tc.constraint_catalog=kcu.constraint_catalog AND tc.constraint_schema=kcu.constraint_schema AND tc.constraint_name=kcu.constraint_name WHERE UPPER(tc.table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(tc.table_name)='HUB_TASK_SERIES' AND tc.constraint_type='UNIQUE' ORDER BY tc.constraint_name,kcu.ordinal_position", (rs, row) -> rs.getString(1)));
		assertThat(uniqueConstraintExists("hub_task_series", "owner_id", "operation_id")).isTrue();
		assertThat(uniqueConstraintExists("hub_reading_author", "owner_id", "normalized_name")).isTrue();
		assertThat(uniqueConstraintExists("hub_task_series", "operation_id")).isFalse();
		assertThat(uniqueConstraintExists("hub_reading_author", "normalized_name")).isFalse();
		assertThat(foreignKeyExists("fk_hub_task_series_link_owner")).isTrue();
		assertThat(foreignKeyExists("fk_reading_item_author_item_owner")).isTrue();
		assertThat(foreignKeyExists("fk_reading_item_author_author_owner")).isTrue();
		assertThat(foreignKeyExists("fk_reading_item_tag_item_owner")).isTrue();
		assertThat(foreignKeyExists("fk_checklist_revision_task_owner")).isTrue();
		assertThat(foreignKeyExists("fk_checklist_item_task_owner")).isTrue();
		assertThat(foreignKeyExists("fk_google_task_list_connection_owner")).isTrue();
		assertThat(foreignKeyExists("fk_google_task_state_list_owner")).isTrue();
		assertThat(foreignKeyExists("fk_task_notice_connection_owner")).isTrue();
		assertThat(migration.isReady()).isTrue();
		assertThat(migrationApplied(AuthOwnershipMigration.V2)).isTrue();

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_task WHERE owner_id=? AND id=?", Integer.class, ADMIN, TASK_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_task_series WHERE owner_id=? AND id=? AND operation_id=?", Integer.class, ADMIN, SERIES_ID, OPERATION_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_reading_item WHERE owner_id=? AND id=?", Integer.class, ADMIN, ITEM_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_reading_author WHERE owner_id=? AND id=?", Integer.class, ADMIN, AUTHOR_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_task_checklist_item WHERE owner_id=? AND id=?", Integer.class, ADMIN, CHECKLIST_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM google_connection WHERE connection_id='primary' AND owner_id=? AND refresh_token_ciphertext='legacy-ciphertext'", Integer.class, ADMIN)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM google_task_list_state WHERE owner_id=? AND connection_id='primary' AND task_list_id=?", Integer.class, ADMIN, LIST_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM google_task_state WHERE owner_id=? AND task_id=?", Integer.class, ADMIN, GOOGLE_TASK_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_task_notification WHERE owner_id=? AND notification_id=?", Integer.class, ADMIN, NOTIFICATION_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT reminder_time FROM hub_task_user_settings WHERE owner_id=?", java.sql.Time.class, ADMIN).toLocalTime().toString()).isEqualTo("07:45");
		assertThat(jdbc.queryForObject("SELECT reminder_time FROM hub_task_user_settings WHERE owner_id=?", java.sql.Time.class, OTHER).toLocalTime().toString()).isEqualTo("09:00");

		// The composite foreign key must reject a Task owned by ADMIN that points
		// only to a series belonging to OTHER.
		jdbc.update("INSERT INTO hub_task_series(id,owner_id,operation_id,title,start_date,occurrence_count,time_zone_id,ended) VALUES('other-only-series',?, 'other-operation','Other',CURRENT_DATE,2,'UTC',FALSE)", OTHER);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO hub_task(id,owner_id,title,completed,task_type,category,color,priority,notes,series_id) VALUES('cross-owner-link',?,'Bad link',FALSE,'LOCAL','Geral','#A986FF','NORMAL','', 'other-only-series')", ADMIN))
			.isInstanceOf(DataIntegrityViolationException.class);

		// Same portable identifiers and de-duplication keys are legal per owner.
		jdbc.update("INSERT INTO hub_task_series(id,owner_id,operation_id,title,start_date,occurrence_count,time_zone_id,ended) VALUES(?,?,?,?,CURRENT_DATE,2,'UTC',FALSE)", SERIES_ID, OTHER, OPERATION_ID, "same portable series");
		jdbc.update("INSERT INTO hub_task(id,owner_id,title,completed,task_type,category,color,priority,notes,series_id,series_position) VALUES(?,?,?,FALSE,'LOCAL','Geral','#A986FF','NORMAL','',?,0)", TASK_ID, OTHER, "same portable task", SERIES_ID);
		jdbc.update("INSERT INTO hub_reading_author(id,owner_id,name,normalized_name) VALUES(?,?,?,?)", AUTHOR_ID, OTHER, "Same author", "legacy author");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_task_series WHERE id=? AND operation_id=?", Integer.class, SERIES_ID, OPERATION_ID)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_reading_author WHERE id=? AND normalized_name=?", Integer.class, AUTHOR_ID, "legacy author")).isEqualTo(2);

		List<String> beforeRepeat = primaryKey("hub_task");
		migration.migrate();
		assertThat(primaryKey("hub_task")).isEqualTo(beforeRepeat);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_schema_migration WHERE migration_id=?", Integer.class, AuthOwnershipMigration.V2)).isEqualTo(1);
		assertThat(migrationApplied(AuthOwnershipMigration.V3)).isTrue();
		assertThat(primaryKey("hub_note")).containsExactly("OWNER_ID", "ID");
		assertThat(primaryKey("hub_note_tag")).containsExactly("OWNER_ID", "NOTE_ID", "TAG_ORDER");
		assertThat(primaryKey("hub_habit_schedule")).containsExactly("OWNER_ID", "ID");
		assertThat(primaryKey("hub_focus_active_slot")).containsExactly("OWNER_ID");
		assertThat(foreignKeyExists("fk_hub_note_tag_note")).isTrue();
		assertThat(foreignKeyExists("fk_hub_habit_schedule_habit")).isTrue();
		assertThat(foreignKeyExists("fk_hub_focus_slot_session")).isTrue();
		assertThat(foreignKeyExists("fk_hub_habit_pause_habit")).isTrue();
		assertThat(primaryKey("hub_habit")).containsExactly("OWNER_ID", "ID");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu ON tc.constraint_catalog=kcu.constraint_catalog AND tc.constraint_schema=kcu.constraint_schema AND tc.constraint_name=kcu.constraint_name WHERE UPPER(tc.table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(tc.table_name)='HUB_FOCUS_SESSION' AND UPPER(kcu.column_name)='TASK_ID' AND tc.constraint_type='FOREIGN KEY'", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_note WHERE owner_id=? AND id=?", Integer.class, ADMIN, NOTE_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_note_tag WHERE owner_id=? AND note_id=? AND tag_order=0", Integer.class, ADMIN, NOTE_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_habit WHERE owner_id=? AND id=?", Integer.class, ADMIN, HABIT_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_habit_schedule WHERE owner_id=? AND habit_id=?", Integer.class, ADMIN, HABIT_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_focus_session WHERE owner_id=? AND id=?", Integer.class, ADMIN, FOCUS_ID)).isEqualTo(1);

		// V3 refuses to guess an owner or repair an orphaned child relationship.
		String orphanScheduleId = UUID.randomUUID().toString();
		jdbc.update("DELETE FROM hub_schema_migration WHERE migration_id=?", AuthOwnershipMigration.V3);
		for (String child : List.of("hub_habit_schedule", "hub_habit_pause", "hub_habit_checkin"))
			jdbc.execute("ALTER TABLE " + child + " DROP CONSTRAINT IF EXISTS " + switch (child) {
				case "hub_habit_schedule" -> "fk_hub_habit_schedule_habit";
				case "hub_habit_pause" -> "fk_hub_habit_pause_habit";
				default -> "fk_hub_habit_checkin_habit";
			});
		convertPrimaryKey("hub_habit", List.of("id"));
		jdbc.execute("ALTER TABLE hub_habit_pause ADD CONSTRAINT fk_hub_habit_pause_habit FOREIGN KEY (habit_id) REFERENCES hub_habit(id) ON DELETE CASCADE");
		jdbc.execute("ALTER TABLE hub_habit_checkin ADD CONSTRAINT fk_hub_habit_checkin_habit FOREIGN KEY (habit_id) REFERENCES hub_habit(id) ON DELETE CASCADE");
		try {
			jdbc.update("INSERT INTO hub_habit_schedule(id,owner_id,habit_id,effective_from,weekdays_mask) VALUES(?,?,?,CURRENT_DATE,1)", orphanScheduleId, OTHER, "missing-habit");
			assertThatThrownBy(migration::migrate).isInstanceOf(IllegalStateException.class).hasMessageContaining("órfãos");
			assertThat(migration.isReady()).isFalse();
			assertThat(migrationApplied(AuthOwnershipMigration.V3)).isFalse();
			assertThat(primaryKey("hub_habit")).containsExactly("ID");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_habit_schedule WHERE id=?", Integer.class, orphanScheduleId)).isEqualTo(1);
		} finally {
			jdbc.update("DELETE FROM hub_habit_schedule WHERE id=?", orphanScheduleId);
		}
		migration.migrate();
		assertThat(migrationApplied(AuthOwnershipMigration.V3)).isTrue();
		assertThat(primaryKey("hub_habit")).containsExactly("OWNER_ID", "ID");
		jdbc.update("DELETE FROM hub_schema_migration WHERE migration_id=?", AuthOwnershipMigration.V3);
		Long preservedNoteRevision = jdbc.queryForObject("SELECT revision FROM hub_note_user_revision WHERE owner_id=?", Long.class, ADMIN);
		jdbc.execute("DROP TABLE hub_note_user_revision");
		rebuildNoteRevisionAsNullableLegacyTable(preservedNoteRevision);
		try {
			jdbc.update("INSERT INTO hub_note_user_revision(owner_id,revision) VALUES(NULL,0)");
			assertThatThrownBy(migration::migrate).isInstanceOf(IllegalStateException.class).hasMessageContaining("proprietário ausente");
			assertThat(migration.isReady()).isFalse();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_note_user_revision WHERE owner_id IS NULL", Integer.class)).isEqualTo(1);
		} finally {
			jdbc.execute("DROP TABLE hub_note_user_revision");
			rebuildNoteRevisionAsNullableLegacyTable(preservedNoteRevision);
		}
		migration.migrate();
		assertThat(migration.isReady()).isTrue();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_schema_migration WHERE migration_id=?", Integer.class, AuthOwnershipMigration.V3)).isEqualTo(1);
		assertThat(primaryKey("hub_note_user_revision")).containsExactly("OWNER_ID");
		assertThat(foreignKeyExists("fk_hub_note_revision_owner")).isTrue();
		assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE UPPER(table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(table_name)='HUB_NOTE_USER_REVISION' AND UPPER(column_name)='OWNER_ID'", String.class)).isEqualTo("NO");
		assertThat(jdbc.queryForObject("SELECT revision FROM hub_note_user_revision WHERE owner_id=?", Long.class, ADMIN)).isEqualTo(preservedNoteRevision);
		jdbc.update("DELETE FROM hub_schema_migration WHERE migration_id=?", AuthOwnershipMigration.V3);
		jdbc.execute("ALTER TABLE hub_note_tag DROP CONSTRAINT IF EXISTS uq_hub_note_tag_value");
		try {
			jdbc.update("INSERT INTO hub_note_tag(owner_id,note_id,tag_order,tag,normalized_tag) VALUES(?,?,1,'Legacy','legacy')", ADMIN, NOTE_ID);
			assertThatThrownBy(migration::migrate).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("hub_note_tag").hasMessageContaining("duplicadas");
			assertThat(migrationApplied(AuthOwnershipMigration.V3)).isFalse();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_note_tag WHERE owner_id=? AND note_id=?", Integer.class, ADMIN, NOTE_ID)).isEqualTo(2);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_note WHERE owner_id=? AND id=?", Integer.class, ADMIN, NOTE_ID)).isEqualTo(1);
		} finally {
			jdbc.update("DELETE FROM hub_note_tag WHERE owner_id=? AND note_id=? AND tag_order=1", ADMIN, NOTE_ID);
			jdbc.execute("ALTER TABLE hub_note_tag ADD CONSTRAINT IF NOT EXISTS uq_hub_note_tag_value UNIQUE (owner_id, note_id, normalized_tag)");
		}
		migration.migrate();
		assertThat(migrationApplied(AuthOwnershipMigration.V3)).isTrue();
		String cascadeNote = UUID.randomUUID().toString();
		String cascadeHabit = UUID.randomUUID().toString();
		String cascadeSchedule = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO hub_note(id,owner_id,title,body,created_at,updated_at) VALUES(?,?,?,'cascade',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", cascadeNote, ADMIN, "Cascade note");
		jdbc.update("INSERT INTO hub_note_tag(owner_id,note_id,tag_order,tag,normalized_tag) VALUES(?,?,0,'cascade','cascade')", ADMIN, cascadeNote);
		jdbc.update("INSERT INTO hub_habit(id,owner_id,name,start_date,created_at,updated_at) VALUES(?,?,?,CURRENT_DATE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", cascadeHabit, ADMIN, "Cascade habit");
		jdbc.update("INSERT INTO hub_habit_schedule(id,owner_id,habit_id,effective_from,weekdays_mask) VALUES(?,?,?,CURRENT_DATE,1)", cascadeSchedule, ADMIN, cascadeHabit);
		jdbc.update("DELETE FROM hub_note WHERE owner_id=? AND id=?", ADMIN, cascadeNote);
		jdbc.update("DELETE FROM hub_habit WHERE owner_id=? AND id=?", ADMIN, cascadeHabit);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_note_tag WHERE owner_id=? AND note_id=?", Integer.class, ADMIN, cascadeNote)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hub_habit_schedule WHERE owner_id=? AND id=?", Integer.class, ADMIN, cascadeSchedule)).isZero();
	}

	private void seedUsersAndV1Data() {
		String profile = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO hub_permission_profile(id,name,normalized_name,permissions,system_profile) VALUES(?,?,?, ?,FALSE)", profile, "Migration", "migration-" + profile, "TASK_READ,TASK_WRITE");
		for (String owner : List.of(ADMIN, OTHER)) {
			String email = owner + "@migration.test";
			jdbc.update("INSERT INTO hub_user(id,display_name,email,normalized_email,password_hash,profile_id,enabled,created_at) VALUES(?,?,?,?,?,?,TRUE,CURRENT_TIMESTAMP)", owner, owner, email, email, "unused", profile);
			jdbc.update("INSERT INTO hub_task_user_revision(owner_id,revision) VALUES(?,0)", owner);
			jdbc.update("INSERT INTO hub_reading_user_revision(owner_id,revision) VALUES(?,0)", owner);
		}
		jdbc.update("UPDATE hub_task_collection_state SET owner_id=? WHERE state_id=1", ADMIN);
		jdbc.update("UPDATE hub_reading_collection_state SET owner_id=? WHERE state_id=1", ADMIN);
		jdbc.update("UPDATE hub_task_settings SET owner_id=?,reminder_time=TIME '07:45' WHERE settings_id=1", ADMIN);
		jdbc.update("INSERT INTO hub_task_user_settings(owner_id,reminder_time) VALUES(?,TIME '07:45')", ADMIN);
		jdbc.update("INSERT INTO hub_task_user_settings(owner_id,reminder_time) VALUES(?,TIME '09:00')", OTHER);
		jdbc.update("INSERT INTO hub_task_series(id,owner_id,operation_id,title,start_date,occurrence_count,time_zone_id,ended) VALUES(?,?,?,?,'2026-01-01',2,'UTC',FALSE)", SERIES_ID, ADMIN, OPERATION_ID, "legacy series");
		jdbc.update("INSERT INTO hub_task(id,owner_id,title,completed,task_type,category,color,priority,notes,series_id,series_position) VALUES(?,?,?,FALSE,'LOCAL','Geral','#A986FF','NORMAL','legacy note',?,0)", TASK_ID, ADMIN, "legacy task", SERIES_ID);
		jdbc.update("INSERT INTO hub_task_checklist_revision(owner_id,task_id,revision) VALUES(?,?,2)", ADMIN, TASK_ID);
		jdbc.update("INSERT INTO hub_task_checklist_item(id,owner_id,task_id,item_order,item_text,checked) VALUES(?,?,?,0,'legacy checklist',FALSE)", CHECKLIST_ID, ADMIN, TASK_ID);
		jdbc.update("INSERT INTO hub_reading_item(id,owner_id,title,item_type,reading_status,progress_marker,personal_note,archived,revision,created_at,updated_at) VALUES(?,?,?,'BOOK','TO_READ','', '',FALSE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", ITEM_ID, ADMIN, "legacy reading");
		jdbc.update("INSERT INTO hub_reading_author(id,owner_id,name,normalized_name) VALUES(?,?,?,?)", AUTHOR_ID, ADMIN, "Legacy Author", "legacy author");
		jdbc.update("INSERT INTO hub_reading_item_author(owner_id,reading_item_id,author_id) VALUES(?,?,?)", ADMIN, ITEM_ID, AUTHOR_ID);
		jdbc.update("INSERT INTO hub_reading_item_tag(owner_id,reading_item_id,tag_order,tag) VALUES(?,?,0,'legacy tag')", ADMIN, ITEM_ID);
		jdbc.update("INSERT INTO google_connection(connection_id,owner_id,refresh_token_ciphertext,refresh_token_nonce,scopes,status,updated_at) VALUES('primary',?,'legacy-ciphertext','01234567890123456789012345678901','tasks','CONNECTED',CURRENT_TIMESTAMP)", ADMIN);
		jdbc.update("INSERT INTO google_task_list_state(owner_id,connection_id,task_list_id,selected,deleted) VALUES(?,'primary',?,TRUE,FALSE)", ADMIN, LIST_ID);
		jdbc.update("INSERT INTO google_task_state(owner_id,connection_id,task_list_id,task_id,completed,deleted) VALUES(?,'primary',?,?,FALSE,FALSE)", ADMIN, LIST_ID, GOOGLE_TASK_ID);
		jdbc.update("INSERT INTO hub_task_notification(owner_id,notification_id,connection_id,task_list_id,task_id,due_date,scheduled_at,state,overdue,created_at) VALUES(? ,?,'primary',?,?,CURRENT_DATE,CURRENT_TIMESTAMP,'PENDING',FALSE,CURRENT_TIMESTAMP)", ADMIN, NOTIFICATION_ID, LIST_ID, GOOGLE_TASK_ID);
	}

	private void seedV3Data() {
		jdbc.update("INSERT INTO hub_note(id,owner_id,title,body,pinned,archived,related_task_id,related_task_unavailable,revision,created_at,updated_at) VALUES(?,?,?,'preserved note',FALSE,FALSE,NULL,FALSE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", NOTE_ID, ADMIN, "V3 note");
		jdbc.update("INSERT INTO hub_note_tag(owner_id,note_id,tag_order,tag,normalized_tag) VALUES(?,?,0,'legacy','legacy')", ADMIN, NOTE_ID);
		jdbc.update("INSERT INTO hub_note_user_revision(owner_id,revision) VALUES(?,2)", ADMIN);
		jdbc.update("INSERT INTO hub_habit(id,owner_id,name,start_date,revision,created_at,updated_at) VALUES(?,?,?,CURRENT_DATE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", HABIT_ID, ADMIN, "V3 habit");
		jdbc.update("INSERT INTO hub_habit_schedule(id,owner_id,habit_id,effective_from,weekdays_mask) VALUES(?,?,?,CURRENT_DATE,127)", UUID.randomUUID().toString(), ADMIN, HABIT_ID);
		jdbc.update("INSERT INTO hub_habit_pause(id,owner_id,habit_id,paused_on) VALUES(?,?,?,CURRENT_DATE)", UUID.randomUUID().toString(), ADMIN, HABIT_ID);
		jdbc.update("INSERT INTO hub_habit_checkin(id,owner_id,habit_id,checkin_date,created_at) VALUES(?,?,?,CURRENT_DATE,CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), ADMIN, HABIT_ID);
		jdbc.update("INSERT INTO hub_habit_user_revision(owner_id,revision) VALUES(?,3)", ADMIN);
		jdbc.update("INSERT INTO hub_focus_session(id,owner_id,objective,planned_seconds,focused_seconds,session_state,started_at,resumed_at,paused_at,ended_at,end_reason,task_id,revision) VALUES(?,?,?,3600,900,'ENDED',CURRENT_TIMESTAMP,NULL,NULL,CURRENT_TIMESTAMP,'COMPLETED',NULL,0)", FOCUS_ID, ADMIN, "V3 focus");
		jdbc.update("INSERT INTO hub_focus_active_slot(owner_id,session_id) VALUES(?,NULL)", ADMIN);
	}

	private void rebuildNoteRevisionAsNullableLegacyTable(Long revision) {
		jdbc.execute("CREATE TABLE hub_note_user_revision (owner_id VARCHAR(36), revision BIGINT NOT NULL DEFAULT 0)");
		jdbc.update("INSERT INTO hub_note_user_revision(owner_id,revision) VALUES(?,?)", ADMIN, revision);
	}

	private void downgradeV3KeysToLegacyShape() {
		for (String[] constraint : List.of(
				new String[] {"hub_note_tag", "fk_hub_note_tag_note"},
				new String[] {"hub_habit_schedule", "fk_hub_habit_schedule_habit"},
				new String[] {"hub_habit_pause", "fk_hub_habit_pause_habit"},
				new String[] {"hub_habit_checkin", "fk_hub_habit_checkin_habit"},
				new String[] {"hub_focus_active_slot", "fk_hub_focus_slot_session"}))
			jdbc.execute("ALTER TABLE " + constraint[0] + " DROP CONSTRAINT IF EXISTS " + constraint[1]);
		convertPrimaryKey("hub_note", List.of("id"));
		convertPrimaryKey("hub_note_tag", List.of("note_id", "tag_order"));
		convertPrimaryKey("hub_habit", List.of("id"));
		convertPrimaryKey("hub_habit_schedule", List.of("id"));
		convertPrimaryKey("hub_habit_pause", List.of("id"));
		convertPrimaryKey("hub_habit_checkin", List.of("id"));
		convertPrimaryKey("hub_focus_session", List.of("id"));
		jdbc.execute("ALTER TABLE hub_note_tag ADD CONSTRAINT fk_hub_note_tag_note FOREIGN KEY (note_id) REFERENCES hub_note(id) ON DELETE CASCADE");
		jdbc.execute("ALTER TABLE hub_habit_schedule ADD CONSTRAINT fk_hub_habit_schedule_habit FOREIGN KEY (habit_id) REFERENCES hub_habit(id) ON DELETE CASCADE");
		jdbc.execute("ALTER TABLE hub_habit_pause ADD CONSTRAINT fk_hub_habit_pause_habit FOREIGN KEY (habit_id) REFERENCES hub_habit(id) ON DELETE CASCADE");
		jdbc.execute("ALTER TABLE hub_habit_checkin ADD CONSTRAINT fk_hub_habit_checkin_habit FOREIGN KEY (habit_id) REFERENCES hub_habit(id) ON DELETE CASCADE");
		jdbc.execute("ALTER TABLE hub_focus_active_slot ADD CONSTRAINT fk_hub_focus_slot_session FOREIGN KEY (session_id) REFERENCES hub_focus_session(id)");
	}

	private void downgradeKeysToV1Shape() {
		dropMigrationForeignKeys();
		for (String table : List.of("hub_task", "hub_task_series", "hub_reading_item", "hub_reading_author"))
			jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS uq_" + table + "_owner_id_constraint");
		for (var entry : LEGACY_PRIMARY_KEYS.entrySet()) convertPrimaryKey(entry.getKey(), entry.getValue());
		jdbc.execute("ALTER TABLE hub_task ADD CONSTRAINT uq_hub_task_owner_id_constraint UNIQUE(owner_id,id)");
		jdbc.execute("ALTER TABLE hub_task_series ADD CONSTRAINT uq_hub_task_series_owner_id_constraint UNIQUE(owner_id,id)");
		jdbc.execute("ALTER TABLE hub_reading_item ADD CONSTRAINT uq_hub_reading_item_owner_id_constraint UNIQUE(owner_id,id)");
		jdbc.execute("ALTER TABLE hub_reading_author ADD CONSTRAINT uq_hub_reading_author_owner_id_constraint UNIQUE(owner_id,id)");
		jdbc.execute("ALTER TABLE hub_task_series DROP CONSTRAINT IF EXISTS uq_hub_task_series_owner_operation");
		jdbc.execute("ALTER TABLE hub_reading_author DROP CONSTRAINT IF EXISTS uq_hub_reading_author_owner_name");
		jdbc.execute("ALTER TABLE hub_task_series ADD CONSTRAINT uq_legacy_series_operation UNIQUE(operation_id)");
		jdbc.execute("ALTER TABLE hub_reading_author ADD CONSTRAINT uq_legacy_author_name UNIQUE(normalized_name)");
		jdbc.execute("CREATE UNIQUE INDEX uq_hub_task_series_position ON hub_task(series_id,series_position)");
	}

	private void dropMigrationForeignKeys() {
		for (String table : NEW_PRIMARY_KEYS.keySet()) {
			List<String> names = jdbc.query("SELECT constraint_name FROM information_schema.table_constraints WHERE UPPER(table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(table_name)=UPPER(?) AND constraint_type='FOREIGN KEY'", (rs, row) -> rs.getString(1), table);
			for (String name : names) jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS \"" + name.replace("\"", "\"\"") + "\"");
		}
	}

	private void convertPrimaryKey(String table, List<String> columns) {
		List<String> actual = primaryKey(table);
		if (actual.equals(columns.stream().map(value -> value.toUpperCase(java.util.Locale.ROOT)).toList())) return;
		dropPrimaryKey(table);
		jdbc.execute("ALTER TABLE " + table + " ADD PRIMARY KEY(" + String.join(",", columns) + ")");
	}

	private void dropPrimaryKey(String table) {
		List<String> names = jdbc.query("SELECT constraint_name FROM information_schema.table_constraints WHERE UPPER(table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(table_name)=UPPER(?) AND constraint_type='PRIMARY KEY'",
			(rs, row) -> rs.getString(1), table);
		if (names.size() > 1) throw new IllegalStateException("More than one primary key found for " + table);
		if (!names.isEmpty()) jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + names.getFirst().replace("\"", "\"\"") + "\"");
	}

	private List<String> primaryKey(String table) {
		return jdbc.query("SELECT kcu.column_name FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu ON tc.constraint_catalog=kcu.constraint_catalog AND tc.constraint_schema=kcu.constraint_schema AND tc.constraint_name=kcu.constraint_name WHERE UPPER(tc.table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(tc.table_name)=UPPER(?) AND tc.constraint_type='PRIMARY KEY' ORDER BY kcu.ordinal_position", (rs, row) -> rs.getString(1).toUpperCase(java.util.Locale.ROOT), table);
	}

	private boolean uniqueConstraintExists(String table, String... expectedColumns) {
		List<List<String>> keys = jdbc.query("SELECT tc.constraint_name,kcu.column_name,kcu.ordinal_position FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu ON tc.constraint_catalog=kcu.constraint_catalog AND tc.constraint_schema=kcu.constraint_schema AND tc.constraint_name=kcu.constraint_name WHERE UPPER(tc.table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(tc.table_name)=UPPER(?) AND tc.constraint_type='UNIQUE' ORDER BY tc.constraint_name,kcu.ordinal_position", (rs, row) -> List.of(rs.getString(1), rs.getString(2).toUpperCase(java.util.Locale.ROOT)), table);
		java.util.Map<String,List<String>> grouped = new java.util.LinkedHashMap<>();
		for (List<String> row : keys) grouped.computeIfAbsent(row.get(0), ignored -> new java.util.ArrayList<>()).add(row.get(1));
		List<String> expected = java.util.Arrays.stream(expectedColumns).map(column -> column.toUpperCase(java.util.Locale.ROOT)).toList();
		return grouped.values().stream().anyMatch(columns -> columns.equals(expected));
	}

	private boolean foreignKeyExists(String name) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE UPPER(table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(constraint_name)=UPPER(?) AND constraint_type='FOREIGN KEY'", Integer.class, name);
		return count != null && count > 0;
	}

	private boolean migrationApplied(String id) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM hub_schema_migration WHERE migration_id=?", Integer.class, id);
		return count != null && count > 0;
	}
}
