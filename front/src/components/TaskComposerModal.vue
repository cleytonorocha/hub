<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useTaskHub } from '../services/taskHub'
import { CUSTOM_TASK_CATEGORY_VALUE, readTaskPreferences, TASK_CATEGORY_SUGGESTIONS } from '../utils/preferences'

const props = defineProps({
  initialDate: { type: String, default: '' },
  initialTime: { type: String, default: '' },
  requireDate: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'created'])
const { createTask, createTaskSeries } = useTaskHub()
const taskDefaults = readTaskPreferences()
const title = ref('')
const date = ref(props.initialDate)
const time = ref(props.initialTime)
const category = ref(taskDefaults.category)
const categoryChoice = computed({
  get: () => TASK_CATEGORY_SUGGESTIONS.includes(category.value) ? category.value : CUSTOM_TASK_CATEGORY_VALUE,
  set: (value) => { if (value !== CUSTOM_TASK_CATEGORY_VALUE) category.value = value },
})
const color = ref(taskDefaults.color)
const priority = ref(taskDefaults.priority)
const notes = ref('')
const repeatWeekly = ref(false)
const occurrenceCount = ref(2)
const operationId = ref(crypto.randomUUID())
const saving = ref(false)
const error = ref('')
const titleInput = ref(null)
const occurrenceDates = computed(() => {
  if (!date.value || !repeatWeekly.value) return []
  const count = Number(occurrenceCount.value)
  if (!Number.isInteger(count) || count < 2 || count > 52) return []
  const [year, month, day] = date.value.split('-').map(Number)
  const start = new Date(Date.UTC(year, month - 1, day))
  return Array.from({ length: count }, (_, index) => {
    const current = new Date(start)
    current.setUTCDate(current.getUTCDate() + index * 7)
    return `${current.getUTCFullYear()}-${String(current.getUTCMonth() + 1).padStart(2, '0')}-${String(current.getUTCDate()).padStart(2, '0')}`
  })
})

watch([title, date, time, repeatWeekly, occurrenceCount, category, color, priority, notes], () => {
  operationId.value = crypto.randomUUID()
})

function formatDate(value) {
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'long' }).format(new Date(`${value}T12:00:00`))
}

function handleKeydown(event) {
  if (event.key === 'Escape' && !saving.value) emit('close')
  if (event.key !== 'Tab') return
  const dialog = event.currentTarget
  const focusable = [...dialog.querySelectorAll('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled])')]
  if (!focusable.length) return
  if (event.shiftKey && document.activeElement === focusable[0]) {
    event.preventDefault()
    focusable.at(-1).focus()
  } else if (!event.shiftKey && document.activeElement === focusable.at(-1)) {
    event.preventDefault()
    focusable[0].focus()
  }
}

async function submit() {
  error.value = ''
  if (!title.value.trim()) {
    error.value = 'Informe um título.'
    titleInput.value?.focus()
    return
  }
  if (props.requireDate && !date.value) {
    error.value = 'Escolha a data em que esta Task aparecerá no calendário.'
    return
  }
  if (repeatWeekly.value && !date.value) {
    error.value = 'Escolha a data inicial da série semanal.'
    return
  }
  if (repeatWeekly.value && (!Number.isInteger(Number(occurrenceCount.value)) || Number(occurrenceCount.value) < 2 || Number(occurrenceCount.value) > 52)) {
    error.value = 'A série precisa ter entre 2 e 52 ocorrências.'
    return
  }
  saving.value = true
  try {
    const data = {
      title: title.value.trim(),
      dueDate: date.value || null,
      dueTime: time.value || null,
      type: 'LOCAL',
      category: category.value.trim() || 'Geral',
      color: color.value,
      priority: priority.value,
      notes: notes.value,
    }
    if (repeatWeekly.value) {
      const seriesRequest = { ...data, operationId: operationId.value, startDate: date.value,
        occurrenceCount: Number(occurrenceCount.value), timeZoneId: Intl.DateTimeFormat().resolvedOptions().timeZone }
      delete seriesRequest.dueDate
      const created = await createTaskSeries(seriesRequest)
      emit('created', created.occurrences[0])
    } else {
      emit('created', await createTask(data))
    }
  } catch (apiError) {
    error.value = apiError.message
  } finally {
    saving.value = false
  }
}

