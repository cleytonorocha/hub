<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { cancelVideoDownload, createVideoDownload, getVideoDownload, VideoDownloadError } from '../services/videoDownloads'
import {
  VIDEO_PLATFORMS, VIDEO_QUALITIES, formatVideoSize, isActiveVideoDownload, isTerminalVideoDownload,
  normalizeVideoLink, validateVideoLink, videoFileUrl, videoQualityMessage, videoStatusMessage,
} from '../utils/videoDownloads'

const link = ref('')
const selectedPlatform = ref('')
const preferredQuality = ref(720)
const reduceFileSize = ref(false)
const platformField = ref(null)
const linkField = ref(null)
const qualityField = ref(null)
const job = ref(null)
const creating = ref(false)
const cancelling = ref(false)
const checking = ref(false)
const requestError = ref('')
const canRetryStatus = ref(false)
const frameSource = ref('about:blank')
const frameName = 'hub-video-download'
const handedOff = reactive(new Set())
let timer
let statusController
let generation = 0
let disposed = false

const linkStatus = computed(() => validateVideoLink(selectedPlatform.value, link.value))
const busy = computed(() => creating.value || cancelling.value || isActiveVideoDownload(job.value?.state))
const canCancel = computed(() => !creating.value && !cancelling.value && isActiveVideoDownload(job.value?.state))
const deliveryRequested = computed(() => Boolean(job.value && handedOff.has(job.value.id)))
const downloadHref = computed(() => job.value?.id ? videoFileUrl(job.value.id) : '')
const hasFile = computed(() => job.value?.fileName && ['READY', 'TRANSFERRING', 'COMPLETED'].includes(job.value.state))
const qualityMessage = computed(() => videoQualityMessage(job.value))
const transferPercent = computed(() => {
  const total = Number(job.value?.sizeBytes)
  const sent = Number(job.value?.bytesTransferred)
  if (!Number.isFinite(total) || total <= 0 || !Number.isFinite(sent)) return 0
  return Math.min(100, Math.floor((sent / total) * 100))
})
const submitLabel = computed(() => creating.value ? 'Solicitando…' : job.value?.state === 'RUNNING' && job.value.phase === 'COMPRESSING' ? 'Comprimindo vídeo…' : job.value?.state === 'TRANSFERRING' ? 'Enviando ao navegador…'
  : job.value?.state === 'READY' ? 'Arquivo pronto' : busy.value ? 'Preparando vídeo…' : 'Baixar vídeo')
const statusMessage = computed(() => creating.value ? 'Solicitando a preparação do vídeo…'
  : cancelling.value ? 'Cancelando o download…'
    : job.value?.state === 'RUNNING' && job.value.phase === 'COMPRESSING' ? 'Comprimindo o vídeo para tentar reduzir o tamanho…'
    : videoStatusMessage(job.value, deliveryRequested.value))
const expiryLabel = computed(() => {
  const date = new Date(job.value?.expiresAt || '')
  return Number.isFinite(date.getTime()) ? date.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) : ''
})

function stopPolling() {
  generation += 1
  clearTimeout(timer)
  timer = undefined
  statusController?.abort()
  statusController = undefined
}

function publicError(error) {
  if (error instanceof VideoDownloadError) return error.message
  return 'A conexão foi interrompida. Verifique sua conexão e tente novamente.'
}

async function handToBrowser(id) {
  if (handedOff.has(id)) return
  await nextTick()
  if (disposed || job.value?.id !== id || job.value.state !== 'READY') return
  handedOff.add(id)
  frameSource.value = videoFileUrl(id)
}

async function pollJob(id) {
  const currentGeneration = generation
  const controller = new AbortController()
  statusController = controller
  checking.value = true
  try {
    const status = await getVideoDownload(id, controller.signal)
    if (disposed || currentGeneration !== generation || job.value?.id !== id) return
    job.value = status
    requestError.value = ''
    canRetryStatus.value = false
    if (status.state === 'READY') await handToBrowser(id)
    if (!disposed && currentGeneration === generation && !isTerminalVideoDownload(status.state)) {
      timer = setTimeout(() => pollJob(id), 2000)
    }
  } catch (error) {
    if (disposed || currentGeneration !== generation || error.name === 'AbortError') return
    if (error.status === 404 || error.status === 410) {
      job.value = { ...job.value, state: 'EXPIRED' }
      requestError.value = ''
    } else if (error.status === 401 || error.status === 403) {
      job.value = { ...job.value, state: 'FAILED', error: { message: publicError(error) } }
      requestError.value = ''
    } else {
      requestError.value = publicError(error)
      canRetryStatus.value = true
    }
  } finally {
    if (currentGeneration === generation) {
      checking.value = false
      if (statusController === controller) statusController = undefined
    }
  }
}

