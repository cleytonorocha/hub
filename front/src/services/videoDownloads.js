import { csrfHeader } from './auth'
import { videoFileUrl } from '../utils/videoDownloads'

let lastCsrfHeader

export class VideoDownloadError extends Error {
  constructor(message, status = 0, code = '') {
    super(message)
    this.name = 'VideoDownloadError'
    this.status = status
    this.code = code
  }
}

async function readResponse(response) {
  if (response.status === 204) return null
  let payload = {}
  try { payload = await response.json() } catch { /* Proxy responses may not be JSON. */ }
  if (!response.ok) {
    const fallback = response.status === 401 ? 'Sua sessão expirou. Entre novamente.'
      : response.status === 403 ? 'Não foi possível autorizar este download. Atualize a página e tente novamente.'
        : response.status === 429 ? 'Servidor ocupado. Tente novamente em instantes.'
          : response.status === 404 ? 'Esta preparação não está mais disponível. Prepare o vídeo novamente.'
            : response.status === 410 ? 'Este arquivo expirou. Prepare o vídeo novamente.'
              : response.status === 503 ? 'O serviço de download está indisponível. Tente novamente mais tarde.'
                : 'Não foi possível concluir a solicitação. Tente novamente.'
    throw new VideoDownloadError(payload.message || payload.detail || fallback, response.status, payload.code || '')
  }
  return payload
}

async function mutate(path, method, body, options = {}) {
  const tokenHeader = options.keepalive && lastCsrfHeader ? lastCsrfHeader : await csrfHeader()
  lastCsrfHeader = tokenHeader
  const [headerName, token] = tokenHeader
  return readResponse(await fetch(path, {
    method,
    credentials: 'same-origin',
    cache: 'no-store',
    keepalive: Boolean(options.keepalive),
    headers: { 'Content-Type': 'application/json', [headerName]: token },
    ...(body ? { body: JSON.stringify(body) } : {}),
  }))
}

function jobPath(id) { return '/api/video-downloads/' + encodeURIComponent(id) }

function validJob(payload, expectedId) {
  try { videoFileUrl(payload?.id) } catch { throw new VideoDownloadError('O servidor retornou uma preparação inválida. Tente novamente.', 502) }
  if ((expectedId && payload.id !== expectedId) || !['RUNNING', 'READY', 'TRANSFERRING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(payload.state)) {
    throw new VideoDownloadError('O servidor retornou uma preparação inválida. Tente novamente.', 502)
  }
  return payload
}

export async function createVideoDownload(request) { return validJob(await mutate('/api/video-downloads', 'POST', request)) }
export function cancelVideoDownload(id, options) { return mutate(jobPath(id), 'DELETE', null, options) }
export async function getVideoDownload(id, signal) {
  return validJob(await readResponse(await fetch(jobPath(id), { signal, credentials: 'same-origin', cache: 'no-store' })), id)
}
