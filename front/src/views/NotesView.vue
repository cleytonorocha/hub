<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { hasPermission } from '../services/auth'
import { createNote, deleteNote, getNote, listNoteTags, listNotes, updateNote } from '../services/notes'

const canWrite = computed(() => hasPermission('NOTES_WRITE'))
const canReadTasks = computed(() => hasPermission('TASK_READ'))
const dialog = ref(null)
const searchInput = ref(null)
const titleInput = ref(null)
const rows = ref([])
const tags = ref([])
const tasks = ref([])
const activeNote = ref(null)
const conflictNote = ref(null)
const loading = ref(true)
const loaded = ref(false)
const saving = ref(false)
const deleting = ref(false)
const error = ref('')
const notice = ref('')
const query = ref('')
const appliedQuery = ref('')
const selectedTag = ref('')
const appliedTag = ref('')
const archived = ref(false)
const page = ref(0)
const size = 20
const total = ref(0)
const totalPages = ref(0)
const mode = ref('')
const form = reactive(emptyForm())
const formBaseline = ref('')
let requestSequence = 0
let returnFocus = null

const formDirty = computed(() => JSON.stringify(toPayload()) !== formBaseline.value)
const hasSearch = computed(() => Boolean(appliedQuery.value || appliedTag.value))
const firstResult = computed(() => total.value ? page.value * size + 1 : 0)
const lastResult = computed(() => Math.min((page.value + 1) * size, total.value))

function emptyForm(note = null) {
  return {
    title: note?.title || '',
    body: note?.body || '',
    tagsText: (note?.tags || []).join(', '),
    pinned: note?.pinned || false,
    archived: note?.archived || false,
    relatedTaskId: note?.relatedTaskId || '',
    reaffirmRelatedTask: false,
    revision: note?.revision ?? null,
  }
}

function toPayload() {
  return {
    title: form.title,
    body: form.body,
    tags: form.tagsText.split(',').map((tag) => tag.trim()).filter(Boolean),
    pinned: form.pinned,
    archived: form.archived,
    relatedTaskId: form.relatedTaskId || null,
    reaffirmRelatedTask: form.reaffirmRelatedTask,
    revision: form.revision,
  }
}

function applyNoteToForm(note) {
  Object.assign(form, emptyForm(note))
  formBaseline.value = JSON.stringify(toPayload())
}

onMounted(async () => {
  await Promise.all([loadPage(0), loadTasks()])
  window.addEventListener('beforeunload', protectUnsaved)
})

onBeforeUnmount(() => window.removeEventListener('beforeunload', protectUnsaved))

