<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { hasPermission } from '../services/auth'
import {
  createGoogleTask, disconnectGoogle, getGoogleConnectionStatus, getGoogleReminderSettings,
  listGoogleNotifications, listGoogleTaskLists, listGoogleTasks, markAllGoogleNotificationsRead,
  markGoogleNotificationRead, saveGoogleReminderSettings, selectGoogleTaskList,
  startGoogleConnection, synchronizeGoogleTasks, updateGoogleTask,
} from '../services/googleTasks'

const route = useRoute()
const status = ref(null)
const lists = ref([])
const tasks = ref([])
const notifications = ref([])
const reminderTime = ref('09:00')
const taskTitle = ref('')
const taskDueDate = ref('')
const taskListId = ref('')
const taskDrafts = reactive({})
const loading = ref(true)
const syncing = ref(false)
const connecting = ref(false)
const disconnecting = ref(false)
const savingTask = ref('')
const savingList = ref('')
const savingReminder = ref(false)
const creatingTask = ref(false)
const markingRead = ref('')
const pageError = ref('')
const taskError = ref('')
const success = ref('')
const reminderError = ref('')

const connected = computed(() => status.value?.status === 'CONNECTED')
const configurationRequired = computed(() => status.value?.configured === false || status.value?.status === 'CONFIGURATION_REQUIRED')
const canWriteTasks = computed(() => hasPermission('TASK_WRITE'))
const canWriteSettings = computed(() => hasPermission('SETTINGS_WRITE'))
const canReadSettings = computed(() => hasPermission('SETTINGS_READ'))
const selectedLists = computed(() => lists.value.filter((item) => item.selected))
const unreadNotifications = computed(() => notifications.value.filter((item) => item.state === 'UNREAD'))
const taskListOptions = computed(() => selectedLists.value)

function taskKey(task) { return task.listId + ':' + task.id }
function draftFor(task) {
  const key = taskKey(task)
  if (!taskDrafts[key]) taskDrafts[key] = { dueDate: task.dueDate || '', completed: Boolean(task.completed) }
  return taskDrafts[key]
}
function hasTaskChanges(task) {
  const draft = draftFor(task)
  return draft.dueDate !== (task.dueDate || '') || draft.completed !== Boolean(task.completed)
}
function taskIdLabel(task) { return 'Data limite para ' + task.title }
function formatDate(value) {
  if (!value) return 'Sem prazo'
  const date = new Date(value + 'T00:00:00')
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('pt-BR')
}
function formatDateTime(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
}
function setTasks(value) {
  const selectedListIds = new Set(selectedLists.value.map((list) => list.id))
  tasks.value = (value || []).filter((task) => !task.deleted && selectedListIds.has(task.listId))
  for (const task of tasks.value) draftFor(task)
}

async function load() {
  loading.value = true
  pageError.value = ''
  taskError.value = ''
  reminderError.value = ''
  success.value = ''
  try {
    status.value = await getGoogleConnectionStatus()
    if (!connected.value) return
    const requests = [listGoogleTaskLists(), listGoogleTasks(), listGoogleNotifications()]
    if (canReadSettings.value) requests.push(getGoogleReminderSettings())
    const [remoteLists, remoteTasks, remoteNotifications, reminder] = await Promise.all(requests)
    lists.value = remoteLists || []
    setTasks(remoteTasks || [])
    notifications.value = remoteNotifications || []
    if (reminder?.reminderTime) reminderTime.value = reminder.reminderTime
    if (!taskListId.value || !lists.value.some((item) => item.id === taskListId.value && item.selected)) {
      taskListId.value = selectedLists.value[0]?.id || ''
    }
  } catch (error) {
    pageError.value = error.message || 'Não foi possível carregar os dados do Google Tasks.'
  } finally {
    loading.value = false
  }
}

