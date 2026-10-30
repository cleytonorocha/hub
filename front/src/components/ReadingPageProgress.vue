<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  currentPage: { type: Number, required: true },
  totalPages: { type: Number, required: true },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['save'])
const draft = ref(props.currentPage)
const changed = computed(() => draft.value !== props.currentPage)
const percentage = computed(() => Math.round((draft.value / props.totalPages) * 100))

watch(() => props.currentPage, (page) => { draft.value = page })

function normalizeDraft() {
  const parsed = Number(draft.value)
  if (!Number.isFinite(parsed)) draft.value = props.currentPage
  else draft.value = Math.max(0, Math.min(props.totalPages, Math.trunc(parsed)))
}

function save() {
  normalizeDraft()
  if (changed.value && !props.disabled) emit('save', draft.value)
}
</script>

<template>
  <section class="reading-page-progress" aria-labelledby="reading-page-progress-title">
    <div class="reading-page-progress-heading">
      <div>
        <h3 id="reading-page-progress-title">Seu progresso</h3>
        <p>{{ draft }} de {{ totalPages }} páginas · {{ percentage }}%</p>
      </div>
      <span class="reading-page-progress-percent" aria-hidden="true">{{ percentage }}%</span>
    </div>
    <progress :value="draft" :max="totalPages" :aria-label="`${draft} de ${totalPages} páginas lidas`"></progress>
    <label class="task-field reading-page-current-field">
      <span>Página atual</span>
      <input v-model.number="draft" type="number" min="0" :max="totalPages" step="1" :disabled="disabled" @change="normalizeDraft">
    </label>
    <input v-model.number="draft" class="reading-page-slider" type="range" min="0" :max="totalPages" step="1" :disabled="disabled" :aria-label="`Página atual, de 0 a ${totalPages}`">
    <div class="reading-dialog-actions">
      <button class="task-secondary-button" type="button" :disabled="disabled || !changed" @click="draft = currentPage">Desfazer</button>
      <button class="task-primary-button" type="button" :disabled="disabled || !changed" @click="save">{{ disabled ? 'Salvando…' : 'Salvar progresso' }}</button>
    </div>
  </section>
</template>
