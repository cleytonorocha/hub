<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authState, login } from '../services/auth'

const email = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const route = useRoute()
const router = useRouter()

async function submit() {
  busy.value = true; error.value = ''
  try { await login(email.value, password.value); await router.replace(typeof route.query.redirect === 'string' ? route.query.redirect : '/') }
  catch { error.value = authState.error || 'Não foi possível entrar. Tente novamente.' }
  finally { busy.value = false }
}
</script>

<template>
  <section class="auth-page" aria-labelledby="login-title">
    <form class="auth-card" @submit.prevent="submit">
      <div class="auth-brand" aria-hidden="true">◆ Hub</div>
      <h1 id="login-title">Entrar no Hub</h1>
      <p>Acesse suas tarefas e sua biblioteca.</p>
      <label for="login-email">E-mail</label>
      <input id="login-email" v-model.trim="email" type="email" autocomplete="username" required maxlength="254" />
      <label for="login-password">Senha</label>
      <input id="login-password" v-model="password" type="password" autocomplete="current-password" required />
      <p v-if="error" class="auth-error" role="alert">{{ error }}</p>
      <button class="primary-button" type="submit" :disabled="busy">{{ busy ? 'Entrando…' : 'Entrar' }}</button>
    </form>
  </section>
</template>
