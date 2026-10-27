<script setup>
import { computed, ref, watch } from 'vue'
import {
  CUSTOM_TASK_CATEGORY_VALUE,
  readTaskPreferences,
  readVideoDownloaderCardPreference,
  readWeeklySummaryPreference,
  normalizeTaskPreferences,
  writeTaskPreferences,
  writeVideoDownloaderCardPreference,
  writeWeeklySummaryPreference,
  TASK_CATEGORY_SUGGESTIONS,
} from '../utils/preferences'
import {
  CALENDAR_MODES,
  CALENDAR_PRESENTATIONS,
  DEFAULT_CALENDAR_PRESENTATIONS,
  readCalendarPresentationPreferences,
  writeCalendarPresentationPreferences,
} from '../utils/calendarPreferences'

const activeSection = ref('overview')
const weeklySummaryVisible = ref(readWeeklySummaryPreference())
const videoDownloaderCardVisible = ref(readVideoDownloaderCardPreference())
const taskPreferences = ref(readTaskPreferences())
const calendarPresentationPreferences = ref(readCalendarPresentationPreferences())
const categoryChoice = computed({
  get: () => TASK_CATEGORY_SUGGESTIONS.includes(taskPreferences.value.category) ? taskPreferences.value.category : CUSTOM_TASK_CATEGORY_VALUE,
  set: (value) => { if (value !== CUSTOM_TASK_CATEGORY_VALUE) taskPreferences.value.category = value },
})

watch(weeklySummaryVisible, writeWeeklySummaryPreference)
watch(videoDownloaderCardVisible, writeVideoDownloaderCardPreference)
watch(taskPreferences, writeTaskPreferences, { deep: true })
watch(calendarPresentationPreferences, writeCalendarPresentationPreferences, { deep: true })

const taskPriorities = [
  { value: 'LOW', label: 'Baixa' },
  { value: 'NORMAL', label: 'Normal' },
  { value: 'HIGH', label: 'Alta' },
]

function normalizeCategory() {
  taskPreferences.value.category = normalizeTaskPreferences(taskPreferences.value).category
}

function systemPresentationLabel(mode) {
  return CALENDAR_PRESENTATIONS[mode].find(({ id }) => id === DEFAULT_CALENDAR_PRESENTATIONS[mode])?.label || ''
}

</script>

