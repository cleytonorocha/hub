<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { authState, hasPermission } from '../services/auth'
import { createHabit, listHabits, markHabit, pauseHabit, resumeHabit, unmarkHabit, updateHabit } from '../services/habits'
import { formatDateLabel, getCurrentWeek, getDateKey } from '../utils/calendar'

const weekdays = [
  { value: 1, label: 'Segunda' }, { value: 2, label: 'Terça' }, { value: 3, label: 'Quarta' },
  { value: 4, label: 'Quinta' }, { value: 5, label: 'Sexta' }, { value: 6, label: 'Sábado' }, { value: 7, label: 'Domingo' },
]
const activeTab = ref('today')
const weekStart = ref(getDateKey(getCurrentWeek().start))
const data = ref(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const formError = ref('')
const selectedId = ref('')
const form = reactive({ name: '', startDate: '', days: [], revision: null })
const canWrite = computed(() => hasPermission('HABITS_WRITE'))
const todayHabits = computed(() => (data.value?.habits || []).flatMap((habit) => {
  const day = habit.days.find((entry) => entry.date === data.value.today)
  return day && ['DONE', 'PENDING_TODAY'].includes(day.status) ? [{ ...habit, todayDay: day }] : []
}))
const hasHabits = computed(() => Boolean(data.value?.habits.length))
const historyLabel = computed(() => {
  if (!data.value) return ''
  const start = fromDateKey(data.value.weekStart)
  const end = fromDateKey(data.value.weekEnd)
  return `${formatDateLabel(start)} — ${formatDateLabel(end)}`
})
const isCurrentWeek = computed(() => weekStart.value === getDateKey(getCurrentWeek().start))

onMounted(() => load())

function fromDateKey(value) {
  const [year, month, day] = value.split('-').map(Number)
  return new Date(year, month - 1, day)
}

function shiftWeek(amount) {
  const date = fromDateKey(weekStart.value)
  date.setDate(date.getDate() + amount * 7)
  weekStart.value = getDateKey(date)
  load()
}

async function load() {
  loading.value = true
  error.value = ''
  try { data.value = await listHabits(weekStart.value) }
  catch (cause) { error.value = cause.message }
  finally { loading.value = false }
}

function showCreate() {
  selectedId.value = ''
  form.name = ''
  form.startDate = data.value?.today || getDateKey(new Date())
  form.days = []
  form.revision = null
  formError.value = ''
  notice.value = ''
  activeTab.value = 'configure'
}

function editHabit(habit) {
  selectedId.value = habit.id
  form.name = habit.name
  form.startDate = habit.startDate
  form.days = weekdays.filter(({ value }) => habit.weekdaysMask & (1 << (value - 1))).map(({ value }) => value)
  form.revision = habit.revision
  formError.value = ''
  notice.value = ''
  activeTab.value = 'configure'
}

function openHistory() {
  weekStart.value = getDateKey(getCurrentWeek().start)
  activeTab.value = 'history'
  load()
}

async function saveHabit() {
  if (!canWrite.value || saving.value) return
  formError.value = ''
  notice.value = ''
  if (!form.name.trim()) { formError.value = 'Informe um nome para o hábito.'; return }
  if (!form.days.length) { formError.value = 'Selecione ao menos um dia previsto.'; return }
  saving.value = true
  try {
    if (selectedId.value) {
      await updateHabit(selectedId.value, { name: form.name, startDate: form.startDate, weekdays: form.days, revision: form.revision })
      notice.value = 'Hábito atualizado.'
    } else {
      await createHabit({ name: form.name, startDate: form.startDate, weekdays: form.days })
      notice.value = 'Hábito criado.'
      selectedId.value = ''
    }
    await load()
    if (selectedId.value) {
      const current = data.value?.habits.find((habit) => habit.id === selectedId.value)
      if (current) {
        form.revision = current.revision
        form.startDate = current.startDate
      }
    }
  } catch (cause) { formError.value = cause.message }
  finally { saving.value = false }
}

async function toggleCheckIn(habit) {
  if (!canWrite.value || saving.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    if (habit.todayDay.checked) await unmarkHabit(habit.id, data.value.today)
    else await markHabit(habit.id, data.value.today)
    notice.value = habit.todayDay.checked ? 'Registro de hoje removido.' : 'Registro de hoje salvo.'
    await load()
  } catch (cause) { error.value = cause.message }
  finally { saving.value = false }
}

async function toggleHistoryCheckIn(habit, day) {
  if (!canWrite.value || saving.value || day.date > data.value.today) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    if (day.checked) await unmarkHabit(habit.id, day.date)
    else await markHabit(habit.id, day.date)
    notice.value = day.checked ? 'Registro removido.' : 'Registro salvo.'
    await load()
  } catch (cause) { error.value = cause.message }
  finally { saving.value = false }
}

