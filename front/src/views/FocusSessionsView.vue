<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { hasPermission } from '../services/auth'
import {
  deleteFocusSession, endFocusSession, getActiveFocusSession,
  listFocusSessions, pauseFocusSession, resumeFocusSession, startFocusSession,
} from '../services/focusSessions'

const canWrite = computed(() => hasPermission('FOCUS_WRITE'))
const canReadTasks = computed(() => hasPermission('TASK_READ'))
const active = ref(null)
const history = ref([])
const offset = ref(0)
const hasMore = ref(false)
const tasks = ref([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const objective = ref('')
const plannedMinutes = ref(25)
const selectedTaskId = ref('')
const needsRecovery = ref(false)
const now = ref(Date.now())
const ending = ref(null)
const adjustedSeconds = ref('')
const endDialog = ref(null)
const dialogReturnFocus = ref(null)
const hiddenAt = ref(0)
let ticker

const elapsedSeconds = computed(() => {
  if (!active.value) return 0
  const current = active.value.focusedSeconds || 0
  if (active.value.state !== 'ACTIVE' || !active.value.resumedAt) return current
  return current + Math.max(0, Math.floor((now.value - new Date(active.value.resumedAt).getTime()) / 1000))
})
const remainingSeconds = computed(() => Math.max(0, (active.value?.plannedSeconds || 0) - elapsedSeconds.value))
const plannedReached = computed(() => Boolean(active.value && remainingSeconds.value === 0 && active.value.state === 'ACTIVE'))

function formatDuration(value) {
  const seconds = Math.max(0, Math.floor(value || 0))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const remainder = seconds % 60
  return hours ? `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`
    : `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`
}

async function loadTasks() {
  if (!canReadTasks.value) return
  try {
    const response = await fetch('/api/tasks')
    if (!response.ok) return
    tasks.value = await response.json()
  } catch { /* Task linking remains optional when the task service is unavailable. */ }
}

async function loadHistory(reset = false) {
  const page = await listFocusSessions(reset ? 0 : offset.value)
  history.value = reset ? page.sessions : [...history.value, ...page.sessions]
  offset.value = page.nextOffset
  hasMore.value = page.hasMore
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [current] = await Promise.all([getActiveFocusSession(), loadHistory(true), loadTasks()])
    active.value = current
    needsRecovery.value = Boolean(current)
    if (current) {
      needsRecovery.value = true
    }
  } catch (cause) {
    error.value = cause.message
  } finally { loading.value = false }
}

function recover() {
  needsRecovery.value = false
  notice.value = 'Sessão recuperada. Confira o tempo registrado antes de encerrar.'
}

async function runAction(action, successMessage) {
  if (!active.value || busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const updated = await action(active.value)
    active.value = updated.state === 'ENDED' ? null : updated
    needsRecovery.value = false
    if (successMessage) notice.value = successMessage
    if (updated.state === 'ENDED') await loadHistory(true)
  } catch (cause) {
    error.value = cause.message
    try { active.value = await getActiveFocusSession(); needsRecovery.value = Boolean(active.value) } catch { /* Keep the last confirmed state visible. */ }
  } finally { busy.value = false }
}

async function beginSession() {
  error.value = ''
  notice.value = ''
  if (!objective.value.trim()) { error.value = 'Informe o objetivo da sessão.'; return }
  const minutes = Number(plannedMinutes.value)
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > 720) { error.value = 'Escolha uma duração entre 1 minuto e 12 horas.'; return }
  busy.value = true
  try {
    active.value = await startFocusSession({
      id: crypto.randomUUID(), objective: objective.value.trim(), plannedSeconds: minutes * 60,
      taskId: selectedTaskId.value || null,
    })
    objective.value = ''
    selectedTaskId.value = ''
    needsRecovery.value = false
    notice.value = 'Sessão iniciada.'
  } catch (cause) {
    error.value = cause.message
    try {
      active.value = await getActiveFocusSession()
      needsRecovery.value = Boolean(active.value)
    } catch { /* Keep the start error visible. */ }
  } finally { busy.value = false }
}

async function openEnd(session = active.value) {
  if (!session) return
  dialogReturnFocus.value = document.activeElement
  ending.value = session
  adjustedSeconds.value = ''
  await nextTick()
  endDialog.value?.focus()
}

function closeEnd() {
  ending.value = null
  nextTick(() => dialogReturnFocus.value?.focus?.())
}

