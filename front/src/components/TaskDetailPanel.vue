<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useTaskHub } from '../services/taskHub'
import { CUSTOM_TASK_CATEGORY_VALUE, TASK_CATEGORY_SUGGESTIONS } from '../utils/preferences'

const props = defineProps({
  task: { type: Object, default: null },
  saving: { type: Boolean, default: false },
  error: { type: String, default: '' },
  showCalendar: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'change', 'open-calendar'])
const { getTaskSeries, previewCloseTaskSeries, closeTaskSeries, getTaskChecklist, saveTaskChecklist } = useTaskHub()
const dueDate = ref('')
const dueTime = ref('')
const category = ref('Geral')
const categoryChoice = computed({
  get: () => TASK_CATEGORY_SUGGESTIONS.includes(category.value) ? category.value : CUSTOM_TASK_CATEGORY_VALUE,
  set: (value) => { if (value !== CUSTOM_TASK_CATEGORY_VALUE) category.value = value },
})
const color = ref('#A986FF')
const priority = ref('NORMAL')
const notes = ref('')
const closeButton = ref(null)
const panel = ref(null)
const seriesInfo = ref(null)
const closeReview = ref(null)
const seriesError = ref('')
const seriesBusy = ref(false)
const confirmingSeriesClose = ref(false)
const checklistItems = ref([])
const checklistRevision = ref(0)
const checklistLoading = ref(false)
const checklistSaving = ref(false)
const checklistDirty = ref(false)
const checklistError = ref('')
const newChecklistText = ref('')
const confirmPendingCompletion = ref(false)
const checklistProgress = computed(() => `${checklistItems.value.filter((item) => item.checked).length} de ${checklistItems.value.length}`)
const pendingChecklistCount = computed(() => checklistItems.value.filter((item) => !item.checked).length)
const detailsChanged = computed(() => dueDate.value !== (props.task?.dueDate || '')
  || dueTime.value !== (props.task?.dueTime || '').slice(0, 5)
  || category.value !== (props.task?.category || 'Geral')
  || color.value !== (props.task?.color || '#A986FF')
  || priority.value !== (props.task?.priority || 'NORMAL')
  || notes.value !== (props.task?.notes || ''))

watch(() => [props.task?.id, props.task?.dueDate, props.task?.dueTime, props.task?.category, props.task?.color, props.task?.priority, props.task?.notes, props.task?.seriesId], async () => {
  dueDate.value = props.task?.dueDate || ''
  dueTime.value = (props.task?.dueTime || '').slice(0, 5)
  category.value = props.task?.category || 'Geral'
  color.value = props.task?.color || '#A986FF'
  priority.value = props.task?.priority || 'NORMAL'
  notes.value = props.task?.notes || ''
  seriesInfo.value = null
  closeReview.value = null
  seriesError.value = ''
  checklistItems.value = []
  checklistRevision.value = 0
  checklistDirty.value = false
  checklistError.value = ''
  if (props.task?.seriesId) {
    try { seriesInfo.value = await getTaskSeries(props.task.seriesId) }
    catch (error) { seriesError.value = error.message }
  }
  if (props.task?.type === 'LOCAL' && !props.task?.seriesId) await loadChecklist(props.task.id)
  nextTick(() => closeButton.value?.focus())
}, { immediate: true })

watch(() => props.saving, async (saving, wasSaving) => {
  if (!props.showCalendar) return
  await nextTick()
  if (!panel.value || !props.task || props.saving !== saving) return
  if (saving) panel.value.focus({ preventScroll: true })
  else if (wasSaving) (closeButton.value || panel.value).focus({ preventScroll: true })
})

function trapFocus(event) {
  if (event.key === 'Escape') {
    emit('close')
    return
  }
  if (event.key !== 'Tab') return
  const focusable = [...event.currentTarget.querySelectorAll('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled])')]
  if (!focusable.length) {
    event.preventDefault()
    panel.value?.focus({ preventScroll: true })
    return
  }
  if (document.activeElement === panel.value) {
    event.preventDefault()
    ;(event.shiftKey ? focusable.at(-1) : focusable[0]).focus()
    return
  }
  if (event.shiftKey && document.activeElement === focusable[0]) {
    event.preventDefault()
    focusable.at(-1).focus()
  } else if (!event.shiftKey && document.activeElement === focusable.at(-1)) {
    event.preventDefault()
    focusable[0].focus()
  }
}

function formattedDate(value) {
  if (!value) return 'Sem data'
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'long' }).format(new Date(value + 'T12:00:00'))
}