onMounted(() => nextTick(() => titleInput.value?.focus()))
</script>

<template>
  <div class="task-modal-backdrop" @click.self="!saving && emit('close')">
    <section class="task-composer" role="dialog" aria-modal="true" aria-labelledby="task-composer-title" @keydown="handleKeydown">
      <header class="task-composer-header">
        <div><p class="settings-eyebrow">TAREFA LOCAL</p><h2 id="task-composer-title">Nova Task</h2></div>
        <button class="task-icon-button" type="button" aria-label="Fechar criação" :disabled="saving" @click="emit('close')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg></button>
      </header>
      <form class="task-composer-form" @submit.prevent="submit">
        <label class="task-field"><span>Título</span><input ref="titleInput" v-model="title" maxlength="1024" autocomplete="off" placeholder="Ex.: Revisar planejamento" required></label>
        <label class="task-field"><span>Data</span><input v-model="date" type="date" :required="requireDate"><small>{{ requireDate ? 'Esta Task aparecerá no dia selecionado.' : 'Deixe em branco para colocar em Sem data.' }}</small></label>
        <label class="task-series-toggle"><input v-model="repeatWeekly" type="checkbox"><span>Repetir semanalmente</span></label>
        <template v-if="repeatWeekly">
          <label class="task-field"><span>Quantidade de ocorrências</span><input v-model.number="occurrenceCount" type="number" min="2" max="52" required><small>Entre 2 e 52, incluindo a data inicial.</small></label>
          <section class="task-series-preview" aria-live="polite" aria-label="Prévia das datas da série">
            <strong>{{ occurrenceDates.length }} ocorrências · até {{ occurrenceDates.length ? formatDate(occurrenceDates.at(-1)) : 'escolha uma data' }}</strong>
            <ol v-if="occurrenceDates.length"><li v-for="day in occurrenceDates" :key="day">{{ formatDate(day) }}</li></ol>
            <p>As ocorrências são tarefas independentes. Alterar uma não move as outras.</p>
          </section>
        </template>
        <label class="task-field"><span>Horário</span><input v-model="time" type="time"><small>Opcional. Você pode ajustar o horário antes de salvar.</small></label>
        <label class="task-field"><span>Categoria</span><select v-model="categoryChoice"><option v-for="suggestion in TASK_CATEGORY_SUGGESTIONS" :key="suggestion" :value="suggestion">{{ suggestion }}</option><option :value="CUSTOM_TASK_CATEGORY_VALUE">Outra categoria…</option></select></label>
        <label v-if="categoryChoice === CUSTOM_TASK_CATEGORY_VALUE" class="task-field"><span>Nome da categoria</span><input v-model="category" maxlength="64" placeholder="Digite uma categoria" autocomplete="off"></label>
        <div class="task-field-pair">
          <label class="task-field"><span>Cor</span><input v-model="color" type="color" aria-label="Cor da tarefa"></label>
          <label class="task-field"><span>Prioridade</span><select v-model="priority"><option value="LOW">Baixa</option><option value="NORMAL">Normal</option><option value="HIGH">Alta</option></select></label>
        </div>
        <label class="task-field"><span>Notas</span><textarea v-model="notes" maxlength="2000" rows="3" placeholder="Detalhes ou contexto para esta tarefa"></textarea></label>
        <p v-if="error" class="task-form-error" role="alert">{{ error }}</p>
        <footer class="task-composer-actions">
          <button class="task-secondary-button" type="button" :disabled="saving" @click="emit('close')">Cancelar</button>
          <button class="task-primary-button" type="submit" :disabled="saving">{{ saving ? 'Salvando…' : 'Criar Task' }}</button>
        </footer>
      </form>
    </section>
  </div>
</template>
