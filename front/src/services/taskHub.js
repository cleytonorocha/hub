import { reactive } from 'vue'

const state = reactive({ tasks: [], loading: true, refreshing: false, error: '' })
let accountGeneration = 0

export function resetTaskHub() {
  accountGeneration += 1
  state.tasks = []
  state.loading = true
  state.refreshing = false
  state.error = ''
}

async function request(path, options = {}) {
  let response
  try {
    response = await fetch(path, {
      ...options,
      headers: { 'Content-Type': 'application/json', ...options.headers },
    })
  } catch {
    throw new Error('Não foi possível acessar as tarefas locais. Confirme se o backend está em execução.')
  }
  const text = response.status === 204 ? '' : await response.text()
  let payload = null
  if (text) {
    try { payload = JSON.parse(text) } catch { payload = { message: text } }
  }
  if (!response.ok) {
    const message = payload?.message || payload?.detail || payload?.title
    throw new Error(message || `A API local respondeu com HTTP ${response.status}. Confirme se o backend está em execução.`)
  }
  return payload
}

async function refreshTasks() {
  if (state.refreshing) return state.tasks
  const generation = accountGeneration
  state.refreshing = true
  state.error = ''
  try {
    const tasks = await request('/api/tasks')
    if (generation === accountGeneration) state.tasks = tasks
    return state.tasks
  } catch (error) {
    if (generation === accountGeneration) state.error = error.message
    throw error
  } finally {
    if (generation === accountGeneration) state.refreshing = false
  }
}

async function initialize() {
  const generation = accountGeneration
  state.loading = true
  try { await refreshTasks() } catch { /* Keep the error visible in the calendar. */ }
  finally { if (generation === accountGeneration) state.loading = false }
}

async function createTask(task) {
  const generation = accountGeneration
  const created = await request('/api/tasks', { method: 'POST', body: JSON.stringify(task) })
  if (generation === accountGeneration) state.tasks.push(created)
  return created
}

async function updateTask(task, changes) {
  const generation = accountGeneration
  const updated = await request(`/api/tasks/${encodeURIComponent(task.id)}`, {
    method: 'PATCH',
    body: JSON.stringify({
      dueDate: changes.dueDate,
      dueTime: changes.dueTime,
      completed: changes.completed,
      confirmPendingChecklist: changes.confirmPendingChecklist === true,
      category: changes.category,
      color: changes.color,
      priority: changes.priority,
      notes: changes.notes,
    }),
  })
  if (generation === accountGeneration) {
    const index = state.tasks.findIndex((item) => item.id === updated.id)
    if (index >= 0) state.tasks.splice(index, 1, updated)
  }
  return updated
}

async function deleteTask(task) {
  const generation = accountGeneration
  await request(`/api/tasks/${encodeURIComponent(task.id)}`, { method: 'DELETE' })
  if (generation === accountGeneration) state.tasks = state.tasks.filter((item) => item.id !== task.id)
}

async function searchTasks(criteria) {
  const params = new URLSearchParams()
  Object.entries(criteria).forEach(([key, value]) => {
    if (value !== '' && value !== null && value !== undefined) params.set(key, String(value))
  })
  return request(`/api/tasks/search?${params.toString()}`)
}

async function createTaskSeries(series) {
  const generation = accountGeneration
  const created = await request('/api/task-series', { method: 'POST', body: JSON.stringify(series) })
  if (generation === accountGeneration) state.tasks.push(...created.occurrences)
  return created
}

async function getTaskSeries(id) {
  return request(`/api/task-series/${encodeURIComponent(id)}`)
}

async function previewCloseTaskSeries(id, timeZoneId) {
  const params = new URLSearchParams({ timeZoneId })
  return request(`/api/task-series/${encodeURIComponent(id)}/close-preview?${params}`)
}

async function closeTaskSeries(id, review) {
  const result = await request(`/api/task-series/${encodeURIComponent(id)}/close`, {
    method: 'POST',
    body: JSON.stringify({ today: review.today, timeZoneId: review.timeZoneId, expectedRevision: review.revision }),
  })
  await refreshTasks()
  return result
}

async function getTaskChecklist(taskId) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}/checklist`)
}

async function saveTaskChecklist(taskId, checklist) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}/checklist`, {
    method: 'PUT',
    body: JSON.stringify(checklist),
  })
}

export function useTaskHub() {
  return { state, initialize, refreshTasks, createTask, updateTask, deleteTask, searchTasks,
    createTaskSeries, getTaskSeries, previewCloseTaskSeries, closeTaskSeries,
    getTaskChecklist, saveTaskChecklist }
}
