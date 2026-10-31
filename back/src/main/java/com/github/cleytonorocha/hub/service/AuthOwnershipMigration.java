package com.github.cleytonorocha.hub.service;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the ownership constraints after schema.sql has created the versioned
 * objects. Kept separate from the ApplicationRunner so calls through this bean
 * are proxied and REQUIRES_NEW is effective.
 */
@Service
public class AuthOwnershipMigration {
	public static final String V1 = "AUTH_OWNER_INTEGRITY_V1";
	public static final String V2 = "OWNER_COMPOSITE_KEYS_V2";
	public static final String V3 = "NOTES_HABITS_FOCUS_OWNER_INTEGRITY_V3";
	private static final java.util.Map<String, List<String>> V3_COMPOSITE_KEYS = java.util.Map.ofEntries(
		java.util.Map.entry("hub_note", List.of("owner_id", "id")),
		java.util.Map.entry("hub_note_tag", List.of("owner_id", "note_id", "tag_order")),
		java.util.Map.entry("hub_note_user_revision", List.of("owner_id")),
		java.util.Map.entry("hub_habit", List.of("owner_id", "id")),
		java.util.Map.entry("hub_habit_schedule", List.of("owner_id", "id")),
		java.util.Map.entry("hub_habit_pause", List.of("owner_id", "id")),
		java.util.Map.entry("hub_habit_checkin", List.of("owner_id", "id")),
		java.util.Map.entry("hub_habit_user_revision", List.of("owner_id")),
		java.util.Map.entry("hub_focus_session", List.of("owner_id", "id")),
		java.util.Map.entry("hub_focus_active_slot", List.of("owner_id")));
	private static final List<String> V3_OWNER_TABLES = List.copyOf(V3_COMPOSITE_KEYS.keySet());
	private static final List<String> OWNER_TABLES = List.of("hub_task", "hub_task_series", "hub_reading_item",
		"hub_reading_author", "hub_reading_item_author", "hub_reading_item_tag", "hub_task_checklist_revision",
		"hub_task_checklist_item", "hub_task_collection_state", "hub_reading_collection_state", "hub_task_settings",
		"google_connection", "google_task_list_state", "google_task_state", "hub_task_notification",
		"hub_task_user_revision", "hub_reading_user_revision");
	private static final java.util.Map<String, List<String>> COMPOSITE_KEYS = java.util.Map.ofEntries(
		java.util.Map.entry("hub_task", List.of("owner_id", "id")),
		java.util.Map.entry("hub_task_series", List.of("owner_id", "id")),
		java.util.Map.entry("hub_reading_item", List.of("owner_id", "id")),
		java.util.Map.entry("hub_reading_author", List.of("owner_id", "id")),
		java.util.Map.entry("hub_reading_item_author", List.of("owner_id", "reading_item_id", "author_id")),
		java.util.Map.entry("hub_reading_item_tag", List.of("owner_id", "reading_item_id", "tag_order")),
		java.util.Map.entry("hub_task_checklist_revision", List.of("owner_id", "task_id")),
		java.util.Map.entry("hub_task_checklist_item", List.of("owner_id", "id")),
		java.util.Map.entry("google_task_list_state", List.of("owner_id", "connection_id", "task_list_id")),
		java.util.Map.entry("google_task_state", List.of("owner_id", "connection_id", "task_list_id", "task_id")),
		java.util.Map.entry("hub_task_notification", List.of("owner_id", "notification_id")));
	private final JdbcTemplate jdbc;
	private volatile boolean ready;

	public AuthOwnershipMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void migrate() {
		ready = false;
		if (applied(V1) && applied(V2) && applied(V3)) { ready = true; return; }
		Long users = jdbc.queryForObject("SELECT COUNT(*) FROM hub_user", Long.class);
		if (users == null || users == 0) { ready = false; return; }
		for (String table : OWNER_TABLES) {
			Long unassigned = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE owner_id IS NULL", Long.class);
			if (unassigned != null && unassigned > 0)
				throw new IllegalStateException("A migração de propriedade está incompleta em " + table + ".");
		}
		validateV3OwnershipData();
		validateV3UniqueData();
		List<NamedForeignKey> approvedV3ForeignKeys = preflightV3ReferencingForeignKeys();
		applyV1();
		applyV2();
		applyV3(approvedV3ForeignKeys);
		validateOwnership();
		markApplied(V1);
		markApplied(V2);
		markApplied(V3);
		ready = true;
	}

