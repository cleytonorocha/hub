<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { setupFirstAdmin } from '../services/auth'

const name = ref('')
const email = ref('')
const password = ref('')
const confirmation = ref('')
const busy = ref(false)
const error = ref('')
const migrationSummary = ref(null)
const router = useRouter()

async function submit() {
  error.value = ''
  if (password.value !== confirmation.value) { error.value = 'As senhas não correspondem.'; return }
  busy.value = true
  try {
    const result = await setupFirstAdmin({ displayName: name.value, email: email.value, password: password.value })
    migrationSummary.value = result.migratedLegacyRows || {}
  } catch (failure) { error.value = failure.message || 'Não foi possível concluir a configuração inicial.' }
  finally { busy.value = false }
}
</script>

<template>
  <section class="auth-page" aria-labelledby="setup-title">
    <form class="auth-card" @submit.prevent="submit">
      <div class="auth-brand" aria-hidden="true">◆ Hub</div>
      <template v-if="migrationSummary">
        <h1 id="setup-title">Instalação configurada</h1>
        <p>A conta Administrador foi criada. Os dados locais anteriores foram atribuídos a ela.</p>
        <p class="migration-total">{{ Object.values(migrationSummary).reduce((sum, count) => sum + Number(count || 0), 0) }} registros verificados e associados à conta inicial.</p>
        <button class="primary-button" type="button" @click="router.replace({ name: 'login' })">Ir para entrar</button>
      </template>
      <template v-else>
      <h1 id="setup-title">Crie sua conta Administrador</h1>
      <p>Esta etapa aparece apenas uma vez nesta instalação.</p>
      <label for="setup-name">Nome</label>
      <input id="setup-name" v-model.trim="name" autocomplete="name" required maxlength="120" />
      <label for="setup-email">E-mail</label>
      <input id="setup-email" v-model.trim="email" type="email" autocomplete="email" required maxlength="254" />
      <label for="setup-password">Senha <span class="form-help">Use 12 a 128 caracteres.</span></label>
      <input id="setup-password" v-model="password" type="password" autocomplete="new-password" required minlength="12" maxlength="128" />
      <label for="setup-confirmation">Confirme a senha</label>
      <input id="setup-confirmation" v-model="confirmation" type="password" autocomplete="new-password" required minlength="12" maxlength="128" />
      <p v-if="error" class="auth-error" role="alert">{{ error }}</p>
      <button class="primary-button" type="submit" :disabled="busy">{{ busy ? 'Salvando…' : 'Criar Administrador' }}</button>
      </template>
    </form>
  </section>
</template>