function onEndDialogKeydown(event) {
  if (event.key === 'Escape') { event.preventDefault(); closeEnd(); return }
  if (event.key !== 'Tab') return
  const focusable = [...(endDialog.value?.querySelectorAll('button:not([disabled]), input:not([disabled])') || [])]
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}

async function confirmEnd() {
  if (!ending.value) return
  const session = ending.value
  let override = null
  if (adjustedSeconds.value !== '') {
    override = Number(adjustedSeconds.value)
    if (!Number.isInteger(override) || override < 0 || override > 43200) {
      error.value = 'O ajuste deve ficar entre 0 e 43.200 segundos.'
      return
    }
  }
  closeEnd()
  if (active.value?.id === session.id) await runAction((item) => endFocusSession(item, override), 'Sessão encerrada e registrada no histórico.')
  else {
    busy.value = true
    try { await endFocusSession(session, override); notice.value = 'Sessão encerrada.'; await loadHistory(true); await load() }
    catch (cause) { error.value = cause.message }
    finally { busy.value = false }
  }
}

async function removeSession(session) {
  if (!window.confirm(`Excluir a sessão “${session.objective}” do histórico?`)) return
  busy.value = true
  error.value = ''
  try {
    await deleteFocusSession(session)
    history.value = history.value.filter((item) => item.id !== session.id)
    notice.value = 'Sessão removida do histórico.'
  } catch (cause) { error.value = cause.message } finally { busy.value = false }
}

function onVisibilityChange() {
  if (document.visibilityState === 'hidden') { hiddenAt.value = Date.now(); return }
  if (hiddenAt.value && Date.now() - hiddenAt.value > 12000 && active.value?.state === 'ACTIVE') {
    needsRecovery.value = true
    notice.value = 'A aba ficou suspensa. Confira o tempo antes de encerrar.'
  }
  hiddenAt.value = 0
  now.value = Date.now()
  getActiveFocusSession().then((current) => { active.value = current }).catch((cause) => { error.value = cause.message })
}

