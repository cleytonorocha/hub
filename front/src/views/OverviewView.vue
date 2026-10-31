<script setup>
import { computed, nextTick, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { formatWeekLabel, getCurrentWeek, getDateKey } from '../utils/calendar'
import { getPendingNotifications } from '../utils/notifications'
import { useTaskHub } from '../services/taskHub'
import { readVideoDownloaderCardPreference, readWeeklySummaryPreference } from '../utils/preferences'
import TaskComposerModal from '../components/TaskComposerModal.vue'
import ContextMenu from '../components/ContextMenu.vue'
import { authState, hasPermission, isAdministrator } from '../services/auth'
import { getNavigationItems } from '../navigation'

const week = getCurrentWeek()
const today = getDateKey(new Date())
const { state } = useTaskHub()
const profileInitials = computed(() => (authState.user?.displayName || '').split(/\s+/).slice(0, 2).map((part) => part[0] || '').join('').toUpperCase())
const notificationCount = computed(() => getPendingNotifications(state.tasks).length)
const contextMenu = ref(null)
const composerOpen = ref(false)
const composerDate = ref('')
const composerTrigger = ref(null)
const contextMenuItems = computed(() => contextMenu.value ? [{ id: 'create', label: 'Criar task aqui' }] : [])

function tasksForDay(day) {
  return state.tasks
    .filter((task) => task.dueDate === day.key)
    .sort((first, second) => Number(first.completed) - Number(second.completed)
      || (first.dueTime || '99:99').localeCompare(second.dueTime || '99:99')
      || first.title.localeCompare(second.title, 'pt-BR'))
}
function dayAccessibleName(day) {
  const tasks = tasksForDay(day)
  const titles = tasks.slice(0, 2).map((task) => task.title).join(', ')
  return day.dateLabel + (titles ? ', tasks: ' + titles + (tasks.length > 2 ? ' e mais' : '') : '')
}

function isContextMenuKey(event) {
  return (event.shiftKey && event.key === 'F10') || event.key === 'ContextMenu' || event.key === 'Menu'
}

function openDayContext(event) {
  if (event.type === 'contextmenu' && event.button !== 2) return
  const target = event.target?.closest?.('[data-calendar-context="slot"]')
  if (!target || !event.currentTarget.contains(target)) return
  const date = target.dataset.calendarDate
  if (!date) return
  const keyboard = event.type === 'keydown'
  const bounds = target.getBoundingClientRect()
  if (event.cancelable) event.preventDefault()
  event.stopPropagation()
  contextMenu.value = {
    target,
    date,
    keyboard,
    x: keyboard ? bounds.left + Math.min(bounds.width, 16) : event.clientX,
    y: keyboard ? bounds.bottom : event.clientY,
  }
}

function handleDayContextKeydown(event) {
  if (isContextMenuKey(event)) openDayContext(event)
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
  if (action !== 'create') return
  composerDate.value = context.date
  composerTrigger.value = context.target
  composerOpen.value = true
}

async function closeComposer() {
  composerOpen.value = false
  await nextTick()
  composerTrigger.value?.focus?.()
}
const greeting = computed(() => {
  const hour = new Date().getHours()
  return hour >= 5 && hour < 12 ? 'Bom dia!' : hour >= 12 && hour < 18 ? 'Boa tarde!' : 'Boa noite!'
})
const weekLabel = formatWeekLabel(week.days[0], week.days[6])
const weeklySummaryVisible = ref(readWeeklySummaryPreference())
const videoDownloaderCardVisible = ref(readVideoDownloaderCardPreference())
const quickLinks = computed(() => getNavigationItems('overview', hasPermission, isAdministrator)
  .filter((item) => item.key !== 'video-downloader' || videoDownloaderCardVisible.value))
const days = week.days.map((date) => ({
  key: getDateKey(date),
  weekday: new Intl.DateTimeFormat('pt-BR', { weekday: 'short' }).format(date).replace('.', ''),
  dayNumber: date.getDate(),
  month: new Intl.DateTimeFormat('pt-BR', { month: 'short' }).format(date).replace('.', ''),
  dateLabel: new Intl.DateTimeFormat('pt-BR', { dateStyle: 'full' }).format(date),
  today: getDateKey(date) === today,
}))
</script>

<template>
  <section class="hero" aria-labelledby="welcome-title">
    <div class="hero-topbar">
      <RouterLink class="hero-notification-link" to="/notifications" :aria-label="notificationCount ? 'Abrir notificações, ' + notificationCount + ' pendentes' : 'Abrir notificações'" title="Notificações">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" /></svg>
        <span v-if="notificationCount" class="hero-notification-count">{{ notificationCount > 99 ? '99+' : notificationCount }}</span>
      </RouterLink>
      <div class="profile-control" :aria-label="'Perfil de ' + (authState.user?.displayName || 'usuário')"><span class="profile-avatar" aria-hidden="true">{{ profileInitials }}</span></div>
    </div>
    <div class="hero-copy"><h1 id="welcome-title">{{ greeting }}</h1><p class="hero-description">Um espaço para acompanhar o que importa.</p></div>
  </section>

  <section id="page-content" class="page-content" aria-labelledby="week-title" tabindex="-1">
    <div class="section-heading">
      <div class="week-heading"><h2 id="week-title">Esta semana</h2><p>{{ weekLabel }}</p></div>
      <RouterLink class="calendar-link" to="/calendar">Abrir calendário completo <span aria-hidden="true">›</span></RouterLink>
    </div>
    <div v-if="!weeklySummaryVisible" class="week-hidden-message" role="status"><span>O resumo semanal está oculto.</span><RouterLink to="/settings">Abrir Configurações</RouterLink></div>
    <div v-else class="week-scroll" tabindex="0" aria-label="Semana atual, deslize horizontalmente para consultar os dias">
      <div class="week-strip">
        <RouterLink v-for="day in days" :key="day.key" class="week-day" :class="{ 'week-day-today': day.today }" :to="{ name: 'calendar', query: { date: day.key, view: 'day' } }" :aria-label="'Abrir calendário para ' + dayAccessibleName(day)" data-calendar-context="slot" :data-calendar-date="day.key" @contextmenu="openDayContext" @keydown="handleDayContextKeydown">
          <header class="day-heading"><span class="day-name">{{ day.weekday }}</span><time class="day-number" :datetime="day.key">{{ day.dayNumber }} {{ day.month }}</time></header>
          <ul v-if="tasksForDay(day).length" class="week-day-tasks" :aria-label="'Tasks de ' + day.dateLabel">
            <li v-for="task in tasksForDay(day).slice(0, 2)" :key="task.id" :class="{ 'week-day-task-completed': task.completed }">
              <span class="week-day-task-dot" :style="{ '--task-color': task.color || '#A986FF' }" aria-hidden="true"></span>
              <span class="week-day-task-title">{{ task.title }}</span>
            </li>
            <li v-if="tasksForDay(day).length > 2" class="week-day-task-more" aria-label="Há mais tasks">...</li>
          </ul>
        </RouterLink>
      </div>
    </div>

    <section v-if="quickLinks.length" class="overview-shortcuts" aria-labelledby="quick-links-title">
      <div class="section-heading overview-shortcuts-heading"><h2 id="quick-links-title">Acessos rápidos</h2></div>
      <div class="overview-feature-grid">
        <RouterLink v-for="item in quickLinks" :key="item.key" class="overview-feature-card" :to="item.to">
          <span class="overview-feature-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><use :href="'#icon-' + item.icon" /></svg></span>
          <span class="overview-feature-copy"><strong>{{ item.label }}</strong><span>{{ item.description }}</span></span>
          <span class="overview-feature-action" aria-hidden="true">›</span>
        </RouterLink>
      </div>
    </section>
  </section>

  <TaskComposerModal v-if="composerOpen" :initial-date="composerDate" :require-date="true" @close="closeComposer" @created="closeComposer" />
  <ContextMenu :open="!!contextMenu" :x="contextMenu?.x || 0" :y="contextMenu?.y || 0" :items="contextMenuItems" :focus-target="contextMenu?.target" :restore-focus="!!contextMenu?.keyboard" @close="closeContextMenu" @select="selectContextAction" />
</template>