function saveDetails() {
  emit('change', {
    dueDate: dueDate.value || null,
    dueTime: dueTime.value || null,
    completed: props.task.completed,
    category: category.value.trim() || 'Geral',
    color: color.value,
    priority: priority.value,
    notes: notes.value,
  })
}

function toggleCompletion() {
  if (!props.task.completed && pendingChecklistCount.value > 0) {
    confirmPendingCompletion.value = true
    return
  }
  saveCompletion(false)
}

function saveCompletion(confirmPendingChecklist) {
  confirmPendingCompletion.value = false
  emit('change', {
    dueDate: props.task.dueDate || null,
    dueTime: props.task.dueTime || null,
    completed: !props.task.completed,
    category: props.task.category,
    color: props.task.color,
    priority: props.task.priority,
    notes: props.task.notes,
    confirmPendingChecklist,
  })
}

async function loadChecklist(taskId) {
  checklistLoading.value = true
  try {
    const result = await getTaskChecklist(taskId)
    if (props.task?.id !== taskId) return
    checklistItems.value = result.items
    checklistRevision.value = result.revision
    checklistDirty.value = false
    checklistError.value = ''
  } catch (error) {
    if (props.task?.id === taskId) checklistError.value = error.message
  } finally { checklistLoading.value = false }
}

function markChecklistDirty() {
  checklistDirty.value = true
  checklistError.value = ''
}

function addChecklistItem() {
  const text = newChecklistText.value.trim()
  if (!text) { checklistError.value = 'Escreva uma etapa antes de adicioná-la.'; return }
  if (text.length > 200) { checklistError.value = 'Cada etapa pode ter até 200 caracteres.'; return }
  if (checklistItems.value.length >= 20) { checklistError.value = 'O checklist pode ter até 20 etapas.'; return }
  checklistItems.value.push({ id: crypto.randomUUID(), text, checked: false, position: checklistItems.value.length })
  newChecklistText.value = ''
  markChecklistDirty()
}

function removeChecklistItem(index) {
  const item = checklistItems.value[index]
  if (!item || !window.confirm(`Remover a etapa “${item.text}”?`)) return
  checklistItems.value.splice(index, 1)
  checklistItems.value.forEach((entry, position) => { entry.position = position })
  markChecklistDirty()
}

async function reloadChecklist() {
  if (checklistDirty.value && !window.confirm('Descartar as alterações locais e carregar a última versão salva?')) return
  await loadChecklist(props.task.id)
}

async function saveChecklist() {
  if (checklistSaving.value || !checklistDirty.value) return
  if (checklistItems.value.some((item) => !item.text.trim() || item.text.length > 200)) {
    checklistError.value = 'Todas as etapas precisam ter texto e até 200 caracteres.'
    return
  }
  checklistSaving.value = true
  checklistError.value = ''
  try {
    const result = await saveTaskChecklist(props.task.id, { expectedRevision: checklistRevision.value,
      items: checklistItems.value.map((item, position) => ({ ...item, text: item.text.trim(), position })) })
    checklistItems.value = result.items
    checklistRevision.value = result.revision
    checklistDirty.value = false
  } catch (error) { checklistError.value = error.message }
  finally { checklistSaving.value = false }
}

async function reviewSeriesEnd() {
  if (!props.task?.seriesId || seriesBusy.value) return
  seriesBusy.value = true
  seriesError.value = ''
  try {
    closeReview.value = await previewCloseTaskSeries(props.task.seriesId, Intl.DateTimeFormat().resolvedOptions().timeZone)
    confirmingSeriesClose.value = true
  } catch (error) { seriesError.value = error.message }
  finally { seriesBusy.value = false }
}

async function confirmSeriesEnd() {
  if (!closeReview.value || seriesBusy.value) return
  const currentZone = Intl.DateTimeFormat().resolvedOptions().timeZone
  if (currentZone !== closeReview.value.timeZoneId) {
    await reviewSeriesEnd()
    if (closeReview.value) seriesError.value = 'O fuso local mudou. Confira a revisão atualizada antes de confirmar.'
    return
  }
  seriesBusy.value = true
  seriesError.value = ''
  try {
    const result = await closeTaskSeries(props.task.seriesId, closeReview.value)
    if (result.status === 'ENDED' && !props.task.completed && props.task.dueDate > result.today) emit('close')
    closeReview.value = null
    confirmingSeriesClose.value = false
    seriesInfo.value = await getTaskSeries(props.task.seriesId)
  } catch (error) {
    seriesError.value = error.message
    seriesBusy.value = false
    await reviewSeriesEnd()
    if (closeReview.value) seriesError.value = 'A série mudou. Confira a nova quantidade antes de confirmar novamente.'
  } finally { seriesBusy.value = false }
}
</script>

