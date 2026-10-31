async function request(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
  })
  const body = response.status === 204 ? '' : await response.text()
  let payload = {}
  try { payload = body ? JSON.parse(body) : {} } catch { /* Use the status message. */ }
  if (!response.ok) throw new Error(payload.detail || payload.message || `A API respondeu com HTTP ${response.status}.`)
  return body ? payload : null
}

export function getActiveFocusSession() { return request('/api/focus-sessions/active') }

export function listFocusSessions(offset = 0, limit = 30) {
  const params = new URLSearchParams({ offset: String(offset), limit: String(limit) })
  return request(`/api/focus-sessions?${params}`)
}

export function startFocusSession(session) {
  return request('/api/focus-sessions', { method: 'POST', body: JSON.stringify(session) })
}

export function pauseFocusSession(session) {
  return request(`/api/focus-sessions/${encodeURIComponent(session.id)}/pause`, {
    method: 'POST', body: JSON.stringify({ revision: session.revision }),
  })
}

export function resumeFocusSession(session) {
  return request(`/api/focus-sessions/${encodeURIComponent(session.id)}/resume`, {
    method: 'POST', body: JSON.stringify({ revision: session.revision }),
  })
}

export function endFocusSession(session, focusedSeconds = null) {
  return request(`/api/focus-sessions/${encodeURIComponent(session.id)}/end`, {
    method: 'POST', body: JSON.stringify({ revision: session.revision, focusedSeconds }),
  })
}

export function deleteFocusSession(session) {
  return request(`/api/focus-sessions/${encodeURIComponent(session.id)}?revision=${session.revision}`, { method: 'DELETE' })
}
