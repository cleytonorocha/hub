CREATE TABLE google_connection (
    connection_id VARCHAR(40) PRIMARY KEY,
    refresh_token_ciphertext CLOB,
    refresh_token_nonce VARCHAR(32),
    scopes VARCHAR(512),
    status VARCHAR(32) NOT NULL,
    last_synced_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE google_task_list_state (
    connection_id VARCHAR(40) NOT NULL,
    task_list_id VARCHAR(256) NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    last_sync_run VARCHAR(36),
    PRIMARY KEY (connection_id, task_list_id)
);

CREATE TABLE google_task_state (
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

CREATE INDEX idx_google_task_state_list ON google_task_state (connection_id, task_list_id, deleted);

CREATE TABLE hub_task_notification (
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

CREATE INDEX idx_hub_task_notification_due ON hub_task_notification (state, scheduled_at);

CREATE TABLE hub_task_settings (
    settings_id INTEGER PRIMARY KEY,
    reminder_time TIME NOT NULL DEFAULT TIME '09:00'
);

INSERT INTO hub_task_settings (settings_id, reminder_time) VALUES (1, TIME '09:00');
