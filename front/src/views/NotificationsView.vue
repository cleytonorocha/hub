<script setup>
import { computed, nextTick, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getNotificationWindow, getPendingNotifications } from '../utils/notifications'
import { useTaskHub } from '../services/taskHub'
import ContextMenu from '../components/ContextMenu.vue'

const router = useRouter()
const { state, updateTask, refreshTasks } = useTaskHub()
const selectedId = ref('')
const activeFilter = ref('all')
const search = ref('')
const actionError = ref('')
const completing = ref(false)
const contextMenu = ref(null)
const notificationList = ref(null)
const notificationDetailTitle = ref(null)
const { today: todayKey, tomorrow: tomorrowKey } = getNotificationWindow()
const filters = [
  { id: 'all', label: 'Todas' },
  { id: 'overdue', label: 'Vencidas' },
  { id: 'today', label: 'Hoje' },
  { id: 'tomorrow', label: 'Amanhã' },
]
const reminders = computed(() => getPendingNotifications(state.tasks)
  .sort((a, b) => a.dueDate.localeCompare(b.dueDate)
    || (a.dueTime || '99:99').localeCompare(b.dueTime || '99:99')
    || a.title.localeCompare(b.title, 'pt-BR')))
const counts = computed(() => ({
  all: reminders.value.length,
  overdue: reminders.value.filter((task) => task.dueDate < todayKey).length,
  today: reminders.value.filter((task) => task.dueDate === todayKey).length,
  tomorrow: reminders.value.filter((task) => task.dueDate === tomorrowKey).length,
}))
const filteredReminders = computed(() => {
  const needle = normalize(search.value.trim())
  return reminders.value.filter((task) => {
    const due = reminderKind(task)
    const matchesFilter = activeFilter.value === 'all' || activeFilter.value === due
    const matchesSearch = !needle || normalize([task.title, task.category, task.notes].filter(Boolean).join(' ')).includes(needle)
    return matchesFilter && matchesSearch
  })
})
const selectedTask = computed(() => filteredReminders.value.find((task) => task.id === selectedId.value) || filteredReminders.value[0] || null)
const contextMenuItems = [
  { id: 'details', label: 'Ver detalhes' },
  { id: 'calendar', label: 'Abrir no calendário' },
  { id: 'complete', label: 'Concluir task' },
]

function normalize(value) {
  return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('pt-BR')
}

function reminderKind(task) {
  if (task.dueDate < todayKey) return 'overdue'
  return task.dueDate === tomorrowKey ? 'tomorrow' : 'today'
}

function reminderTitle(task) {
  return { overdue: 'Prazo vencido', today: 'Prazo hoje', tomorrow: 'Prazo amanhã' }[reminderKind(task)]
}

