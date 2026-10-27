<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { formatWeekLabel, getCurrentWeek, getDateKey } from '../utils/calendar'
import {
  CALENDAR_PRESENTATIONS,
  DEFAULT_CALENDAR_PRESENTATIONS,
  readCalendarPresentationPreferences,
} from '../utils/calendarPreferences'
import TaskCalendarList from '../components/TaskCalendarList.vue'
import TaskComposerModal from '../components/TaskComposerModal.vue'
import TaskDetailPanel from '../components/TaskDetailPanel.vue'
import ContextMenu from '../components/ContextMenu.vue'
import { useTaskHub } from '../services/taskHub'

const route = useRoute()
const router = useRouter()
const { state: taskState, refreshTasks, updateTask } = useTaskHub()
const composerOpen = ref(false)
const composerDate = ref('')
const composerTime = ref('')
const composerRequiresDate = ref(true)
const noDateListOpen = ref(false)
const selectedTask = ref(null)
const taskSaving = ref(false)
const taskError = ref('')
const composerTrigger = ref(null)
const detailTrigger = ref(null)
const contextMenu = ref(null)
const modes = [
  { id: 'week', label: 'Semana' },
  { id: 'day', label: 'Dia' },
  { id: 'month', label: 'Mês' },
  { id: 'year', label: 'Ano' },
]
const presentations = CALENDAR_PRESENTATIONS
const calendarPresentationPreferences = readCalendarPresentationPreferences()
const weekdays = ['Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb', 'Dom']
const fullWeekdays = ['segunda-feira', 'terça-feira', 'quarta-feira', 'quinta-feira', 'sexta-feira', 'sábado', 'domingo']
const hours = Array.from({ length: 24 }, (_, hour) => hour)
const dayPeriods = [
  { id: 'overnight', label: 'Madrugada', range: '00–05', start: 0, end: 6 },
  { id: 'morning', label: 'Manhã', range: '06–11', start: 6, end: 12 },
  { id: 'afternoon', label: 'Tarde', range: '12–17', start: 12, end: 18 },
  { id: 'evening', label: 'Noite', range: '18–23', start: 18, end: 24 },
]

function parseDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return new Date()
  const date = new Date(value + 'T12:00:00')
  return getDateKey(date) === value ? date : new Date()
}

function dateLabel(date, options = { dateStyle: 'full' }) {
  return new Intl.DateTimeFormat('pt-BR', options).format(date)
}

function monthName(date, style = 'long') {
  return new Intl.DateTimeFormat('pt-BR', { month: style }).format(date)
}

function weekdayName(date, style = 'long') {
  return new Intl.DateTimeFormat('pt-BR', { weekday: style }).format(date)
}

