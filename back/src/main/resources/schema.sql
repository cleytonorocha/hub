CREATE TABLE IF NOT EXISTS google_connection (
    connection_id VARCHAR(40) PRIMARY KEY,
    owner_id VARCHAR(36),
    refresh_token_ciphertext CLOB,
    refresh_token_nonce VARCHAR(32),
    scopes VARCHAR(512),
    status VARCHAR(32) NOT NULL,
    last_synced_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_google_connection_owner UNIQUE (owner_id),
    CONSTRAINT uq_google_connection_owner_id UNIQUE (owner_id, connection_id)
);

CREATE TABLE IF NOT EXISTS google_task_list_state (
    owner_id VARCHAR(36) NOT NULL,
    connection_id VARCHAR(40) NOT NULL,
    task_list_id VARCHAR(256) NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    last_sync_run VARCHAR(36),
    PRIMARY KEY (owner_id, connection_id, task_list_id),
    CONSTRAINT fk_google_task_list_connection_owner FOREIGN KEY (owner_id, connection_id)
        REFERENCES google_connection(owner_id, connection_id)
);

CREATE TABLE IF NOT EXISTS google_task_state (
    owner_id VARCHAR(36) NOT NULL,
    connection_id VARCHAR(40) NOT NULL,
    task_list_id VARCHAR(256) NOT NULL,
    task_id VARCHAR(256) NOT NULL,
    remote_updated VARCHAR(64),
    remote_etag VARCHAR(256),
    due_date DATE,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    last_sync_run VARCHAR(36),
    PRIMARY KEY (owner_id, connection_id, task_list_id, task_id),
    CONSTRAINT fk_google_task_state_list_owner FOREIGN KEY (owner_id, connection_id, task_list_id)
        REFERENCES google_task_list_state(owner_id, connection_id, task_list_id)
);

CREATE TABLE IF NOT EXISTS hub_task_notification (
    owner_id VARCHAR(36) NOT NULL,
    notification_id VARCHAR(36) NOT NULL,
    connection_id VARCHAR(40) NOT NULL,
    task_list_id VARCHAR(256) NOT NULL,
    task_id VARCHAR(256) NOT NULL,
    due_date DATE NOT NULL,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(16) NOT NULL,
    overdue BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (owner_id, notification_id),
    CONSTRAINT uq_task_notice UNIQUE (owner_id, connection_id, task_list_id, task_id, due_date),
    CONSTRAINT fk_task_notice_connection_owner FOREIGN KEY (owner_id, connection_id)
        REFERENCES google_connection(owner_id, connection_id)
);

CREATE TABLE IF NOT EXISTS hub_task_settings (
    settings_id INTEGER PRIMARY KEY,
    reminder_time TIME NOT NULL DEFAULT TIME '09:00'
);

INSERT INTO hub_task_settings (settings_id, reminder_time)
SELECT 1, TIME '09:00'
WHERE NOT EXISTS (SELECT 1 FROM hub_task_settings WHERE settings_id = 1);

CREATE TABLE IF NOT EXISTS hub_task (
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    title VARCHAR(1024) NOT NULL,
    due_date DATE,
    due_time TIME,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    task_type VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
    category VARCHAR(64) NOT NULL DEFAULT 'Geral',
    color VARCHAR(7) NOT NULL DEFAULT '#A986FF',
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    notes VARCHAR(2000) NOT NULL DEFAULT '',
    PRIMARY KEY (owner_id, id)
);

CREATE ALIAS IF NOT EXISTS HUB_NORMALIZE_SEARCH FOR "com.github.cleytonorocha.hub.service.TaskSearchNormalization.normalize";

ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS category VARCHAR(64) NOT NULL DEFAULT 'Geral';
ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS due_time TIME;
ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS color VARCHAR(7) NOT NULL DEFAULT '#A986FF';
ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS notes VARCHAR(2000) NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS hub_task_collection_state (
    state_id INTEGER PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_hub_task_collection_state_id CHECK (state_id = 1)
);

INSERT INTO hub_task_collection_state (state_id, revision)
SELECT 1, 0
WHERE NOT EXISTS (SELECT 1 FROM hub_task_collection_state WHERE state_id = 1);

