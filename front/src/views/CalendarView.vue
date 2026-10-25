<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
function dateKey(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}
function parseDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return new Date()
  const date = new Date(value + 'T12:00:00')
  return dateKey(date) === value ? date : new Date()
}
const selectedDate = computed(() => {
  return parseDate(route.query.date)
})
const selectedKey = computed(() => dateKey(selectedDate.value))
const todayKey = dateKey(new Date())
const month = computed(() => new Date(selectedDate.value.getFullYear(), selectedDate.value.getMonth(), 1))
const monthTitle = computed(() => new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric' }).format(month.value))
const cells = computed(() => {
  const first = month.value
  const offset = (first.getDay() + 6) % 7
  const count = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate()
  const length = Math.ceil((offset + count) / 7) * 7
  return Array.from({ length }, (_, index) => {
    const date = new Date(first.getFullYear(), first.getMonth(), index - offset + 1)
    return { key: dateKey(date), number: date.getDate(), inMonth: date.getMonth() === first.getMonth(), today: date.toDateString() === new Date().toDateString() }
  })
})
function moveMonth(delta) {
  const next = new Date(month.value.getFullYear(), month.value.getMonth() + delta, 1)
  router.push({ name: 'calendar', query: { date: dateKey(next) } })
}
function selectDate(key) {
  router.push({ name: 'calendar', query: { date: key } })
}
function goToToday() {
  selectDate(todayKey)
}
function accessibleDate(date) {
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'full' }).format(date)
}
</script>

<template>
  <section class="content-view" aria-labelledby="calendar-title">
    <header class="view-header"><p class="settings-eyebrow">SUA AGENDA</p><h1 id="calendar-title">Calendário</h1><p>Uma visão mensal para organizar sua semana.</p></header>
    <section id="page-content" class="month-calendar" aria-label="Calendário mensal">
      <div class="month-toolbar"><button type="button" aria-label="Mês anterior" @click="moveMonth(-1)">‹</button><h2 aria-live="polite">{{ monthTitle }}</h2><button class="month-today-button" type="button" @click="goToToday">Hoje</button><button type="button" aria-label="Próximo mês" @click="moveMonth(1)">›</button></div>
      <div class="month-grid month-weekdays" aria-hidden="true"><span v-for="name in ['Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb', 'Dom']" :key="name">{{ name }}</span></div>
      <div class="month-grid month-days">
        <button v-for="cell in cells" :key="cell.key" class="month-day" :class="{ 'month-day-outside': !cell.inMonth, 'month-day-today': cell.today, 'month-day-selected': cell.key === selectedKey }" type="button" :aria-label="'Selecionar ' + accessibleDate(new Date(cell.key + 'T12:00:00'))" :aria-pressed="cell.key === selectedKey" @click="selectDate(cell.key)"><time :datetime="cell.key">{{ cell.number }}</time></button>
      </div>
      <p class="empty-state" role="status">Nenhum item neste mês.</p>
    </section>
  </section>
</template>

