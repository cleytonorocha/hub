export const CALENDAR_MODES = [
  { id: 'day', label: 'Dia' },
  { id: 'week', label: 'Semana' },
  { id: 'month', label: 'Mês' },
  { id: 'year', label: 'Ano' },
]

export const CALENDAR_PRESENTATIONS = Object.freeze({
  day: [
    { id: 'timeline', label: 'Grade horária' },
    { id: 'ruler', label: 'Régua 24h' },
    { id: 'periods', label: 'Períodos do dia' },
    { id: 'table', label: 'Tabela de horas' },
  ],
  week: [
    { id: 'columns', label: 'Colunas' },
    { id: 'list', label: 'Lista por dia' },
    { id: 'workweek', label: 'Dias úteis' },
    { id: 'focus', label: 'Dia em foco' },
  ],
  month: [
    { id: 'grid', label: 'Grade mensal' },
    { id: 'weeks', label: 'Lista de semanas' },
    { id: 'list', label: 'Lista cronológica' },
    { id: 'focus', label: 'Semana em foco' },
  ],
  year: [
    { id: 'matrix', label: '12 meses' },
    { id: 'quarters', label: 'Trimestres' },
    { id: 'list', label: 'Lista cronológica' },
    { id: 'focus', label: 'Mês em foco' },
  ],
})

export const DEFAULT_CALENDAR_PRESENTATIONS = Object.freeze({
  day: 'timeline',
  week: 'columns',
  month: 'grid',
  year: 'matrix',
})

const CALENDAR_PRESENTATIONS_KEY = 'hub.calendar.presentations'

export function normalizeCalendarPresentations(value = {}) {
  if (!value || typeof value !== 'object') value = {}
  return Object.fromEntries(CALENDAR_MODES.map(({ id }) => {
    const choices = CALENDAR_PRESENTATIONS[id]
    const candidate = value[id]
    return [id, candidate === '' || choices.some(({ id: choiceId }) => choiceId === candidate) ? candidate : '']
  }))
}

export function readCalendarPresentationPreferences() {
  try {
    const stored = window.localStorage.getItem(CALENDAR_PRESENTATIONS_KEY)
    return stored ? normalizeCalendarPresentations(JSON.parse(stored)) : normalizeCalendarPresentations()
  } catch {
    return normalizeCalendarPresentations()
  }
}

export function writeCalendarPresentationPreferences(value) {
  const normalized = normalizeCalendarPresentations(value)
  try { window.localStorage.setItem(CALENDAR_PRESENTATIONS_KEY, JSON.stringify(normalized)) } catch { /* Keep the current session preference. */ }
  return normalized
}
