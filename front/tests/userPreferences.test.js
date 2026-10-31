import test from 'node:test'
import assert from 'node:assert/strict'

const owner = 'preference-owner'
const keys = {
  weekly: `hub:${owner}:hub.overview.weeklySummary`,
  downloader: `hub:${owner}:hub.overview.videoDownloaderCard`,
  sidebar: `hub:${owner}:sidebar-pinned`,
  tasks: `hub:${owner}:hub.tasks.defaults`,
  calendar: `hub:${owner}:hub.calendar.presentations`,
}

function jsonResponse(body, status = 200, etag = '"prefs-0"') {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ETag: etag } })
}

test('legacy fallback survives failed initialization and is removed only after a clean retry succeeds', async () => {
  const values = new Map([
    [keys.weekly, 'false'], [keys.downloader, 'true'], [keys.sidebar, 'true'],
    [keys.tasks, JSON.stringify({ category: 'Pessoal', color: '#123456', priority: 'HIGH' })],
    [keys.calendar, JSON.stringify({ day: 'table', week: 'list', month: 'grid', year: 'quarters' })],
  ])
  let writes = 0
  const removals = []
  globalThis.window = { localStorage: {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => { writes += 1; values.set(key, value) },
    removeItem: (key) => { removals.push(key); values.delete(key) },
  } }

  let getCount = 0
  let initializationBody = null
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/user-preferences' && options.method !== 'PUT') {
      getCount += 1
      if (getCount === 1) return jsonResponse({ detail: 'temporarily unavailable' }, 503)
      return jsonResponse({ preferences: {}, revision: 0, initialized: false })
    }
    if (url === '/api/auth/csrf') return jsonResponse({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' })
    if (url === '/api/user-preferences/initialize') {
      initializationBody = JSON.parse(options.body)
      return jsonResponse({ preferences: initializationBody, revision: 0, initialized: true })
    }
    throw new Error(`Unexpected request: ${options.method || 'GET'} ${url}`)
  }

  const preferences = await import('../src/services/userPreferences.js')
  preferences.resetUserPreferences()
  await preferences.initializeUserPreferences(owner)
  assert.equal(preferences.userPreferencesState.document.weeklySummaryVisible, false)
  assert.equal(preferences.userPreferencesState.document.sidebarPinned, true)
  assert.equal(preferences.userPreferencesState.pending, true)
  assert.ok(preferences.userPreferencesState.error)
  assert.equal(values.get(keys.weekly), 'false')
  assert.equal(writes, 0)
  assert.deepEqual(removals, [])

  await preferences.retryUserPreferences()
  assert.equal(getCount, 2)
  assert.equal(initializationBody.taskDefaults.category, 'Pessoal')
  assert.equal(initializationBody.calendarPresentations.year, 'quarters')
  assert.equal(preferences.userPreferencesState.initialized, true)
  assert.equal(preferences.userPreferencesState.pending, false)
  assert.equal(preferences.userPreferencesState.error, '')
  assert.equal(writes, 0)
  assert.deepEqual(removals.sort(), Object.values(keys).sort())
  assert.equal(values.size, 0)

  preferences.resetUserPreferences()
  delete globalThis.window
  delete globalThis.fetch
})
