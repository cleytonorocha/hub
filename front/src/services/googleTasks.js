import { csrfHeader } from './auth'

async function request(path, options = {}) {
  let response
  try {
    const method = (options.method || 'GET').toUpperCase()
    const csrf = method === 'GET' || method === 'HEAD' ? null : await csrfHeader()
    response = await fetch(path, {
      ...options,
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', ...(csrf ? { [csrf[0]]: csrf[1] } : {}), ...(options.headers || {}) },
      cache: options.cache || 'no-store',
    })
  } catch {
    throw new Error('Não foi possível acessar o Google Tasks. Verifique sua conexão e tente novamente.')
  }
  if (response.status === 204) return null
  const body = await response.text()
  let payload = {}
  try { payload = body ? JSON.parse(body) : {} } catch { payload = {} }
  if (!response.ok) {
    throw new Error(payload.detail || payload.message || payload.title || 'Não foi possível concluir a operação do Google Tasks.')
  }
  return payload
}

export function getGoogleConnectionStatus() { return request('/api/google-tasks/status') }
export function startGoogleConnection() { return request('/api/google-tasks/connect', { method: 'POST', body: '{}' }) }
export function disconnectGoogle() { return request('/api/google-tasks/disconnect', { method: 'POST', body: '{}' }) }
export function synchronizeGoogleTasks() { return request('/api/google-tasks/sync', { method: 'POST', body: '{}' }) }
export function listGoogleTasks() { return request('/api/google-tasks/tasks') }
export function listGoogleTaskLists() { return request('/api/google-tasks/lists') }
export function selectGoogleTaskList(id, selected) {
  return request('/api/google-tasks/lists/' + encodeURIComponent(id) + '/selection', {
    method: 'PUT', body: JSON.stringify({ selected }),
  })
}
export function createGoogleTask(task) {
  return request('/api/google-tasks/tasks', { method: 'POST', body: JSON.stringify(task) })
}
export function updateGoogleTask(task, changes) {
  return request('/api/google-tasks/lists/' + encodeURIComponent(task.listId) + '/tasks/' + encodeURIComponent(task.id), {
    method: 'PATCH', body: JSON.stringify({
      dueDate: changes.dueDate || null,
      completed: Boolean(changes.completed),
      expectedUpdated: task.updated,
      expectedEtag: task.etag || null,
    }),
  })
}
export function listGoogleNotifications(unreadOnly = false) {
  return request('/api/google-tasks/notifications?unreadOnly=' + String(Boolean(unreadOnly)))
}
export function markGoogleNotificationRead(id) {
  return request('/api/google-tasks/notifications/' + encodeURIComponent(id) + '/read', { method: 'PATCH', body: '{}' })
}
export function markAllGoogleNotificationsRead() {
  return request('/api/google-tasks/notifications/read-all', { method: 'POST', body: '{}' })
}
export function getGoogleReminderSettings() { return request('/api/settings/tasks') }
export function saveGoogleReminderSettings(reminderTime) {
  return request('/api/settings/tasks', { method: 'PUT', body: JSON.stringify({ reminderTime }) })
}