CREATE TABLE IF NOT EXISTS hub_task_series (
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    title VARCHAR(1024) NOT NULL,
    start_date DATE NOT NULL,
    due_time TIME,
    occurrence_count INTEGER NOT NULL,
    time_zone_id VARCHAR(64) NOT NULL,
    ended BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_hub_task_series_count CHECK (occurrence_count BETWEEN 2 AND 52),
    PRIMARY KEY (owner_id, id),
    CONSTRAINT uq_hub_task_series_owner_operation UNIQUE (owner_id, operation_id)
);

ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS series_id VARCHAR(36);
ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS series_position INTEGER;

CREATE TABLE IF NOT EXISTS hub_task_checklist_revision (
    owner_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_id, task_id),
    CONSTRAINT fk_hub_task_checklist_revision_owner FOREIGN KEY (owner_id, task_id)
        REFERENCES hub_task(owner_id, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS hub_task_checklist_item (
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    item_order INTEGER NOT NULL,
    item_text VARCHAR(200) NOT NULL,
    checked BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (owner_id, id),
    CONSTRAINT uq_hub_task_checklist_order_owner UNIQUE (owner_id, task_id, item_order),
    CONSTRAINT fk_hub_task_checklist_item_owner FOREIGN KEY (owner_id, task_id)
        REFERENCES hub_task(owner_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_hub_task_checklist_order CHECK (item_order BETWEEN 0 AND 19)
);

CREATE TABLE IF NOT EXISTS hub_reading_item (
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    title VARCHAR(1024) NOT NULL,
    item_type VARCHAR(16) NOT NULL,
    author_or_source VARCHAR(512),
    reference_url VARCHAR(2048),
    reading_status VARCHAR(16) NOT NULL,
    progress_marker VARCHAR(512) NOT NULL DEFAULT '',
    personal_note VARCHAR(2000) NOT NULL DEFAULT '',
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_hub_reading_item_type CHECK (item_type IN ('BOOK', 'ARTICLE', 'VIDEO')),
    CONSTRAINT chk_hub_reading_item_status CHECK (reading_status IN ('TO_READ', 'IN_PROGRESS', 'COMPLETED')),
    PRIMARY KEY (owner_id, id)
);

ALTER TABLE hub_reading_item ADD COLUMN IF NOT EXISTS cover_image_name VARCHAR(40);
ALTER TABLE hub_reading_item ADD COLUMN IF NOT EXISTS cover_image_sha256 VARCHAR(64);
ALTER TABLE hub_reading_item ADD COLUMN IF NOT EXISTS total_pages INTEGER;
ALTER TABLE hub_reading_item ADD COLUMN IF NOT EXISTS current_page INTEGER;
ALTER TABLE hub_reading_item ADD COLUMN IF NOT EXISTS cover_source_url VARCHAR(2048);
ALTER TABLE hub_reading_item ADD CONSTRAINT IF NOT EXISTS chk_hub_reading_item_cover_hash
    CHECK ((cover_image_name IS NULL AND cover_image_sha256 IS NULL)
        OR (cover_image_name IS NOT NULL AND cover_image_sha256 IS NOT NULL));
ALTER TABLE hub_reading_item ADD CONSTRAINT IF NOT EXISTS chk_hub_reading_item_page_progress
    CHECK ((total_pages IS NULL AND current_page IS NULL)
        OR (item_type = 'BOOK' AND total_pages BETWEEN 1 AND 100000
            AND current_page BETWEEN 0 AND total_pages));

CREATE TABLE IF NOT EXISTS hub_reading_item_tag (
    owner_id VARCHAR(36) NOT NULL,
    reading_item_id VARCHAR(36) NOT NULL,
    tag_order INTEGER NOT NULL,
    tag VARCHAR(64) NOT NULL,
    CONSTRAINT pk_hub_reading_item_tag PRIMARY KEY (owner_id, reading_item_id, tag_order),
    CONSTRAINT fk_hub_reading_item_tag_item_owner FOREIGN KEY (owner_id, reading_item_id) REFERENCES hub_reading_item(owner_id, id),
    CONSTRAINT chk_hub_reading_item_tag_order CHECK (tag_order BETWEEN 0 AND 19)
);

CREATE TABLE IF NOT EXISTS hub_reading_author (
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    name VARCHAR(160) NOT NULL,
    normalized_name VARCHAR(160) NOT NULL,
    PRIMARY KEY (owner_id, id),
    CONSTRAINT uq_hub_reading_author_owner_name UNIQUE (owner_id, normalized_name)
);

CREATE TABLE IF NOT EXISTS hub_reading_item_author (
    owner_id VARCHAR(36) NOT NULL,
    reading_item_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    CONSTRAINT pk_hub_reading_item_author PRIMARY KEY (owner_id, reading_item_id, author_id),
    CONSTRAINT fk_hub_reading_item_author_item_owner FOREIGN KEY (owner_id, reading_item_id) REFERENCES hub_reading_item(owner_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_hub_reading_item_author_author_owner FOREIGN KEY (owner_id, author_id) REFERENCES hub_reading_author(owner_id, id)
);

CREATE TABLE IF NOT EXISTS hub_reading_collection_state (
    state_id INTEGER PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_hub_reading_collection_state_id CHECK (state_id = 1)
);

INSERT INTO hub_reading_collection_state (state_id, revision)
SELECT 1, 0
WHERE NOT EXISTS (SELECT 1 FROM hub_reading_collection_state WHERE state_id = 1);

CREATE TABLE IF NOT EXISTS hub_user (
    id VARCHAR(36) PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL,
    normalized_email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    profile_id VARCHAR(36) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS hub_user_preference (
    owner_id VARCHAR(36) PRIMARY KEY,
    preferences CLOB NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_hub_user_preference_owner FOREIGN KEY (owner_id) REFERENCES hub_user(id),
    CONSTRAINT chk_hub_user_preference_revision CHECK (revision >= 0)
);
ALTER TABLE hub_user_preference ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE hub_user_preference ADD CONSTRAINT IF NOT EXISTS chk_hub_user_preference_revision CHECK (revision >= 0);

CREATE TABLE IF NOT EXISTS hub_task_user_settings (
    owner_id VARCHAR(36) PRIMARY KEY,
    reminder_time TIME NOT NULL DEFAULT TIME '09:00',
    CONSTRAINT fk_hub_task_user_settings_owner FOREIGN KEY (owner_id) REFERENCES hub_user(id)
);

CREATE TABLE IF NOT EXISTS hub_permission_profile (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    normalized_name VARCHAR(80) NOT NULL UNIQUE,
    permissions CLOB NOT NULL,
    system_profile BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS hub_task_user_revision (
    owner_id VARCHAR(36) PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS hub_reading_user_revision (
    owner_id VARCHAR(36) PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS hub_note (
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    title VARCHAR(512) NOT NULL,
    body VARCHAR(2048) NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    related_task_id VARCHAR(36),
    related_task_unavailable BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (owner_id, id),
    CONSTRAINT fk_hub_note_owner FOREIGN KEY (owner_id) REFERENCES hub_user(id),
    CONSTRAINT chk_hub_note_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 512),
    CONSTRAINT chk_hub_note_revision CHECK (revision >= 0)
);

CREATE TABLE IF NOT EXISTS hub_note_tag (
    owner_id VARCHAR(36) NOT NULL,
    note_id VARCHAR(36) NOT NULL,
    tag_order INTEGER NOT NULL,
    tag VARCHAR(64) NOT NULL,
    normalized_tag VARCHAR(64) NOT NULL,
    PRIMARY KEY (owner_id, note_id, tag_order),
    CONSTRAINT uq_hub_note_tag_value UNIQUE (owner_id, note_id, normalized_tag),
    CONSTRAINT fk_hub_note_tag_note FOREIGN KEY (owner_id, note_id)
        REFERENCES hub_note(owner_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_hub_note_tag CHECK (CHAR_LENGTH(TRIM(tag)) BETWEEN 1 AND 64)
);

CREATE TABLE IF NOT EXISTS hub_note_user_revision (
    owner_id VARCHAR(36) PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_hub_note_revision_owner FOREIGN KEY (owner_id) REFERENCES hub_user(id)
);

CREATE TABLE IF NOT EXISTS hub_habit (
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    start_date DATE NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (owner_id, id),
    CONSTRAINT fk_hub_habit_owner FOREIGN KEY (owner_id) REFERENCES hub_user(id),
    CONSTRAINT chk_hub_habit_name CHECK (CHAR_LENGTH(TRIM(name)) BETWEEN 1 AND 120)
);

CREATE TABLE IF NOT EXISTS hub_habit_schedule (
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    habit_id VARCHAR(36) NOT NULL,
    effective_from DATE NOT NULL,
    weekdays_mask INTEGER NOT NULL,
    PRIMARY KEY (owner_id, id),
    CONSTRAINT uq_hub_habit_schedule_date UNIQUE (owner_id, habit_id, effective_from),
    CONSTRAINT fk_hub_habit_schedule_habit FOREIGN KEY (owner_id, habit_id)
        REFERENCES hub_habit(owner_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_hub_habit_schedule_weekdays CHECK (weekdays_mask BETWEEN 1 AND 127)
);

CREATE TABLE IF NOT EXISTS hub_habit_pause (
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    habit_id VARCHAR(36) NOT NULL,
    paused_on DATE NOT NULL,
    resumed_on DATE,
    PRIMARY KEY (owner_id, id),
    CONSTRAINT fk_hub_habit_pause_habit FOREIGN KEY (owner_id, habit_id)
        REFERENCES hub_habit(owner_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_hub_habit_pause_range CHECK (resumed_on IS NULL OR resumed_on >= paused_on)
);

CREATE TABLE IF NOT EXISTS hub_habit_checkin (
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    habit_id VARCHAR(36) NOT NULL,
    checkin_date DATE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (owner_id, id),
    CONSTRAINT uq_hub_habit_checkin_date UNIQUE (owner_id, habit_id, checkin_date),
    CONSTRAINT fk_hub_habit_checkin_habit FOREIGN KEY (owner_id, habit_id)
        REFERENCES hub_habit(owner_id, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS hub_habit_user_revision (
    owner_id VARCHAR(36) PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_hub_habit_user_revision_owner FOREIGN KEY (owner_id) REFERENCES hub_user(id)
);

CREATE TABLE IF NOT EXISTS hub_focus_session (
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    objective VARCHAR(240) NOT NULL,
    planned_seconds INTEGER NOT NULL,
    focused_seconds INTEGER NOT NULL DEFAULT 0,
    session_state VARCHAR(16) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resumed_at TIMESTAMP WITH TIME ZONE,
    paused_at TIMESTAMP WITH TIME ZONE,
    ended_at TIMESTAMP WITH TIME ZONE,
    end_reason VARCHAR(16),
    task_id VARCHAR(36),
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_id, id),
    CONSTRAINT fk_hub_focus_session_owner FOREIGN KEY (owner_id) REFERENCES hub_user(id),
    CONSTRAINT chk_hub_focus_planned CHECK (planned_seconds BETWEEN 60 AND 43200),
    CONSTRAINT chk_hub_focus_seconds CHECK (focused_seconds >= 0),
    CONSTRAINT chk_hub_focus_state CHECK (session_state IN ('ACTIVE', 'PAUSED', 'ENDED')),
    CONSTRAINT chk_hub_focus_end CHECK ((session_state = 'ENDED' AND ended_at IS NOT NULL AND end_reason IS NOT NULL)
        OR (session_state <> 'ENDED' AND ended_at IS NULL AND end_reason IS NULL))
);

CREATE TABLE IF NOT EXISTS hub_focus_active_slot (
    owner_id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36),
    CONSTRAINT fk_hub_focus_slot_owner FOREIGN KEY (owner_id) REFERENCES hub_user(id),
    CONSTRAINT fk_hub_focus_slot_session FOREIGN KEY (owner_id, session_id)
        REFERENCES hub_focus_session(owner_id, id)
);

CREATE INDEX IF NOT EXISTS idx_hub_focus_history ON hub_focus_session (owner_id, started_at DESC, id);

CREATE TABLE IF NOT EXISTS hub_auth_bootstrap (
    bootstrap_id INTEGER PRIMARY KEY,
    CONSTRAINT chk_hub_auth_bootstrap_id CHECK (bootstrap_id = 1)
);
INSERT INTO hub_auth_bootstrap (bootstrap_id)
SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM hub_auth_bootstrap WHERE bootstrap_id = 1);

CREATE TABLE IF NOT EXISTS hub_schema_migration (
    migration_id VARCHAR(120) PRIMARY KEY,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE hub_task_series ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE hub_reading_item ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE hub_reading_author ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE hub_reading_item_author ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE hub_reading_item_tag ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE hub_task_checklist_revision ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE hub_task_checklist_item ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE hub_task_collection_state ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE hub_reading_collection_state ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE hub_task_settings ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE google_connection ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE google_task_list_state ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE google_task_state ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE hub_task_notification ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);

ALTER TABLE google_connection ADD CONSTRAINT IF NOT EXISTS uq_google_connection_owner UNIQUE (owner_id);
ALTER TABLE google_connection ADD CONSTRAINT IF NOT EXISTS uq_google_connection_owner_id UNIQUE (owner_id, connection_id);
ALTER TABLE hub_task ADD CONSTRAINT IF NOT EXISTS uq_hub_task_owner_id_constraint UNIQUE (owner_id, id);
ALTER TABLE hub_task_series ADD CONSTRAINT IF NOT EXISTS uq_hub_task_series_owner_id_constraint UNIQUE (owner_id, id);
ALTER TABLE hub_reading_item ADD CONSTRAINT IF NOT EXISTS uq_hub_reading_item_owner_id_constraint UNIQUE (owner_id, id);
ALTER TABLE hub_reading_author ADD CONSTRAINT IF NOT EXISTS uq_hub_reading_author_owner_id_constraint UNIQUE (owner_id, id);
CREATE INDEX IF NOT EXISTS idx_hub_reading_item_author_owner_item ON hub_reading_item_author (owner_id, reading_item_id);
CREATE INDEX IF NOT EXISTS idx_hub_reading_item_tag_owner_item ON hub_reading_item_tag (owner_id, reading_item_id, tag_order);
CREATE INDEX IF NOT EXISTS idx_hub_task_checklist_owner_task ON hub_task_checklist_item (owner_id, task_id, item_order);

CREATE INDEX IF NOT EXISTS idx_google_task_state_list ON google_task_state (owner_id, connection_id, task_list_id, deleted);
CREATE INDEX IF NOT EXISTS idx_hub_task_notification_due ON hub_task_notification (owner_id, state, scheduled_at);
CREATE INDEX IF NOT EXISTS idx_hub_task_checklist_task ON hub_task_checklist_item (owner_id, task_id, item_order);
CREATE INDEX IF NOT EXISTS idx_hub_reading_item_archive_updated ON hub_reading_item (owner_id, archived, updated_at, id);
CREATE INDEX IF NOT EXISTS idx_hub_reading_item_archive_status_type ON hub_reading_item (owner_id, archived, reading_status, item_type, updated_at, id);
CREATE INDEX IF NOT EXISTS idx_hub_reading_item_author_author ON hub_reading_item_author (owner_id, author_id, reading_item_id);
CREATE INDEX IF NOT EXISTS idx_hub_note_archive_pin_updated ON hub_note (owner_id, archived, pinned, updated_at, id);
CREATE INDEX IF NOT EXISTS idx_hub_note_tag_owner_tag_note ON hub_note_tag (owner_id, normalized_tag, note_id);

CREATE INDEX IF NOT EXISTS idx_hub_task_owner_due ON hub_task (owner_id, due_date, due_time, id);
CREATE INDEX IF NOT EXISTS idx_hub_reading_owner_status_updated ON hub_reading_item (owner_id, archived, reading_status, updated_at, id);
CREATE INDEX IF NOT EXISTS idx_hub_task_series_owner ON hub_task_series (owner_id, id);

