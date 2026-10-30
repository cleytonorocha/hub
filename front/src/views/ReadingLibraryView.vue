<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { createReadingAuthor, createReadingItem, getReadingItem, importReadingCoverFromUrl, listReadingAuthors, listReadingItems, removeReadingCover, updateReadingItem, uploadReadingCover } from '../services/readingLibrary'
import ReadingPageProgress from '../components/ReadingPageProgress.vue'

const queryInput = ref(null)
const titleInput = ref(null)
const authorDialog = ref(null)
const authorNameInput = ref(null)
const authors = ref([])
const authorName = ref('')
const authorSaving = ref(false)
const authorError = ref('')
const rows = ref([])
const activeItem = ref(null)
const dialog = ref(null)
const loading = ref(false)
const loaded = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const formBaseline = ref('')
const query = ref('')
const appliedQuery = ref('')
const appliedStatus = ref('')
const appliedType = ref('')
const appliedAuthorId = ref('')
const state = reactive({ status: '', type: '', authorId: '', archived: false, page: 0, size: 20, total: 0, totalPages: 0 })
const mode = ref('detail')
const form = reactive(emptyForm())
let requestSequence = 0
let returnFocus = null
let previousType = 'BOOK'
let previousTotalPages = ''

const hasFilters = computed(() => Boolean(appliedStatus.value || appliedType.value || appliedAuthorId.value))
const firstResult = computed(() => state.total ? state.page * state.size + 1 : 0)
const lastResult = computed(() => Math.min((state.page + 1) * state.size, state.total))
const statusRows = computed(() => [
  { status: 'IN_PROGRESS', label: 'Em andamento' },
  { status: 'TO_READ', label: 'A ler' },
  { status: 'COMPLETED', label: 'Concluído' },
].map((group) => ({
  ...group,
  items: rows.value.filter((item) => item.status === group.status),
})).filter((group) => group.items.length > 0))
const filtersDirty = computed(() => state.status !== appliedStatus.value || state.type !== appliedType.value || state.authorId !== appliedAuthorId.value)
const dirtySearch = computed(() => query.value !== appliedQuery.value || filtersDirty.value)
const dialogTitle = computed(() => mode.value === 'create' ? 'Adicionar conteúdo' : mode.value === 'edit' ? 'Editar conteúdo' : activeItem.value?.title || 'Conteúdo')
const formDirty = computed(() => JSON.stringify(toPayload()) !== formBaseline.value)
const hasPageProgress = (item) => item?.type === 'BOOK' && item.totalPages != null && item.totalPages !== ''

function emptyForm(item = null) {
  return {
    id: item?.id || '',
    title: item?.title || '',
    type: item?.type || 'BOOK',
    authorOrSource: item?.authorOrSource || '',
    authorIds: item?.authors?.map((author) => author.id) || [],
    coverSourceUrl: item?.coverSourceUrl || '',
    url: item?.url || '',
    status: item?.status || 'TO_READ',
    progressMarker: item?.progressMarker || '',
    totalPages: item?.totalPages ?? '',
    currentPage: item?.currentPage ?? null,
    note: item?.note || '',
    tagsText: (item?.tags || []).join(', '),
    archived: item?.archived || false,
    revision: item?.revision ?? null,
  }
}

onMounted(async () => {
  window.addEventListener('hub-reading-items-changed', reloadCurrentPage)
  try { authors.value = await listReadingAuthors() } catch { /* Item browsing remains available if this request fails. */ }
  await loadPage(0)
})

onBeforeUnmount(() => window.removeEventListener('hub-reading-items-changed', reloadCurrentPage))

async function reloadCurrentPage() {
  await loadPage(state.page)
}

