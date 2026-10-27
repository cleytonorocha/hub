export const DEFAULT_TASK_PREFERENCES = Object.freeze({
  category: 'Geral',
  color: '#A986FF',
  priority: 'NORMAL',
})

export const TASK_CATEGORY_SUGGESTIONS = Object.freeze([
  'Geral',
  'Pessoal',
  'Trabalho',
  'Estudo',
  'Saúde',
  'Casa',
  'Finanças',
  'Família',
  'Compras',
  'Lazer',
  'Projetos',
  'Rotina',
])

export const CUSTOM_TASK_CATEGORY_VALUE = '__custom_task_category__'

const WEEKLY_SUMMARY_KEY = 'hub.overview.weeklySummary'
const VIDEO_DOWNLOADER_CARD_KEY = 'hub.overview.videoDownloaderCard'
const TASK_DEFAULTS_KEY = 'hub.tasks.defaults'
const TASK_PRIORITIES = new Set(['LOW', 'NORMAL', 'HIGH'])
const HEX_COLOR = /^#[0-9A-Fa-f]{6}$/

export function readWeeklySummaryPreference() {
  try { return window.localStorage.getItem(WEEKLY_SUMMARY_KEY) !== 'false' } catch { return true }
}

export function writeWeeklySummaryPreference(visible) {
  try { window.localStorage.setItem(WEEKLY_SUMMARY_KEY, String(Boolean(visible))) } catch { /* Keep the current session preference. */ }
}

export function readVideoDownloaderCardPreference() {
  try { return window.localStorage.getItem(VIDEO_DOWNLOADER_CARD_KEY) !== 'false' } catch { return true }
}

export function writeVideoDownloaderCardPreference(visible) {
  try { window.localStorage.setItem(VIDEO_DOWNLOADER_CARD_KEY, String(Boolean(visible))) } catch { /* Keep the current session preference. */ }
}

export function normalizeTaskPreferences(value = {}) {
  if (!value || typeof value !== 'object') value = {}
  const category = typeof value.category === 'string' ? value.category.trim() : ''
  return {
    category: category && category.length <= 64 ? category : DEFAULT_TASK_PREFERENCES.category,
    color: typeof value.color === 'string' && HEX_COLOR.test(value.color) ? value.color : DEFAULT_TASK_PREFERENCES.color,
    priority: TASK_PRIORITIES.has(value.priority) ? value.priority : DEFAULT_TASK_PREFERENCES.priority,
  }
}

export function readTaskPreferences() {
  try {
    const stored = window.localStorage.getItem(TASK_DEFAULTS_KEY)
    return stored ? normalizeTaskPreferences(JSON.parse(stored)) : { ...DEFAULT_TASK_PREFERENCES }
  } catch {
    return { ...DEFAULT_TASK_PREFERENCES }
  }
}

export function writeTaskPreferences(value) {
  const normalized = normalizeTaskPreferences(value)
  try { window.localStorage.setItem(TASK_DEFAULTS_KEY, JSON.stringify(normalized)) } catch { /* Keep the current session preference. */ }
  return normalized
}
