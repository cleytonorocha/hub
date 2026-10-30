export function installSecureFetch() {
  if (window.__hubSecureFetchInstalled) return
  window.__hubSecureFetchInstalled = true
  const original = window.fetch.bind(window)
  window.fetch = async (input, init = {}) => {
    const method = String(init.method || (input instanceof Request ? input.method : 'GET')).toUpperCase()
    const unsafe = !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)
    const headers = new Headers(init.headers || (input instanceof Request ? input.headers : undefined))
    if (unsafe && !headers.has('X-XSRF-TOKEN')) {
      let token = readCookie('XSRF-TOKEN')
      if (!token) {
        await original('/api/auth/csrf', { credentials: 'same-origin' })
        token = readCookie('XSRF-TOKEN')
      }
      if (token) headers.set('X-XSRF-TOKEN', token)
    }
    const response = await original(input, { ...init, headers, credentials: 'same-origin' })
    if (response.status === 401) window.dispatchEvent(new Event('hub:unauthorized'))
    return response
  }
}

function readCookie(name) {
  const value = document.cookie.split('; ').find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1)
  try { return value ? decodeURIComponent(value) : '' } catch { return value || '' }
}
