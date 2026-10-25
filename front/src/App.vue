<script setup>
import { RouterLink, RouterView } from 'vue-router'
import { ref } from 'vue'

const menuOpen = ref(false)
const navigation = [
  { label: 'Visão Geral', icon: 'home', to: '/' },
  { label: 'Calendário', icon: 'calendar', to: '/calendar' },
  { label: 'Notificações', icon: 'bell', to: '/notifications' },
  { label: 'Configurações', icon: 'settings', to: '/settings' },
]
</script>

<template>
  <svg class="icon-sprite" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
    <symbol id="icon-home" viewBox="0 0 24 24"><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1V10Z" /></symbol>
    <symbol id="icon-calendar" viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M16 3v4M8 3v4M3 10h18" /></symbol>
    <symbol id="icon-bell" viewBox="0 0 24 24"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" /><path d="M10 21h4" /></symbol>
    <symbol id="icon-settings" viewBox="0 0 24 24"><circle cx="12" cy="12" r="3" /><path d="m19.4 15 .1.1 1.4 1.1-1.4 2.4-1.7-.7a8 8 0 0 1-1.6.9L16 21h-8l-.2-2.2a8 8 0 0 1-1.6-.9l-1.7.7-1.4-2.4 1.4-1.1a7 7 0 0 1 0-2l-1.4-1.1 1.4-2.4 1.7.7a8 8 0 0 1 1.6-.9L8 5h8l.2 2.2a8 8 0 0 1 1.6.9l1.7-.7 1.4 2.4-1.4 1.1a7 7 0 0 1-.1 2.1Z" /></symbol>
  </svg>
  <div class="app-shell">
    <button v-if="menuOpen" class="sidebar-backdrop" type="button" aria-label="Fechar menu" @click="menuOpen = false"></button>
    <aside id="main-navigation" class="sidebar" :class="{ 'sidebar-open': menuOpen }">
      <RouterLink class="brand" to="/" @click="menuOpen = false">
        <svg class="brand-mark" viewBox="0 0 36 36" aria-hidden="true"><path d="M18 2 25 9 18 16 11 9 18 2Zm11 11 5 5-5 5-5-5 5-5ZM7 13l5 5-5 5-5-5 5-5Zm11 7 7 7-7 7-7-7 7-7Z" /></svg>
        <span>Hub</span>
      </RouterLink>
      <nav aria-label="Navegação principal" class="primary-nav">
        <RouterLink v-for="item in navigation" :key="item.to" class="nav-link" :to="item.to" active-class="nav-link-current" exact-active-class="nav-link-current" @click="menuOpen = false">
          <svg class="nav-icon" aria-hidden="true"><use :href="'#icon-' + item.icon" /></svg><span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-footer"><span aria-hidden="true">✦</span>Seu espaço pessoal</div>
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