	private void applyV3(List<NamedForeignKey> approvedForeignKeys) {
		validateV3OwnershipData();
		validateV3UniqueData();
		dropV3ReferencingForeignKeys(approvedForeignKeys);
		for (String table : V3_OWNER_TABLES) jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN owner_id SET NOT NULL");
		for (var entry : V3_COMPOSITE_KEYS.entrySet()) ensurePrimaryKey(entry.getKey(), entry.getValue());
		addConstraint("hub_note", "fk_hub_note_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_note_tag", "fk_hub_note_tag_note", "FOREIGN KEY (owner_id, note_id) REFERENCES hub_note(owner_id, id) ON DELETE CASCADE");
		addConstraint("hub_note_user_revision", "fk_hub_note_revision_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_habit", "fk_hub_habit_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_habit_schedule", "fk_hub_habit_schedule_habit", "FOREIGN KEY (owner_id, habit_id) REFERENCES hub_habit(owner_id, id) ON DELETE CASCADE");
		addConstraint("hub_habit_pause", "fk_hub_habit_pause_habit", "FOREIGN KEY (owner_id, habit_id) REFERENCES hub_habit(owner_id, id) ON DELETE CASCADE");
		addConstraint("hub_habit_checkin", "fk_hub_habit_checkin_habit", "FOREIGN KEY (owner_id, habit_id) REFERENCES hub_habit(owner_id, id) ON DELETE CASCADE");
		addConstraint("hub_habit_user_revision", "fk_hub_habit_user_revision_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_focus_session", "fk_hub_focus_session_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_focus_active_slot", "fk_hub_focus_slot_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_focus_active_slot", "fk_hub_focus_slot_session", "FOREIGN KEY (owner_id, session_id) REFERENCES hub_focus_session(owner_id, id)");
		addConstraint("hub_note_tag", "uq_hub_note_tag_value", "UNIQUE (owner_id, note_id, normalized_tag)");
		addConstraint("hub_habit_schedule", "uq_hub_habit_schedule_date", "UNIQUE (owner_id, habit_id, effective_from)");
		addConstraint("hub_habit_checkin", "uq_hub_habit_checkin_date", "UNIQUE (owner_id, habit_id, checkin_date)");
		requireV3Unique("hub_note_tag", "uq_hub_note_tag_value", List.of("owner_id", "note_id", "normalized_tag"));
		requireV3Unique("hub_habit_schedule", "uq_hub_habit_schedule_date", List.of("owner_id", "habit_id", "effective_from"));
		requireV3Unique("hub_habit_checkin", "uq_hub_habit_checkin_date", List.of("owner_id", "habit_id", "checkin_date"));
		requireV3ForeignKey("hub_note", "fk_hub_note_owner", List.of("owner_id"), "hub_user", List.of("id"), "NO ACTION", "NO ACTION");
		requireV3ForeignKey("hub_note_tag", "fk_hub_note_tag_note", List.of("owner_id", "note_id"), "hub_note", List.of("owner_id", "id"), "CASCADE", "NO ACTION");
		requireV3ForeignKey("hub_note_user_revision", "fk_hub_note_revision_owner", List.of("owner_id"), "hub_user", List.of("id"), "NO ACTION", "NO ACTION");
		requireV3ForeignKey("hub_habit", "fk_hub_habit_owner", List.of("owner_id"), "hub_user", List.of("id"), "NO ACTION", "NO ACTION");
		requireV3ForeignKey("hub_habit_schedule", "fk_hub_habit_schedule_habit", List.of("owner_id", "habit_id"), "hub_habit", List.of("owner_id", "id"), "CASCADE", "NO ACTION");
		requireV3ForeignKey("hub_habit_pause", "fk_hub_habit_pause_habit", List.of("owner_id", "habit_id"), "hub_habit", List.of("owner_id", "id"), "CASCADE", "NO ACTION");
		requireV3ForeignKey("hub_habit_checkin", "fk_hub_habit_checkin_habit", List.of("owner_id", "habit_id"), "hub_habit", List.of("owner_id", "id"), "CASCADE", "NO ACTION");
		requireV3ForeignKey("hub_habit_user_revision", "fk_hub_habit_user_revision_owner", List.of("owner_id"), "hub_user", List.of("id"), "NO ACTION", "NO ACTION");
		requireV3ForeignKey("hub_focus_session", "fk_hub_focus_session_owner", List.of("owner_id"), "hub_user", List.of("id"), "NO ACTION", "NO ACTION");
		requireV3ForeignKey("hub_focus_active_slot", "fk_hub_focus_slot_owner", List.of("owner_id"), "hub_user", List.of("id"), "NO ACTION", "NO ACTION");
		requireV3ForeignKey("hub_focus_active_slot", "fk_hub_focus_slot_session", List.of("owner_id", "session_id"), "hub_focus_session", List.of("owner_id", "id"), "NO ACTION", "NO ACTION");
		requireNoV3ForeignKey("hub_focus_session", "task_id");
		validateV3OwnershipData();
		validateV3UniqueData();
		for (var entry : V3_COMPOSITE_KEYS.entrySet()) requirePrimaryKey(entry.getKey(), entry.getValue());
	}

