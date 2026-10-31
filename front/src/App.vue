<script setup>
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTaskHub } from './services/taskHub'
import { authState, logout, expireSession, hasPermission, isAdministrator } from './services/auth'
import { writeSidebarPinnedPreference } from './utils/preferences'
import { retryUserPreferences, userPreferencesState } from './services/userPreferences'
import { useRouter } from 'vue-router'
import { getNavigationItems } from './navigation'

const menuOpen = ref(false)
const sidebarPinned = ref(true)
const sidebarHovered = ref(false)
const sidebarCollapsed = computed(() => !sidebarPinned.value && !sidebarHovered.value)
const toolsOpen = ref(false)
const route = useRoute()
const router = useRouter()
const { initialize } = useTaskHub()
function handleUnauthorized() {
  if (!authState.user) return
  expireSession()
  router.replace({ name: 'login' })
}
onMounted(() => {
  if (authState.user) initialize()
  window.addEventListener('hub:unauthorized', handleUnauthorized)
})
onBeforeUnmount(() => window.removeEventListener('hub:unauthorized', handleUnauthorized))
watch(() => authState.user?.id, (id, previousId) => {
  if (id) {
    if (id !== previousId) initialize()
  }
})
watch(() => userPreferencesState.ready ? userPreferencesState.document.sidebarPinned : null, (pinned) => {
  if (pinned !== null) sidebarPinned.value = pinned
}, { immediate: true })

watch(() => route.path, (path) => {
  if (path.startsWith('/tools/')) toolsOpen.value = true
}, { immediate: true })

function toggleSidebarPin() {
  sidebarPinned.value = !sidebarPinned.value
  writeSidebarPinnedPreference(sidebarPinned.value)
}

async function signOut() { await logout(); await router.replace({ name: 'login' }) }

const navigation = computed(() => getNavigationItems('sidebar-primary', hasPermission, isAdministrator))
const toolNavigation = computed(() => getNavigationItems('tools', hasPermission, isAdministrator))
const adminNavigation = computed(() => getNavigationItems('sidebar-admin', hasPermission, isAdministrator))
const settingsNavigation = computed(() => getNavigationItems('sidebar-settings', hasPermission, isAdministrator))
</script>