async function togglePause(habit) {
  if (!canWrite.value || saving.value) return
  saving.value = true
  formError.value = ''
  notice.value = ''
  try {
    if (habit.paused) await resumeHabit(habit.id)
    else await pauseHabit(habit.id)
    notice.value = habit.paused ? 'Hábito retomado.' : 'Hábito pausado.'
    await load()
    const updated = data.value?.habits.find((item) => item.id === habit.id)
    if (updated) editHabit(updated)
  } catch (cause) { formError.value = cause.message }
  finally { saving.value = false }
}

function statusLabel(day) {
  return ({
    DONE: 'Feito', PENDING_TODAY: 'Ainda não registrado', NOT_RECORDED: 'Não registrado',
    NOT_SCHEDULED: 'Não previsto', BEFORE_START: 'Antes do início', PAUSED: 'Pausado', FUTURE: 'Previsto',
  })[day.status] || 'Não previsto'
}

function todaySummary(habit) {
  return habit.todayDay.checked ? 'Registro feito hoje' : 'Previsto para hoje'
}
</script>

<template>
  <section class="habits-page" aria-labelledby="habits-title">
    <header class="habits-header">
      <div>
        <p class="habits-eyebrow">FERRAMENTAS</p>
        <h1 id="habits-title">Hábitos e rotinas</h1>
        <p class="habits-intro">Acompanhe suas práticas nos dias que você escolheu.</p>
      </div>
      <button v-if="canWrite" class="habits-primary" type="button" @click="showCreate">Novo hábito</button>
    </header>

    <nav class="habits-tabs" aria-label="Seções de hábitos">
      <button type="button" :aria-pressed="activeTab === 'today'" @click="activeTab = 'today'; load()">Hoje</button>
      <button v-if="canWrite" type="button" :aria-pressed="activeTab === 'configure'" @click="activeTab = 'configure'; load()">Configurar hábito</button>
      <button type="button" :aria-pressed="activeTab === 'history'" @click="openHistory">Histórico semanal</button>
    </nav>

    <p v-if="notice" class="habits-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="habits-error" role="alert">{{ error }} <button type="button" @click="load">Tentar novamente</button></p>
    <p v-if="loading && !data" class="habits-loading" role="status">Carregando hábitos…</p>

    <template v-if="data && activeTab === 'today'">
      <div v-if="!hasHabits" class="habits-empty">
        <h2>Nenhum hábito cadastrado</h2>
        <p>Crie um hábito para registrar suas práticas nos dias previstos.</p>
        <button v-if="canWrite" class="habits-primary" type="button" @click="showCreate">Criar primeiro hábito</button>
      </div>
      <div v-else-if="!todayHabits.length" class="habits-empty">
        <h2>Nenhum hábito previsto para hoje</h2>
        <p>Seus registros continuam disponíveis no histórico semanal.</p>
        <button type="button" class="habits-secondary" @click="openHistory">Ver histórico</button>
      </div>
      <ul v-else class="today-habit-list">
        <li v-for="habit in todayHabits" :key="habit.id" class="today-habit">
          <div><h2>{{ habit.name }}</h2><p>{{ todaySummary(habit) }}</p></div>
          <button v-if="canWrite" class="habits-check" :class="{ 'habits-check-done': habit.todayDay.checked }" type="button" :disabled="saving" :aria-pressed="habit.todayDay.checked" :aria-label="`${habit.todayDay.checked ? 'Desfazer registro' : 'Registrar'}: ${habit.name}, hoje`" @click="toggleCheckIn(habit)">
            {{ habit.todayDay.checked ? 'Feito · Desfazer' : 'Registrar hoje' }}
          </button>
          <span v-else class="habits-readonly-state">{{ habit.todayDay.checked ? 'Feito' : 'Ainda não registrado' }}</span>
        </li>
      </ul>
    </template>

    <template v-if="data && activeTab === 'configure' && canWrite">
      <div class="habit-editor-layout">
        <form class="habit-editor" @submit.prevent="saveHabit">
          <h2>{{ selectedId ? 'Editar hábito' : 'Configurar hábito' }}</h2>
          <label for="habit-name">Nome</label>
          <input id="habit-name" v-model="form.name" maxlength="120" autocomplete="off" required />
          <label for="habit-start">Data de início</label>
          <input id="habit-start" v-model="form.startDate" type="date" :disabled="Boolean(selectedId) && !data?.habits.find((habit) => habit.id === selectedId)?.canEditStartDate" :min="data?.today" required />
          <p v-if="selectedId && !data?.habits.find((habit) => habit.id === selectedId)?.canEditStartDate" class="habit-help">A data inicial fica protegida depois que o hábito começa, para preservar o histórico.</p>
          <fieldset>
            <legend>Dias previstos</legend>
            <label v-for="day in weekdays" :key="day.value" class="weekday-option">
              <input v-model="form.days" type="checkbox" :value="day.value" />
              <span>{{ day.label }}</span>
            </label>
          </fieldset>
          <p class="habit-frequency">{{ form.days.length ? `Previsto ${form.days.length === 1 ? 'em' : 'nos'} ${weekdays.filter((day) => form.days.includes(day.value)).map((day) => day.label.toLowerCase()).join(', ')}` : 'Selecione os dias da semana.' }}</p>
          <p v-if="formError" class="habits-error" role="alert">{{ formError }}</p>
          <div class="habit-form-actions">
            <button class="habits-primary" type="submit" :disabled="saving">{{ saving ? 'Salvando…' : selectedId ? 'Salvar alterações' : 'Criar hábito' }}</button>
            <button v-if="selectedId" class="habits-secondary" type="button" :disabled="saving" @click="togglePause(data.habits.find((habit) => habit.id === selectedId))">{{ data.habits.find((habit) => habit.id === selectedId)?.paused ? 'Retomar' : 'Pausar' }}</button>
          </div>
        </form>
        <aside class="habit-config-list" aria-label="Seus hábitos">
          <h2>Seus hábitos</h2>
          <p v-if="!data.habits.length">Nenhum hábito cadastrado ainda.</p>
          <button v-for="habit in data.habits" :key="habit.id" type="button" :class="{ 'habit-config-selected': selectedId === habit.id }" @click="editHabit(habit)">
            <span>{{ habit.name }}</span><small>{{ habit.paused ? 'Pausado' : `${habit.eligible} dias previstos nesta semana` }}</small>
          </button>
        </aside>
      </div>
    </template>

    <template v-if="data && activeTab === 'history'">
      <section class="habit-history" aria-labelledby="habit-history-title">
        <div class="habit-history-heading">
          <div><h2 id="habit-history-title">Histórico semanal</h2><p>{{ historyLabel }}</p></div>
          <div class="habit-week-controls">
            <button class="habits-secondary" type="button" aria-label="Semana anterior" @click="shiftWeek(-1)">Anterior</button>
            <button class="habits-secondary" type="button" :disabled="isCurrentWeek" aria-label="Semana seguinte" @click="shiftWeek(1)">Seguinte</button>
          </div>
        </div>
        <p v-if="!data.habits.length" class="habits-empty-note">Nenhum hábito cadastrado.</p>
        <p v-else-if="data.eligible === 0" class="habits-empty-note">Sem dias previstos nesta semana.</p>
        <p v-else class="habit-week-total">Registros / dias previstos na semana: <strong>{{ data.completed }} de {{ data.eligible }}</strong></p>
        <div v-for="habit in data.habits" :key="habit.id" class="habit-history-card">
          <div class="habit-history-card-heading"><h3>{{ habit.name }}</h3><span>{{ habit.completed }} de {{ habit.eligible }} dias previstos</span></div>
          <ol class="habit-days">
            <li v-for="day in habit.days" :key="day.date" :class="`habit-day-${day.status.toLowerCase()}`">
              <span class="habit-day-name">{{ new Intl.DateTimeFormat('pt-BR', { weekday: 'short' }).format(fromDateKey(day.date)) }}</span>
              <time :datetime="day.date">{{ fromDateKey(day.date).getDate() }}</time>
              <span class="habit-day-status">{{ statusLabel(day) }}</span>
              <button v-if="canWrite && day.eligible && day.date <= data.today" type="button" class="habit-day-action" :disabled="saving" :aria-label="`${day.checked ? 'Desfazer registro' : 'Registrar'} ${habit.name}, ${day.date}`" @click="toggleHistoryCheckIn(habit, day)">{{ day.checked ? 'Desfazer' : 'Registrar' }}</button>
            </li>
          </ol>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.habits-page { width: min(100%, 1120px); margin: 0 auto; padding: 30px clamp(16px, 3vw, 36px) 56px; color: #f3f1fb; }
.habits-header, .habit-history-heading, .habit-history-card-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.habits-eyebrow { margin: 0 0 8px; color: #b9a0ff; font-size: 12px; font-weight: 700; letter-spacing: .12em; }
.habits-header h1 { margin: 0; font-size: clamp(26px, 4vw, 36px); }
.habits-intro { margin: 8px 0 0; color: #b8bbd0; }
.habits-tabs { display: flex; gap: 8px; border-bottom: 1px solid #292c43; margin: 28px 0 22px; overflow-x: auto; }
.habits-tabs button { min-height: 46px; padding: 0 14px; border: 0; border-bottom: 2px solid transparent; color: #b8bbd0; background: transparent; cursor: pointer; white-space: nowrap; }
.habits-tabs button[aria-pressed="true"] { color: #f3f1fb; border-bottom-color: #a986ff; }
.habits-primary, .habits-secondary, .habits-check, .habit-day-action { min-height: 44px; border-radius: 10px; padding: 0 15px; font: inherit; font-weight: 650; cursor: pointer; }
.habits-primary { border: 1px solid #a986ff; color: #fff; background: #7655bf; }
.habits-secondary { border: 1px solid #454863; color: #eeeaf8; background: #161a2e; }
button:disabled { opacity: .6; cursor: not-allowed; }
.habits-empty, .habit-editor, .habit-config-list, .habit-history-card, .today-habit { border: 1px solid #292c43; border-radius: 14px; background: #111527; }
.habits-empty { padding: 30px; text-align: center; }
.habits-empty h2 { margin: 0 0 8px; }
.habits-empty p, .habit-config-list p { color: #b8bbd0; }
.today-habit-list { display: grid; gap: 12px; padding: 0; margin: 0; list-style: none; }
.today-habit { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 18px; }
.today-habit h2 { margin: 0; font-size: 18px; }
.today-habit p { margin: 6px 0 0; color: #b8bbd0; }
.habits-check { border: 1px solid #6f5d9f; color: #eeeaf8; background: #25213a; }
.habits-check-done { border-color: #609b86; color: #d8ffed; background: #16352c; }
.habits-readonly-state { color: #c6c4d3; }
.habits-notice, .habits-loading, .habits-empty-note { color: #c9c2e5; }
.habits-error { color: #ffb4b4; }
.habits-error button { min-height: 40px; margin-left: 8px; border: 1px solid #78505a; border-radius: 8px; color: #fff; background: #30202a; cursor: pointer; }
.habit-editor-layout { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(260px, 1fr); gap: 16px; align-items: start; }
.habit-editor, .habit-config-list { padding: 20px; }
.habit-editor h2, .habit-config-list h2 { margin: 0 0 18px; font-size: 20px; }
.habit-editor > label { display: block; margin: 16px 0 7px; color: #dedced; font-weight: 600; }
.habit-editor input[type="text"], .habit-editor input:not([type]) , .habit-editor input[type="date"] { width: 100%; min-height: 46px; box-sizing: border-box; padding: 10px 12px; border: 1px solid #454863; border-radius: 8px; color: #f3f1fb; background: #0b0e1f; font: inherit; }
.habit-editor input:disabled { opacity: .8; }
.habit-help { margin: 7px 0 0; color: #aeb1c8; font-size: 13px; }
.habit-editor fieldset { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin: 20px 0 0; padding: 12px; border: 1px solid #454863; border-radius: 10px; }
.habit-editor legend { padding: 0 6px; color: #dedced; font-weight: 600; }
.weekday-option { display: flex; align-items: center; gap: 9px; min-height: 40px; color: #e9e7f1; }
.weekday-option input { width: 18px; height: 18px; accent-color: #a986ff; }
.habit-frequency { color: #b8bbd0; font-size: 14px; }
.habit-form-actions, .habit-week-controls { display: flex; gap: 8px; flex-wrap: wrap; }
.habit-config-list { display: grid; gap: 8px; }
.habit-config-list h2 { margin-bottom: 4px; }
.habit-config-list > button { display: grid; gap: 5px; min-height: 60px; text-align: left; padding: 10px 12px; border: 1px solid #353951; border-radius: 9px; color: #f3f1fb; background: #0b0e1f; cursor: pointer; }
.habit-config-list > button.habit-config-selected { border-color: #a986ff; }
.habit-config-list small { color: #b8bbd0; }
.habit-history-heading { align-items: center; margin-bottom: 18px; }
.habit-history-heading h2 { margin: 0 0 5px; }
.habit-history-heading p { margin: 0; color: #b8bbd0; }
.habit-week-total { padding: 13px 15px; border: 1px solid #34374f; border-radius: 10px; color: #dedced; }
.habit-history-card { padding: 17px; margin-top: 12px; }
.habit-history-card-heading { align-items: center; }
.habit-history-card-heading h3 { margin: 0; font-size: 17px; }
.habit-history-card-heading span { color: #c7bedf; font-size: 14px; }
.habit-days { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 8px; padding: 0; margin: 15px 0 0; list-style: none; }
.habit-days li { display: grid; justify-items: center; align-content: start; gap: 5px; min-height: 116px; padding: 9px 4px; border: 1px solid #34374f; border-radius: 9px; text-align: center; background: #0b0e1f; }
.habit-day-name { color: #b8bbd0; font-size: 12px; text-transform: capitalize; }
.habit-days time { font-size: 18px; font-weight: 700; }
.habit-day-status { color: #c8c6d5; font-size: 11px; line-height: 1.25; }
.habit-day-done { border-color: #609b86 !important; background: #153128 !important; }
.habit-day-paused, .habit-day-before_start { opacity: .7; }
.habit-day-action { min-height: 34px; padding: 0 7px; border: 0; color: #cbb7ff; background: transparent; font-size: 11px; }
button:focus-visible, input:focus-visible { outline: 3px solid #c3a7ff; outline-offset: 2px; }
@media (max-width: 760px) { .habit-editor-layout { grid-template-columns: 1fr; } .habits-header { align-items: flex-start; flex-direction: column; } .habit-history-heading { align-items: flex-start; flex-direction: column; } }
@media (max-width: 540px) { .habit-days { grid-template-columns: 1fr; } .habit-days li { grid-template-columns: 56px 36px minmax(0, 1fr) auto; justify-items: start; align-items: center; min-height: 48px; padding: 5px 9px; text-align: left; } .habit-day-action { justify-self: end; } .today-habit { align-items: flex-start; flex-direction: column; } }
</style>
