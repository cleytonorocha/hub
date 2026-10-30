package com.github.cleytonorocha.hub.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class TaskCollectionRevision {
	private final JdbcTemplate jdbc;

	public TaskCollectionRevision(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	public long lock() {
		Long revision = jdbc.queryForObject(
			"SELECT revision FROM hub_task_collection_state WHERE state_id = 1 FOR UPDATE", Long.class);
		if (revision == null) throw new IllegalStateException("A revisão das tarefas não foi inicializada.");
		return revision;
	}

	public void increment() {
		if (jdbc.update("UPDATE hub_task_collection_state SET revision = revision + 1 WHERE state_id = 1") != 1) {
			throw new IllegalStateException("Não foi possível atualizar a revisão das tarefas.");
		}
	}
}