function formatDate(value) {
  return new Intl.DateTimeFormat('pt-BR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(new Date(value + 'T12:00:00'))
}

function formatTime(value) { return value ? value.slice(0, 5) : '' }

function openTaskInCalendar(task = selectedTask.value) {
  if (!task) return
  const query = { date: task.dueDate, view: 'day', taskId: task.id }
  if (task.dueTime) query.hour = String(Number(task.dueTime.slice(0, 2)))
  router.push({ name: 'calendar', query })
}

async function refreshNotifications() {
  try { await refreshTasks() } catch { /* The page shows the error from shared task state. */ }
}

async function completeTask(task) {
  if (!task || completing.value) return
  actionError.value = ''
  completing.value = true
  try {
    await updateTask(task, {
      dueDate: task.dueDate,
      dueTime: task.dueTime || null,
      completed: true,
      category: task.category,
      color: task.color,
      priority: task.priority,
      notes: task.notes,
    })
    if (selectedId.value === task.id) selectedId.value = ''
  } catch (error) {
    actionError.value = error.message
  } finally {
    completing.value = false
  }
}

async function completeSelectedTask() { return completeTask(selectedTask.value) }

function contextMenuKey(event) {
  return (event.shiftKey && event.key === 'F10') || event.key === 'ContextMenu' || event.key === 'Menu'
}

function openNotificationContext(event, task) {
  const keyboard = event.type === 'keydown'
  const target = event.currentTarget || event.target
  const bounds = target.getBoundingClientRect()
  event.preventDefault()
  event.stopPropagation()
  contextMenu.value = {
    task,
    target,
    keyboard,
    x: keyboard ? bounds.left + Math.min(bounds.width, 16) : event.clientX,
    y: keyboard ? bounds.bottom : event.clientY,
  }
}

function handleNotificationContextKeydown(event, task) {
  if (contextMenuKey(event)) openNotificationContext(event, task)
}

async function closeContextMenu(restoreFocus = false) {
  const current = contextMenu.value
  contextMenu.value = null
  if (restoreFocus && current?.keyboard) {
    await nextTick()
    current.target?.focus?.()
  }
}

async function selectContextAction(action) {
  const context = contextMenu.value
  if (!context) return
  await closeContextMenu()
  if (action === 'details') {
    selectedId.value = context.task.id
    await nextTick()
    notificationDetailTitle.value?.focus()
  } else if (action === 'calendar') {
    openTaskInCalendar(context.task)
  } else if (action === 'complete') {
    selectedId.value = context.task.id
    await completeTask(context.task)
    await nextTick()
    const focusTarget = notificationList.value?.querySelector('.notification-row[aria-current="true"]')
      || document.querySelector('.notification-refresh')
    focusTarget?.focus()
  }
}
</script>

<template>
  <section class="content-view notifications-view" aria-labelledby="notifications-title">
    <header class="view-header"><p class="settings-eyebrow">CENTRAL DE ATENÇÃO</p><h1 id="notifications-title">Notificações</h1><p>Acompanhe prazos vencidos e tarefas para hoje e amanhã.</p></header>
    <section id="page-content" class="notification-center" aria-label="Central de notificações">
      <div class="notification-summary" aria-label="Resumo dos prazos">
        <button v-for="item in filters.slice(1)" :key="item.id" class="notification-summary-card" :class="['notification-summary-' + item.id, { 'notification-summary-active': activeFilter === item.id }]" type="button" :aria-pressed="activeFilter === item.id" @click="activeFilter = item.id">
          <span>{{ item.label }}</span><strong>{{ counts[item.id] }}</strong>
        </button>
        <button class="notification-summary-card notification-summary-all" :class="{ 'notification-summary-active': activeFilter === 'all' }" type="button" :aria-pressed="activeFilter === 'all'" @click="activeFilter = 'all'">
          <span>Tarefas pendentes</span><strong>{{ counts.all }}</strong>
        </button>
      </div>

      <div class="notifications-toolbar">
        <div class="notification-filter" role="group" aria-label="Filtrar notificações">
          <button v-for="item in filters" :key="item.id" type="button" :aria-pressed="activeFilter === item.id" @click="activeFilter = item.id">{{ item.label }} <span>{{ counts[item.id] }}</span></button>
        </div>
        <label class="notification-search"><span class="visually-hidden">Buscar notificações</span><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 5 5"/></svg><input v-model="search" type="search" placeholder="Buscar tarefa ou categoria"></label>
        <button class="notification-refresh" type="button" :disabled="state.refreshing" @click="refreshNotifications">{{ state.refreshing ? 'Atualizando…' : 'Atualizar' }}</button>
      </div>

      <p v-if="state.error" class="notification-error" role="alert">{{ state.error }}</p>
      <div v-if="!state.loading && filteredReminders.length" class="notification-workspace">
        <ul ref="notificationList" class="notification-list" aria-label="Tarefas com prazos próximos ou vencidos">
          <li v-for="task in filteredReminders" :key="task.id">
            <button class="notification-row" :class="['notification-row-' + reminderKind(task), { 'notification-row-selected': selectedTask?.id === task.id }]" :style="{ '--task-color': task.color || '#A986FF' }" type="button" :aria-current="selectedTask?.id === task.id ? 'true' : undefined" @click="selectedId = task.id" @contextmenu.prevent.stop="openNotificationContext($event, task)" @keydown="handleNotificationContextKeydown($event, task)">
              <span class="notification-icon" aria-hidden="true">{{ reminderKind(task) === 'overdue' ? '!' : '◷' }}</span>
              <span class="notification-copy"><strong>{{ task.title }}</strong><span>{{ task.category || 'Geral' }}<template v-if="task.priority === 'HIGH'"> · Prioridade alta</template></span><small>{{ reminderTitle(task) }} · {{ formatDate(task.dueDate) }}<template v-if="task.dueTime"> · {{ formatTime(task.dueTime) }}</template></small></span>
              <span class="notification-row-arrow" aria-hidden="true">›</span>
            </button>
          </li>
        </ul>
        <aside v-if="selectedTask" class="notification-detail" aria-labelledby="notification-detail-title">
          <div class="notification-detail-topline"><span class="notification-icon" :class="'notification-icon-' + reminderKind(selectedTask)" aria-hidden="true">{{ reminderKind(selectedTask) === 'overdue' ? '!' : '◷' }}</span><span class="notification-priority" :class="'notification-priority-' + (selectedTask.priority || 'NORMAL').toLowerCase()">{{ selectedTask.priority === 'HIGH' ? 'Prioridade alta' : selectedTask.priority === 'LOW' ? 'Prioridade baixa' : 'Prioridade normal' }}</span></div>
          <p class="settings-eyebrow">{{ selectedTask.category || 'GERAL' }}</p>
          <h2 id="notification-detail-title" ref="notificationDetailTitle" tabindex="-1">{{ reminderTitle(selectedTask) }}</h2>
          <h3>{{ selectedTask.title }}</h3>
          <dl class="notification-facts"><div><dt>Data</dt><dd>{{ formatDate(selectedTask.dueDate) }}</dd></div><div v-if="selectedTask.dueTime"><dt>Horário</dt><dd>{{ formatTime(selectedTask.dueTime) }}</dd></div><div><dt>Status</dt><dd>Pendente</dd></div></dl>
          <p v-if="selectedTask.notes" class="notification-notes">{{ selectedTask.notes }}</p>
          <div class="notification-detail-actions"><button class="task-primary-button" type="button" @click="openTaskInCalendar">Abrir no calendário</button><button class="task-secondary-button" type="button" :disabled="completing" @click="completeSelectedTask">{{ completing ? 'Concluindo…' : 'Concluir task' }}</button></div>
          <p v-if="actionError" class="notification-error" role="alert">{{ actionError }}</p>
        </aside>
      </div>
      <div v-else-if="state.loading" class="notification-empty" role="status"><span class="notification-loading" aria-hidden="true"></span><strong>Carregando notificações</strong><p>Buscando os prazos das suas tarefas locais.</p></div>
      <div v-else-if="reminders.length" class="notification-empty" role="status"><span class="notification-icon" aria-hidden="true">⌕</span><strong>Nenhuma tarefa neste filtro</strong><p>Experimente outro prazo ou limpe a busca.</p><button class="task-secondary-button" type="button" @click="activeFilter = 'all'; search = ''">Limpar filtros</button></div>
      <div v-else class="notification-empty" role="status"><span class="notification-icon" aria-hidden="true">✓</span><strong>{{ state.error ? 'Não foi possível carregar os prazos' : 'Você está em dia' }}</strong><p>{{ state.error ? 'Confira a conexão com as tarefas locais e tente atualizar.' : 'As tarefas com prazo para hoje ou amanhã aparecerão aqui.' }}</p><button v-if="state.error" class="task-secondary-button" type="button" :disabled="state.refreshing" @click="refreshNotifications">Tentar novamente</button></div>
    </section>
    <ContextMenu :open="!!contextMenu" :x="contextMenu?.x || 0" :y="contextMenu?.y || 0" :items="contextMenuItems" :focus-target="contextMenu?.target" :restore-focus="!!contextMenu?.keyboard" @close="closeContextMenu" @select="selectContextAction" />
  </section>
</template>

