import { reactive } from 'vue'

const PREF_KEYS = Object.freeze({
  weeklySummaryVisible: 'hub.overview.weeklySummary',
  videoDownloaderCardVisible: 'hub.overview.videoDownloaderCard',
  sidebarPinned: 'sidebar-pinned',
  taskDefaults: 'hub.tasks.defaults',
  calendarPresentations: 'hub.calendar.presentations',
})
const DEFAULTS = Object.freeze({
  weeklySummaryVisible: true,
  videoDownloaderCardVisible: true,
  sidebarPinned: false,
  taskDefaults: Object.freeze({ category: 'Geral', color: '#A986FF', priority: 'NORMAL' }),
  calendarPresentations: Object.freeze({ day: '', week: '', month: '', year: '' }),
})

export const userPreferencesState = reactive({
  document: structuredClone(DEFAULTS), serverDocument: structuredClone(DEFAULTS), revision: 0,
  etag: '"prefs-0"', initialized: false, ownerId: null, ready: false, saving: false, pending: false, error: '',
})

let loadedFor = null
let loadPromise = null
let saveQueue = Promise.resolve()
let dirty = {}
let legacyCaptureOwner = null
let legacyCapture = null

function copy(value) { return JSON.parse(JSON.stringify(value)) }
function localKey(ownerId, key) { return `hub:${ownerId}:${key}` }

