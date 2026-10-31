async function request(path, options = {}) {
  let response
  try {
    response = await fetch(path, {
      ...options,
      headers: { 'Content-Type': 'application/json', ...options.headers },
    })
  } catch {
    throw new Error('Não foi possível acessar as notas. Confirme se o backend está em execução.')
  }
  const text = response.status === 204 ? '' : await response.text()
  let payload = null
  if (text) {
    try { payload = JSON.parse(text) } catch { payload = { message: text } }
  }
  if (!response.ok) {
    const error = new Error(payload?.detail || payload?.message || `A API local respondeu com HTTP ${response.status}.`)
    error.status = response.status
    throw error
  }
  return payload
}

export function listNotes(criteria = {}) {
  const params = new URLSearchParams({
    q: criteria.query || '',
    archived: String(Boolean(criteria.archived)),
    page: String(criteria.page || 0),
    size: String(criteria.size || 20),
  })
  if (criteria.tag) params.set('tag', criteria.tag)
  return request(`/api/notes?${params.toString()}`)
}

export function listNoteTags(archived = false) {
  return request(`/api/notes/tags?archived=${String(Boolean(archived))}`)
}

export function getNote(id) { return request(`/api/notes/${encodeURIComponent(id)}`) }
export function createNote(note) { return request('/api/notes', { method: 'POST', body: JSON.stringify(note) }) }
export function updateNote(id, note) { return request(`/api/notes/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(note) }) }
export function deleteNote(id) { return request(`/api/notes/${encodeURIComponent(id)}`, { method: 'DELETE' }) }