function protectUnsaved(event) {
  if (mode.value && mode.value !== 'detail' && formDirty.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}

onBeforeRouteLeave(() => {
  if (!mode.value || mode.value === 'detail' || !formDirty.value) return true
  if (!window.confirm('Há alterações não salvas. Descartar e sair?')) return false
  dialog.value?.close()
  return true
})

async function loadTasks() {
  if (!canReadTasks.value) return
  try {
    const response = await fetch('/api/tasks')
    if (response.ok) tasks.value = await response.json()
  } catch { /* A relação com tarefa é opcional. */ }
}

async function loadPage(nextPage = page.value) {
  const sequence = ++requestSequence
  loading.value = true
  error.value = ''
  try {
    const [result, availableTags] = await Promise.all([
      listNotes({ query: appliedQuery.value, tag: appliedTag.value, archived: archived.value, page: nextPage, size }),
      listNoteTags(archived.value),
    ])
    if (sequence !== requestSequence) return
    if (nextPage > 0 && nextPage >= result.totalPages) {
      await loadPage(Math.max(0, result.totalPages - 1))
      return
    }
    rows.value = result.content
    tags.value = availableTags
    page.value = result.page
    total.value = result.totalElements
    totalPages.value = result.totalPages
    loaded.value = true
  } catch (cause) {
    if (sequence === requestSequence) error.value = cause.message
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function search() {
  appliedQuery.value = query.value.trim()
  appliedTag.value = selectedTag.value
  loadPage(0)
}

function clearFilters() {
  query.value = ''
  appliedQuery.value = ''
  selectedTag.value = ''
  appliedTag.value = ''
  loadPage(0)
  nextTick(() => searchInput.value?.focus())
}

async function toggleArchived() {
  archived.value = !archived.value
  selectedTag.value = ''
  appliedTag.value = ''
  loadPage(0)
}

function openDialog(nextMode, note = null, event = null) {
  returnFocus = event?.currentTarget || document.activeElement
  mode.value = nextMode
  activeNote.value = note
  conflictNote.value = null
  error.value = ''
  notice.value = ''
  Object.assign(form, emptyForm(note))
  formBaseline.value = JSON.stringify(toPayload())
  if (!dialog.value?.open) dialog.value?.showModal()
  nextTick(() => nextMode === 'create' || nextMode === 'edit'
    ? titleInput.value?.focus()
    : document.getElementById('notes-dialog-title')?.focus())
}

function openNew(event) { openDialog('create', null, event) }
function openDetail(note, event) { openDialog('detail', note, event) }
function openEdit() { openDialog('edit', activeNote.value) }

function closeDialog() {
  if (saving.value || deleting.value) return
  if (mode.value !== 'detail' && formDirty.value && !window.confirm('Descartar as alterações desta nota?')) return
  dialog.value?.close()
  mode.value = ''
  activeNote.value = null
  conflictNote.value = null
  error.value = ''
  nextTick(() => {
    if (returnFocus?.isConnected) returnFocus.focus()
    else searchInput.value?.focus()
  })
}

function onDialogCancel(event) { event.preventDefault(); closeDialog() }

function onTaskSelected() { form.reaffirmRelatedTask = Boolean(form.relatedTaskId) }

async function saveNote() {
  if (saving.value || conflictNote.value) return
  const creating = mode.value === 'create'
  saving.value = true
  error.value = ''
  try {
    const payload = toPayload()
    const saved = creating
      ? await createNote({ title: payload.title, body: payload.body, tags: payload.tags, pinned: payload.pinned, relatedTaskId: payload.relatedTaskId })
      : await updateNote(activeNote.value.id, payload)
    activeNote.value = saved
    applyNoteToForm(saved)
    mode.value = 'detail'
    notice.value = creating ? 'Nota criada.' : 'Alterações salvas.'
    await loadPage(page.value)
  } catch (cause) {
    error.value = cause.message
    if (cause.status === 409 && mode.value === 'edit') await loadConflictVersion()
  } finally { saving.value = false }
}

async function loadConflictVersion() {
  try {
    conflictNote.value = await getNote(activeNote.value.id)
    error.value = 'Esta nota mudou em outra janela. Seu rascunho foi preservado; escolha como resolver o conflito.'
  } catch (cause) {
    error.value = `${cause.message} Seu rascunho continua aberto.`
  }
}

function useSavedVersion() {
  if (!conflictNote.value) return
  activeNote.value = conflictNote.value
  applyNoteToForm(conflictNote.value)
  conflictNote.value = null
  error.value = ''
  notice.value = 'A versão salva foi carregada.'
}

async function overwriteSavedVersion() {
  if (!conflictNote.value || saving.value) return
  if (!window.confirm('Substituir a versão salva pelo seu rascunho? Esta ação não pode ser desfeita.')) return
  saving.value = true
  error.value = ''
  try {
    const latest = conflictNote.value
    const payload = { ...toPayload(), revision: latest.revision }
    const saved = await updateNote(activeNote.value.id, payload)
    activeNote.value = saved
    conflictNote.value = null
    applyNoteToForm(saved)
    mode.value = 'detail'
    notice.value = 'Seu rascunho substituiu a versão salva.'
    await loadPage(page.value)
  } catch (cause) {
    error.value = cause.message
    if (cause.status === 409) await loadConflictVersion()
  } finally { saving.value = false }
}

async function updateNoteState(changes, successMessage) {
  if (!activeNote.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    activeNote.value = await updateNote(activeNote.value.id, {
      title: activeNote.value.title,
      body: activeNote.value.body,
      tags: activeNote.value.tags,
      pinned: activeNote.value.pinned,
      archived: activeNote.value.archived,
      relatedTaskId: activeNote.value.relatedTaskId,
      reaffirmRelatedTask: false,
      revision: activeNote.value.revision,
      ...changes,
    })
    applyNoteToForm(activeNote.value)
    notice.value = successMessage
    await loadPage(page.value)
  } catch (cause) {
    error.value = cause.message
    if (cause.status === 409) await loadConflictVersion()
  } finally { saving.value = false }
}

async function togglePin() {
  if (!activeNote.value) return
  await updateNoteState({ pinned: !activeNote.value.pinned }, activeNote.value.pinned ? 'Nota desafixada.' : 'Nota fixada.')
}

async function toggleArchive() {
  if (!activeNote.value) return
  const archivedNow = !activeNote.value.archived
  if (!window.confirm(archivedNow ? 'Arquivar esta nota? Ela continuará disponível na lista de arquivadas.' : 'Restaurar esta nota na biblioteca?')) return
  await updateNoteState({ archived: archivedNow }, archivedNow ? 'Nota arquivada.' : 'Nota restaurada.')
}

async function removeNote() {
  if (!activeNote.value || deleting.value) return
  if (!window.confirm(`Excluir permanentemente “${activeNote.value.title}”? Esta ação não pode ser desfeita.`)) return
  deleting.value = true
  error.value = ''
  try {
    await deleteNote(activeNote.value.id)
    notice.value = 'Nota excluída.'
    deleting.value = false
    closeDialog()
    await loadPage(page.value)
  } catch (cause) { error.value = cause.message }
  finally { deleting.value = false }
}

function formatDate(value) {
  if (!value) return ''
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
</script>

<template>
  <div id="page-content" class="notes-view">
    <header class="notes-header">
      <div><p class="settings-eyebrow">FERRAMENTAS</p><h1>Notas e conhecimento</h1><p>Guarde ideias e referências sem precisar transformá-las em tarefas.</p></div>
      <button v-if="canWrite" class="task-primary-button" type="button" @click="openNew($event)">+ Nova nota</button>
    </header>

    <p v-if="notice" class="notes-notice" role="status" aria-live="polite">{{ notice }}</p>

    <form class="notes-filters" @submit.prevent="search">
      <label class="task-field notes-query"><span>Buscar em títulos e notas</span><input ref="searchInput" v-model="query" type="search" maxlength="200" placeholder="Buscar uma ideia, decisão ou referência"></label>
      <label class="task-field"><span>Etiqueta</span><select v-model="selectedTag"><option value="">Todas</option><option v-for="tag in tags" :key="tag" :value="tag">{{ tag }}</option></select></label>
      <div class="notes-filter-actions">
        <button class="task-primary-button" type="submit" :disabled="loading">{{ loading ? 'Pesquisando…' : 'Pesquisar' }}</button>
        <button class="task-secondary-button" type="button" :aria-pressed="archived" @click="toggleArchived">{{ archived ? 'Ver notas ativas' : 'Ver arquivadas' }}</button>
        <button v-if="hasSearch" class="task-secondary-button" type="button" @click="clearFilters">Limpar filtros</button>
      </div>
    </form>

    <section class="notes-results" aria-labelledby="notes-results-title" :aria-busy="loading">
      <div class="notes-results-heading"><div><h2 id="notes-results-title" tabindex="-1">{{ archived ? 'Notas arquivadas' : 'Sua biblioteca' }}</h2><p v-if="loaded && !error" role="status" aria-live="polite">{{ total ? `${firstResult}–${lastResult} de ${total}` : 'Nenhuma nota' }}</p></div><button class="task-secondary-button" type="button" :disabled="loading" @click="loadPage(page)">Atualizar</button></div>
      <p v-if="loading && !loaded" class="notes-state" role="status">Carregando notas…</p>
      <div v-else-if="error" class="notes-error" role="alert"><p>{{ error }}</p><p v-if="rows.length">Os resultados anteriores continuam visíveis.</p><button class="task-secondary-button" type="button" :disabled="loading" @click="loadPage(page)">Tentar novamente</button></div>
      <p v-else-if="loading" class="notes-state" role="status">Atualizando resultados…</p>
      <div v-else-if="loaded && !total && !hasSearch" class="notes-empty"><strong>{{ archived ? 'Você ainda não tem notas arquivadas.' : 'Sua biblioteca está vazia.' }}</strong><p>{{ archived ? 'As notas arquivadas continuarão disponíveis aqui.' : 'Crie uma nota para guardar ideias, decisões e referências.' }}</p><button v-if="canWrite && !archived" class="task-primary-button" type="button" @click="openNew($event)">Criar primeira nota</button></div>
      <div v-else-if="loaded && !total" class="notes-empty"><strong>Nenhuma nota encontrada.</strong><p>Experimente outra busca ou remova o filtro por etiqueta.</p><button class="task-secondary-button" type="button" @click="clearFilters">Limpar filtros</button></div>
      <ul v-else class="notes-list">
        <li v-for="note in rows" :key="note.id">
          <button class="notes-card" type="button" @click="openDetail(note, $event)">
            <span class="notes-card-heading"><strong>{{ note.title }}</strong><span v-if="note.pinned" class="notes-pin-label">Fixada</span></span>
            <span class="notes-card-body">{{ note.body || 'Sem texto adicional.' }}</span>
            <span v-if="note.tags.length" class="notes-tags"><span v-for="tag in note.tags" :key="tag">{{ tag }}</span></span>
            <span class="notes-card-footer">Alterada {{ formatDate(note.updatedAt) }}</span>
          </button>
        </li>
      </ul>
      <nav v-if="totalPages > 1" class="notes-pagination" aria-label="Paginação das notas">
        <button class="task-secondary-button" type="button" :disabled="page === 0 || loading" @click="loadPage(page - 1)">Anterior</button><span aria-live="polite">Página {{ page + 1 }} de {{ totalPages }}</span><button class="task-secondary-button" type="button" :disabled="page + 1 >= totalPages || loading" @click="loadPage(page + 1)">Próxima</button>
      </nav>
    </section>

    <dialog ref="dialog" class="notes-dialog" aria-labelledby="notes-dialog-title" @cancel="onDialogCancel" @click.self="closeDialog">
      <header class="notes-dialog-header"><div><p class="settings-eyebrow">{{ mode === 'create' ? 'NOVA NOTA' : mode === 'edit' ? 'EDITAR NOTA' : 'NOTAS E CONHECIMENTO' }}</p><h2 id="notes-dialog-title" tabindex="-1">{{ mode === 'detail' ? activeNote?.title : mode === 'edit' ? 'Editar nota' : 'Nova nota' }}</h2></div><button class="task-secondary-button" type="button" aria-label="Fechar" @click="closeDialog">Fechar</button></header>
      <p v-if="notice" class="notes-notice" role="status" aria-live="polite">{{ notice }}</p>

      <article v-if="mode === 'detail' && activeNote" class="notes-detail">
        <p class="notes-updated">Alterada {{ formatDate(activeNote.updatedAt) }}</p>
        <div class="notes-detail-body">{{ activeNote.body || 'Esta nota não tem texto adicional.' }}</div>
        <div v-if="activeNote.tags.length" class="notes-tags" aria-label="Etiquetas"><span v-for="tag in activeNote.tags" :key="tag">{{ tag }}</span></div>
        <section v-if="activeNote.relatedTaskId" class="notes-related-task" aria-label="Tarefa relacionada">
          <h3>Tarefa relacionada</h3><p v-if="activeNote.relatedTaskStatus === 'AVAILABLE'">{{ activeNote.relatedTaskTitle }}</p><p v-else>A tarefa vinculada não está disponível para esta conta. A nota foi preservada.</p>
        </section>
        <p v-if="error" class="notes-error" role="alert">{{ error }}<button v-if="error.includes('outra janela')" class="task-secondary-button" type="button" @click="loadConflictVersion">Ver versão salva</button></p>
        <footer class="notes-detail-actions">
          <button class="task-secondary-button" type="button" :disabled="saving" :aria-pressed="activeNote.pinned" @click="togglePin">{{ activeNote.pinned ? 'Desafixar' : 'Fixar' }}</button>
          <button class="task-secondary-button" type="button" :disabled="saving" @click="toggleArchive">{{ activeNote.archived ? 'Restaurar' : 'Arquivar' }}</button>
          <button v-if="canWrite" class="task-secondary-button" type="button" @click="openEdit">Editar</button>
          <button v-if="canWrite" class="notes-delete-button" type="button" :disabled="deleting" @click="removeNote">{{ deleting ? 'Excluindo…' : 'Excluir nota' }}</button>
        </footer>
      </article>

      <form v-else class="notes-form" @submit.prevent="saveNote">
        <label class="task-field"><span>Título</span><input ref="titleInput" v-model="form.title" type="text" maxlength="512" required></label>
        <label class="task-field"><span>Texto</span><textarea v-model="form.body" maxlength="2048" rows="12" placeholder="Escreva uma ideia, decisão ou referência…"></textarea><small>{{ form.body.length }} de 2.048 caracteres</small></label>
        <label class="task-field"><span>Etiquetas · separadas por vírgula</span><input v-model="form.tagsText" type="text" maxlength="1299" placeholder="Trabalho, Pessoal"><small>Até 20 etiquetas; cada uma pode ter até 64 caracteres.</small></label>
        <label class="notes-check"><input v-model="form.pinned" type="checkbox"><span>Fixar esta nota na biblioteca</span></label>
        <label v-if="canReadTasks" class="task-field"><span>Tarefa relacionada · opcional</span><select v-model="form.relatedTaskId" @change="onTaskSelected"><option value="">Sem tarefa relacionada</option><option v-for="task in tasks" :key="task.id" :value="task.id">{{ task.title }}</option></select><small>Vincular uma tarefa não altera o conteúdo dela.</small></label>
        <p v-else class="notes-permission-hint">Seu perfil não permite consultar tarefas para criar vínculos. A nota continua independente.</p>
        <p v-if="mode === 'edit' && activeNote?.relatedTaskStatus === 'UNAVAILABLE' && form.relatedTaskId" class="notes-unavailable-hint">O vínculo atual está indisponível. Escolher uma tarefa ativa o novo vínculo.</p>
        <p v-if="error" class="notes-error" role="alert">{{ error }}</p>
        <section v-if="conflictNote" class="notes-conflict" aria-labelledby="notes-conflict-title">
          <h3 id="notes-conflict-title">A versão salva mudou</h3><p>Seu rascunho permanece aqui. Escolha usar o conteúdo salvo ou substituir essa versão pelo rascunho.</p>
          <details><summary>Ver a versão salva</summary><strong>{{ conflictNote.title }}</strong><p class="notes-conflict-body">{{ conflictNote.body || 'Sem texto adicional.' }}</p><div v-if="conflictNote.tags.length" class="notes-tags"><span v-for="tag in conflictNote.tags" :key="tag">{{ tag }}</span></div></details>
          <div class="notes-conflict-actions"><button class="task-secondary-button" type="button" :disabled="saving" @click="useSavedVersion">Usar versão salva</button><button class="notes-delete-button" type="button" :disabled="saving" @click="overwriteSavedVersion">Substituir pela minha versão</button></div>
        </section>
        <footer class="notes-form-actions"><button class="task-primary-button" type="submit" :disabled="saving || !canWrite || Boolean(conflictNote)">{{ saving ? 'Salvando…' : 'Salvar nota' }}</button><button class="task-secondary-button" type="button" :disabled="saving" @click="closeDialog">Cancelar</button></footer>
      </form>
    </dialog>
  </div>
</template>