function localDocument(ownerId) {
  const document = copy(DEFAULTS)
  try {
    const storage = window.localStorage
    const weekly = storage.getItem(localKey(ownerId, PREF_KEYS.weeklySummaryVisible))
    const downloader = storage.getItem(localKey(ownerId, PREF_KEYS.videoDownloaderCardVisible))
    const sidebar = storage.getItem(localKey(ownerId, PREF_KEYS.sidebarPinned))
    document.weeklySummaryVisible = weekly !== 'false'
    document.videoDownloaderCardVisible = downloader !== 'false'
    document.sidebarPinned = sidebar === 'true'
    const task = storage.getItem(localKey(ownerId, PREF_KEYS.taskDefaults))
    const calendar = storage.getItem(localKey(ownerId, PREF_KEYS.calendarPresentations))
    if (task) {
      const value = JSON.parse(task)
      if (value && typeof value === 'object' && !Array.isArray(value)) {
        if (typeof value.category === 'string' && value.category.trim() && value.category.trim().length <= 64) document.taskDefaults.category = value.category.trim()
        if (typeof value.color === 'string' && /^#[\da-f]{6}$/i.test(value.color)) document.taskDefaults.color = value.color.toUpperCase()
        if (['LOW', 'NORMAL', 'HIGH'].includes(value.priority)) document.taskDefaults.priority = value.priority
      }
    }
    if (calendar) {
      const value = JSON.parse(calendar)
      const allowed = { day: ['', 'timeline', 'ruler', 'periods', 'table'], week: ['', 'columns', 'list', 'workweek', 'focus'], month: ['', 'grid', 'weeks', 'list', 'focus'], year: ['', 'matrix', 'quarters', 'list', 'focus'] }
      if (value && typeof value === 'object' && !Array.isArray(value)) {
        for (const [mode, choices] of Object.entries(allowed)) if (choices.includes(value[mode])) document.calendarPresentations[mode] = value[mode]
      }
    }
  } catch { /* Keep validated defaults for inaccessible or malformed local values. */ }
  return document
}

function captureLegacy(ownerId) {
  if (legacyCaptureOwner !== ownerId) {
    legacyCaptureOwner = ownerId
    legacyCapture = localDocument(ownerId)
  }
  return copy(legacyCapture)
}

function removeLegacy(ownerId) {
  try {
    const storage = window.localStorage
    for (const key of Object.values(PREF_KEYS)) storage.removeItem(localKey(ownerId, key))
  } catch { /* Keep the legacy values if storage cannot be updated. */ }
}

async function readJson(response) {
  const text = await response.text()
  let body = {}
  try { body = text ? JSON.parse(text) : {} } catch { /* handled below */ }
  if (!response.ok) {
    const error = new Error(body.detail || body.message || `HTTP ${response.status}`)
    error.status = response.status
    error.body = body
    error.etag = response.headers.get('ETag')
    throw error
  }
  return { body, etag: response.headers.get('ETag') }
}

async function csrf() {
  const { body } = await readJson(await fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' }))
  if (!body.headerName || !body.token) throw new Error('Token CSRF inválido.')
  return { [body.headerName]: body.token }
}

function acceptSnapshot(snapshot, etag, ownerId = userPreferencesState.ownerId) {
  userPreferencesState.serverDocument = copy(snapshot.preferences)
  userPreferencesState.document = { ...copy(snapshot.preferences), ...mergeDirty(copy(snapshot.preferences), dirty) }
  userPreferencesState.revision = snapshot.revision
  userPreferencesState.etag = etag || `"prefs-${snapshot.revision}"`
  userPreferencesState.initialized = snapshot.initialized
  userPreferencesState.ownerId = ownerId
  userPreferencesState.ready = true
  userPreferencesState.error = ''
  userPreferencesState.pending = Object.keys(dirty).length > 0 || !snapshot.initialized
}

async function fetchSnapshot() {
  return readJson(await fetch('/api/user-preferences', { credentials: 'same-origin', cache: 'no-store' }))
}

async function initializeServer(ownerId, document) {
  const headers = await csrf()
  const { body, etag } = await readJson(await fetch('/api/user-preferences/initialize', {
    method: 'POST', credentials: 'same-origin', headers: { ...headers, 'Content-Type': 'application/json' },
    body: JSON.stringify(document),
  }))
  acceptSnapshot(body, etag, ownerId)
  loadedFor = ownerId
  return body
}

export async function initializeUserPreferences(ownerId) {
  if (!ownerId) return
  if (loadedFor === ownerId) {
    if (Object.keys(dirty).length || userPreferencesState.pending) {
      saveQueue = saveQueue.catch(() => null).then(() => flushDirty())
      return saveQueue
    }
    return
  }
  if (loadPromise) return loadPromise
  if (userPreferencesState.ownerId && userPreferencesState.ownerId !== ownerId) {
    dirty = {}
    saveQueue = Promise.resolve()
    userPreferencesState.document = copy(DEFAULTS)
    userPreferencesState.serverDocument = copy(DEFAULTS)
    userPreferencesState.revision = 0
    userPreferencesState.etag = '"prefs-0"'
    userPreferencesState.initialized = false
    userPreferencesState.ready = false
    userPreferencesState.pending = false
    legacyCaptureOwner = null
    legacyCapture = null
  }
  loadPromise = (async () => {
    userPreferencesState.ownerId = ownerId
    userPreferencesState.ready = false
    userPreferencesState.pending = true
    try {
      const { body, etag } = await fetchSnapshot()
      if (body.initialized) {
        acceptSnapshot(body, etag, ownerId)
        userPreferencesState.pending = Object.keys(dirty).length > 0
        if (!userPreferencesState.pending) removeLegacy(ownerId)
      }
      else {
        const legacy = captureLegacy(ownerId)
        userPreferencesState.serverDocument = copy(body.preferences)
        userPreferencesState.document = mergeDirty(legacy, dirty)
        userPreferencesState.revision = body.revision
        userPreferencesState.etag = etag || `"prefs-${body.revision}"`
        userPreferencesState.initialized = false
        userPreferencesState.ready = true
        await initializeServer(ownerId, legacy)
        if (!Object.keys(dirty).length) removeLegacy(ownerId)
      }
      loadedFor = ownerId
    } catch (error) {
      userPreferencesState.error = error.message
      const legacy = captureLegacy(ownerId)
      userPreferencesState.document = mergeDirty(legacy, dirty)
      userPreferencesState.ready = true
      userPreferencesState.ownerId = ownerId
      userPreferencesState.pending = true
      loadedFor = null
    }
  })()
  try { await loadPromise } finally { loadPromise = null }
}

function mergeDirty(base, changes) {
  const merged = copy(base)
  for (const [path, value] of Object.entries(changes)) setPath(merged, path, value)
  return merged
}

function setPath(document, path, value) {
  const parts = path.split('.')
  let target = document
  for (const part of parts.slice(0, -1)) target = target[part]
  target[parts.at(-1)] = copy(value)
}

export function getUserPreference(path, fallback) {
  const value = path.split('.').reduce((current, key) => current?.[key], userPreferencesState.document)
  return value === undefined ? fallback : copy(value)
}

export function updateUserPreference(path, value) {
  if (!userPreferencesState.ownerId) return
  setPath(userPreferencesState.document, path, value)
  dirty[path] = copy(value)
  userPreferencesState.pending = true
  saveQueue = saveQueue.catch(() => null).then(() => flushDirty())
}

async function flushDirty() {
  const ownerId = userPreferencesState.ownerId
  if (!ownerId || !Object.keys(dirty).length) return
  userPreferencesState.saving = true
  userPreferencesState.pending = true
  try {
    if (!userPreferencesState.initialized) await initializeServer(ownerId, captureLegacy(ownerId))
    const sent = copy(dirty)
    const body = mergeDirty(userPreferencesState.serverDocument, sent)
    const headers = await csrf()
    let response = await fetch('/api/user-preferences', {
      method: 'PUT', credentials: 'same-origin', headers: { ...headers, 'Content-Type': 'application/json', 'If-Match': userPreferencesState.etag },
      body: JSON.stringify(body),
    })
    let result
    try { result = await readJson(response) }
    catch (error) {
      if (error.status !== 409) throw error
      const latest = error.body
      const latestEtag = error.etag || `"prefs-${latest.revision}"`
      const retryBody = mergeDirty(latest.preferences, dirty)
      const retryHeaders = await csrf()
      response = await fetch('/api/user-preferences', {
        method: 'PUT', credentials: 'same-origin',
        headers: { ...retryHeaders, 'Content-Type': 'application/json', 'If-Match': latestEtag },
        body: JSON.stringify(retryBody),
      })
      result = await readJson(response)
      if (response.status === 409) throw new Error('As preferências mudaram em outro dispositivo. Reabra a página para sincronizar.')
    }
    if (!result.body.initialized || result.body.revision <= userPreferencesState.revision) {
      throw new Error('O servidor não confirmou a nova revisão das preferências.')
    }
    userPreferencesState.serverDocument = copy(result.body.preferences)
    userPreferencesState.revision = result.body.revision
    userPreferencesState.etag = result.etag || `"prefs-${result.body.revision}"`
    userPreferencesState.initialized = true
    for (const [path, value] of Object.entries(sent)) {
      if (JSON.stringify(path.split('.').reduce((item, key) => item?.[key], userPreferencesState.document)) === JSON.stringify(value)) delete dirty[path]
    }
    userPreferencesState.document = mergeDirty(userPreferencesState.serverDocument, dirty)
    userPreferencesState.error = ''
    userPreferencesState.pending = Object.keys(dirty).length > 0
    if (!userPreferencesState.pending) removeLegacy(ownerId)
  } catch (error) {
    userPreferencesState.error = error.message
    userPreferencesState.pending = true
  } finally {
    userPreferencesState.saving = false
  }
}

export async function retryUserPreferences() {
  const ownerId = userPreferencesState.ownerId
  if (!ownerId) return
  userPreferencesState.error = ''
  if (loadedFor !== ownerId) return initializeUserPreferences(ownerId)
  if (Object.keys(dirty).length || userPreferencesState.pending) {
    saveQueue = saveQueue.catch(() => null).then(() => flushDirty())
    return saveQueue
  }
}

export function resetUserPreferences() {
  loadedFor = null
  loadPromise = null
  saveQueue = Promise.resolve()
  dirty = {}
  legacyCaptureOwner = null
  legacyCapture = null
  Object.assign(userPreferencesState, {
    document: copy(DEFAULTS), serverDocument: copy(DEFAULTS), revision: 0, etag: '"prefs-0"',
    initialized: false, ownerId: null, ready: false, saving: false, pending: false, error: '',
  })
}