<template>
  <section class="content-view settings-view" aria-labelledby="settings-title">
    <header class="view-header">
      <p class="settings-eyebrow">PREFERÊNCIAS</p>
      <h1 id="settings-title">Configurações</h1>
      <p>Personalize cada área do Hub em um só lugar.</p>
    </header>
    <div id="page-content" class="settings-layout">
      <nav class="settings-subnav" aria-label="Subseções de configurações">
        <a href="#overview-settings" :class="{ 'settings-subnav-active': activeSection === 'overview' }" :aria-current="activeSection === 'overview' ? 'location' : undefined" @click="activeSection = 'overview'">Visão Geral</a>
        <a href="#task-settings" :class="{ 'settings-subnav-active': activeSection === 'tasks' }" :aria-current="activeSection === 'tasks' ? 'location' : undefined" @click="activeSection = 'tasks'">Tarefas</a>
        <a href="#calendar-settings" :class="{ 'settings-subnav-active': activeSection === 'calendar' }" :aria-current="activeSection === 'calendar' ? 'location' : undefined" @click="activeSection = 'calendar'">Calendário</a>
      </nav>

      <div class="settings-content">
        <section id="overview-settings" class="settings-group" aria-labelledby="overview-settings-title">
          <header class="settings-section-heading">
            <div><p class="settings-section-kicker">VISÃO GERAL</p><h2 id="overview-settings-title">Visão Geral</h2></div>
            <p>Escolha o que aparece ao abrir o Hub.</p>
          </header>
          <div class="preference-row">
            <div class="preference-copy"><h3>Resumo semanal</h3><p id="weekly-summary-description">Mostra a semana atual na Visão Geral.</p></div>
            <label class="switch-control"><span class="visually-hidden">Mostrar resumo semanal</span><input v-model="weeklySummaryVisible" type="checkbox" role="switch" aria-label="Mostrar resumo semanal" aria-describedby="weekly-summary-description"><span class="switch-track" aria-hidden="true"></span></label>
          </div>
          <div class="preference-row">
            <div class="preference-copy"><h3>Card Baixar vídeos</h3><p id="video-downloader-card-description">Exibe ou oculta o acesso a Baixar vídeos na Visão Geral.</p></div>
            <label class="switch-control"><span class="visually-hidden">Mostrar card Baixar vídeos</span><input v-model="videoDownloaderCardVisible" type="checkbox" role="switch" aria-label="Mostrar card Baixar vídeos na Visão Geral" aria-describedby="video-downloader-card-description"><span class="switch-track" aria-hidden="true"></span></label>
          </div>
        </section>

        <section id="task-settings" class="settings-group" aria-labelledby="task-settings-title">
          <header class="settings-section-heading">
            <div><p class="settings-section-kicker">TAREFAS</p><h2 id="task-settings-title">Tarefas</h2></div>
            <p>Defina como as novas tasks locais começam. Você ainda pode mudar esses dados ao criar cada task.</p>
          </header>
          <div class="preference-row">
            <div class="preference-copy"><h3>Categoria padrão</h3><p id="default-category-description">Usada ao criar uma task, até você escolher outra categoria.</p></div>
            <select v-model="categoryChoice" class="preference-select" aria-label="Categoria padrão para novas tasks" aria-describedby="default-category-description"><option v-for="suggestion in TASK_CATEGORY_SUGGESTIONS" :key="suggestion" :value="suggestion">{{ suggestion }}</option><option :value="CUSTOM_TASK_CATEGORY_VALUE">Outra categoria…</option></select>
            <input v-if="categoryChoice === CUSTOM_TASK_CATEGORY_VALUE" v-model.trim="taskPreferences.category" class="preference-input" type="text" maxlength="64" aria-label="Nome da categoria padrão personalizada" aria-describedby="default-category-description" @blur="normalizeCategory">
          </div>
          <div class="preference-row">
            <div class="preference-copy"><h3>Cor padrão</h3><p id="default-color-description">Cor usada para destacar novas tasks no calendário.</p></div>
            <label class="preference-color-control"><span class="visually-hidden">Cor padrão para novas tasks</span><input v-model="taskPreferences.color" type="color" aria-label="Cor padrão para novas tasks" aria-describedby="default-color-description"></label>
          </div>
          <div class="preference-row">
            <div class="preference-copy"><h3>Prioridade padrão</h3><p id="default-priority-description">Prioridade inicial das novas tasks locais.</p></div>
            <select v-model="taskPreferences.priority" class="preference-select" aria-label="Prioridade padrão para novas tasks" aria-describedby="default-priority-description">
              <option v-for="item in taskPriorities" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
          </div>
          <p class="settings-storage-note">As preferências são salvas neste navegador.</p>
        </section>

        <section id="calendar-settings" class="settings-group" aria-labelledby="calendar-settings-title">
          <header class="settings-section-heading">
            <div><p class="settings-section-kicker">CALENDÁRIO</p><h2 id="calendar-settings-title">Calendário</h2></div>
            <p>Escolha a apresentação inicial de cada visualização. Você pode trocar a apresentação a qualquer momento no calendário.</p>
          </header>
          <div v-for="mode in CALENDAR_MODES" :key="mode.id" class="preference-row">
            <div class="preference-copy"><h3>Visualização {{ mode.label.toLocaleLowerCase('pt-BR') }}</h3><p :id="'calendar-' + mode.id + '-description'">Selecione o formato usado ao abrir esta visualização.</p></div>
            <select v-model="calendarPresentationPreferences[mode.id]" class="preference-select" :aria-label="'Apresentação padrão da visualização ' + mode.label.toLocaleLowerCase('pt-BR')" :aria-describedby="'calendar-' + mode.id + '-description'">
              <option value="">Padrão do sistema ({{ systemPresentationLabel(mode.id) }})</option>
              <option v-for="presentation in CALENDAR_PRESENTATIONS[mode.id]" :key="presentation.id" :value="presentation.id">{{ presentation.label }}</option>
            </select>
          </div>
          <p class="settings-storage-note">As preferências são salvas neste navegador.</p>
        </section>
      </div>
    </div>
  </section>
</template>
