export const VIDEO_PLATFORMS = [
  { id: 'YOUTUBE', label: 'YouTube' },
  { id: 'INSTAGRAM', label: 'Instagram' },
]

export const VIDEO_QUALITIES = [480, 720, 1080]
const youtubeHosts = new Set(['youtube.com', 'www.youtube.com', 'm.youtube.com', 'music.youtube.com', 'youtube-nocookie.com', 'www.youtube-nocookie.com'])
const instagramHosts = new Set(['instagram.com', 'www.instagram.com'])
const activeStates = new Set(['RUNNING', 'READY', 'TRANSFERRING'])
const terminalStates = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'])

export function normalizeVideoLink(value) {
  const trimmed = String(value || '').trim()
  if (!trimmed) return ''
  if (/^https:\/\//i.test(trimmed)) return trimmed
  if (/^http:\/\//i.test(trimmed)) return 'https://' + trimmed.slice('http://'.length)
  if (/^[a-z][a-z\d+.-]*:/i.test(trimmed)) return trimmed
  return 'https://' + trimmed.replace(/^\/\//, '')
}

export function validateVideoLink(platformId, rawValue) {
  const value = normalizeVideoLink(rawValue)
  if (!value) return { state: 'empty', message: 'Cole o link de um vídeo público.' }
  const platform = VIDEO_PLATFORMS.find(({ id }) => id === platformId)
  if (!platform) return { state: 'incomplete', message: 'Escolha a plataforma do vídeo.' }
  const invalid = (message = 'Informe o link de um vídeo individual do ' + platform.label + '.') => ({ state: 'invalid', message })
  if (value.length > 2048) return invalid('O link deve ter no máximo 2.048 caracteres.')

  try {
    const parsed = new URL(value)
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.port) {
      return invalid('Use um link HTTPS, sem credenciais ou porta personalizada.')
    }
    let url
    if (platformId === 'YOUTUBE') {
      let videoId
      if (parsed.hostname === 'youtu.be') {
        videoId = parsed.pathname.match(/^\/([A-Za-z0-9_-]{11})\/?$/)?.[1]
      } else if (youtubeHosts.has(parsed.hostname)) {
        if (/^\/watch\/?$/.test(parsed.pathname) && parsed.searchParams.getAll('v').length === 1) {
          videoId = parsed.searchParams.get('v')
        } else {
          videoId = parsed.pathname.match(/^\/(?:shorts|embed|v)\/([A-Za-z0-9_-]{11})\/?$/)?.[1]
        }
      } else {
        return invalid('Este link não corresponde ao YouTube.')
      }
      if (!videoId || !/^[A-Za-z0-9_-]{11}$/.test(videoId)) return invalid()
      url = 'https://www.youtube.com/watch?v=' + videoId
    } else {
      if (!instagramHosts.has(parsed.hostname)) return invalid('Este link não corresponde ao Instagram.')
      const match = parsed.pathname.match(/^\/(reel|reels|p|tv)\/([A-Za-z0-9_-]+)\/?$/)
      if (!match) return invalid()
      url = 'https://www.instagram.com/' + (match[1] === 'reels' ? 'reel' : match[1]) + '/' + match[2] + '/'
    }
    return { state: 'valid', message: 'Link reconhecido para ' + platform.label + '.', url }
  } catch {
    return invalid('Digite um link válido começando com https://.')
  }
}

export function isActiveVideoDownload(state) { return activeStates.has(state) }
export function isTerminalVideoDownload(state) { return terminalStates.has(state) }

export function videoFileUrl(id) {
  if (typeof id !== 'string' || !/^[A-Za-z0-9-]{1,80}$/.test(id)) throw new Error('Identificador de download inválido.')
  return '/api/video-downloads/' + encodeURIComponent(id) + '/file'
}

export function videoQualityMessage(job) {
  if (!job?.fileName) return ''
  const resolution = Number.isInteger(job.effectiveQuality) && job.effectiveQuality > 0 ? job.effectiveQuality + 'p' : null
  if (job.fallbackToOriginal) {
    return resolution
      ? `${job.requestedQuality}p indisponível; será baixado o original em ${resolution}, sem conversão.`
      : 'A qualidade preferida não está disponível; será baixado o original, sem conversão. Resolução não informada.'
  }
  return resolution ? 'Vídeo preparado em ' + resolution + '.' : 'Vídeo preparado. Resolução não informada.'
}

export function videoStatusMessage(job, deliveryRequested = false) {
  switch (job?.state) {
    case 'RUNNING': return 'Preparando vídeo. Isso pode levar alguns minutos.'
    case 'READY': return deliveryRequested ? 'Download enviado ao navegador. Acompanhe na lista de downloads.' : 'Vídeo pronto para baixar.'
    case 'TRANSFERRING': return ''
    case 'COMPLETED': return 'Transferência concluída e enviada ao navegador.'
    case 'CANCELLED': return 'Download cancelado.'
    case 'EXPIRED': return 'Este arquivo expirou. Prepare o vídeo novamente.'
    case 'FAILED': return job.error?.message || 'Não foi possível preparar este vídeo. Confira o link e tente novamente.'
    default: return ''
  }
}

export function formatVideoSize(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return ''
  const unit = bytes >= 1024 ** 3 ? 'GiB' : bytes >= 1024 ** 2 ? 'MiB' : 'KiB'
  const divisor = unit === 'GiB' ? 1024 ** 3 : unit === 'MiB' ? 1024 ** 2 : 1024
  return new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 }).format(bytes / divisor) + ' ' + unit
}
