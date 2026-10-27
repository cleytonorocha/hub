<script setup>
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { formatWeekLabel, getCurrentWeek, getDateKey } from '../utils/calendar'
import { getPendingNotifications } from '../utils/notifications'
import { useTaskHub } from '../services/taskHub'
import { readVideoDownloaderCardPreference, readWeeklySummaryPreference } from '../utils/preferences'

const week = getCurrentWeek()
const today = getDateKey(new Date())
const { state } = useTaskHub()
const notificationCount = computed(() => getPendingNotifications(state.tasks).length)
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
const greeting = computed(() => {
  const hour = new Date().getHours()
  return hour >= 5 && hour < 12 ? 'Bom dia!' : hour >= 12 && hour < 18 ? 'Boa tarde!' : 'Boa noite!'
})
const weekLabel = formatWeekLabel(week.days[0], week.days[6])
const weeklySummaryVisible = ref(readWeeklySummaryPreference())
const videoDownloaderCardVisible = ref(readVideoDownloaderCardPreference())
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
      <div class="profile-control" aria-label="Perfil de Julia Santos"><span class="profile-avatar" aria-hidden="true">JS</span></div>
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
        <RouterLink v-for="day in days" :key="day.key" class="week-day" :class="{ 'week-day-today': day.today }" :to="{ name: 'calendar', query: { date: day.key } }" :aria-label="'Abrir calendário para ' + dayAccessibleName(day)">
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

    <div v-if="videoDownloaderCardVisible" class="overview-feature-grid">
      <RouterLink class="overview-feature-card" to="/tools/video-downloader">
        <span class="overview-feature-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v13a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 18.5v-13Z" /><path d="m10 8 5 4-5 4V8Z" /></svg></span>
        <span class="overview-feature-copy"><strong>Baixar vídeos</strong><span>Confira um link e escolha a plataforma do vídeo.</span></span>
        <span class="overview-feature-action" aria-hidden="true">›</span>
      </RouterLink>
    </div>
  </section>
</template>

