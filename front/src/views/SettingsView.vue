<script setup>
import { ref, watch } from 'vue'

const preferenceKey = 'hub.overview.weeklySummary'
function readPreference() {
  try { return window.localStorage.getItem(preferenceKey) !== 'false' } catch { return true }
}
const weeklySummaryVisible = ref(readPreference())
watch(weeklySummaryVisible, (visible) => {
  try { window.localStorage.setItem(preferenceKey, String(visible)) } catch { /* A opção permanece ativa na sessão atual. */ }
})
</script>

<template>
  <section class="content-view settings-view" aria-labelledby="settings-title">
    <header class="view-header"><p class="settings-eyebrow">PREFERÊNCIAS</p><h1 id="settings-title">Configurações</h1><p>Personalize o que aparece na sua Visão Geral.</p></header>
    <section id="page-content" class="settings-group" aria-labelledby="overview-settings-title">
      <h2 id="overview-settings-title">Visão Geral</h2>
      <div class="preference-row"><div class="preference-copy"><h3>Resumo semanal</h3><p id="weekly-summary-description">Mostra a semana atual na Visão Geral.</p></div>
        <label class="switch-control"><span class="visually-hidden">Mostrar resumo semanal</span><input v-model="weeklySummaryVisible" type="checkbox" role="switch" aria-label="Mostrar resumo semanal" aria-describedby="weekly-summary-description"><span class="switch-track" aria-hidden="true"></span></label>
      </div>
    </section>
  </section>
</template>
