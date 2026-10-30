<script setup>
import { computed, onMounted, ref } from 'vue'
import { hasPermission } from '../services/auth'

const users = ref([])
const profiles = ref([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const success = ref('')
const tab = ref('users')
const userForm = ref({ displayName: '', email: '', password: '', profileId: '' })
const profileForm = ref({ id: '', name: '', permissions: [] })
const permissionOptions = [
  ['TASK_READ', 'Ver tarefas'], ['TASK_WRITE', 'Criar e editar tarefas'], ['TASK_DELETE', 'Excluir tarefas'],
  ['READING_READ', 'Ver biblioteca'], ['READING_WRITE', 'Editar biblioteca'], ['READING_ARCHIVE', 'Arquivar conteúdo'],
  ['SETTINGS_READ', 'Ver configurações'], ['SETTINGS_WRITE', 'Alterar configurações'], ['VIDEO_DOWNLOAD', 'Baixar vídeos'],
  ['USER_MANAGE', 'Gerenciar usuários'], ['PROFILE_MANAGE', 'Gerenciar perfis'],
]
const canEditProfiles = computed(() => hasPermission('PROFILE_MANAGE'))

async function request(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { 'Content-Type': 'application/json', ...(options.headers || {}) } })
  const body = response.status === 204 ? '' : await response.text()
  let result = {}
  try { result = body ? JSON.parse(body) : {} } catch { result = {} }
  if (!response.ok) throw new Error(result.detail || result.message || `A API respondeu com HTTP ${response.status}.`)
  return result
}

async function load() {
  loading.value = true; error.value = ''
  try {
    users.value = await request('/api/admin/users')
    profiles.value = await request('/api/admin/profiles')
    if (!userForm.value.profileId) userForm.value.profileId = profiles.value.find((profile) => profile.name === 'Usuário')?.id || profiles.value[0]?.id || ''
  } catch (failure) { error.value = failure.message || 'Não foi possível carregar os usuários.' }
  finally { loading.value = false }
}

async function createUser() {
  error.value = ''; success.value = ''; saving.value = true
  try {
    await request('/api/admin/users', { method: 'POST', body: JSON.stringify(userForm.value) })
    userForm.value = { displayName: '', email: '', password: '', profileId: userForm.value.profileId }
    success.value = 'Conta criada.'; await load()
  } catch (failure) { error.value = failure.message }
  finally { saving.value = false }
}

async function updateUser(user, changes) {
  error.value = ''; success.value = ''
  if (changes.enabled === false && !window.confirm(`Desativar o acesso de ${user.displayName}?`)) return
  try {
    await request(`/api/admin/users/${encodeURIComponent(user.id)}`, { method: 'PUT', body: JSON.stringify({ profileId: changes.profileId || user.profileId, enabled: changes.enabled ?? user.enabled }) })
    success.value = 'Acesso atualizado.'; await load()
  } catch (failure) { error.value = failure.message }
}

function editProfile(profile) { profileForm.value = { id: profile.id, name: profile.name, permissions: [...profile.permissions] }; tab.value = 'profiles' }
function togglePermission(permission) {
  const current = new Set(profileForm.value.permissions)
  current.has(permission) ? current.delete(permission) : current.add(permission)
  profileForm.value.permissions = [...current]
}
async function saveProfile() {
  error.value = ''; success.value = ''; saving.value = true
  const isNew = !profileForm.value.id
  const path = isNew ? '/api/admin/profiles' : `/api/admin/profiles/${encodeURIComponent(profileForm.value.id)}`
  try {
    await request(path, { method: isNew ? 'POST' : 'PUT', body: JSON.stringify({ name: profileForm.value.name, permissions: profileForm.value.permissions }) })
    profileForm.value = { id: '', name: '', permissions: [] }; success.value = 'Perfil salvo.'; await load()
  } catch (failure) { error.value = failure.message }
  finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <section class="page-content user-admin-page" aria-labelledby="users-title">
    <header class="page-heading"><div><p class="eyebrow">Administração</p><h1 id="users-title">Usuários e perfis</h1><p>Contas separadas e permissões por módulo.</p></div></header>
    <div class="settings-tabs" role="tablist" aria-label="Administração">
      <button role="tab" :aria-selected="tab === 'users'" @click="tab = 'users'">Usuários</button>
      <button v-if="canEditProfiles" role="tab" :aria-selected="tab === 'profiles'" @click="tab = 'profiles'">Perfis</button>
    </div>
    <p v-if="error" class="auth-error" role="alert">{{ error }}</p><p v-if="success" class="success-message" role="status">{{ success }}</p>
    <p v-if="loading" role="status">Carregando…</p>
    <template v-else-if="tab === 'users'">
      <form class="settings-card user-create-form" @submit.prevent="createUser">
        <h2>Adicionar usuário</h2>
        <label>Nome<input v-model.trim="userForm.displayName" required maxlength="120" autocomplete="name" /></label>
        <label>E-mail<input v-model.trim="userForm.email" type="email" required maxlength="254" autocomplete="email" /></label>
        <label>Senha inicial<input v-model="userForm.password" type="password" required minlength="12" maxlength="128" autocomplete="new-password" /></label>
        <label>Perfil<select v-model="userForm.profileId" required><option v-for="profile in profiles" :key="profile.id" :value="profile.id">{{ profile.name }}</option></select></label>
        <button class="primary-button" type="submit" :disabled="saving">Criar conta</button>
      </form>
      <div class="user-list" aria-label="Usuários cadastrados">
        <article v-for="user in users" :key="user.id" class="settings-card user-row">
          <div><h2>{{ user.displayName }}</h2><p>{{ user.email }} · {{ user.profileName }} · {{ user.enabled ? 'Ativo' : 'Desativado' }}</p></div>
          <div class="user-row-actions">
            <label>Perfil<select :value="user.profileId" @change="updateUser(user, { profileId: $event.target.value })"><option v-for="profile in profiles" :key="profile.id" :value="profile.id">{{ profile.name }}</option></select></label>
            <button type="button" class="secondary-button" @click="updateUser(user, { enabled: !user.enabled })">{{ user.enabled ? 'Desativar' : 'Reativar' }}</button>
          </div>
        </article>
      </div>
    </template>
    <section v-else-if="canEditProfiles" class="settings-card profile-editor" aria-labelledby="profile-editor-title">
      <h2 id="profile-editor-title">{{ profileForm.id ? 'Editar perfil' : 'Novo perfil' }}</h2>
      <form @submit.prevent="saveProfile">
        <label>Nome do perfil<input v-model.trim="profileForm.name" required maxlength="80" /></label>
        <fieldset><legend>Permissões</legend><label v-for="[permission, label] in permissionOptions" :key="permission" class="permission-option"><input type="checkbox" :checked="profileForm.permissions.includes(permission)" @change="togglePermission(permission)" />{{ label }}</label></fieldset>
        <div class="form-actions"><button class="primary-button" type="submit" :disabled="saving">Salvar perfil</button><button type="button" class="secondary-button" @click="editProfile({ id: '', name: '', permissions: [] })">Novo perfil</button></div>
      </form>
      <div class="profile-list"><button v-for="profile in profiles" :key="profile.id" class="secondary-button" type="button" @click="editProfile(profile)">{{ profile.name }} · Editar</button></div>
    </section>
  </section>
</template>
