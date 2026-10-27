<script setup>
import { computed, ref } from 'vue'

const link = ref('')
const selectedPlatform = ref('')

const platforms = [
  {
    id: 'youtube', label: 'YouTube', hosts: ['youtube.com', 'youtu.be'],
    guidance: 'Vídeos de outras pessoas não podem ser baixados como arquivo. Para assistir offline, use o recurso de download do YouTube Premium. Para vídeos próprios, abra o YouTube Studio.',
    helpUrl: 'https://support.google.com/youtube/answer/56100?hl=pt-BR',
  },
  {
    id: 'instagram', label: 'Instagram', hosts: ['instagram.com'],
    guidance: 'Reels podem ser baixados pelo próprio Instagram quando o criador permite. Abra o Reel e use Compartilhar > Baixar. Para seus próprios arquivos, solicite uma exportação na Central de Contas.',
    helpUrl: 'https://help.instagram.com/181231772500920',
  },
  {
    id: 'tiktok', label: 'TikTok', hosts: ['tiktok.com'],
    guidance: 'Abra o vídeo no TikTok e use Compartilhar > Salvar vídeo. A opção aparece somente quando o criador permite o download.',
    helpUrl: 'https://support.tiktok.com/pt_BR/using-tiktok/exploring-videos/video-downloads',
  },
  {
    id: 'facebook', label: 'Facebook', hosts: ['facebook.com', 'fb.watch'],
    guidance: 'Abra o vídeo no Facebook e use o menu de opções. As opções disponíveis dependem de quem publicou o vídeo e das permissões da publicação.',
    helpUrl: 'https://www.facebook.com/help/',
  },
  {
    id: 'x', label: 'X', hosts: ['x.com', 'twitter.com'],
    guidance: 'No X, vídeos podem ser baixados pelo menu do post quando o autor habilitou downloads e sua conta tem acesso ao recurso. Caso contrário, abra o post para assistir.',
    helpUrl: 'https://help.x.com/pt/using-x/x-videos',
  },
  {
    id: 'twitch', label: 'Twitch', hosts: ['twitch.tv'],
    guidance: 'A API oficial oferece download de clips com autorização e escopos de editor/canal. Abra o clip; para integrar downloads diretos nesta aplicação é necessário configurar OAuth da Twitch.',
    helpUrl: 'https://dev.twitch.tv/docs/api/reference/#get-clips-download',
  },
  {
    id: 'reddit', label: 'Reddit', hosts: ['reddit.com', 'redd.it'],
    guidance: 'Abra a publicação no Reddit. “Salvar” guarda o post na sua conta, não um arquivo local; use uma opção de download oferecida pelo próprio post quando estiver disponível.',
    helpUrl: 'https://support.reddithelp.com/hc/en-us/articles/205243605-How-do-I-save-a-post-and-where-does-it-get-saved',
  },
]

function normalizeVideoLink(value) {
  const trimmed = value.trim()
  if (!trimmed) return ''
  if (/^https:\/\//i.test(trimmed)) return trimmed
  if (/^http:\/\//i.test(trimmed)) return 'https://' + trimmed.slice('http://'.length)
  if (/^[a-z][a-z\d+.-]*:/i.test(trimmed)) return trimmed
  return 'https://' + trimmed.replace(/^\/\//, '')
}

function normalizeLinkField() {
  link.value = normalizeVideoLink(link.value)
}

const linkStatus = computed(() => {
  const value = link.value.trim()
  if (!value) return { state: 'empty', message: 'Cole um link para conferir se ele é válido.' }
  if (!selectedPlatform.value) return { state: 'incomplete', message: 'Escolha a plataforma do vídeo.' }

  try {
    const parsed = new URL(normalizeVideoLink(value))
    if (!['http:', 'https:'].includes(parsed.protocol) || !parsed.hostname) throw new Error('URL inválida')
    const platform = platforms.find(({ id }) => id === selectedPlatform.value)
    const matchesPlatform = platform.hosts.some((host) => parsed.hostname === host || parsed.hostname.endsWith('.' + host))
    return matchesPlatform
      ? { state: 'valid', message: 'Link válido para ' + platform.label + '.' }
      : { state: 'invalid', message: 'Este link não parece ser do ' + platform.label + '.' }
  } catch {
    return { state: 'invalid', message: 'Digite um link válido começando com https://.' }
  }
})

const selectedPlatformDetails = computed(() => platforms.find(({ id }) => id === selectedPlatform.value))

function openVideo() {
  if (linkStatus.value.state !== 'valid') return
  window.open(normalizeVideoLink(link.value), '_blank', 'noopener,noreferrer')
}
</script>

<template>
  <section class="content-view video-downloader-view" aria-labelledby="video-downloader-title">
    <header class="view-header">
      <p class="settings-eyebrow">FERRAMENTAS</p>
      <h1 id="video-downloader-title">Baixar vídeos</h1>
      <p>Prepare um link de vídeo para baixar.</p>
    </header>

    <section class="video-downloader-card" aria-labelledby="video-downloader-form-title">
      <div class="video-downloader-heading">
        <span class="video-downloader-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 3v12m-5-5 5 5 5-5M5 17v3h14v-3" /></svg></span>
        <div><h2 id="video-downloader-form-title">Download</h2><p>Informe a origem e o link do vídeo.</p></div>
      </div>

      <div class="video-downloader-fields">
        <label class="video-downloader-field" for="video-platform">
          <span>Plataforma</span>
          <select id="video-platform" v-model="selectedPlatform">
            <option value="">Selecione uma plataforma</option>
            <option v-for="platform in platforms" :key="platform.id" :value="platform.id">{{ platform.label }}</option>
          </select>
        </label>

        <label class="video-downloader-field" for="video-link">
          <span>Link do vídeo</span>
          <input id="video-link" v-model.trim="link" type="url" inputmode="url" autocomplete="url" placeholder="youtube.com/…" :aria-invalid="linkStatus.state === 'invalid'" aria-describedby="video-link-hint video-link-status" @blur="normalizeLinkField">
          <small id="video-link-hint">O prefixo https:// é adicionado automaticamente quando necessário.</small>
        </label>

        <p id="video-link-status" class="video-link-status" :class="'video-link-status-' + linkStatus.state" role="status" aria-live="polite">
          <span class="video-link-status-indicator" aria-hidden="true"></span>{{ linkStatus.message }}
        </p>
      </div>

      <div class="video-downloader-actions">
        <p>Use as opções de download oferecidas pela plataforma e respeite as permissões do vídeo.</p>
        <button class="task-primary-button" type="button" :disabled="linkStatus.state !== 'valid'" @click="openVideo">Abrir vídeo</button>
      </div>

      <aside v-if="selectedPlatformDetails" class="video-platform-guidance" aria-live="polite">
        <div>
          <h3>Como baixar no {{ selectedPlatformDetails.label }}</h3>
          <p>{{ selectedPlatformDetails.guidance }}</p>
        </div>
        <a :href="selectedPlatformDetails.helpUrl" target="_blank" rel="noopener noreferrer">Ver instruções oficiais <span aria-hidden="true">↗</span></a>
      </aside>
    </section>
  </section>
</template>
