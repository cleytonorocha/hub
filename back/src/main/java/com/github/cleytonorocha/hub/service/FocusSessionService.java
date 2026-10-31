package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.FocusSessionDtos.EndRequest;
import com.github.cleytonorocha.hub.dto.FocusSessionDtos.HistoryPage;
import com.github.cleytonorocha.hub.dto.FocusSessionDtos.SessionView;
import com.github.cleytonorocha.hub.dto.FocusSessionDtos.StartRequest;
import com.github.cleytonorocha.hub.dto.FocusSessionDtos.TransitionRequest;
import com.github.cleytonorocha.hub.repository.FocusSessionRepository;
import com.github.cleytonorocha.hub.security.AuthContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FocusSessionService {
	private static final int MAX_PAGE_SIZE = 100;
	private final FocusSessionRepository sessions;
	public FocusSessionService(FocusSessionRepository sessions) { this.sessions = sessions; }

	@Transactional
	public SessionView start(StartRequest request) {
		String owner = AuthContext.userId();
		if (request.taskId() != null && !AuthContext.hasPermission("TASK_READ"))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A permissão para consultar tarefas é necessária para vincular uma tarefa.");
		String currentId = sessions.lockOwnerSlot(owner);
		SessionView existing = sessions.find(owner, request.id());
		if (existing != null) {
			if (!existing.objective().equals(request.objective().strip()) || existing.plannedSeconds() != request.plannedSeconds()
					|| !java.util.Objects.equals(existing.taskId(), request.taskId())) throw conflict("O identificador da sessão já foi usado.");
			return present(existing);
		}
		if (currentId != null) throw conflict("Encerre ou retome a sessão ativa antes de iniciar outra.");
		if (request.taskId() != null && !sessions.taskExists(owner, request.taskId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A tarefa vinculada não existe ou não está disponível.");
		Instant now = Instant.now();
		try {
			sessions.insert(owner, request.id(), request.objective().strip(), request.plannedSeconds(), request.taskId(), now);
			sessions.setSlot(owner, request.id());
		} catch (DuplicateKeyException error) { throw conflict("Não foi possível iniciar outra sessão em paralelo."); }
		return present(sessions.find(owner, request.id()));
	}

	@Transactional
	public SessionView active() {
		String owner = AuthContext.userId();
		return present(sessions.active(owner));
	}

	@Transactional(readOnly = true)
	public HistoryPage history(int offset, int limit) {
		if (offset < 0 || limit < 1 || limit > MAX_PAGE_SIZE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O intervalo do histórico é inválido.");
		List<SessionView> result = sessions.history(AuthContext.userId(), offset, limit + 1);
		result = result.stream().map(this::present).toList();
		boolean hasMore = result.size() > limit;
		if (hasMore) result = result.subList(0, limit);
		return new HistoryPage(result, hasMore, offset + result.size());
	}

	@Transactional
	public SessionView pause(String id, TransitionRequest request) {
		return transition(id, request.revision(), "PAUSED");
	}

	@Transactional
	public SessionView resume(String id, TransitionRequest request) {
		return transition(id, request.revision(), "ACTIVE");
	}

	@Transactional
	public SessionView end(String id, EndRequest request) {
		String owner = AuthContext.userId();
		String activeId = sessions.lockOwnerSlot(owner);
		SessionView session = require(id, owner);
		if (session.state().equals("ENDED")) return present(session);
		checkRevision(session, request.revision());
		if (!id.equals(activeId)) throw conflict("A sessão mudou em outra aba. Atualize antes de encerrar.");
		Instant now = Instant.now();
		int actual = effectiveSeconds(session, now);
		int focused = request.focusedSeconds() == null ? actual : request.focusedSeconds();
		String reason = request.focusedSeconds() != null ? "ADJUSTED" : focused >= session.plannedSeconds() ? "NORMAL" : "EARLY";
		update(session, "ENDED", focused, null, now, now, reason);
		sessions.clearSlot(owner);
		return present(sessions.find(owner, id));
	}

	@Transactional
	public void delete(String id, long revision) {
		String owner = AuthContext.userId();
		sessions.lockOwnerSlot(owner);
		SessionView current = require(id, owner);
		if (!current.state().equals("ENDED")) throw conflict("Encerre a sessão antes de excluí-la.");
		if (current.revision() != revision) throw conflict("A sessão mudou em outra aba. Atualize o histórico.");
		if (sessions.deleteEnded(owner, id, revision) != 1) throw conflict("A sessão mudou em outra aba. Atualize o histórico.");
	}

	private SessionView transition(String id, long revision, String target) {
		String owner = AuthContext.userId();
		String activeId = sessions.lockOwnerSlot(owner);
		SessionView session = require(id, owner);
		if (session.state().equals("ENDED")) throw conflict("A sessão já foi encerrada.");
		if (!id.equals(activeId)) throw conflict("A sessão ativa mudou em outra aba. Atualize a página.");
		if (session.state().equals(target) && session.revision() == revision + 1) return present(session);
		checkRevision(session, revision);
		Instant now = Instant.now();
		if (target.equals("PAUSED")) {
			if (!session.state().equals("ACTIVE")) throw conflict("A sessão já está pausada.");
			update(session, "PAUSED", effectiveSeconds(session, now), null, now, null, null);
		} else {
			if (!session.state().equals("PAUSED")) throw conflict("A sessão não está pausada.");
			update(session, "ACTIVE", session.focusedSeconds(), now, session.pausedAt(), null, null);
		}
		return present(sessions.find(owner, id));
	}
	private SessionView present(SessionView session) {
		if (session == null || AuthContext.hasPermission("TASK_READ")) return session;
		return new SessionView(session.id(), session.objective(), session.plannedSeconds(), session.focusedSeconds(),
			session.state(), session.startedAt(), session.resumedAt(), session.pausedAt(), session.endedAt(),
			session.endReason(), session.taskId(), null, false, session.revision());
	}

	private void update(SessionView session, String state, int focused, Instant resumedAt, Instant pausedAt, Instant endedAt, String reason) {
		if (sessions.transition(AuthContext.userId(), session, state, focused, resumedAt, pausedAt, endedAt, reason) != 1)
			throw conflict("A sessão mudou em outra aba. Atualize antes de continuar.");
	}
	private SessionView require(String id, String owner) {
		SessionView found = sessions.find(owner, id);
		if (found == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sessão não encontrada.");
		return found;
	}
	private int effectiveSeconds(SessionView session, Instant now) {
		if (!session.state().equals("ACTIVE") || session.resumedAt() == null) return session.focusedSeconds();
		long extra = Math.max(0, Duration.between(session.resumedAt(), now).getSeconds());
		return Math.toIntExact(Math.min(Integer.MAX_VALUE, (long) session.focusedSeconds() + extra));
	}
	private void checkRevision(SessionView session, long revision) {
		if (session.revision() != revision) throw conflict("A sessão mudou em outra aba. Atualize antes de continuar.");
	}
	private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
