import { reactive } from 'vue'

const state = reactive({ tasks: [], loading: false, refreshing: false, error: '' })

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
  if (!response.ok) throw new Error(payload?.message || 'Não foi possível concluir a operação.')
  return payload
}

async function refreshTasks() {
  if (state.refreshing) return state.tasks
  state.refreshing = true
  state.error = ''
  try {
    state.tasks = await request('/api/tasks')
    return state.tasks
  } catch (error) {
    state.error = error.message
    throw error
  } finally {
    state.refreshing = false
  }
}

async function initialize() {
  state.loading = true
  try { await refreshTasks() } catch { /* Keep the error visible in the calendar. */ }
  finally { state.loading = false }
}

async function createTask(task) {
  const created = await request('/api/tasks', { method: 'POST', body: JSON.stringify(task) })
  state.tasks.push(created)
  return created
}

async function updateTask(task, changes) {
  const updated = await request(`/api/tasks/${encodeURIComponent(task.id)}`, {
    method: 'PATCH',
    body: JSON.stringify({
      dueDate: changes.dueDate,
      dueTime: changes.dueTime,
      completed: changes.completed,
      category: changes.category,
      color: changes.color,
      priority: changes.priority,
      notes: changes.notes,
    }),
  })
  const index = state.tasks.findIndex((item) => item.id === updated.id)
  if (index >= 0) state.tasks.splice(index, 1, updated)
  return updated
}

export function useTaskHub() {
  return { state, initialize, refreshTasks, createTask, updateTask }
}