<template>
  <div v-if="task" class="task-detail-backdrop" @click.self="emit('close')">
    <aside ref="panel" class="task-detail-panel" role="dialog" tabindex="-1" aria-modal="true" :aria-busy="saving" aria-labelledby="task-detail-title" @keydown="trapFocus">
      <header class="task-detail-header"><div><p class="settings-eyebrow">DETALHES DA TASK</p><h2 id="task-detail-title">Detalhes da Task</h2></div><button ref="closeButton" class="task-icon-button" type="button" aria-label="Fechar detalhes" :disabled="showCalendar && saving" @click="emit('close')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg></button></header>
      <div class="task-detail-content">
        <h3>{{ task.title }}</h3>
        <p class="task-detail-list">{{ task.type === 'GOOGLE' ? 'Google' : 'Local' }}</p>
        <p class="task-detail-date">{{ formattedDate(task.dueDate) }}</p>
        <p v-if="task.dueTime" class="task-detail-date">{{ task.dueTime.slice(0, 5) }}</p>
        <span class="task-status-pill" :class="{ 'task-status-pill-done': task.completed }">{{ task.completed ? 'Concluída' : 'Pendente' }}</span>

        <section v-if="task.seriesId" class="task-series-detail" aria-label="Série semanal">
          <h3>Série semanal</h3>
          <p v-if="seriesInfo">Ocorrência {{ task.seriesPosition }} de {{ seriesInfo.series.occurrenceCount }} · {{ seriesInfo.series.ended ? 'Encerrada' : 'Ativa' }}</p>
          <p>Alterar, concluir ou excluir afeta somente esta ocorrência.</p>
          <ol v-if="seriesInfo"><li v-for="occurrence in seriesInfo.occurrences" :key="occurrence.id">{{ formattedDate(occurrence.dueDate) }} · {{ occurrence.completed ? 'Concluída' : 'Pendente' }}</li></ol>
          <button v-if="seriesInfo && !seriesInfo.series.ended" class="task-secondary-button" type="button" :disabled="seriesBusy" @click="reviewSeriesEnd">{{ seriesBusy ? 'Atualizando…' : 'Encerrar série' }}</button>
          <section v-if="confirmingSeriesClose && closeReview" class="task-series-confirm" aria-live="polite" aria-label="Revisão do encerramento">
            <p>Serão removidas {{ closeReview.pendingFutureCount }} ocorrências pendentes após {{ formattedDate(closeReview.today) }}. Hoje, datas passadas e ocorrências concluídas serão preservadas.</p>
            <button class="task-secondary-button" type="button" :disabled="seriesBusy" @click="confirmingSeriesClose = false; closeReview = null">Manter série</button>
            <button class="task-primary-button" type="button" :disabled="seriesBusy" @click="confirmSeriesEnd">{{ seriesBusy ? 'Encerrando…' : closeReview.pendingFutureCount ? `Encerrar e remover ${closeReview.pendingFutureCount} ocorrências` : 'Encerrar série' }}</button>
            <p v-if="closeReview.pendingFutureCount === 0">Não há ocorrências futuras pendentes para remover.</p>
          </section>
          <p v-if="seriesError" class="task-form-error" role="alert">{{ seriesError }}</p>
        </section>

        <section v-if="task.type === 'LOCAL' && !task.seriesId" class="task-checklist" aria-label="Checklist da tarefa">
          <header><div><h3>Checklist</h3><p>{{ checklistProgress }} concluídas</p></div><span v-if="checklistSaving" role="status">Salvando…</span></header>
          <p v-if="checklistLoading" role="status">Carregando checklist…</p>
          <div v-else-if="checklistError && checklistItems.length === 0"><p class="task-form-error" role="alert">{{ checklistError }}</p><button class="task-secondary-button" type="button" @click="reloadChecklist">Tentar novamente</button></div>
          <template v-else>
            <ol class="task-checklist-list">
              <li v-for="(item, index) in checklistItems" :key="item.id">
                <label><input v-model="item.checked" type="checkbox" :disabled="task.completed || checklistSaving" @change="markChecklistDirty"><input v-model="item.text" class="task-checklist-text" type="text" maxlength="200" :disabled="task.completed || checklistSaving" :aria-label="`Texto da etapa ${index + 1}`" @input="markChecklistDirty"></label>
                <button class="task-secondary-button" type="button" :disabled="task.completed || checklistSaving" :aria-label="`Remover etapa ${item.text}`" @click="removeChecklistItem(index)">Remover</button>
              </li>
            </ol>
            <form v-if="!task.completed" class="task-checklist-add" @submit.prevent="addChecklistItem">
              <label class="task-field"><span>Nova etapa</span><input v-model="newChecklistText" maxlength="200" :disabled="checklistItems.length >= 20 || checklistSaving" placeholder="Ex.: Separar os documentos"></label>
              <button class="task-secondary-button" type="submit" :disabled="checklistItems.length >= 20 || checklistSaving">Adicionar etapa</button>
            </form>
            <p v-if="checklistDirty" class="task-checklist-dirty" role="status">Alterações ainda não salvas.</p>
            <button v-if="!task.completed" class="task-primary-button" type="button" :disabled="!checklistDirty || checklistSaving" @click="saveChecklist">{{ checklistSaving ? 'Salvando…' : 'Salvar checklist' }}</button>
            <p v-if="checklistError" class="task-form-error" role="alert">{{ checklistError }}</p>
            <button v-if="checklistError && !checklistLoading" class="task-secondary-button" type="button" @click="reloadChecklist">Carregar versão salva</button>
            <p v-if="task.completed">Checklist em leitura. Reabra a tarefa para continuar.</p>
          </template>
        </section>

        <label class="task-field"><span>Categoria</span><select v-model="categoryChoice" :disabled="saving"><option v-for="suggestion in TASK_CATEGORY_SUGGESTIONS" :key="suggestion" :value="suggestion">{{ suggestion }}</option><option :value="CUSTOM_TASK_CATEGORY_VALUE">Outra categoria…</option></select></label>
        <label v-if="categoryChoice === CUSTOM_TASK_CATEGORY_VALUE" class="task-field"><span>Nome da categoria</span><input v-model="category" maxlength="64" placeholder="Digite uma categoria" autocomplete="off" :disabled="saving"></label>
        <label class="task-field"><span>Data</span><input v-model="dueDate" type="date" :disabled="saving"></label>
        <label class="task-field"><span>Horário</span><input v-model="dueTime" type="time" :disabled="saving"></label>
        <div class="task-field-pair">
          <label class="task-field"><span>Cor</span><input v-model="color" type="color" aria-label="Cor da tarefa" :disabled="saving"></label>
          <label class="task-field"><span>Prioridade</span><select v-model="priority" :disabled="saving"><option value="LOW">Baixa</option><option value="NORMAL">Normal</option><option value="HIGH">Alta</option></select></label>
        </div>
        <label class="task-field"><span>Notas</span><textarea v-model="notes" maxlength="2000" rows="4" placeholder="Detalhes ou contexto para esta tarefa" :disabled="saving"></textarea></label>
        <button class="task-primary-button" type="button" :disabled="saving || !detailsChanged" @click="saveDetails">{{ saving ? 'Salvando…' : 'Salvar alterações' }}</button>
        <p v-if="error" class="task-form-error" role="alert">{{ error }}</p>
        <button class="task-primary-button task-complete-button" type="button" :disabled="saving || checklistSaving || checklistDirty" @click="toggleCompletion">{{ saving ? 'Salvando…' : task.completed ? 'Reabrir Task' : 'Concluir Task' }}</button>
        <p v-if="checklistDirty" class="task-form-error">Salve o checklist antes de alterar a conclusão desta tarefa.</p>
        <section v-if="confirmPendingCompletion" class="task-series-confirm" role="alertdialog" aria-labelledby="checklist-completion-title">
          <h3 id="checklist-completion-title">Concluir com etapas pendentes?</h3>
          <p>Faltam {{ pendingChecklistCount }} etapas. A tarefa será concluída sem marcá-las.</p>
          <button class="task-secondary-button" type="button" @click="confirmPendingCompletion = false">Voltar ao checklist</button>
          <button class="task-primary-button" type="button" :disabled="saving" @click="saveCompletion(true)">Concluir mesmo assim</button>
        </section>
        <button v-if="showCalendar && task.dueDate" class="task-secondary-button" type="button" :disabled="saving || detailsChanged" @click="emit('open-calendar', task)">Abrir no calendário</button>
        <p v-if="showCalendar && task.dueDate && detailsChanged" class="task-form-error">Salve as alterações antes de abrir o calendário.</p>
      </div>
    </aside>
  </div>
</template>