	private List<NamedForeignKey> preflightV3ReferencingForeignKeys() {
		List<String> changedParents = V3_COMPOSITE_KEYS.entrySet().stream()
			.filter(entry -> !primaryKeyColumns(entry.getKey()).equals(entry.getValue()))
			.map(java.util.Map.Entry::getKey).toList();
		Set<String> changedParentSet = changedParents.stream().map(name -> name.toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
		List<V3ForeignKeyRow> rows = jdbc.query("SELECT tc.table_catalog, tc.table_schema, tc.table_name, tc.constraint_name, kcu.ordinal_position, kcu.column_name, "
			+ "parent_tc.table_catalog, parent_tc.table_schema, parent_tc.table_name, parent_kcu.column_name, rc.delete_rule, rc.update_rule "
			+ "FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu "
			+ "ON tc.constraint_catalog=kcu.constraint_catalog AND tc.constraint_schema=kcu.constraint_schema "
			+ "AND tc.constraint_name=kcu.constraint_name AND tc.table_schema=kcu.table_schema AND tc.table_name=kcu.table_name "
			+ "JOIN information_schema.referential_constraints rc ON tc.constraint_catalog=rc.constraint_catalog "
			+ "AND tc.constraint_schema=rc.constraint_schema AND tc.constraint_name=rc.constraint_name "
			+ "LEFT JOIN information_schema.table_constraints parent_tc ON parent_tc.constraint_catalog=rc.unique_constraint_catalog "
			+ "AND parent_tc.constraint_schema=rc.unique_constraint_schema AND parent_tc.constraint_name=rc.unique_constraint_name "
			+ "AND parent_tc.constraint_type IN ('PRIMARY KEY','UNIQUE') "
			+ "LEFT JOIN information_schema.key_column_usage parent_kcu ON parent_kcu.constraint_catalog=parent_tc.constraint_catalog "
			+ "AND parent_kcu.constraint_schema=parent_tc.constraint_schema AND parent_kcu.constraint_name=parent_tc.constraint_name "
			+ "AND parent_kcu.table_schema=parent_tc.table_schema AND parent_kcu.table_name=parent_tc.table_name "
			+ "AND parent_kcu.ordinal_position=kcu.position_in_unique_constraint "
			+ "WHERE tc.constraint_catalog=CURRENT_CATALOG() AND tc.constraint_type='FOREIGN KEY' "
			+ "ORDER BY tc.table_schema, tc.table_name, tc.constraint_name, kcu.ordinal_position",
			(rs, row) -> new V3ForeignKeyRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6),
				rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12)));
		java.util.Map<String, List<V3ForeignKeyRow>> grouped = new java.util.LinkedHashMap<>();
		for (V3ForeignKeyRow row : rows) {
			if (row.parentCatalog() == null || row.parentSchema() == null || row.parentTable() == null || row.parentColumn() == null)
				throw new IllegalStateException("A migração V3 não conseguiu resolver integralmente uma chave estrangeira antes do DDL.");
			String key = row.childCatalog() + "\u0000" + row.childSchema() + "\u0000" + row.childTable() + "\u0000" + row.constraintName();
			grouped.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(row);
		}
		Set<V3ForeignKeySignature> allowed = allowedV3ForeignKeys();
		List<NamedForeignKey> approvedToDrop = new java.util.ArrayList<>();
		for (List<V3ForeignKeyRow> group : grouped.values()) {
			V3ForeignKeyRow first = group.getFirst();
			if (group.stream().map(V3ForeignKeyRow::childOrdinal).distinct().count() != group.size()
				|| group.stream().anyMatch(row -> !row.parentCatalog().equalsIgnoreCase(first.parentCatalog())
					|| !row.parentSchema().equalsIgnoreCase(first.parentSchema()) || !row.parentTable().equalsIgnoreCase(first.parentTable())))
				throw new IllegalStateException("A migração V3 encontrou uma chave estrangeira ambígua antes do DDL.");
			if (!first.parentCatalog().equalsIgnoreCase(currentCatalog()) || !first.parentSchema().equalsIgnoreCase(currentSchema())
				|| !V3_COMPOSITE_KEYS.containsKey(first.parentTable().toLowerCase(java.util.Locale.ROOT))) continue;
			V3ForeignKeySignature signature = new V3ForeignKeySignature(first.childCatalog().toLowerCase(java.util.Locale.ROOT), first.childSchema().toLowerCase(java.util.Locale.ROOT),
				first.childTable().toLowerCase(java.util.Locale.ROOT), first.constraintName().toLowerCase(java.util.Locale.ROOT),
				group.stream().map(item -> item.childColumn().toLowerCase(java.util.Locale.ROOT)).toList(),
				first.parentCatalog().toLowerCase(java.util.Locale.ROOT), first.parentSchema().toLowerCase(java.util.Locale.ROOT),
				first.parentTable().toLowerCase(java.util.Locale.ROOT), group.stream().map(item -> item.parentColumn().toLowerCase(java.util.Locale.ROOT)).toList(),
				first.deleteRule().toUpperCase(java.util.Locale.ROOT), first.updateRule().toUpperCase(java.util.Locale.ROOT));
			if (!allowed.remove(signature)) throw new IllegalStateException("A migração V3 encontrou uma chave estrangeira não permitida ou ambígua: " + signature + ". Nenhuma chave foi removida.");
			if (changedParentSet.contains(signature.parentTable()))
				approvedToDrop.add(new NamedForeignKey(first.childSchema(), first.childTable(), first.constraintName()));
			else if (!signature.parentColumns().equals(V3_COMPOSITE_KEYS.get(signature.parentTable())))
				throw new IllegalStateException("A migração V3 encontrou uma chave estrangeira legada para uma tabela cuja chave já foi migrada. Nenhuma DDL foi executada.");
		}
		return List.copyOf(approvedToDrop);
	}

	private void dropV3ReferencingForeignKeys(List<NamedForeignKey> approvedToDrop) {
		for (NamedForeignKey key : approvedToDrop) jdbc.execute("ALTER TABLE \"" + key.schema().replace("\"", "\"\"") + "\".\""
			+ key.table().replace("\"", "\"\"") + "\" DROP CONSTRAINT IF EXISTS \"" + key.name().replace("\"", "\"\"") + "\"");
	}

	private Set<V3ForeignKeySignature> allowedV3ForeignKeys() {
		String schema = currentSchema().toLowerCase(java.util.Locale.ROOT);
		String catalog = currentCatalog().toLowerCase(java.util.Locale.ROOT);
		Set<V3ForeignKeySignature> allowed = new java.util.LinkedHashSet<>();
		allowV3ForeignKey(allowed, catalog, schema, "hub_note_tag", "fk_hub_note_tag_note", List.of("owner_id", "note_id"), catalog, schema, "hub_note", List.of("owner_id", "id"), "CASCADE", "NO ACTION");
		allowV3ForeignKey(allowed, catalog, schema, "hub_note_tag", "fk_hub_note_tag_note", List.of("note_id"), catalog, schema, "hub_note", List.of("id"), "CASCADE", "NO ACTION");
		allowV3ForeignKey(allowed, catalog, schema, "hub_habit_schedule", "fk_hub_habit_schedule_habit", List.of("owner_id", "habit_id"), catalog, schema, "hub_habit", List.of("owner_id", "id"), "CASCADE", "NO ACTION");
		allowV3ForeignKey(allowed, catalog, schema, "hub_habit_schedule", "fk_hub_habit_schedule_habit", List.of("habit_id"), catalog, schema, "hub_habit", List.of("id"), "CASCADE", "NO ACTION");
		allowV3ForeignKey(allowed, catalog, schema, "hub_habit_pause", "fk_hub_habit_pause_habit", List.of("owner_id", "habit_id"), catalog, schema, "hub_habit", List.of("owner_id", "id"), "CASCADE", "NO ACTION");
		allowV3ForeignKey(allowed, catalog, schema, "hub_habit_pause", "fk_hub_habit_pause_habit", List.of("habit_id"), catalog, schema, "hub_habit", List.of("id"), "CASCADE", "NO ACTION");
		allowV3ForeignKey(allowed, catalog, schema, "hub_habit_checkin", "fk_hub_habit_checkin_habit", List.of("owner_id", "habit_id"), catalog, schema, "hub_habit", List.of("owner_id", "id"), "CASCADE", "NO ACTION");
		allowV3ForeignKey(allowed, catalog, schema, "hub_habit_checkin", "fk_hub_habit_checkin_habit", List.of("habit_id"), catalog, schema, "hub_habit", List.of("id"), "CASCADE", "NO ACTION");
		allowV3ForeignKey(allowed, catalog, schema, "hub_focus_active_slot", "fk_hub_focus_slot_session", List.of("owner_id", "session_id"), catalog, schema, "hub_focus_session", List.of("owner_id", "id"), "NO ACTION", "NO ACTION");
		allowV3ForeignKey(allowed, catalog, schema, "hub_focus_active_slot", "fk_hub_focus_slot_session", List.of("session_id"), catalog, schema, "hub_focus_session", List.of("id"), "NO ACTION", "NO ACTION");
		return allowed;
	}

	private void allowV3ForeignKey(Set<V3ForeignKeySignature> allowed, String childCatalog, String childSchema, String childTable, String name,
			List<String> childColumns, String parentCatalog, String parentSchema, String parentTable, List<String> parentColumns, String deleteRule, String updateRule) {
		java.util.function.UnaryOperator<String> lower = value -> value.toLowerCase(java.util.Locale.ROOT);
		allowed.add(new V3ForeignKeySignature(lower.apply(childCatalog), lower.apply(childSchema), lower.apply(childTable), lower.apply(name),
			childColumns.stream().map(lower).toList(), lower.apply(parentCatalog), lower.apply(parentSchema), lower.apply(parentTable), parentColumns.stream().map(lower).toList(), deleteRule, updateRule));
	}

	private void validateV3UniqueData() {
		assertNoDuplicateKey("hub_note_tag", "owner_id, note_id, normalized_tag");
		assertNoDuplicateKey("hub_habit_schedule", "owner_id, habit_id, effective_from");
		assertNoDuplicateKey("hub_habit_checkin", "owner_id, habit_id, checkin_date");
	}

	private void assertNoDuplicateKey(String table, String columns) {
		Long duplicates = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT " + columns + " FROM " + table
			+ " GROUP BY " + columns + " HAVING COUNT(*) > 1) duplicate_keys", Long.class);
		if (duplicates != null && duplicates > 0) throw new IllegalStateException("A migração V3 encontrou chaves duplicadas em " + table + " (" + columns + ").");
	}

	private void requireV3Unique(String table, String name, List<String> expectedColumns) {
		List<String> columns = jdbc.query("SELECT kcu.column_name FROM information_schema.table_constraints tc "
			+ "JOIN information_schema.key_column_usage kcu ON tc.constraint_catalog=kcu.constraint_catalog AND tc.constraint_schema=kcu.constraint_schema "
			+ "AND tc.constraint_name=kcu.constraint_name AND tc.table_name=kcu.table_name "
			+ "WHERE UPPER(tc.table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(tc.table_name)=UPPER(?) "
			+ "AND UPPER(tc.constraint_name)=UPPER(?) AND tc.constraint_type='UNIQUE' ORDER BY kcu.ordinal_position",
			(rs, row) -> rs.getString(1).toLowerCase(java.util.Locale.ROOT), table, name);
		if (!columns.equals(expectedColumns)) throw new IllegalStateException("A restrição V3 " + name + " não corresponde às colunas esperadas.");
	}

	private void requireNoV3ForeignKey(String table, String column) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints tc "
			+ "JOIN information_schema.key_column_usage kcu ON tc.constraint_catalog=kcu.constraint_catalog AND tc.constraint_schema=kcu.constraint_schema "
			+ "AND tc.constraint_name=kcu.constraint_name AND tc.table_name=kcu.table_name "
			+ "WHERE UPPER(tc.table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(tc.table_name)=UPPER(?) "
			+ "AND UPPER(kcu.column_name)=UPPER(?) AND tc.constraint_type='FOREIGN KEY'", Integer.class, table, column);
		if (count != null && count != 0) throw new IllegalStateException("A coluna " + table + "." + column + " não deve ter chave estrangeira.");
	}

	private void requireV3ForeignKey(String table, String name, List<String> childColumns, String parentTable,
			List<String> parentColumns, String deleteRule, String updateRule) {
		List<ForeignKeyColumn> actual = jdbc.query("SELECT kcu.column_name, parent_tc.table_catalog, parent_tc.table_schema, parent_tc.table_name, parent_kcu.column_name, rc.delete_rule, rc.update_rule "
			+ "FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu "
			+ "ON tc.constraint_catalog=kcu.constraint_catalog AND tc.constraint_schema=kcu.constraint_schema AND tc.constraint_name=kcu.constraint_name "
			+ "AND tc.table_schema=kcu.table_schema AND tc.table_name=kcu.table_name JOIN information_schema.referential_constraints rc "
			+ "ON tc.constraint_catalog=rc.constraint_catalog AND tc.constraint_schema=rc.constraint_schema AND tc.constraint_name=rc.constraint_name "
			+ "LEFT JOIN information_schema.table_constraints parent_tc ON parent_tc.constraint_catalog=rc.unique_constraint_catalog "
			+ "AND parent_tc.constraint_schema=rc.unique_constraint_schema AND parent_tc.constraint_name=rc.unique_constraint_name "
			+ "AND parent_tc.constraint_type IN ('PRIMARY KEY','UNIQUE') "
			+ "LEFT JOIN information_schema.key_column_usage parent_kcu ON parent_kcu.constraint_catalog=parent_tc.constraint_catalog "
			+ "AND parent_kcu.constraint_schema=parent_tc.constraint_schema AND parent_kcu.constraint_name=parent_tc.constraint_name "
			+ "AND parent_kcu.table_schema=parent_tc.table_schema AND parent_kcu.table_name=parent_tc.table_name "
			+ "AND parent_kcu.ordinal_position=kcu.position_in_unique_constraint "
			+ "WHERE UPPER(tc.table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(tc.table_name)=UPPER(?) "
			+ "AND UPPER(tc.constraint_name)=UPPER(?) AND tc.constraint_type='FOREIGN KEY' ORDER BY kcu.ordinal_position",
			(rs, row) -> new ForeignKeyColumn(rs.getString(1).toLowerCase(java.util.Locale.ROOT),
				lower(rs.getString(2)), lower(rs.getString(3)), lower(rs.getString(4)), lower(rs.getString(5)),
				rs.getString(6).toUpperCase(java.util.Locale.ROOT), rs.getString(7).toUpperCase(java.util.Locale.ROOT)), table, name);
		if (actual.stream().anyMatch(column -> column.parentCatalog() == null || column.parentSchema() == null
			|| column.parentTable() == null || column.parentColumn() == null))
			throw new IllegalStateException("A chave estrangeira V3 " + name + " não pôde ser resolvida completamente.");
		boolean matches = actual.size() == childColumns.size() && java.util.stream.IntStream.range(0, actual.size()).allMatch(index -> {
			ForeignKeyColumn column = actual.get(index);
			return column.childColumn().equals(childColumns.get(index)) && column.parentCatalog().equals(currentCatalog().toLowerCase(java.util.Locale.ROOT))
				&& column.parentSchema().equals(currentSchema().toLowerCase(java.util.Locale.ROOT))
				&& column.parentTable().equals(parentTable) && column.parentColumn().equals(parentColumns.get(index)) && column.deleteRule().equals(deleteRule)
				&& column.updateRule().equals(updateRule);
		});
		if (!matches) throw new IllegalStateException("A chave estrangeira V3 " + name + " não corresponde à relação esperada.");
	}

	private void validateV3OwnershipData() {
		for (String table : V3_OWNER_TABLES) {
			Long nullOwners = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE owner_id IS NULL", Long.class);
			if (nullOwners != null && nullOwners > 0) throw new IllegalStateException("A migração V3 encontrou proprietário ausente em " + table + ".");
			Long unknownOwners = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " d LEFT JOIN hub_user u ON u.id=d.owner_id WHERE u.id IS NULL", Long.class);
			if (unknownOwners != null && unknownOwners > 0) throw new IllegalStateException("A migração V3 encontrou proprietário inexistente em " + table + ".");
		}
		assertNoV3Orphans("SELECT COUNT(*) FROM hub_note_tag c LEFT JOIN hub_note p ON p.owner_id=c.owner_id AND p.id=c.note_id WHERE p.id IS NULL", "etiquetas de notas");
		assertNoV3Orphans("SELECT COUNT(*) FROM hub_habit_schedule c LEFT JOIN hub_habit p ON p.owner_id=c.owner_id AND p.id=c.habit_id WHERE p.id IS NULL", "agendas de hábitos");
		assertNoV3Orphans("SELECT COUNT(*) FROM hub_habit_pause c LEFT JOIN hub_habit p ON p.owner_id=c.owner_id AND p.id=c.habit_id WHERE p.id IS NULL", "pausas de hábitos");
		assertNoV3Orphans("SELECT COUNT(*) FROM hub_habit_checkin c LEFT JOIN hub_habit p ON p.owner_id=c.owner_id AND p.id=c.habit_id WHERE p.id IS NULL", "conclusões de hábitos");
		assertNoV3Orphans("SELECT COUNT(*) FROM hub_focus_active_slot c LEFT JOIN hub_focus_session p ON p.owner_id=c.owner_id AND p.id=c.session_id WHERE c.session_id IS NOT NULL AND p.id IS NULL", "sessões do marcador de foco");
		assertNoV3Orphans("SELECT COUNT(*) FROM (SELECT owner_id FROM hub_focus_session WHERE session_state IN ('ACTIVE','PAUSED') GROUP BY owner_id HAVING COUNT(*) > 1) open_sessions", "sessões abertas de foco duplicadas");
		assertNoV3Orphans("SELECT COUNT(*) FROM hub_focus_session s LEFT JOIN hub_focus_active_slot a ON a.owner_id=s.owner_id WHERE s.session_state IN ('ACTIVE','PAUSED') AND (a.owner_id IS NULL OR a.session_id IS NULL OR a.session_id<>s.id)", "sessões abertas sem marcador correspondente");
		assertNoV3Orphans("SELECT COUNT(*) FROM hub_focus_active_slot a WHERE (a.session_id IS NULL AND EXISTS (SELECT 1 FROM hub_focus_session s WHERE s.owner_id=a.owner_id AND s.session_state IN ('ACTIVE','PAUSED'))) OR (a.session_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM hub_focus_session s WHERE s.owner_id=a.owner_id AND s.id=a.session_id AND s.session_state IN ('ACTIVE','PAUSED'))) OR (a.session_id IS NOT NULL AND EXISTS (SELECT 1 FROM hub_focus_session s WHERE s.owner_id=a.owner_id AND s.session_state IN ('ACTIVE','PAUSED') AND s.id<>a.session_id))", "marcadores ativos de foco");
		for (var entry : V3_COMPOSITE_KEYS.entrySet()) {
			String columns = String.join(", ", entry.getValue());
			Long duplicates = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT " + columns + " FROM " + entry.getKey() + " GROUP BY " + columns + " HAVING COUNT(*) > 1) duplicate_keys", Long.class);
			if (duplicates != null && duplicates > 0) throw new IllegalStateException("A migração V3 encontrou chaves duplicadas em " + entry.getKey() + ".");
		}
	}

	private void assertNoV3Orphans(String sql, String relation) {
		Long count = jdbc.queryForObject(sql, Long.class);
		if (count != null && count > 0) throw new IllegalStateException("A migração V3 encontrou registros órfãos em " + relation + ".");
	}

	private void requirePrimaryKey(String table, List<String> expected) {
		if (expected.stream().anyMatch(column -> !column.matches("[a-z][a-z0-9_]*"))) throw new IllegalArgumentException("Chave composta de migração não reconhecida para " + table + ".");
		List<String> actual = jdbc.query("SELECT kcu.column_name FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu ON tc.constraint_catalog=kcu.constraint_catalog AND tc.constraint_schema=kcu.constraint_schema AND tc.constraint_name=kcu.constraint_name AND kcu.table_schema=tc.table_schema AND kcu.table_name=tc.table_name WHERE UPPER(tc.table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(tc.table_name)=UPPER(?) AND tc.constraint_type='PRIMARY KEY' ORDER BY kcu.ordinal_position", (rs, row) -> rs.getString(1).toLowerCase(java.util.Locale.ROOT), table);
		if (!actual.equals(expected)) throw new IllegalStateException("A chave primária V3 de " + table + " não corresponde à chave proprietária esperada. Esperada: " + expected + "; encontrada no schema " + currentSchema() + ": " + actual + ".");
	}

	private void applyV1() {
		for (String table : OWNER_TABLES) jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN owner_id SET NOT NULL");
		addConstraint("hub_task", "fk_hub_task_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_task_series", "fk_hub_task_series_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_reading_item", "fk_hub_reading_item_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_reading_author", "fk_hub_reading_author_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		for (String table : List.of("google_connection", "hub_task_user_revision", "hub_reading_user_revision",
				"hub_task_collection_state", "hub_reading_collection_state", "hub_task_settings"))
			addConstraint(table, "fk_" + table + "_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
	}

	private void applyV2() {
		validateCompositeKeyData();
		validateSeriesPositionData();
		jdbc.execute("ALTER TABLE google_connection ADD COLUMN IF NOT EXISTS credential_key_version VARCHAR(32)");
		dropSingleColumnUnique("hub_task_series", "operation_id");
		dropSingleColumnUnique("hub_reading_author", "normalized_name");
		addConstraint("hub_task_series", "uq_hub_task_series_owner_operation", "UNIQUE (owner_id, operation_id)");
		addConstraint("hub_reading_author", "uq_hub_reading_author_owner_name", "UNIQUE (owner_id, normalized_name)");
		dropLegacySeriesPositionIndex();
		ensureSeriesPositionOwnerIndex();
		// Drop legacy foreign keys before changing referenced keys. This phase is
		// restartable: every following statement uses IF EXISTS / IF NOT EXISTS.
		for (String table : List.of("hub_task", "hub_task_series", "hub_reading_item", "hub_reading_author",
				"hub_reading_item_author", "hub_reading_item_tag", "hub_task_checklist_revision", "hub_task_checklist_item",
				"google_task_list_state", "google_task_state", "hub_task_notification")) dropForeignKeys(table);
		for (var entry : COMPOSITE_KEYS.entrySet()) ensurePrimaryKey(entry.getKey(), entry.getValue());
		addConstraint("hub_task", "fk_hub_task_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_task_series", "fk_hub_task_series_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_reading_item", "fk_hub_reading_item_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_reading_author", "fk_hub_reading_author_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
		addConstraint("hub_task", "fk_hub_task_series_link_owner", "FOREIGN KEY (owner_id, series_id) REFERENCES hub_task_series(owner_id, id)");
		addConstraint("hub_reading_item_author", "fk_reading_item_author_item_owner", "FOREIGN KEY (owner_id, reading_item_id) REFERENCES hub_reading_item(owner_id, id) ON DELETE CASCADE");
		addConstraint("hub_reading_item_author", "fk_reading_item_author_author_owner", "FOREIGN KEY (owner_id, author_id) REFERENCES hub_reading_author(owner_id, id)");
		addConstraint("hub_reading_item_tag", "fk_reading_item_tag_item_owner", "FOREIGN KEY (owner_id, reading_item_id) REFERENCES hub_reading_item(owner_id, id) ON DELETE CASCADE");
		addConstraint("hub_task_checklist_revision", "fk_checklist_revision_task_owner", "FOREIGN KEY (owner_id, task_id) REFERENCES hub_task(owner_id, id) ON DELETE CASCADE");
		addConstraint("hub_task_checklist_item", "fk_checklist_item_task_owner", "FOREIGN KEY (owner_id, task_id) REFERENCES hub_task(owner_id, id) ON DELETE CASCADE");
		addConstraint("google_connection", "uq_google_connection_owner_id", "UNIQUE (owner_id, connection_id)");
		addConstraint("google_connection", "uq_google_connection_owner", "UNIQUE (owner_id)");
		addConstraint("google_task_list_state", "fk_google_task_list_connection_owner", "FOREIGN KEY (owner_id, connection_id) REFERENCES google_connection(owner_id, connection_id)");
		addConstraint("google_task_state", "fk_google_task_state_list_owner", "FOREIGN KEY (owner_id, connection_id, task_list_id) REFERENCES google_task_list_state(owner_id, connection_id, task_list_id)");
		addConstraint("hub_task_notification", "fk_task_notice_connection_owner", "FOREIGN KEY (owner_id, connection_id) REFERENCES google_connection(owner_id, connection_id)");
		migrateReminderSettings();
	}

	private void migrateReminderSettings() {
		jdbc.update("INSERT INTO hub_task_user_settings(owner_id, reminder_time) SELECT id, TIME '09:00' FROM hub_user WHERE NOT EXISTS (SELECT 1 FROM hub_task_user_settings s WHERE s.owner_id = hub_user.id)");
		jdbc.update("UPDATE hub_task_user_settings SET reminder_time = (SELECT reminder_time FROM hub_task_settings WHERE settings_id = 1) WHERE owner_id = (SELECT owner_id FROM hub_task_settings WHERE settings_id = 1) AND EXISTS (SELECT 1 FROM hub_task_settings WHERE settings_id = 1 AND owner_id IS NOT NULL)");
		addConstraint("hub_task_user_settings", "fk_hub_task_user_settings_owner", "FOREIGN KEY (owner_id) REFERENCES hub_user(id)");
	}

	private void validateOwnership() {
		for (String table : OWNER_TABLES) {
			Long invalid = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " d LEFT JOIN hub_user u ON u.id = d.owner_id WHERE u.id IS NULL", Long.class);
			if (invalid != null && invalid != 0) throw new IllegalStateException("A validação de propriedade falhou em " + table + ".");
		}
		Long orphanedSeries = jdbc.queryForObject("SELECT COUNT(*) FROM hub_task t WHERE t.series_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM hub_task_series s WHERE s.id = t.series_id AND s.owner_id = t.owner_id)", Long.class);
		if (orphanedSeries != null && orphanedSeries != 0) throw new IllegalStateException("Há tarefas vinculadas a séries inexistentes da própria conta.");
	}

	private void ensurePrimaryKey(String table, List<String> expected) {
		if (expected.stream().anyMatch(column -> !column.matches("[a-z][a-z0-9_]*")))
			throw new IllegalArgumentException("Chave composta de migração não reconhecida para " + table + ".");
		if (primaryKeyColumns(table).equals(expected)) return;
		String existingConstraint = primaryKeyConstraintName(table);
		if (existingConstraint != null) jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + existingConstraint.replace("\"", "\"\"") + "\"");
		jdbc.execute("ALTER TABLE " + table + " ADD PRIMARY KEY (" + String.join(", ", expected) + ")");
		requirePrimaryKey(table, expected);
	}

	private String primaryKeyConstraintName(String table) {
		List<String> names = jdbc.query("SELECT constraint_name FROM information_schema.table_constraints WHERE UPPER(table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(table_name)=UPPER(?) AND constraint_type='PRIMARY KEY'", (rs, row) -> rs.getString(1), table);
		if (names.size() > 1) throw new IllegalStateException("A tabela " + table + " tem mais de uma chave primária registrada.");
		return names.isEmpty() ? null : names.getFirst();
	}

	private List<String> primaryKeyColumns(String table) {
		return jdbc.query("SELECT kcu.column_name FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu "
			+ "ON tc.constraint_catalog=kcu.constraint_catalog AND tc.constraint_schema=kcu.constraint_schema AND tc.constraint_name=kcu.constraint_name "
			+ "AND kcu.table_schema=tc.table_schema AND kcu.table_name=tc.table_name "
			+ "WHERE UPPER(tc.table_schema)=UPPER(CURRENT_SCHEMA()) AND UPPER(tc.table_name)=UPPER(?) "
			+ "AND tc.constraint_type='PRIMARY KEY' ORDER BY kcu.ordinal_position",
			(rs, row) -> rs.getString(1).toLowerCase(java.util.Locale.ROOT), table);
	}
	private void validateCompositeKeyData() {
		for (var entry : COMPOSITE_KEYS.entrySet()) {
			String columns = String.join(", ", entry.getValue());
			String duplicates = "SELECT COUNT(*) FROM (SELECT " + columns + " FROM " + entry.getKey()
				+ " GROUP BY " + columns + " HAVING COUNT(*) > 1) duplicate_keys";
			Long count = jdbc.queryForObject(duplicates, Long.class);
			if (count != null && count > 0) throw new IllegalStateException("A migração encontrou chaves duplicadas em " + entry.getKey() + ".");
		}
	}
	private void validateSeriesPositionData() {
		Long duplicates = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT owner_id, series_id, series_position FROM hub_task WHERE series_id IS NOT NULL AND series_position IS NOT NULL GROUP BY owner_id, series_id, series_position HAVING COUNT(*) > 1) duplicate_positions", Long.class);
		if (duplicates != null && duplicates > 0)
			throw new IllegalStateException("A migração encontrou posições de série duplicadas dentro da mesma conta.");
	}
	private void dropLegacySeriesPositionIndex() {
		List<NamedIndex> indexes = namedIndexes("uq_hub_task_series_position");
		if (indexes.isEmpty()) return;
		if (indexes.size() != 1) throw new IllegalStateException("O índice legado de posição da série está ambíguo no schema atual.");
		NamedIndex index = indexes.get(0);
		if (!index.catalogName().equalsIgnoreCase(currentCatalog()) || !index.schemaName().equalsIgnoreCase(currentSchema())
			|| !index.tableCatalog().equalsIgnoreCase(currentCatalog()) || !index.tableSchema().equalsIgnoreCase(currentSchema())
			|| !index.tableName().equalsIgnoreCase("hub_task") || !index.unique()
			|| !index.columns().equals(List.of("series_id", "series_position")))
			throw new IllegalStateException("O índice legado de posição da série não corresponde ao objeto esperado.");
		jdbc.execute("DROP INDEX IF EXISTS uq_hub_task_series_position");
		if (!namedIndexes("uq_hub_task_series_position").isEmpty())
			throw new IllegalStateException("O índice global legado de posição da série não foi removido.");
	}
	private void ensureSeriesPositionOwnerIndex() {
		List<NamedIndex> indexes = namedIndexes("uq_hub_task_series_position_owner");
		if (!indexes.isEmpty() && (indexes.size() != 1 || !indexes.get(0).catalogName().equalsIgnoreCase(currentCatalog())
			|| !indexes.get(0).schemaName().equalsIgnoreCase(currentSchema()) || !indexes.get(0).tableCatalog().equalsIgnoreCase(currentCatalog())
			|| !indexes.get(0).tableSchema().equalsIgnoreCase(currentSchema()) || !indexes.get(0).tableName().equalsIgnoreCase("hub_task")
			|| !indexes.get(0).unique() || !indexes.get(0).columns().equals(List.of("owner_id", "series_id", "series_position"))))
			throw new IllegalStateException("O índice de posição da série por proprietário não corresponde ao objeto esperado.");
		if (indexes.isEmpty()) jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_hub_task_series_position_owner ON hub_task (owner_id, series_id, series_position)");
		List<NamedIndex> applied = namedIndexes("uq_hub_task_series_position_owner");
		if (applied.size() != 1 || !applied.get(0).catalogName().equalsIgnoreCase(currentCatalog())
			|| !applied.get(0).schemaName().equalsIgnoreCase(currentSchema()) || !applied.get(0).tableCatalog().equalsIgnoreCase(currentCatalog())
			|| !applied.get(0).tableSchema().equalsIgnoreCase(currentSchema()) || !applied.get(0).tableName().equalsIgnoreCase("hub_task")
			|| !applied.get(0).unique() || !applied.get(0).columns().equals(List.of("owner_id", "series_id", "series_position")))
			throw new IllegalStateException("A criação do índice de posição da série por proprietário não foi validada.");
	}
	private List<NamedIndex> namedIndexes(String indexName) {
		List<NamedIndex> indexes = jdbc.query("SELECT i.index_catalog, i.index_schema, i.table_catalog, i.table_schema, i.table_name, i.index_type_name FROM information_schema.indexes i WHERE UPPER(i.index_catalog) = UPPER(CURRENT_CATALOG()) AND UPPER(i.index_schema) = UPPER(CURRENT_SCHEMA()) AND UPPER(i.index_name) = UPPER(?)", (rs, row) -> {
			String table = rs.getString("table_name");
			String type = rs.getString("index_type_name");
			List<String> columns = jdbc.query("SELECT column_name FROM information_schema.index_columns WHERE UPPER(index_catalog) = UPPER(CURRENT_CATALOG()) AND UPPER(index_schema) = UPPER(CURRENT_SCHEMA()) AND UPPER(index_name) = UPPER(?) ORDER BY ordinal_position", (columnRs, columnRow) -> columnRs.getString(1), indexName);
			return new NamedIndex(rs.getString("index_catalog"), rs.getString("index_schema"), rs.getString("table_catalog"),
				rs.getString("table_schema"), table, type != null && type.toUpperCase(java.util.Locale.ROOT).contains("UNIQUE"),
				columns.stream().map(c -> c.toLowerCase(java.util.Locale.ROOT)).toList());
		}, indexName);
		return indexes;
	}
	private String currentCatalog() { return jdbc.queryForObject("SELECT CURRENT_CATALOG()", String.class); }
	private String currentSchema() { return jdbc.queryForObject("SELECT CURRENT_SCHEMA()", String.class); }
	private String lower(String value) { return value == null ? null : value.toLowerCase(java.util.Locale.ROOT); }
	private record NamedIndex(String catalogName, String schemaName, String tableCatalog, String tableSchema,
		String tableName, boolean unique, List<String> columns) {}
	private record NamedForeignKey(String schema, String table, String name) {}
	private record ForeignKeyColumn(String childColumn, String parentCatalog, String parentSchema, String parentTable,
		String parentColumn, String deleteRule, String updateRule) {}
	private record V3ForeignKeyRow(String childCatalog, String childSchema, String childTable, String constraintName, int childOrdinal, String childColumn,
		String parentCatalog, String parentSchema, String parentTable, String parentColumn, String deleteRule, String updateRule) {}
	private record V3ForeignKeySignature(String childCatalog, String childSchema, String childTable, String name, List<String> childColumns,
		String parentCatalog, String parentSchema, String parentTable, List<String> parentColumns, String deleteRule, String updateRule) {}

	private void dropForeignKeys(String table) {
		List<String> names = jdbc.query("SELECT constraint_name FROM information_schema.table_constraints WHERE UPPER(table_schema) = UPPER(CURRENT_SCHEMA()) AND UPPER(table_name) = UPPER(?) AND constraint_type = 'FOREIGN KEY'", (rs, n) -> rs.getString(1), table);
		for (String name : names) jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS \"" + name.replace("\"", "\"\"") + "\"");
	}
	private void dropSingleColumnUnique(String table, String column) {
		List<String> names = jdbc.query("SELECT tc.constraint_name FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu ON tc.constraint_catalog = kcu.constraint_catalog AND tc.constraint_schema = kcu.constraint_schema AND tc.constraint_name = kcu.constraint_name WHERE UPPER(tc.table_schema) = UPPER(CURRENT_SCHEMA()) AND UPPER(tc.table_name) = UPPER(?) AND tc.constraint_type = 'UNIQUE' GROUP BY tc.constraint_name HAVING COUNT(*) = 1 AND MAX(UPPER(kcu.column_name)) = UPPER(?)", (rs, n) -> rs.getString(1), table, column);
		for (String name : names) jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS \"" + name.replace("\"", "\"\"") + "\"");
	}
	private void addConstraint(String table, String name, String ddl) { jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT IF NOT EXISTS " + name + " " + ddl); }
	private void markApplied(String version) { if (!applied(version)) jdbc.update("INSERT INTO hub_schema_migration(migration_id) VALUES (?)", version); }
	private boolean applied(String version) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM hub_schema_migration WHERE migration_id = ?", Integer.class, version);
		return count != null && count > 0;
	}
	public boolean isReady() { return ready || (applied(V1) && applied(V2) && applied(V3)); }
	public void requireReady() {
		if (!isReady()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
			"A atualização de propriedade dos dados precisa ser concluída. Reinicie o backend para tentar novamente.");
	}
}
