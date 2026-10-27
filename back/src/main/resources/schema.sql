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

ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS category VARCHAR(64) NOT NULL DEFAULT 'Geral';
ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS due_time TIME;
ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS color VARCHAR(7) NOT NULL DEFAULT '#A986FF';
ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE hub_task ADD COLUMN IF NOT EXISTS notes VARCHAR(2000) NOT NULL DEFAULT '';
