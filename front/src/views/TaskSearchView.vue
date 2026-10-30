<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import TaskDetailPanel from '../components/TaskDetailPanel.vue'
import { useTaskHub } from '../services/taskHub'
import { taskSearchState as searchState } from '../services/taskSearchState'
import { createSearchFilters, invalidDateRange } from '../utils/taskSearch'

const router = useRouter()
const { updateTask, searchTasks } = useTaskHub()
const selectedTask = ref(null)
const saving = ref(false)
const taskError = ref('')
const searchInput = ref(null)
const paginationHeading = ref(null)
const requestSequence = ref(0)
const filters = searchState.filters
const priorities = { LOW: 'Baixa', NORMAL: 'Normal', HIGH: 'Alta' }
const categories = computed(() => searchState.categories)
const invalidRange = computed(() => invalidDateRange(filters))
const isDirty = computed(() => searchState.query !== searchState.appliedQuery
  || Object.keys(filters).some((key) => filters[key] !== searchState.appliedFilters[key]))
const chips = computed(() => {
  const items = []
  if (filters.status !== 'all') items.push({ key: 'status', label: filters.status === 'pending' ? 'Pendente' : 'Concluída' })
  if (filters.category) items.push({ key: 'category', label: filters.category })
  if (filters.priority) items.push({ key: 'priority', label: priorities[filters.priority] })
  if (filters.period === 'undated') items.push({ key: 'period', label: 'Sem data' })
  if (filters.period === 'range') items.push({ key: 'period', label: `Período: ${filters.startDate || 'início'} a ${filters.endDate || 'fim'}` })
  return items
})
const firstResult = computed(() => searchState.total ? searchState.page * searchState.size + 1 : 0)
const lastResult = computed(() => Math.min((searchState.page + 1) * searchState.size, searchState.total))
const showingStaleResults = computed(() => searchState.error && searchState.items.length > 0)

onMounted(async () => {
  window.scrollTo({ top: searchState.scrollY, behavior: 'instant' })
  if (!searchState.hasLoaded) {
    searchState.appliedQuery = searchState.query
    searchState.appliedFilters = { ...filters }
    searchState.page = 0
  }
  await fetchPage(searchState.page)
  await nextTick()
  if (searchState.focusTaskId) {
    const row = document.querySelector(`[data-task-id="${CSS.escape(searchState.focusTaskId)}"]`)
    if (row) row.focus({ preventScroll: true })
    else searchInput.value?.focus({ preventScroll: true })
  }
})
onBeforeUnmount(() => { searchState.scrollY = window.scrollY })

function cloneFilters(value) { return { ...value } }
function resetFilters() { Object.assign(filters, createSearchFilters()) }
function resetAll() { resetFilters(); searchState.query = '' }
function removeFilter(key) {
  if (key === 'period') {
    filters.period = 'all'
    filters.startDate = ''
    filters.endDate = ''
  } else filters[key] = createSearchFilters()[key]
}
function formatDate(date) {
  return date ? new Intl.DateTimeFormat('pt-BR', { dateStyle: 'medium' }).format(new Date(`${date}T12:00:00`)) : 'Sem data'
}
function buildCriteria(page) {
  return {
    q: searchState.appliedQuery,
    ...searchState.appliedFilters,
    page,
    size: searchState.size,
  }
}
async function fetchPage(page) {
  const sequence = ++requestSequence.value
  searchState.loading = true
  searchState.error = ''
  try {
    const result = await searchTasks(buildCriteria(page))
    if (sequence !== requestSequence.value) return
    if (result.totalPages > 0 && page >= result.totalPages) {
      await fetchPage(result.totalPages - 1)
      return
    }
    searchState.items = result.content
    searchState.total = result.totalElements
    searchState.page = result.page
    searchState.totalPages = result.totalPages
    searchState.categories = result.categories
    searchState.hasLoaded = true
  } catch (error) {
    if (sequence === requestSequence.value) searchState.error = error.message
  } finally {
    if (sequence === requestSequence.value) searchState.loading = false
  }
}
async function applySearch() {
  if (invalidRange.value) return
  searchState.appliedQuery = searchState.query
  searchState.appliedFilters = cloneFilters(filters)
  searchState.page = 0
  await fetchPage(0)
}
async function retrySearch() { await fetchPage(searchState.page) }
async function goToPage(page, direction) {
  if (page < 0 || page >= searchState.totalPages || searchState.loading) return
  await fetchPage(page)
  await nextTick()
  if ((direction === 'next' && searchState.page + 1 >= searchState.totalPages)
    || (direction === 'previous' && searchState.page === 0)) {
    paginationHeading.value?.focus({ preventScroll: true })
  }
}
function openTask(task, event) {
  searchState.focusTaskId = task.id
  selectedTask.value = task
  taskError.value = ''
  event.currentTarget.focus({ preventScroll: true })
}
async function closeTask() {
  if (saving.value) return
  selectedTask.value = null
  taskError.value = ''
  await nextTick()
  const row = searchState.focusTaskId
    ? document.querySelector(`[data-task-id="${CSS.escape(searchState.focusTaskId)}"]`)
    : null
  if (row) row.focus({ preventScroll: true })
  else searchInput.value?.focus({ preventScroll: true })
}
async function changeTask(changes) {
  if (!selectedTask.value || saving.value) return
  saving.value = true
  taskError.value = ''
  try {
    selectedTask.value = await updateTask(selectedTask.value, changes)
    await fetchPage(searchState.page)
  } catch (error) { taskError.value = error.message }
  finally { saving.value = false }
}
function openCalendar(task) {
  if (!task?.dueDate) return
  const query = { date: task.dueDate, view: 'day', taskId: task.id }
  if (task.dueTime) query.hour = String(Number(task.dueTime.slice(0, 2)))
  router.push({ name: 'calendar', query })
}
</script>

