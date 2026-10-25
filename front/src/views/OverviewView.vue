<script setup>
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { formatWeekLabel, getCurrentWeek, getDateKey } from '../utils/calendar'

const week = getCurrentWeek()
const today = getDateKey(new Date())
const greeting = computed(() => {
  const hour = new Date().getHours()
  return hour >= 5 && hour < 12 ? 'Bom dia!' : hour >= 12 && hour < 18 ? 'Boa tarde!' : 'Boa noite!'
})
const weekLabel = formatWeekLabel(week.days[0], week.days[6])
const weeklySummaryVisible = ref(readWeeklyPreference())
const days = week.days.map((date) => ({
  key: getDateKey(date),
  weekday: new Intl.DateTimeFormat('pt-BR', { weekday: 'short' }).format(date).replace('.', ''),
  dayNumber: date.getDate(),
  dateLabel: new Intl.DateTimeFormat('pt-BR', { dateStyle: 'full' }).format(date),
  today: getDateKey(date) === today,
}))
function readWeeklyPreference() {
  try { return window.localStorage.getItem('hub.overview.weeklySummary') !== 'false' } catch { return true }
}
</script>

<template>
  <section class="hero" aria-labelledby="welcome-title">
    <RouterLink class="hero-notifications" to="/notifications" aria-label="Abrir notificações">
      <svg class="nav-icon" aria-hidden="true"><use href="#icon-bell" /></svg>
    </RouterLink>
    <div id="page-content" class="hero-layout" tabindex="-1">
      <div class="hero-copy"><h1 id="welcome-title">{{ greeting }}</h1><p class="hero-description">Seu espaço para organizar o que importa.</p></div>
      <section class="weekly-section" aria-labelledby="week-title">
        <div class="section-heading">
          <div class="week-heading"><h2 id="week-title">Esta semana</h2><p>{{ weekLabel }}</p></div>
          <RouterLink class="calendar-link" to="/calendar">Ver calendário completo <span aria-hidden="true">›</span></RouterLink>
        </div>
        <div v-if="!weeklySummaryVisible" class="week-hidden-message" role="status"><span>O resumo semanal está oculto.</span><RouterLink to="/settings">Abrir Configurações</RouterLink></div>
        <div v-else class="week-scroll" tabindex="0" aria-label="Semana atual, deslize horizontalmente para consultar os dias">
          <div class="week-strip">
            <RouterLink v-for="day in days" :key="day.key" class="week-day" :class="{ 'week-day-today': day.today }" :to="{ name: 'calendar', query: { date: day.key } }" :aria-label="'Abrir calendário para ' + day.dateLabel + ', sem itens previstos'">
              <header class="day-heading"><span class="day-name">{{ day.weekday }}</span><time class="day-number" :datetime="day.key">{{ day.dayNumber }}</time></header>
              <span class="day-item-placeholder" aria-hidden="true">—</span>
            </RouterLink>
          </div>
        </div>
        <p v-if="weeklySummaryVisible" class="week-empty-note" role="status">Nenhum item nesta semana.</p>
      </section>
    </div>
  </section>
</template>

