package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.dto.FocusSessionDtos.SessionView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FocusSessionRepository {
	private final JdbcTemplate jdbc;
	public FocusSessionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	public String lockOwnerSlot(String owner) {
		try { jdbc.update("INSERT INTO hub_focus_active_slot(owner_id, session_id) VALUES (?, NULL)", owner); }
		catch (DuplicateKeyException ignored) { }
		return jdbc.queryForObject("SELECT session_id FROM hub_focus_active_slot WHERE owner_id = ? FOR UPDATE",
			(rs, row) -> rs.getString(1), owner);
	}

	public SessionView find(String owner, String id) {
		List<SessionView> result = jdbc.query(selectSql() + " WHERE s.owner_id = ? AND s.id = ?", this::map, owner, id);
		return result.isEmpty() ? null : result.getFirst();
	}

	public SessionView active(String owner) {
		List<SessionView> result = jdbc.query(selectSql() + " JOIN hub_focus_active_slot a ON a.owner_id=s.owner_id AND a.session_id=s.id WHERE s.owner_id = ?", this::map, owner);
		return result.isEmpty() ? null : result.getFirst();
	}

	public List<SessionView> history(String owner, int offset, int limit) {
		return jdbc.query(selectSql() + " WHERE s.owner_id = ? AND s.session_state = 'ENDED' ORDER BY s.started_at DESC, s.id LIMIT ? OFFSET ?",
			this::map, owner, limit, offset);
	}

	public List<SessionView> allForCopy(String owner) {
		return jdbc.query(selectSql() + " WHERE s.owner_id = ? ORDER BY s.started_at, s.id", this::map, owner);
	}

	public boolean taskExists(String owner, String taskId) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM hub_task WHERE owner_id = ? AND id = ?", Integer.class, owner, taskId);
		return count != null && count > 0;
	}

	public void insert(String owner, String id, String objective, int planned, String taskId, Instant now) {
		jdbc.update("INSERT INTO hub_focus_session(id, owner_id, objective, planned_seconds, focused_seconds, session_state, started_at, resumed_at, task_id, revision) VALUES (?, ?, ?, ?, 0, 'ACTIVE', ?, ?, ?, 0)",
			id, owner, objective, planned, ts(now), ts(now), taskId);
	}

	public int transition(String owner, SessionView s, String state, int seconds, Instant resumedAt, Instant pausedAt,
		Instant endedAt, String endReason) {
		return jdbc.update("UPDATE hub_focus_session SET session_state=?, focused_seconds=?, resumed_at=?, paused_at=?, ended_at=?, end_reason=?, revision=revision+1 WHERE owner_id=? AND id=? AND revision=?",
			state, seconds, ts(resumedAt), ts(pausedAt), ts(endedAt), endReason, owner, s.id(), s.revision());
	}

	public int deleteEnded(String owner, String id, long revision) {
		return jdbc.update("DELETE FROM hub_focus_session WHERE owner_id=? AND id=? AND session_state='ENDED' AND revision=?", owner, id, revision);
	}

	public void clearSlot(String owner) { jdbc.update("UPDATE hub_focus_active_slot SET session_id=NULL WHERE owner_id=?", owner); }
	public void setSlot(String owner, String id) { jdbc.update("UPDATE hub_focus_active_slot SET session_id=? WHERE owner_id=?", id, owner); }
	public void restore(String owner, com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupFocusSession s) {
		boolean ended = "ENDED".equals(s.state());
		Instant pausedAt = s.pausedAt();
		jdbc.update("INSERT INTO hub_focus_session(id, owner_id, objective, planned_seconds, focused_seconds, session_state, started_at, resumed_at, paused_at, ended_at, end_reason, task_id, revision) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, 0)",
			s.id(), owner, s.objective(), s.plannedSeconds(), s.focusedSeconds(), s.state(), ts(s.startedAt()), ts(pausedAt), ts(ended ? s.endedAt() : null), s.endReason(), s.taskId());
		if (!ended) setSlot(owner, s.id());
	}

	private String selectSql() {
		return "SELECT s.id,s.objective,s.planned_seconds,s.focused_seconds,s.session_state,s.started_at,s.resumed_at,s.paused_at,s.ended_at,s.end_reason,s.task_id,t.title,s.revision " +
			"FROM hub_focus_session s LEFT JOIN hub_task t ON t.owner_id=s.owner_id AND t.id=s.task_id";
	}
	private SessionView map(ResultSet rs, int row) throws SQLException {
		String taskId = rs.getString("task_id");
		return new SessionView(rs.getString("id"), rs.getString("objective"), rs.getInt("planned_seconds"),
			rs.getInt("focused_seconds"), rs.getString("session_state"), instant(rs, "started_at"), instant(rs, "resumed_at"),
			instant(rs, "paused_at"), instant(rs, "ended_at"), rs.getString("end_reason"), taskId,
			rs.getString("title"), taskId != null && rs.getString("title") != null, rs.getLong("revision"));
	}
	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}
	private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
}
