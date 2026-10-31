import test from 'node:test'
import assert from 'node:assert/strict'
import {
  VIDEO_PLATFORMS, VIDEO_QUALITIES, formatVideoSize, isActiveVideoDownload, isTerminalVideoDownload,
  normalizeVideoLink, validateVideoLink, videoFileUrl, videoQualityMessage, videoStatusMessage,
} from '../src/utils/videoDownloads.js'

test('oferece somente YouTube e Instagram e as três preferências aprovadas', () => {
  assert.deepEqual(VIDEO_PLATFORMS.map(({ id }) => id), ['YOUTUBE', 'INSTAGRAM'])
  assert.deepEqual(VIDEO_QUALITIES, [480, 720, 1080])
})

test('normaliza HTTPS e canonicaliza links individuais sem parâmetros de coleção', () => {
  assert.equal(normalizeVideoLink('  youtube.com/watch?v=abcdefghijk  '), 'https://youtube.com/watch?v=abcdefghijk')
  assert.equal(normalizeVideoLink('http://youtu.be/abcdefghijk'), 'https://youtu.be/abcdefghijk')
  for (const raw of [
    'https://www.youtube.com/watch?v=abcdefghijk&list=ignored&t=20#fragment',
    'youtu.be/abcdefghijk?si=sharing',
    'https://m.youtube.com/shorts/abcdefghijk',
    'https://www.youtube-nocookie.com/embed/abcdefghijk',
  ]) {
    const result = validateVideoLink('YOUTUBE', raw)
    assert.equal(result.state, 'valid', raw)
    assert.equal(result.url, 'https://www.youtube.com/watch?v=abcdefghijk', raw)
  }
  assert.equal(validateVideoLink('YOUTUBE', 'https://youtu.be/not-a-video').url, 'https://www.youtube.com/watch?v=not-a-video')
  assert.equal(validateVideoLink('INSTAGRAM', 'instagram.com/reels/Ab_cd-123/?igsh=sharing').url, 'https://www.instagram.com/reel/Ab_cd-123/')
  assert.equal(validateVideoLink('INSTAGRAM', 'https://www.instagram.com/p/ABC/').url, 'https://www.instagram.com/p/ABC/')
})

test('rejeita hosts enganosos, credenciais, portas, esquemas e URLs não individuais', () => {
  for (const raw of [
    'https://youtube.com.evil.example/watch?v=abcdefghijk',
    'https://evil.youtube.com/watch?v=abcdefghijk',
    'https://youtube.com@evil.example/watch?v=abcdefghijk',
    'https://user:password@youtube.com/watch?v=abcdefghijk',
    'https://youtube.com:8080/watch?v=abcdefghijk',
    'file:///etc/passwd',
    'javascript:alert(1)',
    'https://127.0.0.1/watch?v=abcdefghijk',
    'https://youtube.com/playlist?list=abcdefghijk',
    'https://youtube.com/@creator',
    'https://youtu.be/not-a-vide',
    'https://youtube.com/watch?v=abcdefghijk&v=12345678901',
    'https://youtube.com/watch?v=abcdefghijk&padding=' + 'x'.repeat(2048),
  ]) assert.equal(validateVideoLink('YOUTUBE', raw).state, 'invalid', raw)
  for (const raw of ['https://instagram.com/creator/', 'https://evil.instagram.com/reel/ABC/', 'https://instagram.com/stories/creator/123/']) {
    assert.equal(validateVideoLink('INSTAGRAM', raw).state, 'invalid', raw)
  }
  assert.equal(validateVideoLink('INSTAGRAM', 'https://youtu.be/abcdefghijk').state, 'invalid')
  assert.equal(validateVideoLink('', 'https://youtu.be/abcdefghijk').state, 'incomplete')
  assert.equal(validateVideoLink('YOUTUBE', '').state, 'empty')
})

test('informa resolução real inferior ou fallback original sem prometer conversão', () => {
  assert.match(videoQualityMessage({ fileName: 'video.mp4', requestedQuality: 1080, effectiveQuality: 480, fallbackToOriginal: false }), /480p/)
  const fallback = videoQualityMessage({ fileName: 'video.mp4', requestedQuality: 720, effectiveQuality: 1080, fallbackToOriginal: true })
  assert.match(fallback, /720p indisponível/)
  assert.match(fallback, /original em 1080p, sem conversão/)
  assert.match(videoQualityMessage({ fileName: 'video.mp4', requestedQuality: 720, effectiveQuality: null, fallbackToOriginal: false }), /Resolução não informada/)
})

test('distingue preparo, entrega ao navegador e estados finais sem alegar arquivo salvo', () => {
  assert.equal(isActiveVideoDownload('READY'), true)
  assert.equal(isActiveVideoDownload('TRANSFERRING'), true)
  assert.equal(isActiveVideoDownload('FAILED'), false)
  assert.equal(isTerminalVideoDownload('RUNNING'), false)
  for (const state of ['COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED']) assert.equal(isTerminalVideoDownload(state), true)
  assert.match(videoStatusMessage({ state: 'RUNNING' }), /Preparando/)
  assert.match(videoStatusMessage({ state: 'READY' }, false), /pronto/)
  for (const state of ['READY', 'TRANSFERRING', 'COMPLETED']) {
    const message = videoStatusMessage({ state }, true)
    assert.match(message, /enviado ao navegador/)
    assert.doesNotMatch(message, /concluído|salvo/i)
  }
  assert.match(videoStatusMessage({ state: 'EXPIRED' }), /expirou/)
  assert.equal(videoStatusMessage({ state: 'FAILED', error: { message: 'Limite de tamanho excedido.' } }), 'Limite de tamanho excedido.')
})

test('produz somente attachment interno e mostra o tamanho sem carregar o arquivo', () => {
  assert.equal(videoFileUrl('abc-123'), '/api/video-downloads/abc-123/file')
  for (const id of ['../etc/passwd', 'https://evil.example/file', '', 'a?query=1']) assert.throws(() => videoFileUrl(id))
  assert.equal(formatVideoSize(1024 ** 3), '1 GiB')
  assert.equal(formatVideoSize(5 * 1024 ** 2), '5 MiB')
  assert.equal(formatVideoSize(null), '')
})
