<script setup>
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { computed, onMounted, ref, watch } from 'vue'
import { useTaskHub } from './services/taskHub'

const menuOpen = ref(false)
const storedSidebarPinned = localStorage.getItem('hub-sidebar-pinned')
const wasSidebarExpanded = localStorage.getItem('hub-sidebar-collapsed') === 'false'
const sidebarPinned = ref(storedSidebarPinned === 'true' || (storedSidebarPinned === null && wasSidebarExpanded))
const sidebarHovered = ref(false)
const sidebarCollapsed = computed(() => !sidebarPinned.value && !sidebarHovered.value)
const toolsOpen = ref(false)
const route = useRoute()
const { initialize } = useTaskHub()
onMounted(initialize)

watch(() => route.path, (path) => {
  if (path.startsWith('/tools/')) toolsOpen.value = true
}, { immediate: true })

function toggleSidebarPin() {
  sidebarPinned.value = !sidebarPinned.value
  localStorage.setItem('hub-sidebar-pinned', String(sidebarPinned.value))
}

const navigation = [
  { label: 'Visão Geral', icon: 'home', to: '/' },
  { label: 'Calendário', icon: 'calendar', to: '/calendar' },
  { label: 'Notificações', icon: 'bell', to: '/notifications' },
]
</script>

<template>
  <svg class="icon-sprite" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
    <symbol id="icon-home" viewBox="0 0 24 24"><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1V10Z" /></symbol>
    <symbol id="icon-calendar" viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M16 3v4M8 3v4M3 10h18" /></symbol>
    <symbol id="icon-bell" viewBox="0 0 24 24"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" /><path d="M10 21h4" /></symbol>
    <symbol id="icon-tool" viewBox="0 0 24 24"><path d="M14.7 6.3a5 5 0 0 0-6.4 6.4l-5.1 5.1a2.1 2.1 0 0 0 3 3l5.1-5.1a5 5 0 0 0 6.4-6.4L14 13l-3-3 3.7-3.7Z" /></symbol>
    <symbol id="icon-download" viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" /></symbol>
    <symbol id="icon-chevron" viewBox="0 0 24 24"><path d="m6 9 6 6 6-6" /></symbol>
    <symbol id="icon-settings" viewBox="0 0 24 24"><path d="M4 21v-7m0-4V3M12 21v-9m0-4V3m8 18v-5m0-4V3" /><path d="M2 14h4m4-6h4m4 8h4" /></symbol>
    <symbol id="icon-sidebar-pin" viewBox="0 0 24 24"><path d="m16 3 5 5-4 1-4 4v4l-2-2-5 5 2-7-2-2 4-2 1-4Z" /><path d="m11 13-7 7" /></symbol>
  </svg>
  <div class="app-shell" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <button v-if="menuOpen" class="sidebar-backdrop" type="button" aria-label="Fechar menu" @click="menuOpen = false"></button>
    <aside id="main-navigation" class="sidebar" :class="{ 'sidebar-open': menuOpen }" @mouseenter="sidebarHovered = true" @mouseleave="sidebarHovered = false">
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
        <div class="nav-group">
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
        <RouterLink class="nav-link" to="/settings" aria-label="Configurações" active-class="nav-link-current" exact-active-class="nav-link-current" @click="menuOpen = false">
          <svg class="nav-icon" aria-hidden="true"><use href="#icon-settings" /></svg><span class="nav-label">Configurações</span>
        </RouterLink>
      </nav>
    </aside>
    <main class="main-content">
      <RouterLink class="skip-link" to="#page-content">Pular para o conteúdo</RouterLink>
      <button class="mobile-menu-toggle shell-menu-toggle" type="button" aria-controls="main-navigation" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">
        <span aria-hidden="true">☰</span><span class="visually-hidden">{{ menuOpen ? 'Fechar menu' : 'Abrir menu' }}</span>
      </button>
      <RouterView />
    </main>
  </div>
</template>

