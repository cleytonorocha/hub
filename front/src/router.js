import { createRouter, createWebHistory } from 'vue-router'
import OverviewView from './views/OverviewView.vue'
import CalendarView from './views/CalendarView.vue'
import NotificationsView from './views/NotificationsView.vue'
import SettingsView from './views/SettingsView.vue'
import VideoDownloaderView from './views/VideoDownloaderView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'overview', component: OverviewView },
    { path: '/calendar', name: 'calendar', component: CalendarView },
    { path: '/notifications', name: 'notifications', component: NotificationsView },
    { path: '/settings', name: 'settings', component: SettingsView },
    { path: '/tools/video-downloader', name: 'video-downloader', component: VideoDownloaderView },
  ],
})
