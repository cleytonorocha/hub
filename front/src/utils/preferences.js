import { namespacedKey } from '../services/auth'
import { getUserPreference, updateUserPreference, userPreferencesState } from '../services/userPreferences'

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
  if (userPreferencesState.ready) return getUserPreference('weeklySummaryVisible', true)
  try { return window.localStorage.getItem(namespacedKey(WEEKLY_SUMMARY_KEY)) !== 'false' } catch { return true }
}

export function writeWeeklySummaryPreference(visible) {
  updateUserPreference('weeklySummaryVisible', Boolean(visible))
}

export function readVideoDownloaderCardPreference() {
  if (userPreferencesState.ready) return getUserPreference('videoDownloaderCardVisible', true)
  try { return window.localStorage.getItem(namespacedKey(VIDEO_DOWNLOADER_CARD_KEY)) !== 'false' } catch { return true }
}

export function writeVideoDownloaderCardPreference(visible) {
  updateUserPreference('videoDownloaderCardVisible', Boolean(visible))
}

export function readSidebarPinnedPreference() {
  if (userPreferencesState.ready) return getUserPreference('sidebarPinned', true)
  try { return window.localStorage.getItem(namespacedKey('sidebar-pinned')) === 'true' } catch { return true }
}

export function writeSidebarPinnedPreference(pinned) {
  updateUserPreference('sidebarPinned', Boolean(pinned))
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
  if (userPreferencesState.ready) return normalizeTaskPreferences(getUserPreference('taskDefaults', DEFAULT_TASK_PREFERENCES))
  try {
    const stored = window.localStorage.getItem(namespacedKey(TASK_DEFAULTS_KEY))
    return stored ? normalizeTaskPreferences(JSON.parse(stored)) : { ...DEFAULT_TASK_PREFERENCES }
  } catch {
    return { ...DEFAULT_TASK_PREFERENCES }
  }
}

export function writeTaskPreferences(value) {
  const normalized = normalizeTaskPreferences(value)
  const current = getUserPreference('taskDefaults', DEFAULT_TASK_PREFERENCES)
  for (const key of ['category', 'color', 'priority']) {
    if (normalized[key] !== current[key]) updateUserPreference(`taskDefaults.${key}`, normalized[key])
  }
  return normalized
}
