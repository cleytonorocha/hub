import { createRouter, createWebHistory } from 'vue-router'
import OverviewView from './views/OverviewView.vue'
import CalendarView from './views/CalendarView.vue'
import NotificationsView from './views/NotificationsView.vue'
import SettingsView from './views/SettingsView.vue'
import VideoDownloaderView from './views/VideoDownloaderView.vue'
import TaskSearchView from './views/TaskSearchView.vue'
import ReadingLibraryView from './views/ReadingLibraryView.vue'
import LoginView from './views/LoginView.vue'
import FirstAdminSetupView from './views/FirstAdminSetupView.vue'
import UserManagementView from './views/UserManagementView.vue'
import { authState, initializeAuth, hasPermission, isAdministrator } from './services/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'overview', component: OverviewView, meta: { permission: 'TASK_READ' } },
    { path: '/calendar', name: 'calendar', component: CalendarView, meta: { permission: 'TASK_READ' } },
    { path: '/search', name: 'task-search', component: TaskSearchView, meta: { permission: 'TASK_READ' } },
    { path: '/reading', name: 'reading-library', component: ReadingLibraryView, meta: { permission: 'READING_READ' } },
    { path: '/notifications', name: 'notifications', component: NotificationsView, meta: { permission: 'TASK_READ' } },
    { path: '/settings', name: 'settings', component: SettingsView, meta: { permission: 'SETTINGS_WRITE' } },
    { path: '/tools/video-downloader', name: 'video-downloader', component: VideoDownloaderView, meta: { permission: 'VIDEO_DOWNLOAD' } },
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/setup', name: 'setup', component: FirstAdminSetupView, meta: { public: true } },
    { path: '/admin/users', name: 'user-management', component: UserManagementView, meta: { permission: 'USER_MANAGE', administratorOnly: true } },
  ],
})

router.beforeEach(async (to) => {
  try { await initializeAuth() } catch { return { name: 'login' } }
  if (to.name === 'setup') return authState.setupRequired ? true : { name: authState.user ? 'overview' : 'login' }
  if (authState.setupRequired) return { name: 'setup' }
  if (to.name === 'login') return authState.user ? { name: 'overview' } : true
  if (!authState.user) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.administratorOnly && !isAdministrator()) return { name: 'overview' }
  if (to.meta.permission && !hasPermission(to.meta.permission)) return { name: 'overview' }
  return true
})

export default router