async function connect() {
  connecting.value = true
  pageError.value = ''
  try {
    const result = await startGoogleConnection()
    if (!result?.authorizationUrl) throw new Error('O endereço de autorização do Google não foi recebido.')
    window.location.assign(result.authorizationUrl)
  } catch (error) {
    pageError.value = error.message || 'Não foi possível iniciar a conexão com o Google.'
    connecting.value = false
  }
}

async function disconnect() {
  if (!window.confirm('Desconectar sua conta Google Tasks? O Hub vai parar de sincronizar e cancelar avisos pendentes. O histórico e a seleção de listas serão preservados.')) return
  disconnecting.value = true
  pageError.value = ''
  try {
    await disconnectGoogle()
    status.value = { status: 'NOT_CONNECTED', configured: true, message: 'Conta Google desconectada.' }
    lists.value = []
    tasks.value = []
    notifications.value = []
    success.value = 'Conta Google desconectada.'
  } catch (error) {
    pageError.value = error.message || 'Não foi possível desconectar a conta Google.'
  } finally {
    disconnecting.value = false
  }
}

async function syncNow() {
  syncing.value = true
  pageError.value = ''
  taskError.value = ''
  success.value = ''
  try {
    const result = await synchronizeGoogleTasks()
    lists.value = result?.lists || await listGoogleTaskLists()
    setTasks(result?.tasks || [])
    notifications.value = await listGoogleNotifications()
    status.value = await getGoogleConnectionStatus()
    success.value = 'Google Tasks sincronizado.'
  } catch (error) {
    pageError.value = error.message || 'Não foi possível sincronizar o Google Tasks.'
  } finally {
    syncing.value = false
  }
}

async function toggleList(list) {
  const previousTasks = tasks.value.slice()
  const previous = list.selected
  list.selected = !previous
  setTasks(tasks.value)
  if (taskListId.value === list.id && !list.selected) taskListId.value = selectedLists.value[0]?.id || ''
  else if (!taskListId.value && list.selected) taskListId.value = list.id
  savingList.value = list.id
  pageError.value = ''
  try {
    await selectGoogleTaskList(list.id, list.selected)
  } catch (error) {
    list.selected = previous
    setTasks(previousTasks)
    if (taskListId.value === list.id && !list.selected) taskListId.value = selectedLists.value[0]?.id || ''
    pageError.value = error.message || 'Não foi possível atualizar a seleção da lista.'
  } finally {
    savingList.value = ''
  }
}

async function createTask() {
  taskError.value = ''
  if (!taskTitle.value.trim() || !taskListId.value) {
    taskError.value = 'Informe o título e escolha uma lista do Google.'
    return
  }
  creatingTask.value = true
  try {
    await createGoogleTask({ title: taskTitle.value.trim(), listId: taskListId.value, dueDate: taskDueDate.value || null })
    taskTitle.value = ''
    taskDueDate.value = ''
    const result = await synchronizeGoogleTasks()
    lists.value = result?.lists || lists.value
    setTasks(result?.tasks || [])
    notifications.value = await listGoogleNotifications()
    success.value = 'Tarefa criada no Google Tasks.'
  } catch (error) {
    taskError.value = error.message || 'Não foi possível criar a tarefa.'
  } finally {
    creatingTask.value = false
  }
}

async function saveTask(task) {
  const key = taskKey(task)
  const draft = draftFor(task)
  if (!hasTaskChanges(task)) return
  savingTask.value = key
  taskError.value = ''
  try {
    const updated = await updateGoogleTask(task, draft)
    setTasks(tasks.value.map((item) => taskKey(item) === key ? updated : item))
  } catch (error) {
    taskError.value = error.message || 'A tarefa mudou no Google. Sincronize e tente novamente.'
  } finally {
    savingTask.value = ''
  }
}

async function markRead(notification) {
  markingRead.value = notification.id
  pageError.value = ''
  try {
    await markGoogleNotificationRead(notification.id)
    notification.state = 'READ'
    notification.readAt = new Date().toISOString()
  } catch (error) {
    pageError.value = error.message || 'Não foi possível marcar o aviso como lido.'
  } finally {
    markingRead.value = ''
  }
}

