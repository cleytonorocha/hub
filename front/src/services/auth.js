import { reactive } from 'vue'
import { resetTaskHub } from './taskHub'
import { resetTaskSearchState } from './taskSearchState'
import { resetUserPreferences } from './userPreferences'

export const authState = reactive({ user: null, setupRequired: null, initialized: false, loading: false, error: '' })

async function readJson(response) {
  if (response.status === 204) return null
  const body = await response.text()
  let payload = {}
  try { payload = body ? JSON.parse(body) : {} } catch { payload = {} }
  if (!response.ok) throw new Error(payload.detail || payload.message || payload.title || `HTTP ${response.status}`)
  return payload
}

export async function csrfHeader() {
  const tokenResponse = await fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' })
  const { headerName, token } = await readJson(tokenResponse)
  if (typeof headerName !== 'string' || !headerName.trim() || typeof token !== 'string' || !token.trim()) {
    throw new Error('Não foi possível iniciar a autenticação. Tente novamente.')
  }
  return [headerName, token]
}

export async function initializeAuth(force = false) {
  if (authState.initialized && !force) return authState
  authState.loading = true
  try {
    const setup = await readJson(await fetch('/api/auth/setup-state'))
    authState.setupRequired = setup.setupRequired
    authState.user = null
    if (!setup.setupRequired) {
      try { authState.user = await readJson(await fetch('/api/auth/session')) } catch { authState.user = null }
    }
    authState.initialized = true
    return authState
  } finally { authState.loading = false }
}

export async function login(email, password) {
  authState.error = ''
  try {
    const [headerName, token] = await csrfHeader()
    authState.user = await readJson(await fetch('/api/auth/login', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', [headerName]: token },
      body: JSON.stringify({ email, password }),
    }))
    authState.setupRequired = false
    authState.initialized = true
    return authState.user
  } catch (error) {
    authState.error = error.message.includes('HTTP 401') ? 'E-mail ou senha inválidos.' : error.message
    throw error
  }
}

export async function setupFirstAdmin(account) {
  const [headerName, token] = await csrfHeader()
  const result = await readJson(await fetch('/api/auth/setup', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', [headerName]: token },
    body: JSON.stringify(account),
  }))
  authState.setupRequired = false
  authState.initialized = true
  return result
}

export async function logout() {
  try {
    const [headerName, token] = await csrfHeader()
    await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin', headers: { [headerName]: token } })
  } finally {
    expireSession()
  }
}

export function expireSession() {
  resetTaskHub()
  resetTaskSearchState()
  resetUserPreferences()
  authState.user = null
}

export function hasPermission(permission) { return Boolean(authState.user?.permissions?.includes(permission)) }
export function isAdministrator() {
  return authState.user?.profileName?.trim().toLocaleLowerCase('pt-BR') === 'administrador'
    && hasPermission('USER_MANAGE')
}
export function currentAccountKey() { return authState.user?.id || 'anonymous' }
export function namespacedKey(key) { return `hub:${currentAccountKey()}:${key}` }