onMounted(() => {
  ticker = window.setInterval(() => { now.value = Date.now() }, 1000)
  document.addEventListener('visibilitychange', onVisibilityChange)
  load()
})
onBeforeUnmount(() => {
  window.clearInterval(ticker)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <section id="page-content" class="content-view focus-sessions-view" aria-labelledby="focus-sessions-title">
    <header class="view-header">
      <div>
        <p class="focus-eyebrow">FERRAMENTAS</p>
        <h1 id="focus-sessions-title">Sessões de foco</h1>
        <p>Reserve um período para uma atividade e registre o tempo dedicado.</p>
      </div>
    </header>

    <p v-if="error" class="focus-message focus-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="focus-message" role="status">{{ notice }}</p>
    <p v-if="loading" class="focus-message" role="status">Carregando sessões…</p>

    <template v-else>
      <section v-if="active && needsRecovery" class="focus-card focus-recovery" aria-labelledby="focus-recovery-title">
        <h2 id="focus-recovery-title">Há uma sessão em andamento</h2>
        <p><strong>{{ active.objective }}</strong> · {{ active.state === 'PAUSED' ? 'Pausada' : 'Ativa' }}</p>
        <p>Tempo registrado: {{ formatDuration(elapsedSeconds) }}. Confira esse valor se a aba ficou suspensa.</p>
        <div class="focus-actions">
          <button class="task-primary-button" type="button" @click="recover">Recuperar sessão</button>
          <button v-if="canWrite" class="task-secondary-button" type="button" @click="openEnd(active)">Encerrar e conferir tempo</button>
        </div>
      </section>

      <section v-else-if="active" class="focus-card focus-active" aria-labelledby="focus-active-title">
        <div class="focus-active-heading">
          <div><span class="focus-state" :class="`focus-state-${active.state.toLowerCase()}`">{{ active.state === 'ACTIVE' ? 'Em andamento' : 'Pausada' }}</span>
            <h2 id="focus-active-title">{{ active.objective }}</h2>
          </div>
          <p class="focus-clock" aria-label="Tempo restante">{{ formatDuration(remainingSeconds) }}</p>
        </div>
        <p class="focus-subline">{{ formatDuration(elapsedSeconds) }} focados de {{ formatDuration(active.plannedSeconds) }} planejados</p>
        <p v-if="active.taskId" class="focus-subline">{{ !canReadTasks ? 'Tarefa vinculada · detalhes indisponíveis.' : active.taskAvailable ? `Tarefa: ${active.taskTitle}` : 'A tarefa vinculada não está mais disponível.' }}</p>
        <p v-if="plannedReached" class="focus-message" role="status">A duração planejada foi atingida. Encerre a sessão ou continue conscientemente.</p>
        <p class="focus-subline">A pausa não conta como tempo focado. O Hub não gera alarme com a página fechada.</p>
        <div v-if="canWrite" class="focus-actions">
          <button v-if="active.state === 'ACTIVE'" class="task-primary-button" type="button" :disabled="busy" @click="runAction(pauseFocusSession, 'Sessão pausada.')">Pausar sessão</button>
          <button v-else class="task-primary-button" type="button" :disabled="busy" @click="runAction(resumeFocusSession, 'Sessão retomada.')">Retomar sessão</button>
          <button class="task-secondary-button" type="button" :disabled="busy" @click="openEnd(active)">Encerrar sessão</button>
        </div>
      </section>

      <section v-else-if="canWrite" class="focus-card" aria-labelledby="focus-start-title">
        <h2 id="focus-start-title">Prepare sua sessão</h2>
        <form class="focus-form" @submit.prevent="beginSession">
          <label>Objetivo<input v-model="objective" maxlength="240" required autocomplete="off" placeholder="Ex.: revisar a proposta do projeto" /></label>
          <label>Duração planejada (minutos)<input v-model.number="plannedMinutes" type="number" min="1" max="720" step="1" required /></label>
          <label v-if="canReadTasks">Tarefa relacionada (opcional)
            <select v-model="selectedTaskId"><option value="">Sem tarefa vinculada</option><option v-for="task in tasks" :key="task.id" :value="task.id">{{ task.title }}</option></select>
          </label>
          <button class="task-primary-button" type="submit" :disabled="busy">{{ busy ? 'Iniciando…' : 'Iniciar sessão' }}</button>
        </form>
      </section>
      <section v-else class="focus-card"><h2>Sem sessão ativa</h2><p>Seu perfil pode consultar o histórico, mas não iniciar ou controlar sessões.</p></section>

      <section class="focus-history" aria-labelledby="focus-history-title">
        <div class="focus-section-heading"><div><h2 id="focus-history-title">Histórico</h2><p>Suas sessões encerradas, da mais recente para a mais antiga.</p></div></div>
        <p v-if="history.length === 0" class="focus-empty">Nenhuma sessão encerrada ainda. Quando finalizar uma, ela aparecerá aqui.</p>
        <ul v-else class="focus-history-list">
          <li v-for="session in history" :key="session.id" class="focus-history-item">
            <div><h3>{{ session.objective }}</h3><p>{{ new Date(session.startedAt).toLocaleString('pt-BR') }} · {{ formatDuration(session.focusedSeconds) }} focados de {{ formatDuration(session.plannedSeconds) }} planejados</p>
              <p class="focus-outcome">{{ session.endReason === 'EARLY' ? 'Encerrada antes do planejado' : session.endReason === 'ADJUSTED' ? 'Tempo ajustado ao encerrar' : 'Encerrada' }}</p>
            </div>
            <button v-if="canWrite" class="task-secondary-button" type="button" :disabled="busy" :aria-label="`Excluir sessão ${session.objective}`" @click="removeSession(session)">Excluir</button>
          </li>
        </ul>
        <button v-if="hasMore" class="task-secondary-button focus-load-more" type="button" :disabled="busy" @click="loadHistory()">Carregar sessões anteriores</button>
      </section>
    </template>

    <div v-if="ending" class="focus-dialog-backdrop" @click.self="closeEnd">
      <section ref="endDialog" class="focus-dialog" role="dialog" aria-modal="true" aria-labelledby="focus-end-title" tabindex="-1" @keydown="onEndDialogKeydown">
        <h2 id="focus-end-title">Encerrar sessão</h2>
        <p>Tempo calculado: {{ formatDuration(ending === active ? elapsedSeconds : ending.focusedSeconds) }}. Se a aba ficou suspensa, informe o tempo focado revisado. Deixe em branco para usar o cálculo.</p>
        <label>Tempo focado ajustado (segundos, opcional)<input v-model="adjustedSeconds" type="number" min="0" max="43200" step="1" inputmode="numeric" /></label>
        <div class="focus-actions"><button class="task-secondary-button" type="button" :disabled="busy" @click="closeEnd">Continuar sessão</button><button class="task-primary-button" type="button" :disabled="busy" @click="confirmEnd">{{ busy ? 'Salvando…' : 'Encerrar e salvar' }}</button></div>
      </section>
    </div>
  </section>
</template>
