package com.github.cleytonorocha.hub.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class ReadingCollectionRevision {
	private final JdbcTemplate jdbc;

	public ReadingCollectionRevision(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	public long lock() {
		Long revision = jdbc.queryForObject("SELECT revision FROM hub_reading_collection_state WHERE state_id = 1 FOR UPDATE", Long.class);
		if (revision == null) throw new IllegalStateException("A revisão da biblioteca de leitura não foi inicializada.");
		return revision;
	}

	public void increment() {
		if (jdbc.update("UPDATE hub_reading_collection_state SET revision = revision + 1 WHERE state_id = 1") != 1) {
			throw new IllegalStateException("Não foi possível atualizar a revisão da biblioteca de leitura.");
		}
	}
}