<template>
  <section id="page-content" class="content-view task-search-view" aria-labelledby="task-search-title">
    <header class="view-header task-search-header">
      <div><p class="settings-eyebrow">CALENDÁRIO / TASKS</p><h1 id="task-search-title" tabindex="-1">Buscar tasks</h1><p>Encontre tasks em todas as datas, incluindo concluídas e sem data.</p></div>
      <button class="task-secondary-button" type="button" @click="router.push({ name: 'calendar' })">Voltar ao calendário</button>
    </header>

    <div class="task-search-workspace">
      <form class="task-search-form" @submit.prevent="applySearch">
        <label class="task-field task-search-input"><span>Buscar em todas as tasks</span><input ref="searchInput" v-model="searchState.query" type="search" placeholder="Título, categoria ou observações" autocomplete="off"></label>

        <section class="task-search-filters" aria-labelledby="search-filters-title">
          <div class="task-search-filter-heading"><h2 id="search-filters-title">Filtros</h2><button class="task-secondary-button" type="button" @click="resetFilters">Limpar filtros</button></div>
          <div class="task-search-filter-grid">
            <label class="task-field"><span>Estado</span><select v-model="filters.status"><option value="all">Todas</option><option value="pending">Pendentes</option><option value="completed">Concluídas</option></select></label>
            <label class="task-field"><span>Categoria</span><select v-model="filters.category"><option value="">Todas</option><option v-if="filters.category && !categories.includes(filters.category)" :value="filters.category">{{ filters.category }}</option><option v-for="category in categories" :key="category" :value="category">{{ category }}</option></select></label>
            <label class="task-field"><span>Prioridade</span><select v-model="filters.priority"><option value="">Todas</option><option value="LOW">Baixa</option><option value="NORMAL">Normal</option><option value="HIGH">Alta</option></select></label>
            <label class="task-field"><span>Período</span><select v-model="filters.period"><option value="all">Todas as datas</option><option value="range">Intervalo</option><option value="undated">Somente sem data</option></select></label>
            <template v-if="filters.period === 'range'"><label class="task-field"><span>Data inicial</span><input v-model="filters.startDate" type="date" :aria-invalid="invalidRange" :aria-describedby="invalidRange ? 'date-range-error' : undefined"></label><label class="task-field"><span>Data final</span><input v-model="filters.endDate" type="date" :aria-invalid="invalidRange" :aria-describedby="invalidRange ? 'date-range-error' : undefined"></label></template>
          </div>
          <p v-if="invalidRange" id="date-range-error" class="task-form-error" role="alert">A data inicial não pode ser posterior à data final.</p>
          <div v-if="chips.length" class="task-search-chips" aria-label="Filtros atuais"><button v-for="chip in chips" :key="chip.key" class="task-search-chip" type="button" :aria-label="`Remover filtro ${chip.label}`" @click="removeFilter(chip.key)">{{ chip.label }} <span aria-hidden="true">×</span></button></div>
          <div class="task-search-submit-row">
            <p v-if="isDirty" class="task-search-dirty" role="status">Há alterações; pesquise para atualizar os resultados.</p>
            <button class="task-primary-button task-search-submit" type="submit" :disabled="invalidRange || searchState.loading">{{ searchState.loading ? 'Pesquisando…' : 'Pesquisar' }}</button>
          </div>
        </section>
      </form>

      <section class="task-search-results" aria-labelledby="search-results-title" :aria-busy="searchState.loading">
        <div class="task-search-result-heading"><div><h2 id="search-results-title" ref="paginationHeading" tabindex="-1">Resultados</h2><p v-if="searchState.hasLoaded && !searchState.error" role="status" aria-live="polite">{{ searchState.total ? `${firstResult}–${lastResult} de ${searchState.total}` : '0 tasks' }} · {{ searchState.total === 1 ? '1 task encontrada' : `${searchState.total} tasks encontradas` }}</p></div><div class="task-search-result-actions"><button v-if="searchState.query || chips.length || searchState.appliedQuery || searchState.appliedFilters.status !== 'all' || searchState.appliedFilters.category || searchState.appliedFilters.priority || searchState.appliedFilters.period !== 'all'" class="task-secondary-button" type="button" @click="resetAll">Limpar busca e filtros</button><button class="task-secondary-button" type="button" :disabled="searchState.loading || !searchState.hasLoaded" @click="retrySearch">{{ searchState.loading ? 'Pesquisando…' : 'Atualizar' }}</button></div></div>
        <p v-if="searchState.loading && !searchState.hasLoaded" class="task-search-message" role="status">Carregando tasks…</p>
        <template v-else>
          <div v-if="searchState.error" class="task-search-error" role="alert"><p>{{ searchState.error }}</p><p v-if="showingStaleResults">Os resultados abaixo podem estar desatualizados.</p><button class="task-secondary-button" type="button" :disabled="searchState.loading" @click="retrySearch">{{ searchState.loading ? 'Pesquisando…' : 'Tentar novamente' }}</button></div>
          <template v-if="searchState.hasLoaded && (!searchState.error || searchState.items.length)">
            <p v-if="searchState.loading && searchState.items.length" class="task-search-message" role="status">Exibindo os resultados anteriores enquanto a busca atualiza…</p>
            <div v-if="!searchState.loading && !searchState.error && !searchState.total && !searchState.categories.length" class="task-search-empty"><strong>Você ainda não tem tasks locais.</strong><p>Crie uma task no calendário para encontrá-la aqui.</p><button class="task-secondary-button" type="button" @click="router.push({ name: 'calendar' })">Abrir calendário</button></div>
            <div v-else-if="!searchState.loading && !searchState.error && !searchState.total" class="task-search-empty"><strong>Nenhuma task encontrada.</strong><p>Tente outra busca ou ajuste os filtros.</p><button class="task-secondary-button" type="button" @click="resetAll">Limpar busca e filtros</button></div>
            <ul v-else-if="searchState.items.length" class="task-search-list"><li v-for="task in searchState.items" :key="task.id"><button class="task-search-row" :data-task-id="task.id" type="button" @click="openTask(task, $event)"><span class="task-search-row-main"><strong>{{ task.title }}</strong><span>{{ task.category || 'Geral' }} · {{ formatDate(task.dueDate) }}<template v-if="task.dueTime"> · {{ task.dueTime.slice(0, 5) }}</template></span></span><span class="task-search-row-meta"><span>{{ priorities[task.priority] || 'Normal' }}</span><span>{{ task.completed ? 'Concluída' : 'Pendente' }}</span></span><span aria-hidden="true">›</span></button></li></ul>
            <div v-if="searchState.totalPages > 1" class="task-search-pagination" aria-label="Paginação de resultados"><button class="task-secondary-button" type="button" :disabled="searchState.page === 0 || searchState.loading" @click="goToPage(searchState.page - 1, 'previous')">Anterior</button><span aria-live="polite">Página {{ searchState.page + 1 }} de {{ searchState.totalPages }}</span><button class="task-secondary-button" type="button" :disabled="searchState.page + 1 >= searchState.totalPages || searchState.loading" @click="goToPage(searchState.page + 1, 'next')">Próxima</button></div>
          </template>
        </template>
      </section>
    </div>
    <TaskDetailPanel v-if="selectedTask" :task="selectedTask" :saving="saving" :error="taskError" show-calendar @close="closeTask" @change="changeTask" @open-calendar="openCalendar" />
  </section>
</template>
