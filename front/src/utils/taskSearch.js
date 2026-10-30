export function normalizeSearchText(value) {
  return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('pt-BR')
}

export function createSearchFilters() {
  return { status: 'all', category: '', priority: '', period: 'all', startDate: '', endDate: '' }
}

export function invalidDateRange(filters) {
  return filters.period === 'range' && Boolean(filters.startDate && filters.endDate && filters.startDate > filters.endDate)
}

function compareOptional(first, second) {
  if (!first && !second) return 0
  if (!first) return 1
  if (!second) return -1
  return first.localeCompare(second)
}

export function compareTasks(first, second) {
  return Number(first.completed) - Number(second.completed)
    || compareOptional(first.dueDate, second.dueDate)
    || compareOptional(first.dueTime, second.dueTime)
    || first.title.localeCompare(second.title, 'pt-BR')
    || first.id.localeCompare(second.id)
}

export function filterTasks(tasks, query, filters) {
  if (invalidDateRange(filters)) return []
  const needle = normalizeSearchText(query.trim())
  return tasks.filter((task) => {
    if (needle && ![task.title, task.category, task.notes].some((field) => normalizeSearchText(field || '').includes(needle))) return false
    if (filters.status === 'pending' && task.completed) return false
    if (filters.status === 'completed' && !task.completed) return false
    if (filters.category && task.category !== filters.category) return false
    if (filters.priority && task.priority !== filters.priority) return false
    if (filters.period === 'undated' && task.dueDate) return false
    if (filters.period === 'range') {
      if (!task.dueDate) return false
      if (filters.startDate && task.dueDate < filters.startDate) return false
      if (filters.endDate && task.dueDate > filters.endDate) return false
    }
    return true
  }).sort(compareTasks)
}