async function markAllRead() {
  markingRead.value = 'all'
  pageError.value = ''
  try {
    await markAllGoogleNotificationsRead()
    const now = new Date().toISOString()
    notifications.value.forEach((item) => {
      if (item.state === 'UNREAD') { item.state = 'READ'; item.readAt = now }
    })
  } catch (error) {
    pageError.value = error.message || 'Não foi possível marcar os avisos como lidos.'
  } finally {
    markingRead.value = ''
  }
}

async function saveReminder() {
  savingReminder.value = true
  reminderError.value = ''
  try {
    const result = await saveGoogleReminderSettings(reminderTime.value)
    reminderTime.value = result.reminderTime
    success.value = 'Horário do lembrete salvo.'
  } catch (error) {
    reminderError.value = error.message || 'Não foi possível salvar o horário do lembrete.'
  } finally {
    savingReminder.value = false
  }
}

onMounted(async () => {
  if (route.query.oauth === 'connected') success.value = 'Conta Google conectada.'
  if (route.query.oauth === 'error') pageError.value = 'A autorização não foi concluída. Tente conectar novamente.'
  await load()
})
</script>

<template>
  <section id="page-content" class="page-content google-tasks-page" aria-labelledby="google-tasks-title">
    <header class="google-page-heading">
      <div><p class="settings-eyebrow">INTEGRAÇÃO PESSOAL</p><h1 id="google-tasks-title">Google Tasks</h1><p>Conecte sua conta Google para consultar e organizar suas tarefas.</p></div>
      <button v-if="connected && canWriteTasks" class="task-secondary-button" type="button" :disabled="syncing" @click="syncNow">{{ syncing ? 'Sincronizando…' : 'Sincronizar agora' }}</button>
    </header>

    <p v-if="pageError" class="google-alert google-alert-error" role="alert">{{ pageError }}</p>
    <p v-if="success" class="google-alert google-alert-success" role="status">{{ success }}</p>
    <p v-if="loading" class="google-loading" role="status">Carregando Google Tasks…</p>

    <template v-else>
      <section class="google-card google-connection-card" aria-labelledby="google-connection-title">
        <div class="google-card-heading">
          <div><p class="google-kicker">CONEXÃO</p><h2 id="google-connection-title">Sua conta Google</h2><p>{{ status?.message || 'Verifique o estado da conexão com o Google Tasks.' }}</p></div>
          <span class="google-status" :class="connected ? 'google-status-connected' : 'google-status-disconnected'" role="status">{{ connected ? 'Conectado' : configurationRequired ? 'Configuração necessária' : status?.status === 'CONNECTING' ? 'Conectando' : status?.status === 'REAUTH_REQUIRED' ? 'Reconexão necessária' : 'Desconectado' }}</span>
        </div>
        <p v-if="status?.lastSyncedAt" class="google-last-sync">Última sincronização: {{ formatDateTime(status.lastSyncedAt) }}</p>
        <p v-if="configurationRequired" class="google-hint">A integração ainda não está configurada neste servidor. Peça ao administrador para configurar o acesso OAuth.</p>
        <div class="google-actions">
          <button v-if="!connected" class="task-primary-button" type="button" :disabled="connecting || configurationRequired || !canWriteSettings" @click="connect">{{ connecting ? 'Abrindo autorização…' : status?.status === 'REAUTH_REQUIRED' ? 'Reconectar Google' : 'Conectar Google' }}</button>
          <button v-if="connected && canWriteSettings" class="task-secondary-button" type="button" :disabled="disconnecting" @click="disconnect">{{ disconnecting ? 'Desconectando…' : 'Desconectar' }}</button>
          <p v-if="!canWriteSettings && !connected" class="google-hint">Seu perfil não permite alterar conexões externas.</p>
        </div>
      </section>

      <div v-if="connected" class="google-content-grid">
        <section class="google-card google-lists-card" aria-labelledby="google-lists-title">
          <div class="google-card-heading"><div><p class="google-kicker">ORGANIZAÇÃO</p><h2 id="google-lists-title">Listas</h2><p>Escolha quais listas entram na sincronização.</p></div></div>
          <p v-if="!lists.length" class="google-empty" role="status">Nenhuma lista foi encontrada na conta Google.</p>
          <ul v-else class="google-list-options">
            <li v-for="list in lists" :key="list.id">
              <label><input type="checkbox" :checked="list.selected" :disabled="!canWriteTasks || savingList === list.id" @change="toggleList(list)"><span>{{ list.title }}</span><small>{{ savingList === list.id ? 'Salvando…' : list.selected ? 'Incluída' : 'Pausada' }}</small></label>
            </li>
          </ul>
        </section>

        <section v-if="canReadSettings" class="google-card google-reminder-card" aria-labelledby="google-reminder-title">
          <div class="google-card-heading"><div><p class="google-kicker">AVISOS</p><h2 id="google-reminder-title">Horário do lembrete</h2><p>Defina quando o Hub deve avisar sobre tarefas futuras.</p></div></div>
          <form class="google-reminder-form" @submit.prevent="saveReminder">
            <label for="google-reminder-time">Horário</label>
            <div><input id="google-reminder-time" v-model="reminderTime" type="time" required :disabled="!canWriteSettings || savingReminder"><button class="task-secondary-button" type="submit" :disabled="!canWriteSettings || savingReminder">{{ savingReminder ? 'Salvando…' : 'Salvar' }}</button></div>
          </form>
          <p v-if="reminderError" class="google-inline-error" role="alert">{{ reminderError }}</p>
        </section>

        <section class="google-card google-create-card" aria-labelledby="google-create-title">
          <div class="google-card-heading"><div><p class="google-kicker">NOVA TAREFA</p><h2 id="google-create-title">Adicionar ao Google</h2></div></div>
          <form class="google-create-form" @submit.prevent="createTask">
            <label>Título<input v-model.trim="taskTitle" type="text" maxlength="1024" required :disabled="!canWriteTasks || creatingTask" placeholder="O que você precisa fazer?"></label>
            <label>Lista<select v-model="taskListId" required :disabled="!canWriteTasks || creatingTask || !taskListOptions.length"><option v-for="list in taskListOptions" :key="list.id" :value="list.id">{{ list.title }}</option></select></label>
            <label>Prazo<input v-model="taskDueDate" type="date" :disabled="!canWriteTasks || creatingTask"></label>
            <p v-if="taskError" class="google-inline-error" role="alert">{{ taskError }}</p>
            <button class="task-primary-button" type="submit" :disabled="!canWriteTasks || creatingTask || !taskListId">{{ creatingTask ? 'Criando…' : 'Criar tarefa' }}</button>
          </form>
        </section>

        <section class="google-card google-tasks-card" aria-labelledby="google-task-list-title">
          <div class="google-card-heading"><div><p class="google-kicker">TAREFAS SINCRONIZADAS</p><h2 id="google-task-list-title">Suas tarefas</h2><p>Alterações de prazo e conclusão são enviadas ao Google Tasks.</p></div></div>
          <p v-if="!selectedLists.length" class="google-empty" role="status">Selecione pelo menos uma lista para mostrar tarefas.</p>
          <p v-else-if="!tasks.length" class="google-empty" role="status">Nenhuma tarefa foi encontrada nas listas selecionadas.</p>
          <p v-else-if="taskError" class="google-inline-error" role="alert">{{ taskError }}</p>
          <ul v-if="tasks.length" class="google-task-list">
            <li v-for="task in tasks" :key="taskKey(task)" class="google-task-row">
              <div class="google-task-copy"><strong>{{ task.title }}</strong><span>{{ task.listTitle }} · {{ formatDate(task.dueDate) }}</span></div>
              <div class="google-task-controls">
                <label class="google-completed-control"><input v-model="draftFor(task).completed" type="checkbox" :disabled="!canWriteTasks || savingTask === taskKey(task)"><span>Concluída</span></label>
                <label class="google-date-control"><span class="visually-hidden">{{ taskIdLabel(task) }}</span><input v-model="draftFor(task).dueDate" type="date" :disabled="!canWriteTasks || savingTask === taskKey(task)"></label>
                <button class="task-secondary-button" type="button" :disabled="!canWriteTasks || !hasTaskChanges(task) || savingTask === taskKey(task)" @click="saveTask(task)">{{ savingTask === taskKey(task) ? 'Salvando…' : 'Salvar' }}</button>
              </div>
            </li>
          </ul>
        </section>

        <section class="google-card google-notifications-card" aria-labelledby="google-notifications-title">
          <div class="google-card-heading"><div><p class="google-kicker">CENTRAL DE ATENÇÃO</p><h2 id="google-notifications-title">Avisos Google</h2><p>Avisos desta conexão Google, separados das tarefas locais.</p></div><button v-if="unreadNotifications.length && canWriteTasks" class="task-secondary-button" type="button" :disabled="markingRead === 'all'" @click="markAllRead">{{ markingRead === 'all' ? 'Salvando…' : 'Marcar todos como lidos' }}</button></div>
          <p v-if="!notifications.length" class="google-empty" role="status">Nenhum aviso Google disponível.</p>
          <ul v-else class="google-notification-list">
            <li v-for="notice in notifications" :key="notice.id" :class="{ 'google-notification-unread': notice.state === 'UNREAD' }">
              <div><strong>{{ notice.state === 'UNREAD' ? 'Tarefa próxima do prazo' : 'Aviso de tarefa' }}</strong><span>{{ formatDate(notice.dueDate) }}<template v-if="notice.overdue"> · prazo vencido</template></span><small>{{ formatDateTime(notice.scheduledAt) }}</small></div>
              <button v-if="notice.state === 'UNREAD' && canWriteTasks" class="task-secondary-button" type="button" :disabled="markingRead === notice.id" @click="markRead(notice)">{{ markingRead === notice.id ? 'Salvando…' : 'Marcar como lido' }}</button>
              <span v-else class="google-read-state">{{ notice.state === 'READ' ? 'Lido' : 'Pendente' }}</span>
            </li>
          </ul>
        </section>
      </div>
    </template>
  </section>