const selectedDate = computed(() => parseDate(route.query.date))
const selectedKey = computed(() => getDateKey(selectedDate.value))
const currentMode = computed(() => modes.some(({ id }) => id === route.query.view) ? route.query.view : 'week')
const todayKey = getDateKey(new Date())
const currentPresentation = computed(() => {
  const candidate = route.query.presentation
  const available = presentations[currentMode.value]
  return typeof candidate === 'string' && available.some(({ id }) => id === candidate)
    ? candidate
    : calendarPresentationPreferences[currentMode.value] || DEFAULT_CALENDAR_PRESENTATIONS[currentMode.value]
})
const presentationChoices = computed(() => presentations[currentMode.value])
const year = computed(() => selectedDate.value.getFullYear())
const monthLabel = computed(() => new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric' }).format(selectedDate.value))
const week = computed(() => getCurrentWeek(selectedDate.value))
const weekLabel = computed(() => formatWeekLabel(week.value.start, week.value.days[6]))
const weekDays = computed(() => week.value.days.map((date) => ({
  date,
  key: getDateKey(date),
  label: new Intl.DateTimeFormat('pt-BR', { weekday: 'long' }).format(date),
  number: date.getDate(),
  month: new Intl.DateTimeFormat('pt-BR', { month: 'short' }).format(date).replace('.', ''),
  today: getDateKey(date) === todayKey,
  selected: getDateKey(date) === selectedKey.value,
})))
const selectedDateLabel = computed(() => dateLabel(selectedDate.value))
const focusedHour = computed(() => {
  const value = route.query.hour
  if (typeof value === 'string' && /^\d{1,2}$/.test(value) && Number(value) >= 0 && Number(value) <= 23) return Number(value)
  return selectedKey.value === todayKey ? new Date().getHours() : 9
})
const showWeekend = computed(() => route.query.weekend === 'true')
const weekGridDays = computed(() => currentPresentation.value === 'workweek' && !showWeekend.value ? weekDays.value.slice(0, 5) : weekDays.value)
const filteredTasks = computed(() => taskState.tasks)
const tasksWithoutDate = computed(() => filteredTasks.value.filter((task) => !task.dueDate).sort((a, b) => a.title.localeCompare(b.title, 'pt-BR')))
const contextMenuItems = computed(() => {
  if (!contextMenu.value) return []
  if (contextMenu.value.kind === 'slot') return [{ id: 'create', label: 'Criar task aqui' }]
  const task = contextMenu.value.task
  return [
    { id: 'details', label: 'Abrir detalhes' },
    { id: 'toggle', label: task.completed ? 'Reabrir task' : 'Concluir task' },
  ]
})

function tasksForDate(key) {
  return filteredTasks.value.filter((task) => task.dueDate === key).sort((a, b) => Number(a.completed) - Number(b.completed) || (a.dueTime || '99:99').localeCompare(b.dueTime || '99:99') || a.title.localeCompare(b.title, 'pt-BR'))
}

function taskCount(key) { return tasksForDate(key).length }

function tasksForHour(key, hour) {
  return tasksForDate(key).filter((task) => task.dueTime && Number(task.dueTime.slice(0, 2)) === hour)
}

function tasksForAllDay(key) {
  return tasksForDate(key).filter((task) => !task.dueTime)
}

function openComposer(event, date = selectedKey.value, requireDate = true, time = '') {
  composerTrigger.value = event?.currentTarget || null
  composerDate.value = date || ''
  composerTime.value = time || ''
  composerRequiresDate.value = requireDate
  composerOpen.value = true
}

function openComposerForHour(event, date, hour) {
  openComposer(event, date, true, String(hour).padStart(2, '0') + ':00')
}

async function closeComposer() {
  composerOpen.value = false
  await nextTick()
  composerTrigger.value?.focus()
}

function openTask(task, event) {
  detailTrigger.value = event?.currentTarget || null
  selectedTask.value = task
  taskError.value = ''
}

function getContextTarget(event) {
  const target = event.target?.closest?.('button, [data-calendar-context], .notification-row') || event.target
  if (target?.matches?.('button, .notification-row') || target?.tabIndex >= 0) return target
  return target?.querySelector?.('button') || target
}

function openContextMenu(event, kind, payload = {}) {
  const keyboard = event.type === 'keydown'
  const target = getContextTarget(event)
  const bounds = target?.getBoundingClientRect?.()
  if (event.cancelable) event.preventDefault()
  event.stopPropagation()
  contextMenu.value = {
    kind,
    ...payload,
    target,
    keyboard,
    x: keyboard && bounds ? bounds.left + Math.min(bounds.width, 16) : event.clientX,
    y: keyboard && bounds ? bounds.bottom : event.clientY,
  }
}

function handleCalendarContext(event) {
  if (event.type === 'contextmenu' && event.button !== 2) return
  const target = event.target?.closest?.('[data-calendar-context="slot"]')
  if (!target || !event.currentTarget.contains(target)) return
  const date = target.dataset.calendarDate
  if (!date) return
  openContextMenu(event, 'slot', {
    date,
    time: target.dataset.calendarTime || '',
  })
}

function handleCalendarContextKeydown(event) {
  if (!((event.shiftKey && event.key === 'F10') || event.key === 'ContextMenu' || event.key === 'Menu')) return
  handleCalendarContext(event)
}

function openTaskContext(event, task) {
  if (event.type === 'keydown' && !((event.shiftKey && event.key === 'F10') || event.key === 'ContextMenu' || event.key === 'Menu')) return
  openContextMenu(event, 'task', { task })
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
  if (context.kind === 'slot' && action === 'create') {
    openComposer({ currentTarget: context.target }, context.date, true, context.time)
  } else if (context.kind === 'task' && action === 'details') {
    openTask(context.task, { currentTarget: context.target })
  } else if (context.kind === 'task' && action === 'toggle') {
    const task = context.task
    taskError.value = ''
    try {
      await updateTask(task, {
        dueDate: task.dueDate || null,
        dueTime: task.dueTime || null,
        completed: !task.completed,
        category: task.category,
        color: task.color,
        priority: task.priority,
        notes: task.notes,
      })
      await nextTick()
      context.target?.focus?.()
    } catch (error) {
      taskError.value = error.message
      openTask(task, { currentTarget: context.target })
    }
  }
}

watch(() => route.fullPath, () => closeContextMenu())

async function closeTaskDetail() {
  selectedTask.value = null
  await nextTick()
  detailTrigger.value?.focus()
}

function handleCreated(task) {
  composerOpen.value = false
  selectedTask.value = task
  taskError.value = ''
}

async function refreshCalendarTasks() {
  try { await refreshTasks() } catch { /* The service keeps the error in the calendar state. */ }
}

async function changeTask(changes) {
  if (!selectedTask.value) return
  taskSaving.value = true
  taskError.value = ''
  try {
    selectedTask.value = await updateTask(selectedTask.value, changes)
  } catch (error) {
    taskError.value = error.message
  } finally {
    taskSaving.value = false
  }
}

watch(() => [route.query.taskId, taskState.tasks], ([taskId]) => {
  if (typeof taskId !== 'string') return
  const task = taskState.tasks.find((item) => item.id === taskId)
  if (!task) return
  openTask(task)
  router.replace({ name: 'calendar', query: { ...route.query, taskId: undefined } })
}, { immediate: true })

function buildMonthGrid(yearValue, monthIndex, fixedWeeks = false) {
  const first = new Date(yearValue, monthIndex, 1)
  const offset = (first.getDay() + 6) % 7
  const count = new Date(yearValue, monthIndex + 1, 0).getDate()
  const weekCount = Math.ceil((offset + count) / 7)
  const cellCount = fixedWeeks ? 42 : weekCount * 7
  const cells = Array.from({ length: cellCount }, (_, index) => {
    const day = index - offset + 1
    if (day < 1 || day > count) return null
    const date = new Date(yearValue, monthIndex, day)
    const key = getDateKey(date)
    return { date, key, number: day, today: key === todayKey, selected: key === selectedKey.value }
  })
  return { first, offset, weekCount, cells }
}

const monthGrid = computed(() => buildMonthGrid(year.value, selectedDate.value.getMonth(), true))
const monthlyCells = computed(() => monthGrid.value.cells)
const monthlyWeeks = computed(() => Array.from({ length: monthGrid.value.weekCount }, (_, index) => {
  const cells = monthlyCells.value.slice(index * 7, index * 7 + 7)
  const actual = cells.filter(Boolean)
  return {
    index,
    cells,
    label: actual.length ? actual[0].number + '–' + actual[actual.length - 1].number : '',
  }
}))
const focusedMonthWeekIndex = computed(() => Math.floor((monthGrid.value.offset + selectedDate.value.getDate() - 1) / 7))
const focusedWeek = computed(() => getCurrentWeek(selectedDate.value).days.map((date) => ({
  date,
  key: getDateKey(date),
  label: new Intl.DateTimeFormat('pt-BR', { weekday: 'long' }).format(date),
  number: date.getDate(),
  month: new Intl.DateTimeFormat('pt-BR', { month: 'short' }).format(date).replace('.', ''),
  inMonth: date.getMonth() === selectedDate.value.getMonth() && date.getFullYear() === selectedDate.value.getFullYear(),
  today: getDateKey(date) === todayKey,
  selected: getDateKey(date) === selectedKey.value,
})))
const focusedWeekLabel = computed(() => formatWeekLabel(focusedWeek.value[0].date, focusedWeek.value[6].date))
const focusedMonthGrid = computed(() => buildMonthGrid(year.value, selectedDate.value.getMonth(), true))
const annualMonths = computed(() => Array.from({ length: 12 }, (_, monthIndex) => {
  const grid = buildMonthGrid(year.value, monthIndex)
  return {
    ...grid,
    title: new Intl.DateTimeFormat('pt-BR', { month: 'long' }).format(grid.first),
    shortTitle: new Intl.DateTimeFormat('pt-BR', { month: 'short' }).format(grid.first).replace('.', ''),
  }
}))
const annualQuarters = computed(() => Array.from({ length: 4 }, (_, index) => ({
  title: 'Trimestre ' + (index + 1),
  months: annualMonths.value.slice(index * 3, index * 3 + 3),
})))

function navigate(date, view = currentMode.value, presentation, options = {}) {
  const selectedPresentation = presentation || (
    view === currentMode.value ? currentPresentation.value : (
      calendarPresentationPreferences[view] || DEFAULT_CALENDAR_PRESENTATIONS[view]
    )
  )
  const query = {
    date: getDateKey(date),
    view,
    presentation: selectedPresentation,
  }
  if (view === 'day' && Number.isInteger(options.hour)) query.hour = String(options.hour)
  if (view === 'week' && selectedPresentation === 'workweek') query.weekend = String(Boolean(options.weekend))
  router.push({ name: 'calendar', query })
}

function setMode(view) {
  navigate(selectedDate.value, view, calendarPresentationPreferences[view] || DEFAULT_CALENDAR_PRESENTATIONS[view])
}

function setPresentation(presentation) {
  navigate(selectedDate.value, currentMode.value, presentation, {
    hour: currentMode.value === 'day' ? focusedHour.value : undefined,
    weekend: showWeekend.value || (
      currentMode.value === 'week' && presentation === 'workweek' && [0, 6].includes(selectedDate.value.getDay())
    ),
  })
}

function moveFocusedHour(delta) {
  const date = new Date(selectedDate.value)
  date.setHours(focusedHour.value + delta)
  navigate(date, 'day', 'ruler', { hour: date.getHours() })
}

function selectWeekDate(date) {
  navigate(date, 'week', currentPresentation.value, { weekend: showWeekend.value })
}

function toggleWeekend() {
  navigate(selectedDate.value, 'week', 'workweek', { weekend: !showWeekend.value })
}

function selectMonthDate(date) {
  navigate(date, 'month', currentPresentation.value)
}

function moveFocusedMonthWeek(delta) {
  const date = new Date(selectedDate.value)
  date.setDate(date.getDate() + delta * 7)
  navigate(date, 'month', 'focus')
}

function selectYearDate(date) {
  navigate(date, 'day', calendarPresentationPreferences.day || DEFAULT_CALENDAR_PRESENTATIONS.day)
}

function selectYearMonth(monthIndex) {
  const targetYear = selectedDate.value.getFullYear()
  const lastDay = new Date(targetYear, monthIndex + 1, 0).getDate()
  const date = new Date(targetYear, monthIndex, Math.min(selectedDate.value.getDate(), lastDay))
  navigate(date, 'year', 'focus')
}

function movePeriod(delta) {
  const date = new Date(selectedDate.value)
  if (currentMode.value === 'day') {
    date.setDate(date.getDate() + delta)
  } else if (currentMode.value === 'week') {
    date.setDate(date.getDate() + 7 * delta)
  } else if (currentMode.value === 'month') {
    const targetMonth = new Date(date.getFullYear(), date.getMonth() + delta, 1)
    targetMonth.setDate(Math.min(date.getDate(), new Date(targetMonth.getFullYear(), targetMonth.getMonth() + 1, 0).getDate()))
    date.setTime(targetMonth.getTime())
  } else {
    const targetYear = date.getFullYear() + delta
    date.setDate(Math.min(date.getDate(), new Date(targetYear, date.getMonth() + 1, 0).getDate()))
    date.setFullYear(targetYear)
  }
  navigate(date, currentMode.value, currentPresentation.value, {
    hour: currentMode.value === 'day' && currentPresentation.value === 'ruler' ? focusedHour.value : undefined,
    weekend: showWeekend.value,
  })
}

function goToToday() {
  navigate(new Date(), currentMode.value, currentPresentation.value, {
    hour: currentMode.value === 'day' && currentPresentation.value === 'ruler' ? new Date().getHours() : undefined,
    weekend: showWeekend.value,
  })
}

function handleModeKeydown(event) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  const tabs = [...event.currentTarget.querySelectorAll('[role="tab"]')]
  const activeIndex = tabs.indexOf(event.target)
  if (activeIndex < 0) return

  event.preventDefault()
  const nextIndex = event.key === 'Home'
    ? 0
    : event.key === 'End'
      ? tabs.length - 1
      : (activeIndex + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
  tabs[nextIndex].focus()
  setMode(modes[nextIndex].id)
}
</script>

<template>
  <section class="content-view calendar-view" aria-labelledby="calendar-title">
    <header class="view-header calendar-page-header">
      <div><p class="settings-eyebrow">CALENDÁRIO</p><h1 id="calendar-title">Calendário</h1><p>Escolha uma visão diária, semanal, mensal ou anual.</p></div>
    </header>

    <section id="page-content" class="calendar-workspace" aria-label="Navegação do calendário">
      <div class="calendar-toolbar">
        <div class="calendar-modes" role="tablist" aria-label="Escala do calendário" @keydown="handleModeKeydown">
          <button v-for="item in modes" :id="'calendar-tab-' + item.id" :key="item.id" class="calendar-mode" :class="{ 'calendar-mode-active': currentMode === item.id }" type="button" role="tab" :tabindex="currentMode === item.id ? 0 : -1" :aria-selected="currentMode === item.id" :aria-controls="'calendar-panel-' + item.id" @click="setMode(item.id)">{{ item.label }}</button>
        </div>
        <label class="calendar-presentation-control">
          <span>Apresentação</span>
          <select :value="currentPresentation" aria-label="Apresentação do calendário" @change="setPresentation($event.target.value)">
            <option v-for="item in presentationChoices" :key="item.id" :value="item.id">{{ item.label }}</option>
          </select>
        </label>
        <div class="calendar-period-controls">
          <button class="period-button" type="button" :aria-label="(currentMode === 'day' ? 'Dia' : currentMode === 'week' ? 'Semana' : currentMode === 'month' ? 'Mês' : 'Ano') + ' anterior'" @click="movePeriod(-1)">‹</button>
          <h2 class="calendar-period-title" aria-live="polite">{{ currentMode === 'day' ? selectedDateLabel : currentMode === 'week' ? weekLabel : currentMode === 'month' ? monthLabel : year }}</h2>
          <button class="period-button" type="button" :aria-label="'Próximo ' + (currentMode === 'day' ? 'dia' : currentMode === 'week' ? 'semana' : currentMode === 'month' ? 'mês' : 'ano')" @click="movePeriod(1)">›</button>
          <button class="today-button" type="button" @click="goToToday">Hoje</button>
        </div>
      </div>

      <section class="calendar-task-toolbar" aria-label="Ferramentas de Tasks">
        <div class="calendar-task-toolbar-status" role="status">
          <span class="task-connection-dot task-connection-connected" aria-hidden="true"></span>
          <span>Tarefas locais</span>
        </div>
        <div class="calendar-task-toolbar-actions">
          <button class="task-secondary-button" type="button" :disabled="taskState.refreshing" @click="refreshCalendarTasks">{{ taskState.refreshing ? 'Atualizando…' : 'Atualizar' }}</button>
          <button class="task-secondary-button" type="button" :aria-expanded="noDateListOpen" @click="noDateListOpen = !noDateListOpen">Sem data · {{ tasksWithoutDate.length }}</button>
          <button class="task-primary-button" type="button" @click="openComposer($event, selectedKey, true)">+ Nova Task</button>
        </div>
        <p v-if="taskState.error" class="task-inline-error" role="alert">{{ taskState.error }}</p>
      </section>

      <section v-if="noDateListOpen" class="undated-task-panel" aria-labelledby="undated-task-title">
        <header><div><p class="settings-eyebrow">TASKS</p><h2 id="undated-task-title">Sem data</h2></div><button class="task-primary-button" type="button" @click="openComposer($event, '', false)">+ Nova Task sem data</button></header>
        <TaskCalendarList v-if="tasksWithoutDate.length" :tasks="tasksWithoutDate" :limit="100" @open="openTask" @context="openTaskContext" />
        <p v-else class="task-empty-copy">Não há Tasks sem data nas listas selecionadas.</p>
      </section>

      <section v-show="currentMode === 'week'" id="calendar-panel-week" class="calendar-panel week-calendar-panel" role="tabpanel" aria-labelledby="calendar-tab-week" tabindex="0" @contextmenu="handleCalendarContext" @keydown="handleCalendarContextKeydown">
        <label v-if="currentPresentation === 'workweek'" class="weekend-toggle">
          <input type="checkbox" :checked="showWeekend" @change="toggleWeekend">
          <span>Mostrar fim de semana</span>
        </label>
        <p v-if="currentPresentation === 'columns' || currentPresentation === 'workweek'" class="week-grid-scroll-note">Deslize horizontalmente para ver os outros dias da semana <span aria-hidden="true">↔</span></p>
        <div v-if="currentPresentation === 'columns' || currentPresentation === 'workweek'" class="week-calendar-grid" :class="{ 'week-calendar-grid-workweek': currentPresentation === 'workweek' && !showWeekend }" aria-label="Grade semanal por horário">
          <div class="week-calendar-hour-heading">Horário</div>
          <button v-for="day in weekGridDays" :key="day.key" class="week-calendar-day" data-calendar-context="slot" :data-calendar-date="day.key" :class="{ 'week-calendar-day-selected': day.selected, 'week-calendar-day-today': day.today }" type="button" :aria-label="dateLabel(day.date)" :aria-pressed="day.selected" @click="selectWeekDate(day.date)">
            <span>{{ day.label }}</span><time :datetime="day.key">{{ day.number }} {{ day.month }}</time>
          </button>
          <div class="week-calendar-all-day-label">Dia todo</div>
          <div v-for="day in weekGridDays" :key="'all-day-' + day.key" class="week-calendar-all-day" data-calendar-context="slot" :data-calendar-date="day.key" :aria-label="'Criar task para ' + dateLabel(day.date) + ', dia todo'" role="button" tabindex="0" @click.self="openComposer($event, day.key, true)" @keydown.enter.self.prevent="openComposer($event, day.key, true)" @keydown.space.self.prevent="openComposer($event, day.key, true)"><TaskCalendarList :tasks="tasksForAllDay(day.key)" @open="openTask" @context="openTaskContext" /></div>
          <template v-for="hour in hours" :key="hour">
            <time class="week-calendar-hour">{{ String(hour).padStart(2, '0') }}:00</time>
            <div v-for="day in weekGridDays" :key="day.key + '-' + hour" class="week-calendar-hour-cell" data-calendar-context="slot" :data-calendar-date="day.key" :data-calendar-time="String(hour).padStart(2, '0') + ':00'"><button type="button" :aria-label="'Criar task para ' + dateLabel(day.date) + ' às ' + String(hour).padStart(2, '0') + ':00'" @click="openComposerForHour($event, day.key, hour)"></button><TaskCalendarList :tasks="tasksForHour(day.key, hour)" @open="openTask" @context="openTaskContext" /></div>
          </template>
        </div>
        <ol v-else-if="currentPresentation === 'list'" class="week-day-list">
          <li v-for="day in weekDays" :key="day.key">
            <button class="week-list-day" data-calendar-context="slot" :data-calendar-date="day.key" :class="{ 'week-list-day-selected': day.selected, 'week-list-day-today': day.today }" type="button" :aria-label="'Criar task para ' + dateLabel(day.date)" @click="openComposer($event, day.key, true)">
              <span>{{ day.label }}</span><time :datetime="day.key">{{ day.number }} {{ day.month }}</time><small v-if="taskCount(day.key)" class="task-day-badge">{{ taskCount(day.key) }} Tasks</small>
            </button>
            <TaskCalendarList :tasks="tasksForDate(day.key)" @open="openTask" @context="openTaskContext" />
          </li>
        </ol>
        <div v-else class="week-focus-layout">
          <nav class="week-focus-strip" aria-label="Escolher dia da semana">
            <button v-for="day in weekDays" :key="day.key" data-calendar-context="slot" :data-calendar-date="day.key" type="button" :class="{ 'week-focus-selected': day.selected, 'week-focus-today': day.today }" :aria-label="'Criar task para ' + dateLabel(day.date)" @click="openComposer($event, day.key, true)">
              <span>{{ day.label.slice(0, 3) }}</span><time :datetime="day.key">{{ day.number }} {{ day.month }}</time><small v-if="taskCount(day.key)" class="task-day-badge">{{ taskCount(day.key) }}</small>
            </button>
          </nav>
          <div class="week-focus-heading"><h3>{{ selectedDateLabel }}</h3><span>Dia selecionado</span></div>
          <TaskCalendarList :tasks="tasksForDate(selectedKey)" @open="openTask" @context="openTaskContext" />
          <div class="week-focus-hours">
            <div v-for="hour in hours" :key="hour" class="hour-row"><time>{{ String(hour).padStart(2, '0') }}:00</time><div class="hour-slot-container" data-calendar-context="slot" :data-calendar-date="selectedKey" :data-calendar-time="String(hour).padStart(2, '0') + ':00'"><button class="hour-slot" type="button" :aria-label="'Criar task às ' + String(hour).padStart(2, '0') + ':00 em ' + selectedDateLabel" @click="openComposerForHour($event, selectedKey, hour)"></button><TaskCalendarList :tasks="tasksForHour(selectedKey, hour)" @open="openTask" @context="openTaskContext" /></div></div>
          </div>
        </div>
      </section>

      <section v-show="currentMode === 'day'" id="calendar-panel-day" class="calendar-panel daily-calendar-panel" role="tabpanel" aria-labelledby="calendar-tab-day" :aria-label="'Calendário diário de ' + selectedDateLabel" tabindex="0" @contextmenu="handleCalendarContext" @keydown="handleCalendarContextKeydown">
        <section class="day-task-agenda" aria-labelledby="day-task-agenda-title"><h3 id="day-task-agenda-title">Tasks do dia</h3><TaskCalendarList v-if="tasksForDate(selectedKey).length" :tasks="tasksForDate(selectedKey)" :limit="100" @open="openTask" @context="openTaskContext" /><p v-else>Não há Tasks nesta data.</p></section>
        <template v-if="currentPresentation === 'timeline'">
          <div v-for="hour in hours" :key="hour" class="hour-row">
            <time>{{ String(hour).padStart(2, '0') }}:00</time>
            <div class="hour-slot-container" data-calendar-context="slot" :data-calendar-date="selectedKey" :data-calendar-time="String(hour).padStart(2, '0') + ':00'"><button class="hour-slot" type="button" :aria-label="'Criar task às ' + String(hour).padStart(2, '0') + ':00 em ' + selectedDateLabel" @click="openComposerForHour($event, selectedKey, hour)"></button><TaskCalendarList :tasks="tasksForHour(selectedKey, hour)" @open="openTask" @context="openTaskContext" /></div>
          </div>
        </template>
        <template v-else-if="currentPresentation === 'ruler'">
          <div class="focus-hour-summary"><span>Hora em foco</span><strong>{{ String(focusedHour).padStart(2, '0') }}:00</strong></div>
          <div class="hour-ruler" aria-label="Régua com as 24 horas do dia">
            <button v-for="hour in hours" :key="hour" class="hour-ruler-cell" data-calendar-context="slot" :data-calendar-date="selectedKey" :data-calendar-time="String(hour).padStart(2, '0') + ':00'" type="button" :aria-label="'Criar task às ' + String(hour).padStart(2, '0') + ':00 em ' + selectedDateLabel" @click="openComposerForHour($event, selectedKey, hour)">{{ String(hour).padStart(2, '0') }}:00</button>
          </div>
          <div class="hour-stepper"><button type="button" aria-label="Hora anterior" @click="moveFocusedHour(-1)">‹ Hora</button><button type="button" aria-label="Próxima hora" @click="moveFocusedHour(1)">Hora ›</button></div>
        </template>
        <div v-else-if="currentPresentation === 'periods'" class="day-periods">
          <details v-for="period in dayPeriods" :key="period.id" class="day-period" :open="focusedHour >= period.start && focusedHour < period.end">
            <summary><span>{{ period.label }}</span><time>{{ period.range }}h</time></summary>
            <div class="period-hour-list">
              <button v-for="hour in hours.slice(period.start, period.end)" :key="hour" data-calendar-context="slot" :data-calendar-date="selectedKey" :data-calendar-time="String(hour).padStart(2, '0') + ':00'" type="button" :aria-label="'Criar task às ' + String(hour).padStart(2, '0') + ':00 em ' + selectedDateLabel" @click="openComposerForHour($event, selectedKey, hour)">{{ String(hour).padStart(2, '0') }}:00</button>
            </div>
          </details>
        </div>
        <template v-else>
          <div class="focus-hour-summary"><span>Hora selecionada</span><strong>{{ String(focusedHour).padStart(2, '0') }}:00</strong></div>
          <div class="hour-choice-grid">
            <button v-for="hour in hours" :key="hour" data-calendar-context="slot" :data-calendar-date="selectedKey" :data-calendar-time="String(hour).padStart(2, '0') + ':00'" type="button" :aria-label="'Criar task às ' + String(hour).padStart(2, '0') + ':00 em ' + selectedDateLabel" @click="openComposerForHour($event, selectedKey, hour)">{{ String(hour).padStart(2, '0') }}:00</button>
          </div>
        </template>
      </section>

      <section v-show="currentMode === 'month'" id="calendar-panel-month" class="calendar-panel monthly-calendar-panel" role="tabpanel" aria-labelledby="calendar-tab-month" :aria-label="'Calendário mensal de ' + monthLabel" tabindex="0" @contextmenu="handleCalendarContext" @keydown="handleCalendarContextKeydown">
        <div v-if="currentPresentation === 'grid'" class="monthly-calendar-grid">
          <div class="monthly-weekdays"><span v-for="(day, index) in weekdays" :key="day" :aria-label="fullWeekdays[index]">{{ day }}</span></div>
          <div class="monthly-days">
            <span v-for="(cell, index) in monthlyCells" :key="cell?.key ?? 'blank-' + index" class="monthly-day-cell" :data-calendar-context="cell ? 'slot' : undefined" :data-calendar-date="cell?.key">
              <button v-if="cell" class="monthly-day" :class="{ 'monthly-day-today': cell.today, 'monthly-day-selected': cell.selected }" type="button" :aria-label="'Criar task para ' + dateLabel(cell.date)" @click="openComposer($event, cell.key, true)">{{ cell.number }}</button>
              <TaskCalendarList v-if="cell" :tasks="tasksForDate(cell.key)" compact @open="openTask" @context="openTaskContext" />
            </span>
          </div>
        </div>
        <div v-else-if="currentPresentation === 'weeks'" class="month-week-list">
          <details v-for="weekRow in monthlyWeeks" :key="weekRow.index" :open="weekRow.index === focusedMonthWeekIndex">
            <summary>Semana {{ weekRow.index + 1 }} <span>{{ weekRow.label }} de {{ monthName(selectedDate) }}</span></summary>
            <div class="week-focus-strip">
              <button v-for="cell in weekRow.cells.filter(Boolean)" :key="cell.key" data-calendar-context="slot" :data-calendar-date="cell.key" type="button" :class="{ 'week-focus-selected': cell.selected, 'week-focus-today': cell.today }" :aria-label="'Criar task para ' + dateLabel(cell.date)" @click="openComposer($event, cell.key, true)">
                <span>{{ weekdayName(cell.date, 'short') }}</span><time :datetime="cell.key">{{ cell.number }}</time><small v-if="taskCount(cell.key)" class="task-day-badge">{{ taskCount(cell.key) }}</small>
              </button>
            </div>
          </details>
        </div>
        <ol v-else-if="currentPresentation === 'list'" class="month-date-list">
          <li v-for="cell in monthlyCells.filter(Boolean)" :key="cell.key">
            <button type="button" data-calendar-context="slot" :data-calendar-date="cell.key" :class="{ 'month-date-selected': cell.selected, 'month-date-today': cell.today }" :aria-label="'Criar task para ' + dateLabel(cell.date)" @click="openComposer($event, cell.key, true)">
              <span>{{ weekdayName(cell.date) }}</span><time :datetime="cell.key">{{ cell.number }} {{ monthName(cell.date) }}</time><small v-if="taskCount(cell.key)" class="task-day-badge">{{ taskCount(cell.key) }} Tasks</small>
            </button>
          </li>
        </ol>
        <div v-else class="month-focus-view">
          <div class="month-focus-controls"><button type="button" aria-label="Semana anterior no mês" @click="moveFocusedMonthWeek(-1)">‹ Semana</button><span>Semana {{ focusedMonthWeekIndex + 1 }} de {{ monthGrid.weekCount }}</span><button type="button" aria-label="Próxima semana no mês" @click="moveFocusedMonthWeek(1)">Semana ›</button></div>
          <h3>{{ focusedWeekLabel }}</h3>
          <div class="week-focus-strip">
            <button v-for="day in focusedWeek" :key="day.key" data-calendar-context="slot" :data-calendar-date="day.key" type="button" :class="{ 'week-focus-selected': day.selected, 'week-focus-today': day.today, 'week-focus-outside': !day.inMonth }" :aria-label="'Criar task para ' + dateLabel(day.date)" @click="openComposer($event, day.key, true)">
              <span>{{ day.label.slice(0, 3) }}</span><time :datetime="day.key">{{ day.number }} {{ day.month }}</time><small v-if="taskCount(day.key)" class="task-day-badge">{{ taskCount(day.key) }}</small>
            </button>
          </div>
        </div>
      </section>

      <section v-show="currentMode === 'year'" id="calendar-panel-year" class="calendar-panel annual-calendar-panel" role="tabpanel" aria-labelledby="calendar-tab-year" :aria-label="'Calendário anual de ' + year" tabindex="0" @contextmenu="handleCalendarContext" @keydown="handleCalendarContextKeydown">
        <div v-if="currentPresentation === 'matrix'" class="annual-month-grid">
          <section v-for="month in annualMonths" :key="month.title" class="annual-month">
            <h3><button type="button" @click="navigate(month.first, 'month', 'grid')">{{ month.title }}</button></h3>
            <div class="annual-weekdays"><span v-for="(day, index) in weekdays" :key="day + index" :aria-label="fullWeekdays[index]">{{ day[0] }}</span></div>
            <div class="annual-days">
              <span v-for="(cell, index) in month.cells" :key="cell?.key ?? 'blank-' + index" class="annual-day-cell" :data-calendar-context="cell ? 'slot' : undefined" :data-calendar-date="cell?.key">
                <button v-if="cell" class="annual-day" :class="{ 'annual-day-today': cell.today, 'annual-day-selected': cell.selected }" type="button" :aria-label="'Criar task para ' + dateLabel(cell.date)" @click="openComposer($event, cell.key, true)">{{ cell.number }}</button>
                <TaskCalendarList v-if="cell" :tasks="tasksForDate(cell.key)" compact @open="openTask" @context="openTaskContext" />
              </span>
            </div>
          </section>
        </div>
        <div v-else-if="currentPresentation === 'quarters'" class="annual-quarter-grid">
          <section v-for="quarter in annualQuarters" :key="quarter.title" class="annual-quarter">
            <h3>{{ quarter.title }}</h3>
            <div class="annual-quarter-months">
              <section v-for="month in quarter.months" :key="month.title" class="annual-month">
                <h4><button type="button" @click="navigate(month.first, 'month', 'grid')">{{ month.title }}</button></h4>
                <div class="annual-weekdays"><span v-for="(day, index) in weekdays" :key="day + index" :aria-label="fullWeekdays[index]">{{ day[0] }}</span></div>
                <div class="annual-days"><span v-for="(cell, index) in month.cells" :key="cell?.key ?? 'blank-' + index" class="annual-day-cell" :data-calendar-context="cell ? 'slot' : undefined" :data-calendar-date="cell?.key"><button v-if="cell" class="annual-day" :class="{ 'annual-day-today': cell.today, 'annual-day-selected': cell.selected }" type="button" :aria-label="'Criar task para ' + dateLabel(cell.date)" @click="openComposer($event, cell.key, true)">{{ cell.number }}</button><TaskCalendarList v-if="cell" :tasks="tasksForDate(cell.key)" compact @open="openTask" @context="openTaskContext" /></span></div>
              </section>
            </div>
          </section>
        </div>
        <div v-else-if="currentPresentation === 'list'" class="annual-month-list">
          <details v-for="month in annualMonths" :key="month.title">
            <summary>{{ month.title }} <span>{{ month.cells.filter(Boolean).length }} dias</span></summary>
            <div class="annual-list-days"><button v-for="cell in month.cells.filter(Boolean)" :key="cell.key" data-calendar-context="slot" :data-calendar-date="cell.key" type="button" :class="{ 'annual-day-today': cell.today, 'annual-day-selected': cell.selected }" :aria-label="'Criar task para ' + dateLabel(cell.date)" @click="openComposer($event, cell.key, true)">{{ cell.number }}<small v-if="taskCount(cell.key)">{{ taskCount(cell.key) }}</small></button></div>
          </details>
        </div>
        <div v-else class="year-month-focus">
          <nav class="year-month-selector" aria-label="Selecionar mês do ano">
            <button v-for="(month, index) in annualMonths" :key="month.title" type="button" :class="{ 'year-month-active': selectedDate.getMonth() === index }" :aria-pressed="selectedDate.getMonth() === index" @click="selectYearMonth(index)">{{ month.shortTitle }}</button>
          </nav>
          <h3>{{ monthLabel }}</h3>
          <div class="monthly-calendar-grid">
            <div class="monthly-weekdays"><span v-for="(day, index) in weekdays" :key="day" :aria-label="fullWeekdays[index]">{{ day }}</span></div>
            <div class="monthly-days">
              <span v-for="(cell, index) in focusedMonthGrid.cells" :key="cell?.key ?? 'blank-' + index" class="monthly-day-cell" :data-calendar-context="cell ? 'slot' : undefined" :data-calendar-date="cell?.key">
                <button v-if="cell" class="monthly-day" :class="{ 'monthly-day-today': cell.today, 'monthly-day-selected': cell.selected }" type="button" :aria-label="'Criar task para ' + dateLabel(cell.date)" @click="openComposer($event, cell.key, true)">{{ cell.number }}</button>
                <TaskCalendarList v-if="cell" :tasks="tasksForDate(cell.key)" compact @open="openTask" @context="openTaskContext" />
              </span>
            </div>
          </div>
        </div>
      </section>
      <section v-if="currentMode !== 'day'" class="selected-date-task-agenda" aria-labelledby="selected-date-task-title">
        <div><p class="settings-eyebrow">TASKS DA DATA</p><h2 id="selected-date-task-title">{{ selectedDateLabel }}</h2></div>
        <TaskCalendarList v-if="tasksForDate(selectedKey).length" :tasks="tasksForDate(selectedKey)" :limit="100" @open="openTask" @context="openTaskContext" />
        <p v-else>Não há Tasks nesta data.</p>
      </section>
    </section>
    <TaskComposerModal v-if="composerOpen" :initial-date="composerDate" :initial-time="composerTime" :require-date="composerRequiresDate" @close="closeComposer" @created="handleCreated" />
    <TaskDetailPanel v-if="selectedTask" :task="selectedTask" :saving="taskSaving" :error="taskError" @close="closeTaskDetail" @change="changeTask" />
    <ContextMenu :open="!!contextMenu" :x="contextMenu?.x || 0" :y="contextMenu?.y || 0" :items="contextMenuItems" :focus-target="contextMenu?.target" :restore-focus="!!contextMenu?.keyboard" @close="closeContextMenu" @select="selectContextAction" />
  </section>
</template>
