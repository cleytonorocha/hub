const navigationItems = [
  { key: 'overview', label: 'Visão Geral', icon: 'home', to: '/', permission: 'TASK_READ', surfaces: ['sidebar-primary'] },
  { key: 'calendar', label: 'Calendário', icon: 'calendar', to: '/calendar', permission: 'TASK_READ', surfaces: ['sidebar-primary'] },
  { key: 'notifications', label: 'Notificações', description: 'Acompanhe avisos e tarefas pendentes.', icon: 'bell', to: '/notifications', permission: 'TASK_READ', surfaces: ['sidebar-primary', 'overview'] },
  { key: 'google-tasks', label: 'Google Tasks', description: 'Organize suas listas do Google.', icon: 'google', to: '/google-tasks', permission: 'TASK_READ', surfaces: ['sidebar-primary', 'overview'] },
  { key: 'reading-library', label: 'Biblioteca de leitura', description: 'Organize seus livros e leituras.', icon: 'reading', to: '/reading', permission: 'READING_READ', surfaces: ['sidebar-primary', 'overview'] },
  { key: 'task-search', label: 'Buscar tarefas', description: 'Encontre tarefas nas suas listas.', icon: 'search', to: '/search', permission: 'TASK_READ', surfaces: ['overview'] },
  { key: 'video-downloader', label: 'Baixar vídeos', description: 'Prepare downloads de vídeo.', icon: 'download', to: '/tools/video-downloader', permission: 'VIDEO_DOWNLOAD', surfaces: ['tools', 'overview'] },
  { key: 'focus-sessions', label: 'Sessões de foco', description: 'Inicie e acompanhe sessões de foco.', icon: 'focus', to: '/tools/focus-sessions', permission: 'FOCUS_READ', surfaces: ['tools'] },
  { key: 'habits', label: 'Hábitos e rotinas', description: 'Acompanhe seus dias previstos e registros.', icon: 'tool', to: '/tools/habits', permission: 'HABITS_READ', surfaces: ['tools'] },
  { key: 'notes', label: 'Notas e conhecimento', description: 'Guarde e reencontre suas notas.', icon: 'notes', to: '/tools/notes', permission: 'NOTES_READ', surfaces: ['tools'] },
  { key: 'user-management', label: 'Usuários e perfis', description: 'Gerencie contas e permissões.', icon: 'user', to: '/admin/users', permission: 'USER_MANAGE', administratorOnly: true, surfaces: ['sidebar-admin', 'overview'] },
  { key: 'settings', label: 'Configurações', description: 'Ajuste suas preferências do Hub.', icon: 'settings', to: '/settings', permission: 'SETTINGS_WRITE', surfaces: ['sidebar-settings', 'overview'] },
]

export function getNavigationItems(surface, hasPermission, isAdministrator) {
  return navigationItems.filter((item) => item.surfaces.includes(surface)
    && hasPermission(item.permission)
    && (!item.administratorOnly || isAdministrator()))
}