<template>
  <svg class="icon-sprite" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
    <symbol id="icon-home" viewBox="0 0 24 24"><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1V10Z" /></symbol>
    <symbol id="icon-calendar" viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M16 3v4M8 3v4M3 10h18" /></symbol>
    <symbol id="icon-bell" viewBox="0 0 24 24"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" /><path d="M10 21h4" /></symbol>
    <symbol id="icon-google" viewBox="0 0 24 24"><path d="M21.6 12.23c0-.71-.06-1.4-.18-2.06H12v3.9h5.38a4.6 4.6 0 0 1-2 3.02v2.52h3.23c1.89-1.74 2.99-4.3 2.99-7.38Z" /><path d="M12 22c2.7 0 4.97-.9 6.62-2.39l-3.23-2.52c-.9.6-2.04.96-3.39.96-2.6 0-4.8-1.75-5.59-4.1H3.07v2.6A10 10 0 0 0 12 22Z" /><path d="M6.41 13.95A6 6 0 0 1 6.1 12c0-.68.12-1.34.32-1.95v-2.6H3.07A10 10 0 0 0 2 12c0 1.64.39 3.2 1.07 4.55l3.34-2.6Z" /><path d="M12 5.95c1.47 0 2.79.5 3.83 1.5l2.87-2.87C16.96 2.98 14.7 2 12 2a10 10 0 0 0-8.93 5.45l3.34 2.6c.79-2.35 2.99-4.1 5.59-4.1Z" /></symbol>
    <symbol id="icon-reading" viewBox="0 0 24 24"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v17H6.5A2.5 2.5 0 0 0 4 22V5.5Z" /><path d="M4 18.5A2.5 2.5 0 0 1 6.5 16H20M8 7h8M8 10h7" /></symbol>
    <symbol id="icon-search" viewBox="0 0 24 24"><circle cx="10.8" cy="10.8" r="6.8" /><path d="m16 16 5 5" /></symbol>
    <symbol id="icon-tool" viewBox="0 0 24 24"><path d="M14.7 6.3a5 5 0 0 0-6.4 6.4l-5.1 5.1a2.1 2.1 0 0 0 3 3l5.1-5.1a5 5 0 0 0 6.4-6.4L14 13l-3-3 3.7-3.7Z" /></symbol>
    <symbol id="icon-download" viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" /></symbol>
    <symbol id="icon-focus" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2M3 3l3 3m15-3-3 3"/></symbol>
    <symbol id="icon-notes" viewBox="0 0 24 24"><path d="M5 3h10l4 4v14H5z"/><path d="M14 3v5h5M8 12h8M8 16h8"/></symbol>
    <symbol id="icon-chevron" viewBox="0 0 24 24"><path d="m6 9 6 6 6-6" /></symbol>
    <symbol id="icon-settings" viewBox="0 0 24 24"><path d="M4 21v-7m0-4V3M12 21v-9m0-4V3m8 18v-5m0-4V3" /><path d="M2 14h4m4-6h4m4 8h4" /></symbol>
    <symbol id="icon-user" viewBox="0 0 24 24"><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></symbol>
    <symbol id="icon-logout" viewBox="0 0 24 24"><path d="M10 17l5-5-5-5M15 12H3" /><path d="M12 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-6" /></symbol>
    <symbol id="icon-sidebar-pin" viewBox="0 0 24 24"><path d="m16 3 5 5-4 1-4 4v4l-2-2-5 5 2-7-2-2 4-2 1-4Z" /><path d="m11 13-7 7" /></symbol>
  </svg>
  <div class="app-shell" :class="{ 'sidebar-collapsed': sidebarCollapsed, 'auth-shell': !authState.user }">
    <div v-if="authState.user && (userPreferencesState.pending || userPreferencesState.error)" class="preference-sync-notice" :class="{ 'preference-sync-error': userPreferencesState.error }" :role="userPreferencesState.error ? 'alert' : 'status'" :aria-live="userPreferencesState.error ? 'assertive' : 'polite'">
      <span>{{ userPreferencesState.error ? 'As preferências ainda não foram sincronizadas.' : userPreferencesState.saving ? 'Salvando preferências…' : 'Preferências pendentes de sincronização.' }}</span>
      <button type="button" :disabled="userPreferencesState.saving" aria-label="Tentar sincronizar preferências" @click="retryUserPreferences">{{ userPreferencesState.saving ? 'Salvando…' : 'Tentar novamente' }}</button>
    </div>
    <button v-if="menuOpen" class="sidebar-backdrop" type="button" aria-label="Fechar menu" @click="menuOpen = false"></button>
    <aside v-if="authState.user" id="main-navigation" class="sidebar" :class="{ 'sidebar-open': menuOpen }" @mouseenter="sidebarHovered = true" @mouseleave="sidebarHovered = false">
      <div class="sidebar-header">
      <RouterLink class="brand" to="/" aria-label="Hub — página inicial" @click="menuOpen = false">
        <svg class="brand-mark" viewBox="0 0 36 36" aria-hidden="true"><path d="M18 2 25 9 18 16 11 9 18 2Zm11 11 5 5-5 5-5-5 5-5ZM7 13l5 5-5 5-5-5 5-5Zm11 7 7 7-7 7-7-7 7-7Z" /></svg>
        <span class="brand-name">Hub</span>
      </RouterLink>
      <button class="sidebar-pin-toggle" type="button" aria-controls="main-navigation" :aria-pressed="sidebarPinned" :aria-label="sidebarPinned ? 'Desafixar barra lateral' : 'Fixar barra lateral'" :title="sidebarPinned ? 'Desafixar barra lateral' : 'Fixar barra lateral'" @click="toggleSidebarPin">
        <svg aria-hidden="true"><use href="#icon-sidebar-pin" /></svg>
      </button>
      </div>
      <nav aria-label="Navegação principal" class="primary-nav">
        <RouterLink v-for="item in navigation" :key="item.to" class="nav-link" :to="item.to" :aria-label="item.label" active-class="nav-link-current" exact-active-class="nav-link-current" @click="menuOpen = false">
          <svg class="nav-icon" aria-hidden="true"><use :href="'#icon-' + item.icon" /></svg><span class="nav-label">{{ item.label }}</span>
        </RouterLink>
        <div v-if="toolNavigation.length" class="nav-group">
          <button class="nav-link nav-group-toggle" :class="{ 'nav-link-current': route.path.startsWith('/tools/') }" type="button" aria-controls="tools-subnav" :aria-expanded="toolsOpen" aria-label="Ferramentas" title="Ferramentas" @click="toolsOpen = !toolsOpen">
            <svg class="nav-icon" aria-hidden="true"><use href="#icon-tool" /></svg><span class="nav-label">Ferramentas</span>
            <svg class="nav-group-chevron" :class="{ 'nav-group-chevron-open': toolsOpen }" aria-hidden="true"><use href="#icon-chevron" /></svg>
          </button>
          <div v-if="toolsOpen" id="tools-subnav" class="nav-group-children">
            <RouterLink v-for="item in toolNavigation" :key="item.to" class="nav-link nav-sublink" :to="item.to" :aria-label="item.label" :title="item.label" active-class="nav-link-current" exact-active-class="nav-link-current" @click="menuOpen = false">
              <svg class="nav-icon" aria-hidden="true"><use :href="'#icon-' + item.icon" /></svg><span class="nav-label">{{ item.label }}</span>
            </RouterLink>
          </div>
        </div>
        <RouterLink v-for="item in adminNavigation" :key="item.to" class="nav-link" :to="item.to" :aria-label="item.label" active-class="nav-link-current" @click="menuOpen = false">
          <svg class="nav-icon" aria-hidden="true"><use :href="'#icon-' + item.icon" /></svg><span class="nav-label">{{ item.label }}</span>
        </RouterLink>
        <RouterLink v-for="item in settingsNavigation" :key="item.to" class="nav-link" :to="item.to" :aria-label="item.label" active-class="nav-link-current" exact-active-class="nav-link-current" @click="menuOpen = false">
          <svg class="nav-icon" aria-hidden="true"><use :href="'#icon-' + item.icon" /></svg><span class="nav-label">{{ item.label }}</span>
        </RouterLink>
        <div v-if="authState.user" class="sidebar-account">
          <div class="sidebar-account-identity" :title="`${authState.user.displayName} · ${authState.user.profileName}`">
            <span class="sidebar-account-avatar" aria-hidden="true"><svg><use href="#icon-user" /></svg></span>
            <span class="sidebar-account-copy"><span class="sidebar-account-name">{{ authState.user.displayName }}</span><span class="sidebar-account-role">{{ authState.user.profileName }}</span></span>
          </div>
          <button class="sidebar-account-logout" type="button" aria-label="Sair da conta" title="Sair da conta" @click="signOut">
            <svg aria-hidden="true"><use href="#icon-logout" /></svg><span class="nav-label">Sair</span>
          </button>
        </div>
      </nav>
    </aside>
    <main class="main-content">
      <RouterLink class="skip-link" to="#page-content">Pular para o conteúdo</RouterLink>
      <button v-if="authState.user" class="mobile-menu-toggle shell-menu-toggle" type="button" aria-controls="main-navigation" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">
        <span aria-hidden="true">☰</span><span class="visually-hidden">{{ menuOpen ? 'Fechar menu' : 'Abrir menu' }}</span>
      </button>
      <RouterView />
    </main>
  </div>
</template>

