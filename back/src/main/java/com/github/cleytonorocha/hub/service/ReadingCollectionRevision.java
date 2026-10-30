package com.github.cleytonorocha.hub.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.github.cleytonorocha.hub.security.AuthContext;
import org.springframework.dao.DuplicateKeyException;

@Component
public final class ReadingCollectionRevision {
	private final JdbcTemplate jdbc;

	public ReadingCollectionRevision(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	public long lock() {
		String owner = AuthContext.userId();
		try { jdbc.update("INSERT INTO hub_reading_user_revision(owner_id, revision) VALUES (?, 0)", owner); }
		catch (DuplicateKeyException ignored) { }
		Long revision = jdbc.queryForObject("SELECT revision FROM hub_reading_user_revision WHERE owner_id = ? FOR UPDATE", Long.class, owner);
		if (revision == null) throw new IllegalStateException("A revisão da biblioteca de leitura não foi inicializada.");
		return revision;
	}

	public void increment() {
		if (jdbc.update("UPDATE hub_reading_user_revision SET revision = revision + 1 WHERE owner_id = ?", AuthContext.userId()) != 1) {
			throw new IllegalStateException("Não foi possível atualizar a revisão da biblioteca de leitura.");
		}
	}
}
