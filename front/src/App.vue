<script setup>
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTaskHub } from './services/taskHub'
import { authState, logout, expireSession, hasPermission, isAdministrator, namespacedKey } from './services/auth'
import { useRouter } from 'vue-router'

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
    sidebarPinned.value = localStorage.getItem(namespacedKey('sidebar-pinned')) === 'true'
    if (id !== previousId) initialize()
  }
})

watch(() => route.path, (path) => {
  if (path.startsWith('/tools/')) toolsOpen.value = true
}, { immediate: true })

function toggleSidebarPin() {
  sidebarPinned.value = !sidebarPinned.value
  localStorage.setItem(namespacedKey('sidebar-pinned'), String(sidebarPinned.value))
}

async function signOut() { await logout(); await router.replace({ name: 'login' }) }

const navigation = computed(() => [
  { label: 'Visão Geral', icon: 'home', to: '/', permission: 'TASK_READ' },
  { label: 'Calendário', icon: 'calendar', to: '/calendar', permission: 'TASK_READ' },
  { label: 'Notificações', icon: 'bell', to: '/notifications', permission: 'TASK_READ' },
  { label: 'Biblioteca de leitura', icon: 'reading', to: '/reading', permission: 'READING_READ' },
].filter((item) => hasPermission(item.permission)))
</script>

<template>
  <svg class="icon-sprite" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
    <symbol id="icon-home" viewBox="0 0 24 24"><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1V10Z" /></symbol>
    <symbol id="icon-calendar" viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M16 3v4M8 3v4M3 10h18" /></symbol>
    <symbol id="icon-bell" viewBox="0 0 24 24"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" /><path d="M10 21h4" /></symbol>
    <symbol id="icon-reading" viewBox="0 0 24 24"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v17H6.5A2.5 2.5 0 0 0 4 22V5.5Z" /><path d="M4 18.5A2.5 2.5 0 0 1 6.5 16H20M8 7h8M8 10h7" /></symbol>
    <symbol id="icon-tool" viewBox="0 0 24 24"><path d="M14.7 6.3a5 5 0 0 0-6.4 6.4l-5.1 5.1a2.1 2.1 0 0 0 3 3l5.1-5.1a5 5 0 0 0 6.4-6.4L14 13l-3-3 3.7-3.7Z" /></symbol>
    <symbol id="icon-download" viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" /></symbol>
    <symbol id="icon-chevron" viewBox="0 0 24 24"><path d="m6 9 6 6 6-6" /></symbol>
    <symbol id="icon-settings" viewBox="0 0 24 24"><path d="M4 21v-7m0-4V3M12 21v-9m0-4V3m8 18v-5m0-4V3" /><path d="M2 14h4m4-6h4m4 8h4" /></symbol>
    <symbol id="icon-user" viewBox="0 0 24 24"><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></symbol>
    <symbol id="icon-logout" viewBox="0 0 24 24"><path d="M10 17l5-5-5-5M15 12H3" /><path d="M12 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-6" /></symbol>
    <symbol id="icon-sidebar-pin" viewBox="0 0 24 24"><path d="m16 3 5 5-4 1-4 4v4l-2-2-5 5 2-7-2-2 4-2 1-4Z" /><path d="m11 13-7 7" /></symbol>
  </svg>
  <div class="app-shell" :class="{ 'sidebar-collapsed': sidebarCollapsed, 'auth-shell': !authState.user }">
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
        <div v-if="hasPermission('VIDEO_DOWNLOAD')" class="nav-group">
          <button class="nav-link nav-group-toggle" :class="{ 'nav-link-current': route.path.startsWith('/tools/') }" type="button" aria-controls="tools-subnav" :aria-expanded="toolsOpen" aria-label="Ferramentas" title="Ferramentas" @click="toolsOpen = !toolsOpen">
            <svg class="nav-icon" aria-hidden="true"><use href="#icon-tool" /></svg><span class="nav-label">Ferramentas</span>
            <svg class="nav-group-chevron" :class="{ 'nav-group-chevron-open': toolsOpen }" aria-hidden="true"><use href="#icon-chevron" /></svg>
          </button>
          <div v-if="toolsOpen" id="tools-subnav" class="nav-group-children">
            <RouterLink class="nav-link nav-sublink" to="/tools/video-downloader" aria-label="Baixar vídeos" title="Baixar vídeos" active-class="nav-link-current" exact-active-class="nav-link-current" @click="menuOpen = false">
              <svg class="nav-icon" aria-hidden="true"><use href="#icon-download" /></svg><span class="nav-label">Baixar vídeos</span>
            </RouterLink>
          </div>
        </div>
        <RouterLink v-if="isAdministrator()" class="nav-link" to="/admin/users" aria-label="Usuários e perfis" active-class="nav-link-current" @click="menuOpen = false">
          <svg class="nav-icon" aria-hidden="true"><use href="#icon-user" /></svg><span class="nav-label">Usuários e perfis</span>
        </RouterLink>
        <RouterLink v-if="hasPermission('SETTINGS_WRITE')" class="nav-link" to="/settings" aria-label="Configurações" active-class="nav-link-current" exact-active-class="nav-link-current" @click="menuOpen = false">
          <svg class="nav-icon" aria-hidden="true"><use href="#icon-settings" /></svg><span class="nav-label">Configurações</span>
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