async function startDownload() {
  if (busy.value) return
  if (!selectedPlatform.value) { platformField.value?.focus(); return }
  if (linkStatus.value.state !== 'valid') { linkField.value?.focus(); return }
  if (!VIDEO_QUALITIES.includes(preferredQuality.value)) { qualityField.value?.focus(); return }
  const request = { platform: selectedPlatform.value, url: linkStatus.value.url, preferredQuality: preferredQuality.value, reduceFileSize: reduceFileSize.value }
  link.value = request.url
  stopPolling()
  job.value = null
  requestError.value = ''
  canRetryStatus.value = false
  handedOff.clear()
  frameSource.value = 'about:blank'
  creating.value = true
  try {
    const created = await createVideoDownload(request)
    if (!created?.id || typeof created.id !== 'string') throw new VideoDownloadError('O servidor não confirmou a preparação. Tente novamente.')
    if (disposed) {
      cancelVideoDownload(created.id, { keepalive: true }).catch(() => {})
      return
    }
    job.value = { ...created, requestedQuality: request.preferredQuality }
    await pollJob(created.id)
  } catch (error) {
    if (!disposed) requestError.value = publicError(error)
  } finally {
    if (!disposed) creating.value = false
  }
}

async function cancelDownload() {
  if (!canCancel.value || !job.value?.id) return
  const id = job.value.id
  stopPolling()
  checking.value = false
  cancelling.value = true
  requestError.value = ''
  canRetryStatus.value = false
  try {
    await cancelVideoDownload(id)
    if (!disposed && job.value?.id === id) {
      job.value = { ...job.value, state: 'CANCELLED' }
      frameSource.value = 'about:blank'
    }
  } catch (error) {
    if (!disposed) {
      requestError.value = publicError(error)
      canRetryStatus.value = true
    }
  } finally { if (!disposed) cancelling.value = false }
}

function retryStatus() {
  if (!job.value?.id || checking.value || cancelling.value) return
  stopPolling()
  requestError.value = ''
  canRetryStatus.value = false
  pollJob(job.value.id)
}

function markManualDelivery() {
  if (job.value?.state === 'READY') handedOff.add(job.value.id)
}

function cancelAbandonedPreparation() {
  const current = job.value
  // A native attachment can start before the next status poll. Let it finish after navigation.
  if (current && (current.state === 'RUNNING' || (current.state === 'READY' && !handedOff.has(current.id)))) {
    cancelVideoDownload(current.id, { keepalive: true }).catch(() => {})
  }
}

onMounted(() => window.addEventListener('pagehide', cancelAbandonedPreparation))
onBeforeUnmount(() => {
  disposed = true
  stopPolling()
  cancelAbandonedPreparation()
  window.removeEventListener('pagehide', cancelAbandonedPreparation)
})
</script>