</template>

<style scoped>
.google-tasks-page { display: grid; align-content: start; gap: 18px; min-height: 100vh; padding: 30px clamp(20px, 3vw, 46px) 48px; }
.google-page-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; }
.google-page-heading h1 { margin: 4px 0 7px; font-size: clamp(26px, 3vw, 36px); letter-spacing: -.7px; }
.google-page-heading p:last-child { margin: 0; color: var(--muted); line-height: 1.5; }
.google-card { min-width: 0; padding: 20px; border: 1px solid var(--line); border-radius: 13px; background: linear-gradient(145deg, rgba(17, 20, 40, .92), rgba(11, 14, 28, .92)); }
.google-card-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
.google-card-heading h2 { margin: 3px 0 6px; font-size: 18px; letter-spacing: -.25px; }
.google-card-heading p:last-child { margin: 0; color: var(--muted); font-size: 13px; line-height: 1.5; }
.google-kicker { margin: 0; color: #b99bff; font-size: 10px; font-weight: 700; letter-spacing: .12em; }
.google-connection-card { display: grid; gap: 12px; }
.google-status { flex: 0 0 auto; padding: 7px 10px; border: 1px solid var(--line); border-radius: 999px; color: var(--muted); font-size: 11px; }
.google-status-connected { border-color: rgba(116, 213, 179, .32); background: rgba(68, 130, 112, .1); color: #9ce1c8; }
.google-status-disconnected { color: #d2c7e8; }
.google-last-sync,.google-hint,.google-loading,.google-empty { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.6; }
.google-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.google-actions p { margin: 0; }
.google-content-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); align-items: start; gap: 16px; }
.google-list-options,.google-task-list,.google-notification-list { display: grid; gap: 8px; margin: 17px 0 0; padding: 0; list-style: none; }
.google-list-options li,.google-task-row,.google-notification-list li { min-width: 0; padding: 12px 13px; border: 1px solid rgba(170, 169, 220, .1); border-radius: 9px; background: rgba(8, 10, 22, .48); }
.google-list-options label { display: grid; grid-template-columns: 18px minmax(0, 1fr) auto; align-items: center; gap: 10px; cursor: pointer; }
.google-list-options input,.google-completed-control input { width: 17px; height: 17px; accent-color: var(--accent); }
.google-list-options label > span { overflow-wrap: anywhere; font-size: 13px; }
.google-list-options small,.google-read-state { color: var(--muted); font-size: 11px; }
.google-reminder-form,.google-create-form { display: grid; gap: 10px; margin-top: 17px; }
.google-reminder-form > label,.google-create-form label { display: grid; gap: 7px; color: #dcd9e9; font-size: 12px; font-weight: 560; }
.google-reminder-form > div { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.google-create-form input,.google-create-form select,.google-reminder-form input,.google-date-control input { min-width: 0; min-height: 42px; padding: 0 11px; border: 1px solid rgba(160, 164, 204, .22); border-radius: 8px; background: #101326; color: var(--text); font: inherit; font-size: 13px; }
.google-create-form input:focus-visible,.google-create-form select:focus-visible,.google-reminder-form input:focus-visible,.google-date-control input:focus-visible { outline: 2px solid var(--accent-bright); outline-offset: 2px; }
.google-create-form input:disabled,.google-create-form select:disabled,.google-reminder-form input:disabled,.google-date-control input:disabled { opacity: .65; cursor: not-allowed; }
.google-task-list { margin-top: 17px; }
.google-task-row { display: grid; gap: 12px; }
.google-task-copy { display: grid; gap: 5px; min-width: 0; }
.google-task-copy strong,.google-notification-list strong { overflow-wrap: anywhere; font-size: 13px; }
.google-task-copy span,.google-notification-list span,.google-notification-list small { color: var(--muted); font-size: 11px; line-height: 1.5; }
.google-task-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.google-completed-control { display: inline-flex; align-items: center; gap: 7px; color: #dcd9e9; font-size: 12px; }
.google-date-control { flex: 1 1 160px; }
.google-date-control input { width: 100%; }
.google-notification-list li { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.google-notification-list li > div { display: grid; gap: 4px; min-width: 0; }
.google-notification-unread { border-color: rgba(169, 134, 255, .25) !important; }
.google-inline-error { margin: 0; color: #ffb6c3; font-size: 12px; line-height: 1.5; }
.google-alert { margin: 0; padding: 12px 14px; border: 1px solid var(--line); border-radius: 9px; font-size: 13px; line-height: 1.5; }
.google-alert-error { border-color: rgba(240, 126, 145, .3); background: rgba(149, 48, 72, .1); color: #ffb6c3; }
.google-alert-success { border-color: rgba(116, 213, 179, .25); background: rgba(68, 130, 112, .08); color: #9ce1c8; }
@media (max-width: 760px) {
  .google-page-heading { align-items: flex-start; flex-direction: column; }
  .google-content-grid { grid-template-columns: minmax(0, 1fr); }
  .google-card-heading { flex-direction: column; }
  .google-task-controls,.google-notification-list li { align-items: stretch; flex-direction: column; }
  .google-task-controls > button,.google-notification-list button { width: 100%; }
}
</style>