async function loadPage(page = state.page) {
  const sequence = ++requestSequence
  loading.value = true
  error.value = ''
  try {
    const response = await listReadingItems({
      query: appliedQuery.value,
      status: appliedStatus.value,
      type: appliedType.value,
      authorId: appliedAuthorId.value,
      archived: state.archived,
      page,
      size: state.size,
    })
    if (sequence !== requestSequence) return
    if (page > 0 && page >= response.totalPages) {
      await loadPage(Math.max(0, response.totalPages - 1))
      return
    }
    rows.value = response.content
    state.page = response.page
    state.total = response.totalElements
    state.totalPages = response.totalPages
    loaded.value = true
  } catch (cause) {
    if (sequence === requestSequence) error.value = cause.message
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function search() {
  appliedQuery.value = query.value.trim()
  appliedStatus.value = state.status
  appliedType.value = state.type
  appliedAuthorId.value = state.authorId
  loadPage(0)
}

function resetFilters() {
  state.status = ''
  state.type = ''
  state.authorId = ''
  appliedStatus.value = ''
  appliedType.value = ''
  appliedAuthorId.value = ''
  loadPage(0)
}

function resetSearch() {
  query.value = ''
  appliedQuery.value = ''
  state.status = ''
  state.type = ''
  state.authorId = ''
  appliedStatus.value = ''
  appliedType.value = ''
  appliedAuthorId.value = ''
  loadPage(0)
  nextTick(() => queryInput.value?.focus())
}

async function toggleArchived() {
  state.archived = !state.archived
  state.status = ''
  state.type = ''
  state.authorId = ''
  appliedStatus.value = ''
  appliedType.value = ''
  appliedAuthorId.value = ''
  await loadPage(0)
}

function openDialog(nextMode, item = null, event = null) {
  returnFocus = event?.currentTarget || document.activeElement
  mode.value = nextMode
  activeItem.value = item
  Object.assign(form, emptyForm(item))
  previousType = form.type
  previousTotalPages = form.totalPages
  formBaseline.value = JSON.stringify(toPayload())
  error.value = ''
  notice.value = ''
  if (!dialog.value?.open) dialog.value?.showModal()
  nextTick(() => nextMode !== 'detail' ? titleInput.value?.focus() : document.getElementById('reading-dialog-title')?.focus())
}

function closeDialog() {
  if (saving.value) return
  if (mode.value !== 'detail' && formDirty.value && !window.confirm('Descartar as alterações deste conteúdo?')) return
  dialog.value?.close()
  mode.value = 'detail'
  activeItem.value = null
  error.value = ''
  nextTick(() => {
    if (returnFocus?.isConnected) returnFocus.focus()
    else queryInput.value?.focus()
  })
}

function onDialogCancel(event) {
	event.preventDefault()
	closeDialog()
}

function cancelForm() {
  if (saving.value) return
  if (formDirty.value && !window.confirm('Descartar as alterações deste conteúdo?')) return
  if (mode.value === 'edit') openDialog('detail', activeItem.value)
  else {
    formBaseline.value = JSON.stringify(toPayload())
    closeDialog()
  }
}

function toPayload() {
  return {
    id: form.id,
    title: form.title,
    type: form.type,
    authorOrSource: form.authorOrSource,
    authorIds: form.type === 'BOOK' ? [...form.authorIds] : [],
    url: form.url,
    status: form.status,
    progressMarker: form.progressMarker,
    totalPages: form.totalPages === '' ? null : Number(form.totalPages),
    currentPage: form.totalPages === '' ? null : (form.currentPage === null ? 0 : Number(form.currentPage)),
    note: form.note,
    tags: form.tagsText.split(',').map((tag) => tag.trim()).filter(Boolean),
    archived: form.archived,
    revision: form.revision,
  }
}

function onTypeChanged(event) {
  if (form.type !== 'BOOK' && form.totalPages !== '' && !window.confirm('Este conteúdo tem progresso por páginas. Ao trocar o tipo, esse progresso será removido. Continuar?')) {
    form.type = previousType
    event.target.value = previousType
    return
  }
  if (form.type !== 'BOOK') {
    form.totalPages = ''
    form.currentPage = null
    previousTotalPages = ''
  }
  previousType = form.type
}

function onTotalPagesChanged() {
  if (form.totalPages === '' && previousTotalPages !== '') {
    if (!window.confirm('Remover o total de páginas também apagará a página atual. Deseja continuar?')) {
      form.totalPages = previousTotalPages
      return
    }
    form.currentPage = null
  } else if (form.totalPages !== '' && form.currentPage === null) {
    form.currentPage = 0
  }
  previousTotalPages = form.totalPages
}

async function saveItem() {
  if (saving.value) return
  const creating = mode.value === 'create'
  saving.value = true
  error.value = ''
  try {
    const payload = toPayload()
    const saved = creating ? await createReadingItem(payload) : await updateReadingItem(payload)
    activeItem.value = saved
    Object.assign(form, emptyForm(saved))
    mode.value = 'detail'
    formBaseline.value = JSON.stringify(toPayload())
    notice.value = creating ? 'Conteúdo adicionado à biblioteca.' : 'Alterações salvas.'
    await loadPage(state.page)
  } catch (cause) {
    error.value = cause.message
  } finally {
    saving.value = false
  }
}

async function toggleArchive() {
  const action = activeItem.value?.archived ? 'restaurar' : 'arquivar'
  if (!window.confirm(`Deseja ${action} “${activeItem.value.title}”? O conteúdo e as notas serão preservados.`)) return
  saving.value = true
  error.value = ''
  try {
    const saved = await updateReadingItem({ ...activeItem.value, archived: !activeItem.value.archived })
    activeItem.value = saved
    Object.assign(form, emptyForm(saved))
    notice.value = saved.archived ? 'Conteúdo arquivado.' : 'Conteúdo restaurado.'
    await loadPage(state.page)
  } catch (cause) {
    error.value = cause.message
  } finally {
    saving.value = false
  }
}

async function setStatus(status) {
  if (!activeItem.value || activeItem.value.status === status) return
  saving.value = true
  error.value = ''
  try {
    const saved = await updateReadingItem({ ...activeItem.value, status })
    activeItem.value = saved
    Object.assign(form, emptyForm(saved))
    await loadPage(state.page)
    notice.value = 'Estado de leitura atualizado.'
  } catch (cause) {
    error.value = cause.message
  } finally {
    saving.value = false
  }
}

async function saveCurrentPage(currentPage) {
  if (!activeItem.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const saved = await updateReadingItem({ ...activeItem.value, currentPage })
    activeItem.value = saved
    Object.assign(form, emptyForm(saved))
    await loadPage(state.page)
    notice.value = 'Progresso atualizado.'
  } catch (cause) {
    error.value = cause.message
  } finally {
    saving.value = false
  }
}

async function uploadCover(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || !activeItem.value || saving.value) return
  if (file.size > 8 * 1024 * 1024) {
    error.value = 'A imagem pode ter até 8 MiB.'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const saved = await uploadReadingCover(activeItem.value.id, activeItem.value.revision, file)
    activeItem.value = saved
    Object.assign(form, emptyForm(saved))
    notice.value = 'Imagem de capa salva.'
    await loadPage(state.page)
  } catch (cause) {
    error.value = cause.message
  } finally {
    saving.value = false
  }
}

function openAuthorDialog() {
  authorName.value = ''
  authorError.value = ''
  authorDialog.value?.showModal()
  nextTick(() => authorNameInput.value?.focus())
}

async function saveAuthor() {
  if (authorSaving.value) return
  authorSaving.value = true
  authorError.value = ''
  try {
    const author = await createReadingAuthor(authorName.value)
    authors.value = [...authors.value, author].sort((a, b) => a.name.localeCompare(b.name, 'pt-BR'))
    form.authorIds = [...new Set([...form.authorIds, author.id])]
    authorDialog.value?.close()
  } catch (cause) { authorError.value = cause.message }
  finally { authorSaving.value = false }
}

async function importCoverLink() {
  if (!activeItem.value || saving.value || !form.coverSourceUrl.trim()) return
  saving.value = true
  error.value = ''
  try {
    const saved = await importReadingCoverFromUrl(activeItem.value.id, activeItem.value.revision, form.coverSourceUrl.trim())
    activeItem.value = saved
    Object.assign(form, emptyForm(saved))
    notice.value = 'Capa importada e armazenada no Hub.'
    await loadPage(state.page)
  } catch (cause) { error.value = cause.message }
  finally { saving.value = false }
}

async function deleteCover() {
  if (!activeItem.value?.coverImageUrl || saving.value) return
  if (!window.confirm('Remover a imagem de capa deste conteúdo?')) return
  saving.value = true
  error.value = ''
  try {
    const saved = await removeReadingCover(activeItem.value.id, activeItem.value.revision)
    activeItem.value = saved
    Object.assign(form, emptyForm(saved))
    notice.value = 'Imagem de capa removida.'
    await loadPage(state.page)
  } catch (cause) {
    error.value = cause.message
  } finally {
    saving.value = false
  }
}

function goToPage(page) {
  if (page < 0 || page >= state.totalPages || loading.value) return
  loadPage(page)
  nextTick(() => document.getElementById('reading-results-title')?.focus())
}

async function reloadAfterConflict() {
  await loadPage(state.page)
  activeItem.value = await getReadingItem(activeItem.value.id)
  Object.assign(form, emptyForm(activeItem.value))
  mode.value = 'detail'
  error.value = ''
}

function typeLabel(type) { return ({ BOOK: 'Livro', ARTICLE: 'Artigo', VIDEO: 'Vídeo' })[type] || type }
function statusLabel(status) { return ({ TO_READ: 'A ler', IN_PROGRESS: 'Em andamento', COMPLETED: 'Concluído' })[status] || status }

</script>

<template>
  <section id="page-content" class="content-view reading-library-view" aria-labelledby="reading-library-title">
    <header class="view-header reading-library-header">
      <div>
        <p class="settings-eyebrow">BIBLIOTECA PESSOAL</p>
        <h1 id="reading-library-title">Sua próxima boa leitura</h1>
        <p>Guarde livros, artigos e vídeos e marque onde parou.</p>
      </div>
      <div class="reading-header-actions">
        <button class="task-secondary-button" type="button" @click="toggleArchived">{{ state.archived ? 'Ver fila ativa' : 'Ver arquivados' }}</button>
        <button class="task-primary-button" type="button" @click="openDialog('create', null, $event)">+ Adicionar conteúdo</button>
      </div>
    </header>

    <form class="reading-search-form" role="search" @submit.prevent="search">
      <label class="task-field reading-query-field"><span>Buscar título, autor/origem ou nota</span><input ref="queryInput" v-model="query" type="search" maxlength="200" placeholder="Ex.: astronomia"></label>
      <label class="task-field"><span>Estado</span><select v-model="state.status"><option value="">Todos</option><option value="TO_READ">A ler</option><option value="IN_PROGRESS">Em andamento</option><option value="COMPLETED">Concluído</option></select></label>
      <label class="task-field"><span>Tipo</span><select v-model="state.type"><option value="">Todos</option><option value="BOOK">Livro</option><option value="ARTICLE">Artigo</option><option value="VIDEO">Vídeo</option></select></label>
      <label class="task-field"><span>Autor</span><select v-model="state.authorId"><option value="">Todos</option><option v-for="author in authors" :key="author.id" :value="author.id">{{ author.name }}</option></select></label>
      <div class="reading-search-actions">
        <button class="task-primary-button" type="submit" :disabled="loading">{{ loading ? 'Pesquisando…' : 'Pesquisar' }}</button>
        <button v-if="filtersDirty || hasFilters" class="task-secondary-button" type="button" @click="resetFilters">Limpar filtros</button>
        <button v-if="query || appliedQuery" class="task-secondary-button" type="button" @click="resetSearch">Limpar busca e filtros</button>
      </div>
      <p v-if="dirtySearch" class="reading-filter-pending" role="status">Há alterações de busca ou filtros pendentes. Pressione “Pesquisar” para aplicá-las.</p>
    </form>

    <section class="reading-results" aria-labelledby="reading-results-title" :aria-busy="loading">
      <div class="reading-results-heading">
        <div><h2 id="reading-results-title" tabindex="-1">{{ state.archived ? 'Conteúdos arquivados' : 'Listagem' }}</h2>
          <p v-if="loaded && !error" role="status" aria-live="polite">{{ state.total ? `${firstResult}–${lastResult} de ${state.total}` : 'Nenhum conteúdo' }}</p></div>
        <button class="task-secondary-button" type="button" :disabled="loading" @click="loadPage(state.page)">Atualizar</button>
      </div>
      <p v-if="loading && !loaded" class="reading-state-message" role="status">Carregando biblioteca…</p>
      <div v-if="error" class="reading-error" role="alert"><p>{{ error }}</p><p v-if="rows.length">Os resultados anteriores continuam visíveis.</p><button class="task-secondary-button" type="button" :disabled="loading" @click="loadPage(state.page)">Tentar novamente</button></div>
      <p v-else-if="loading && loaded" class="reading-state-message" role="status">Atualizando os resultados…</p>
      <div v-else-if="loaded && !state.total && !appliedQuery && !hasFilters" class="reading-empty"><strong>{{ state.archived ? 'Você ainda não tem conteúdos arquivados.' : 'Sua biblioteca está vazia.' }}</strong><p>Adicione um livro, artigo ou vídeo para guardar sua referência e onde parou.</p><button v-if="!state.archived" class="task-primary-button" type="button" @click="openDialog('create', null, $event)">Adicionar primeiro conteúdo</button></div>
      <div v-else-if="loaded && !state.total" class="reading-empty"><strong>Nenhum conteúdo encontrado.</strong><p>Experimente outra busca ou remova os filtros.</p><button class="task-secondary-button" type="button" @click="resetSearch">Limpar busca e filtros</button></div>
      <section v-for="group in statusRows" :key="group.status" class="reading-status-row" :aria-labelledby="`reading-status-${group.status}`">
        <h3 :id="`reading-status-${group.status}`" class="reading-status-row-heading">{{ group.label }}</h3>
        <ul class="reading-card-list">
          <li v-for="item in group.items" :key="item.id">
            <button class="reading-card" type="button" @click="openDialog('detail', item, $event)">
              <span class="reading-card-artwork">
                <img v-if="item.coverImageUrl" class="reading-card-cover" :src="`${item.coverImageUrl}?thumbnail=true`" alt="" loading="lazy" decoding="async">
                <span v-else class="reading-card-placeholder" aria-hidden="true">
                  <span class="reading-card-placeholder-mark">{{ item.type === 'VIDEO' ? '▶' : item.type === 'ARTICLE' ? '≡' : '▤' }}</span>
                  <span>{{ typeLabel(item.type) }}</span>
                </span>
                <span v-if="item.authors?.length" class="reading-card-authors">{{ item.authors.map((author) => author.name).join(' · ') }}</span>
              </span>
              <span class="reading-card-top"><span class="reading-type-pill">{{ typeLabel(item.type) }}</span><span class="reading-status-pill">{{ statusLabel(item.status) }}</span></span>
              <strong>{{ item.title }}</strong>
              <span v-if="item.authorOrSource" class="reading-card-source">{{ item.authorOrSource }}</span>
              <span v-if="item.type === 'BOOK' && item.totalPages != null" class="reading-card-page-progress">
                <span class="reading-card-page-label">{{ item.currentPage }} de {{ item.totalPages }} páginas</span>
                <progress :value="item.currentPage" :max="item.totalPages" :aria-label="`${item.currentPage} de ${item.totalPages} páginas lidas`"></progress>
              </span>
              <span v-if="item.progressMarker && !hasPageProgress(item)" class="reading-card-progress">Onde parou: {{ item.progressMarker }}</span>
              <span v-if="item.tags.length" class="reading-tag-list"><span v-for="tag in item.tags" :key="tag">{{ tag }}</span></span>
              <span class="reading-card-open">Abrir registro ›</span>
            </button>
          </li>
        </ul>
      </section>
      <nav v-if="state.totalPages > 1" class="reading-pagination" aria-label="Paginação da biblioteca">
        <button class="task-secondary-button" type="button" :disabled="state.page === 0 || loading" @click="goToPage(state.page - 1)">Anterior</button>
        <span aria-live="polite">Página {{ state.page + 1 }} de {{ state.totalPages }}</span>
        <button class="task-secondary-button" type="button" :disabled="state.page + 1 >= state.totalPages || loading" @click="goToPage(state.page + 1)">Próxima</button>
      </nav>
    </section>

    <dialog ref="dialog" class="reading-dialog" aria-labelledby="reading-dialog-title" @cancel="onDialogCancel" @click.self="closeDialog">
      <header class="reading-dialog-header"><div><p class="settings-eyebrow">{{ mode === 'create' ? 'NOVO CONTEÚDO' : 'BIBLIOTECA DE LEITURA' }}</p><h2 id="reading-dialog-title" tabindex="-1">{{ dialogTitle }}</h2></div><button class="task-secondary-button" type="button" aria-label="Fechar" @click="closeDialog">Fechar</button></header>
      <p v-if="notice" class="task-backup-status" role="status">{{ notice }}</p>
      <div v-if="mode === 'detail' && activeItem" class="reading-detail">
        <img v-if="activeItem.coverImageUrl" class="reading-detail-cover" :src="activeItem.coverImageUrl" :alt="`Capa de ${activeItem.title}`" decoding="async">
        <section class="reading-cover-controls" aria-label="Imagem de capa">
          <label class="task-field"><span>Imagem de capa · opcional</span><input type="file" accept="image/jpeg,image/png" :disabled="saving" @change="uploadCover"><small>JPEG ou PNG · até 8 MiB. A imagem fica armazenada localmente no Hub.</small></label>
          <div class="reading-cover-link">
            <div class="task-field reading-cover-link-field">
              <label for="reading-cover-source-url">Link de imagem do Google Books</label>
              <div class="reading-cover-link-row">
                <input id="reading-cover-source-url" v-model="form.coverSourceUrl" type="url" maxlength="2048" placeholder="https://books.google.com/…" :disabled="saving">
                <button class="task-secondary-button" type="button" aria-label="Importar imagem de capa" title="Importar imagem de capa do Google Books" :disabled="saving || !form.coverSourceUrl.trim()" @click="importCoverLink">Importar</button>
              </div>
              <small>Importa a imagem para o Hub. Somente Google Books, googleusercontent.com ou gstatic.com.</small>
            </div>
          </div>
          <button v-if="activeItem.coverImageUrl" class="task-secondary-button" type="button" :disabled="saving" @click="deleteCover">Remover imagem de capa</button>
          <p v-if="saving" class="reading-state-message" role="status" aria-live="polite">Salvando alterações…</p>
        </section>
        <div class="reading-detail-meta"><span class="reading-type-pill">{{ typeLabel(activeItem.type) }}</span><span class="reading-status-pill">{{ statusLabel(activeItem.status) }}</span><strong v-for="author in activeItem.authors" :key="author.id" class="reading-author-chip">{{ author.name }}</strong><span v-if="activeItem.authorOrSource">{{ activeItem.authorOrSource }}</span></div>
        <div class="reading-status-actions" aria-label="Atualizar estado de leitura">
          <button v-for="status in ['TO_READ', 'IN_PROGRESS', 'COMPLETED']" :key="status" class="task-secondary-button" type="button" :aria-pressed="activeItem.status === status" :disabled="saving || activeItem.status === status" @click="setStatus(status)">{{ statusLabel(status) }}</button>
        </div>
        <ReadingPageProgress v-if="activeItem.type === 'BOOK' && activeItem.totalPages != null"
          :key="`${activeItem.id}-${activeItem.revision}`" :current-page="activeItem.currentPage" :total-pages="activeItem.totalPages"
          :disabled="saving" @save="saveCurrentPage" />
        <section v-if="activeItem.progressMarker && !hasPageProgress(activeItem)" class="reading-detail-block"><h3>Onde você parou</h3><p>{{ activeItem.progressMarker }}</p></section>
        <section class="reading-detail-block"><h3>Nota sobre o conteúdo</h3><p class="reading-detail-note">{{ activeItem.note || 'Nenhuma nota pessoal.' }}</p></section>
        <div v-if="activeItem.tags.length" class="reading-tag-list"><span v-for="tag in activeItem.tags" :key="tag">{{ tag }}</span></div>
        <p v-if="activeItem.url" class="reading-external-hint">Este link abre fora do Hub, em uma nova aba.</p>
        <p v-else class="reading-external-hint">Sem link externo · este registro também serve para livros físicos.</p>
        <div v-if="error" class="reading-error" role="alert"><p>{{ error }}</p><button v-if="error.includes('outra janela')" class="task-secondary-button" type="button" @click="reloadAfterConflict">Recarregar conteúdo e descartar alterações pendentes</button></div>
        <footer class="reading-dialog-actions">
          <button class="task-secondary-button" type="button" @click="openDialog('edit', activeItem)">Editar conteúdo</button>
          <a v-if="activeItem.url" class="task-secondary-button reading-open-link" :href="activeItem.url" target="_blank" rel="noopener noreferrer">Abrir link externo ↗</a>
          <button class="task-secondary-button" type="button" :disabled="saving" @click="toggleArchive">{{ activeItem.archived ? 'Restaurar da biblioteca' : 'Arquivar conteúdo' }}</button>
        </footer>
      </div>
      <form v-else class="reading-form" @submit.prevent="saveItem">
        <label class="task-field"><span>Título</span><input ref="titleInput" v-model="form.title" type="text" maxlength="1024" required></label>
        <div class="reading-form-grid">
          <label class="task-field"><span>Tipo</span><select v-model="form.type" required @change="onTypeChanged"><option value="BOOK">Livro</option><option value="ARTICLE">Artigo</option><option value="VIDEO">Vídeo</option></select></label>
          <label class="task-field"><span>Estado</span><select v-model="form.status"><option value="TO_READ">A ler</option><option value="IN_PROGRESS">Em andamento</option><option value="COMPLETED">Concluído</option></select></label>
        </div>
        <label v-if="form.type === 'BOOK'" class="task-field"><span>Total de páginas · opcional</span><input v-model.number="form.totalPages" type="number" min="1" max="100000" step="1" @change="onTotalPagesChanged"><small>Defina o total para acompanhar a página atual. O Hub não muda o estado de leitura automaticamente.</small></label>
        <p v-if="form.type === 'BOOK' && form.totalPages !== '' && form.currentPage > form.totalPages" class="reading-form-error" role="alert">A página atual não pode ser maior que o total. Reduza primeiro a página atual no detalhe.</p>
        <div class="reading-form-grid">
          <label class="task-field"><span>Autor ou origem · opcional</span><input v-model="form.authorOrSource" type="text" maxlength="512"></label>
          <label class="task-field"><span>Etiquetas · separadas por vírgula</span><input v-model="form.tagsText" type="text" maxlength="1600" placeholder="Trabalho, Pessoal"></label>
        </div>
        <section v-if="form.type === 'BOOK'" class="reading-author-picker" aria-labelledby="reading-author-heading">
          <div class="reading-author-picker-heading"><div><h3 id="reading-author-heading">Autores do livro</h3><p>Escolha um ou mais autores do catálogo.</p></div><button class="task-secondary-button" type="button" @click="openAuthorDialog">+ Cadastrar autor</button></div>
          <div v-if="authors.length" class="reading-author-options"><label v-for="author in authors" :key="author.id"><input v-model="form.authorIds" type="checkbox" :value="author.id"><span>{{ author.name }}</span></label></div>
          <p v-else class="reading-author-empty">Nenhum autor cadastrado ainda.</p>
        </section>
        <label class="task-field"><span>Link de referência · opcional</span><input v-model="form.url" type="url" maxlength="2048" placeholder="https://…"><small>O Hub não acessa o link ao salvar. Um livro físico não precisa de link.</small></label>
        <label v-if="!hasPageProgress(form)" class="task-field"><span>Marcador de progresso · opcional</span><input v-model="form.progressMarker" type="text" maxlength="512" placeholder="Capítulo 3, página 72 ou 12:30"></label>
        <label class="task-field"><span>Nota pessoal · opcional</span><textarea v-model="form.note" maxlength="2000" rows="5"></textarea></label>
        <p v-if="error" class="reading-error" role="alert">{{ error }}</p>
        <footer class="reading-dialog-actions"><button v-if="error.includes('outra janela')" class="task-secondary-button" type="button" :disabled="saving" @click="reloadAfterConflict">Descartar edição e recarregar</button><button class="task-secondary-button" type="button" :disabled="saving" @click="cancelForm">Cancelar</button><button class="task-primary-button" type="submit" :disabled="saving || (form.type === 'BOOK' && form.totalPages !== '' && form.currentPage > form.totalPages)">{{ saving ? 'Salvando…' : mode === 'create' ? 'Adicionar à biblioteca' : 'Salvar alterações' }}</button></footer>
      </form>
    </dialog>
    <dialog ref="authorDialog" class="reading-dialog reading-author-dialog" aria-labelledby="reading-author-dialog-title">
      <form class="reading-form" @submit.prevent="saveAuthor"><header class="reading-dialog-header"><div><p class="settings-eyebrow">CATÁLOGO DA BIBLIOTECA</p><h2 id="reading-author-dialog-title">Cadastrar autor</h2></div><button class="task-secondary-button" type="button" :disabled="authorSaving" @click="authorDialog?.close()">Fechar</button></header>
        <label class="task-field"><span>Nome do autor</span><input ref="authorNameInput" v-model="authorName" type="text" maxlength="160" required placeholder="Nome completo"></label>
        <p v-if="authorError" class="reading-error" role="alert">{{ authorError }}</p>
        <footer class="reading-dialog-actions"><button class="task-secondary-button" type="button" :disabled="authorSaving" @click="authorDialog?.close()">Cancelar</button><button class="task-primary-button" type="submit" :disabled="authorSaving">{{ authorSaving ? 'Salvando…' : 'Salvar autor' }}</button></footer>
      </form>
    </dialog>
  </section>
</template>
