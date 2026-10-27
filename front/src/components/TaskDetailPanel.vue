<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { CUSTOM_TASK_CATEGORY_VALUE, TASK_CATEGORY_SUGGESTIONS } from '../utils/preferences'

const props = defineProps({
  task: { type: Object, default: null },
  saving: { type: Boolean, default: false },
  error: { type: String, default: '' },
})
const emit = defineEmits(['close', 'change'])
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
const detailsChanged = computed(() => dueDate.value !== (props.task?.dueDate || '')
  || dueTime.value !== (props.task?.dueTime || '').slice(0, 5)
  || category.value !== (props.task?.category || 'Geral')
  || color.value !== (props.task?.color || '#A986FF')
  || priority.value !== (props.task?.priority || 'NORMAL')
  || notes.value !== (props.task?.notes || ''))

watch(() => [props.task?.id, props.task?.dueDate, props.task?.dueTime, props.task?.category, props.task?.color, props.task?.priority, props.task?.notes], () => {
  dueDate.value = props.task?.dueDate || ''
  dueTime.value = (props.task?.dueTime || '').slice(0, 5)
  category.value = props.task?.category || 'Geral'
  color.value = props.task?.color || '#A986FF'
  priority.value = props.task?.priority || 'NORMAL'
  notes.value = props.task?.notes || ''
  nextTick(() => closeButton.value?.focus())
}, { immediate: true })

function trapFocus(event) {
  if (event.key === 'Escape') {
    emit('close')
    return
  }
  if (event.key !== 'Tab') return
  const focusable = [...event.currentTarget.querySelectorAll('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled])')]
  if (!focusable.length) return
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
  emit('change', {
    dueDate: props.task.dueDate || null,
    dueTime: props.task.dueTime || null,
    completed: !props.task.completed,
    category: props.task.category,
    color: props.task.color,
    priority: props.task.priority,
    notes: props.task.notes,
  })
}
</script>

<template>
  <div v-if="task" class="task-detail-backdrop" @click.self="emit('close')">
    <aside class="task-detail-panel" role="dialog" aria-modal="true" aria-labelledby="task-detail-title" @keydown="trapFocus">
      <header class="task-detail-header"><div><p class="settings-eyebrow">DETALHES DA TASK</p><h2 id="task-detail-title">Detalhes da Task</h2></div><button ref="closeButton" class="task-icon-button" type="button" aria-label="Fechar detalhes" @click="emit('close')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg></button></header>
      <div class="task-detail-content">
        <h3>{{ task.title }}</h3>
        <p class="task-detail-list">{{ task.type === 'GOOGLE' ? 'Google' : 'Local' }}</p>
        <p class="task-detail-date">{{ formattedDate(task.dueDate) }}</p>
        <p v-if="task.dueTime" class="task-detail-date">{{ task.dueTime.slice(0, 5) }}</p>
        <span class="task-status-pill" :class="{ 'task-status-pill-done': task.completed }">{{ task.completed ? 'Concluída' : 'Pendente' }}</span>

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
        <button class="task-primary-button task-complete-button" type="button" :disabled="saving" @click="toggleCompletion">{{ saving ? 'Salvando…' : task.completed ? 'Reabrir Task' : 'Concluir Task' }}</button>
      </div>
    </aside>
  </div>
</template>
