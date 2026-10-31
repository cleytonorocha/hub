const API = '/api/habits'

async function request(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
  })
  let payload = null
  try { payload = await response.json() } catch { /* Keep the generic message. */ }
  if (!response.ok) {
    const error = new Error(payload?.detail || payload?.message || 'Não foi possível carregar os hábitos.')
    error.status = response.status
    throw error
  }
  return payload
}

function query(timeZoneId, weekStart) {
  const params = new URLSearchParams({ timeZoneId })
  if (weekStart) params.set('weekStart', weekStart)
  return `?${params.toString()}`
}

export function currentTimeZone() {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
}

export function listHabits(weekStart) {
  return request(`${API}${query(currentTimeZone(), weekStart)}`)
}

export function createHabit(habit) {
  return request(API, { method: 'POST', body: JSON.stringify(habit) })
}

export function updateHabit(id, habit) {
  return request(`${API}/${encodeURIComponent(id)}${query(currentTimeZone())}`, {
    method: 'PUT', body: JSON.stringify(habit),
  })
}

export function markHabit(id, date) {
  return request(`${API}/${encodeURIComponent(id)}/check-ins/${encodeURIComponent(date)}${query(currentTimeZone())}`, { method: 'PUT' })
}

export function unmarkHabit(id, date) {
  return request(`${API}/${encodeURIComponent(id)}/check-ins/${encodeURIComponent(date)}${query(currentTimeZone())}`, { method: 'DELETE' })
}

export function pauseHabit(id) {
  return request(`${API}/${encodeURIComponent(id)}/pause${query(currentTimeZone())}`, { method: 'POST' })
}

export function resumeHabit(id) {
  return request(`${API}/${encodeURIComponent(id)}/resume${query(currentTimeZone())}`, { method: 'POST' })
}