<template>
  <section class="content-view video-downloader-view" aria-labelledby="video-downloader-title">
    <header class="view-header">
      <p class="settings-eyebrow">FERRAMENTAS</p>
      <h1 id="video-downloader-title">Baixar vídeos</h1>
      <p>Cole um link do YouTube ou Instagram para baixar o vídeo.</p>
    </header>

    <section class="video-downloader-card" aria-labelledby="video-downloader-form-title">
      <div class="video-downloader-heading">
        <span class="video-downloader-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 3v12m-5-5 5 5 5-5M5 17v3h14v-3" /></svg></span>
        <div><h2 id="video-downloader-form-title">Download</h2><p>Um vídeo público por solicitação, com áudio quando disponível.</p></div>
      </div>

      <form class="video-downloader-form" novalidate @submit.prevent="startDownload">
        <div class="video-downloader-fields" :aria-busy="busy">
          <label class="video-downloader-field" for="video-platform">
            <span>Plataforma</span>
            <select id="video-platform" ref="platformField" v-model="selectedPlatform" :disabled="busy" required>
              <option value="">Selecione uma plataforma</option>
              <option v-for="platform in VIDEO_PLATFORMS" :key="platform.id" :value="platform.id">{{ platform.label }}</option>
            </select>
          </label>

          <label class="video-downloader-field" for="video-link">
            <span>Link do vídeo</span>
            <input id="video-link" ref="linkField" v-model.trim="link" type="url" inputmode="url" autocomplete="url" placeholder="youtube.com/watch?v=…" maxlength="2048" :disabled="busy" required :aria-invalid="linkStatus.state === 'invalid'" aria-describedby="video-link-hint video-link-status" @blur="link = normalizeVideoLink(link)">
            <small id="video-link-hint">O prefixo https:// é adicionado automaticamente. Use o link de um vídeo ou Reel individual.</small>
          </label>

          <p id="video-link-status" class="video-link-status" :class="'video-link-status-' + linkStatus.state">
            <span class="video-link-status-indicator" aria-hidden="true"></span>{{ linkStatus.message }}
          </p>

          <label class="video-downloader-field" for="video-quality">
            <span>Qualidade preferida</span>
            <select id="video-quality" ref="qualityField" v-model.number="preferredQuality" :disabled="busy" aria-describedby="video-quality-hint">
              <option v-for="quality in VIDEO_QUALITIES" :key="quality" :value="quality">{{ quality }}p</option>
            </select>
            <small id="video-quality-hint">Quando não houver uma versão nessa qualidade ou inferior, baixaremos a original. A proporção do vídeo será preservada.</small>
          </label>

          <label class="video-download-compress" for="video-reduce-size">
            <input id="video-reduce-size" v-model="reduceFileSize" type="checkbox" :disabled="busy" aria-describedby="video-reduce-size-hint">
            <span><strong>Reduzir o tamanho do arquivo</strong><small id="video-reduce-size-hint">Recodifica o vídeo para tentar diminuir o tamanho; isso pode reduzir a qualidade visual. Se a versão ficar maior, o arquivo original será mantido.</small></span>
          </label>
        </div>

        <div class="video-downloader-actions">
          <p>Arquivos de até 1 GiB. A preparação pode levar até 10 minutos.</p>
          <div class="video-download-buttons">
            <button v-if="canCancel || cancelling" class="task-secondary-button" type="button" :disabled="cancelling" @click="cancelDownload">{{ cancelling ? 'Cancelando…' : 'Cancelar' }}</button>
            <button class="task-primary-button" type="submit" :disabled="busy || linkStatus.state !== 'valid'">{{ submitLabel }}</button>
          </div>
        </div>
      </form>

      <div v-if="requestError" class="video-download-error" role="alert">
        <p>{{ requestError }}</p>
        <button v-if="canRetryStatus" type="button" class="task-secondary-button" :disabled="checking || cancelling" @click="retryStatus">{{ checking ? 'Consultando…' : 'Consultar preparação novamente' }}</button>
      </div>

      <p v-if="job?.state === 'FAILED'" class="video-download-error" role="alert">{{ statusMessage }}</p>
      <p v-else-if="statusMessage" class="video-download-status" role="status" aria-live="polite">{{ statusMessage }}</p>

      <section v-if="hasFile" class="video-download-result" aria-label="Arquivo preparado">
        <p :class="{ 'video-download-fallback': job.fallbackToOriginal }">{{ qualityMessage }}</p>
        <p v-if="job.reduceFileSize && job.sizeReduced" class="video-download-compression-note">Arquivo recodificado para reduzir o tamanho.</p>
        <p v-else-if="job.reduceFileSize && job.state !== 'RUNNING'" class="video-download-compression-note">A versão comprimida não ficou menor; o arquivo original foi mantido.</p>
        <div v-if="['TRANSFERRING', 'COMPLETED'].includes(job.state)" class="video-download-progress" role="status" aria-live="polite">
          <label for="video-download-progress">{{ job.state === 'COMPLETED' ? 'Download enviado ao navegador' : 'Baixando vídeo…' }}</label>
          <progress id="video-download-progress" :value="transferPercent" max="100" :aria-label="'Progresso do download: ' + transferPercent + '%'">{{ transferPercent }}%</progress>
          <span>{{ formatVideoSize(job.bytesTransferred) || '0 KiB' }} de {{ formatVideoSize(job.sizeBytes) }} · {{ transferPercent }}%</span>
        </div>
        <dl>
          <div><dt>Arquivo</dt><dd>{{ job.fileName }}</dd></div>
          <div><dt>Tamanho</dt><dd>{{ formatVideoSize(job.sizeBytes) || 'Não informado' }}</dd></div>
          <div v-if="job.width && job.height"><dt>Resolução</dt><dd>{{ job.width }} × {{ job.height }}</dd></div>
        </dl>
        <template v-if="job.state === 'READY' && !cancelling">
          <a class="task-secondary-button" :href="downloadHref" :target="frameName" @click="markManualDelivery">Baixar arquivo preparado</a>
          <p class="video-download-help">Se o download não iniciar automaticamente, use o link acima.<template v-if="expiryLabel"> Disponível até {{ expiryLabel }}.</template></p>
        </template>
      </section>

      <p v-if="linkStatus.state === 'valid' && !busy" class="video-download-source"><a :href="linkStatus.url" target="_blank" rel="noopener noreferrer">Abrir vídeo na plataforma <span aria-hidden="true">↗</span><span class="visually-hidden">, em uma nova aba</span></a></p>
      <iframe :name="frameName" :src="frameSource" title="Transferência do vídeo" class="video-download-frame" tabindex="-1" aria-hidden="true"></iframe>
    </section>
  </section>
</template>
