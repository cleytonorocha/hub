CREATE TABLE IF NOT EXISTS google_connection (
    connection_id VARCHAR(40) PRIMARY KEY,
    refresh_token_ciphertext CLOB,
    refresh_token_nonce VARCHAR(32),
    scopes VARCHAR(512),
    status VARCHAR(32) NOT NULL,
    last_synced_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS google_task_list_state (
    connection_id VARCHAR(40) NOT NULL,
    task_list_id VARCHAR(256) NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    last_sync_run VARCHAR(36),
    PRIMARY KEY (connection_id, task_list_id)
);

CREATE TABLE IF NOT EXISTS google_task_state (
    connection_id VARCHAR(40) NOT NULL,
    task_list_id VARCHAR(256) NOT NULL,
    task_id VARCHAR(256) NOT NULL,
    remote_updated VARCHAR(64),
    remote_etag VARCHAR(256),
    due_date DATE,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    last_sync_run VARCHAR(36),
    PRIMARY KEY (connection_id, task_list_id, task_id)
);

CREATE INDEX IF NOT EXISTS idx_google_task_state_list ON google_task_state (connection_id, task_list_id, deleted);

CREATE TABLE IF NOT EXISTS hub_task_notification (
    notification_id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(40) NOT NULL,
    task_list_id VARCHAR(256) NOT NULL,
    task_id VARCHAR(256) NOT NULL,
    due_date DATE NOT NULL,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(16) NOT NULL,
    overdue BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_task_notice UNIQUE (connection_id, task_list_id, task_id, due_date)
);

CREATE INDEX IF NOT EXISTS idx_hub_task_notification_due ON hub_task_notification (state, scheduled_at);

CREATE TABLE IF NOT EXISTS hub_task_settings (
    settings_id INTEGER PRIMARY KEY,
    reminder_time TIME NOT NULL DEFAULT TIME '09:00'
);

INSERT INTO hub_task_settings (settings_id, reminder_time)
SELECT 1, TIME '09:00'
WHERE NOT EXISTS (SELECT 1 FROM hub_task_settings WHERE settings_id = 1);

CREATE TABLE IF NOT EXISTS hub_task (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36),
    title VARCHAR(1024) NOT NULL,
    due_date DATE,
    due_time TIME,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    task_type VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
    category VARCHAR(64) NOT NULL DEFAULT 'Geral',
    color VARCHAR(7) NOT NULL DEFAULT '#A986FF',
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    notes VARCHAR(2000) NOT NULL DEFAULT ''
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
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36),
    operation_id VARCHAR(36) NOT NULL UNIQUE,
    title VARCHAR(1024) NOT NULL,
    start_date DATE NOT NULL,
    due_time TIME,
    occurrence_count INTEGER NOT NULL,
    time_zone_id VARCHAR(64) NOT NULL,
    ended BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_hub_task_series_count CHECK (occurrence_count BETWEEN 2 AND 52)
);

ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS series_id VARCHAR(36);
ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS series_position INTEGER;
ALTER TABLE hub_task ADD CONSTRAINT IF NOT EXISTS fk_hub_task_series FOREIGN KEY (series_id) REFERENCES hub_task_series(id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_hub_task_series_position ON hub_task (series_id, series_position);

CREATE TABLE IF NOT EXISTS hub_task_checklist_revision (
    task_id VARCHAR(36) PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_hub_task_checklist_revision FOREIGN KEY (task_id) REFERENCES hub_task(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS hub_task_checklist_item (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    item_order INTEGER NOT NULL,
    item_text VARCHAR(200) NOT NULL,
    checked BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_hub_task_checklist_order UNIQUE (task_id, item_order),
    CONSTRAINT fk_hub_task_checklist_item FOREIGN KEY (task_id) REFERENCES hub_task(id) ON DELETE CASCADE,
    CONSTRAINT chk_hub_task_checklist_order CHECK (item_order BETWEEN 0 AND 19)
);

CREATE INDEX IF NOT EXISTS idx_hub_task_checklist_task ON hub_task_checklist_item (task_id, item_order);

CREATE TABLE IF NOT EXISTS hub_reading_item (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36),
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
    CONSTRAINT chk_hub_reading_item_status CHECK (reading_status IN ('TO_READ', 'IN_PROGRESS', 'COMPLETED'))
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
    reading_item_id VARCHAR(36) NOT NULL,
    tag_order INTEGER NOT NULL,
    tag VARCHAR(64) NOT NULL,
    CONSTRAINT pk_hub_reading_item_tag PRIMARY KEY (reading_item_id, tag_order),
    CONSTRAINT fk_hub_reading_item_tag_item FOREIGN KEY (reading_item_id) REFERENCES hub_reading_item(id),
    CONSTRAINT chk_hub_reading_item_tag_order CHECK (tag_order BETWEEN 0 AND 19)
);

CREATE INDEX IF NOT EXISTS idx_hub_reading_item_archive_updated ON hub_reading_item (archived, updated_at, id);
CREATE INDEX IF NOT EXISTS idx_hub_reading_item_archive_status_type ON hub_reading_item (archived, reading_status, item_type, updated_at, id);

CREATE TABLE IF NOT EXISTS hub_reading_author (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36),
    name VARCHAR(160) NOT NULL,
    normalized_name VARCHAR(160) NOT NULL
);

CREATE TABLE IF NOT EXISTS hub_reading_item_author (
    reading_item_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    CONSTRAINT pk_hub_reading_item_author PRIMARY KEY (reading_item_id, author_id),
    CONSTRAINT fk_hub_reading_item_author_item FOREIGN KEY (reading_item_id) REFERENCES hub_reading_item(id) ON DELETE CASCADE,
    CONSTRAINT fk_hub_reading_item_author_author FOREIGN KEY (author_id) REFERENCES hub_reading_author(id)
);
CREATE INDEX IF NOT EXISTS idx_hub_reading_item_author_author ON hub_reading_item_author (author_id, reading_item_id);

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

ALTER TABLE hub_task ADD CONSTRAINT IF NOT EXISTS uq_hub_task_owner_id_constraint UNIQUE (owner_id, id);
ALTER TABLE hub_task_series ADD CONSTRAINT IF NOT EXISTS uq_hub_task_series_owner_id_constraint UNIQUE (owner_id, id);
ALTER TABLE hub_reading_item ADD CONSTRAINT IF NOT EXISTS uq_hub_reading_item_owner_id_constraint UNIQUE (owner_id, id);
ALTER TABLE hub_reading_author ADD CONSTRAINT IF NOT EXISTS uq_hub_reading_author_owner_id_constraint UNIQUE (owner_id, id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_hub_reading_author_owner_name ON hub_reading_author (owner_id, normalized_name);
CREATE INDEX IF NOT EXISTS idx_hub_reading_item_author_owner_item ON hub_reading_item_author (owner_id, reading_item_id);
CREATE INDEX IF NOT EXISTS idx_hub_reading_item_tag_owner_item ON hub_reading_item_tag (owner_id, reading_item_id, tag_order);
CREATE INDEX IF NOT EXISTS idx_hub_task_checklist_owner_task ON hub_task_checklist_item (owner_id, task_id, item_order);

CREATE INDEX IF NOT EXISTS idx_hub_task_owner_due ON hub_task (owner_id, due_date, due_time, id);
CREATE INDEX IF NOT EXISTS idx_hub_reading_owner_status_updated ON hub_reading_item (owner_id, archived, reading_status, updated_at, id);
CREATE INDEX IF NOT EXISTS idx_hub_task_series_owner ON hub_task_series (owner_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_hub_task_owner_id ON hub_task (owner_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_hub_task_series_owner_id ON hub_task_series (owner_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_hub_reading_item_owner_id ON hub_reading_item (owner_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_hub_reading_author_owner_id ON hub_reading_author (owner_id, id);

