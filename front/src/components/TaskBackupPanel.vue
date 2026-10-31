<script setup>
import { computed, ref } from 'vue'
import { useTaskHub } from '../services/taskHub'
import { exportTaskCopy, importTaskCopy, MAX_FILE_BYTES, previewTaskCopy } from '../services/taskBackup'
import { hasPermission } from '../services/auth'

const { state, refreshTasks } = useTaskHub()
const exporting = ref(false)
const working = ref(false)
const fileInput = ref(null)
const selectedFile = ref(null)
const preview = ref(null)
const error = ref('')
const status = ref('')
const localTaskCount = computed(() => state.tasks.filter((task) => task.type === 'LOCAL').length)
const newRecordCount = computed(() => (preview.value?.newTasks || 0) + (preview.value?.newReadingItems || 0) + (preview.value?.newAuthors || 0) + (preview.value?.newHabits || 0) + (preview.value?.newFocusSessions || 0) + (preview.value?.notes?.newNotes || 0))
const maxSelectedSize = computed(() => selectedFile.value?.name.toLowerCase().endsWith('.zip') ? MAX_FILE_BYTES : 5 * 1024 * 1024)
const exportOmissions = computed(() => [
  ['HABITS_READ', 'Hábitos e rotinas'],
  ['FOCUS_READ', 'Sessões de foco'],
  ['NOTES_READ', 'Notas e conhecimento'],
].filter(([permission]) => !hasPermission(permission)).map(([, label]) => label))
const previewOmissions = computed(() => (preview.value?.omittedSections || []).map((section) => ({
  habits: 'hábitos',
  focusSessions: 'sessões de foco',
  notes: 'notas',
}[section])).filter(Boolean))

function formatList(values) {
  if (values.length < 2) return values.join('')
  if (values.length === 2) return `${values[0]} e ${values[1]}`
  return `${values.slice(0, -1).join(', ')} e ${values.at(-1)}`
}

function selectFile(event) {
  selectedFile.value = event.target.files?.[0] || null
  preview.value = null
  error.value = ''
  status.value = ''
  if (selectedFile.value?.size > maxSelectedSize.value) {
    error.value = selectedFile.value.name.toLowerCase().endsWith('.zip')
      ? 'O pacote excede o limite de 100 MiB. Escolha uma cópia menor.'
      : 'O arquivo JSON excede o limite de 5 MiB. Escolha uma cópia menor.'
    selectedFile.value = null
    event.target.value = ''
  }
}

async function exportCopy() {
  exporting.value = true
  error.value = ''
  status.value = ''
  try {
    const bytes = await exportTaskCopy()
    status.value = exportOmissions.value.length
      ? `Cópia parcial pronta para salvar (${formatBytes(bytes)}). Não inclui ${formatList(exportOmissions.value)}, porque seu perfil não permite exportá-los. O Hub não consegue confirmar onde o navegador a salvou.`
      : `Arquivo pronto para salvar (${formatBytes(bytes)}). O Hub não consegue confirmar onde o navegador o salvou.`
  } catch (cause) {
    error.value = cause.message
  } finally {
    exporting.value = false
  }
}

async function reviewFile() {
  if (!selectedFile.value || working.value) return
  working.value = true
  error.value = ''
  status.value = ''
  preview.value = null
  try {
    preview.value = await previewTaskCopy(selectedFile.value)
  } catch (cause) {
    error.value = cause.message
  } finally {
    working.value = false
  }
}

async function confirmImport() {
  if (!selectedFile.value || !preview.value?.canImport || working.value) return
  working.value = true
  error.value = ''
  status.value = ''
  try {
    const result = await importTaskCopy(selectedFile.value, preview.value.token)
    if (result.status === 'REVIEW_REQUIRED') {
      preview.value = result.preview
      status.value = 'Os dados locais mudaram. Revise a prévia atualizada antes de confirmar.'
      return
    }
    const successParts = []
    if (result.addedTasks) successParts.push(`${result.addedTasks} ${result.addedTasks === 1 ? 'task adicionada' : 'tasks adicionadas'}`)
    if (result.addedReadingItems) successParts.push(`${result.addedReadingItems} ${result.addedReadingItems === 1 ? 'conteúdo de leitura adicionado' : 'conteúdos de leitura adicionados'}`)
    if (result.addedAuthors) successParts.push(`${result.addedAuthors} ${result.addedAuthors === 1 ? 'autor adicionado' : 'autores adicionados'}`)
    if (result.addedFocusSessions) successParts.push(`${result.addedFocusSessions} ${result.addedFocusSessions === 1 ? 'sessão de foco adicionada' : 'sessões de foco adicionadas'}`)
    if (result.addedNotes) successParts.push(`${result.addedNotes} ${result.addedNotes === 1 ? 'nota adicionada' : 'notas adicionadas'}`)
    if (result.identicalTasks) successParts.push(`${result.identicalTasks} ${result.identicalTasks === 1 ? 'task idêntica ignorada' : 'tasks idênticas ignoradas'}`)
    if (result.identicalReadingItems) successParts.push(`${result.identicalReadingItems} ${result.identicalReadingItems === 1 ? 'conteúdo idêntico ignorado' : 'conteúdos idênticos ignorados'}`)
    if (result.identicalFocusSessions) successParts.push(`${result.identicalFocusSessions} ${result.identicalFocusSessions === 1 ? 'sessão de foco idêntica ignorada' : 'sessões de foco idênticas ignoradas'}`)
    if (result.identicalNotes) successParts.push(`${result.identicalNotes} ${result.identicalNotes === 1 ? 'nota idêntica ignorada' : 'notas idênticas ignoradas'}`)
    let successMessage = successParts.length ? `${successParts.join('. ')}.` : 'A cópia não contém registros novos.'
    if (result.repeatedIdenticalIds) successMessage += ` ${result.repeatedIdenticalIds} duplicatas idênticas no arquivo foram consolidadas.`
    if (result.repeatedIdenticalReadingIds) successMessage += ` ${result.repeatedIdenticalReadingIds} conteúdos duplicados idênticos foram consolidados.`
    if (result.repeatedIdenticalNoteIds) successMessage += ` ${result.repeatedIdenticalNoteIds} notas duplicadas idênticas foram consolidadas.`
    if (result.addedCovers) successMessage += ` ${result.addedCovers} ${result.addedCovers === 1 ? 'imagem de capa foi adicionada' : 'imagens de capa foram adicionadas'}.`
    preview.value = null
    selectedFile.value = null
    if (fileInput.value) fileInput.value.value = ''
    status.value = successMessage
    window.dispatchEvent(new Event('hub-reading-items-changed'))
    window.dispatchEvent(new Event('hub:focus-sessions-changed'))
    window.dispatchEvent(new Event('hub-notes-changed'))
    try { await refreshTasks() }
    catch { status.value += ' A lista local não atualizou; tente atualizar as tarefas.' }
  } catch (cause) {
    preview.value = null
    error.value = cause.message
  } finally {
    working.value = false
  }
}

function clearReview() {
  preview.value = null
  error.value = ''
  status.value = ''
}

function formatBytes(bytes) {
  return new Intl.NumberFormat('pt-BR').format(bytes)
}

function formatGeneratedAt(value) {
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value))
}
</script>

<template>
  <section class="task-backup-panel" aria-labelledby="task-backup-title">
    <header class="settings-section-heading">
      <div><p class="settings-section-kicker">DADOS</p><h2 id="task-backup-title">Cópia portátil do Hub</h2></div>
        <p>Exporte ou adicione tarefas, Biblioteca, hábitos, sessões de foco e notas permitidos pelo seu perfil. Capas ficam no pacote ZIP; cópias antigas continuam aceitas.</p>
    </header>

    <div class="task-backup-card">
      <div class="task-backup-copy">
        <h3>Exportar cópia portátil</h3>
        <p>Inclui tasks locais, séries, checklists, biblioteca, hábitos, sessões de foco e notas se seu perfil tiver as permissões correspondentes. Sessões ativas são congeladas como pausadas na cópia; a sessão original continua. Não inclui preferências, credenciais, dados Google ou downloads.</p>
        <p class="settings-storage-note">{{ localTaskCount }} {{ localTaskCount === 1 ? 'task local' : 'tasks locais' }} · Limite: 5.000 tarefas, conteúdos, hábitos e notas; 50.000 sessões; manifesto de 90 MiB e pacote de 100 MiB.</p>
        <p v-if="exportOmissions.length" class="task-backup-partial-warning" role="status" aria-live="polite">Sua permissão não permite incluir: {{ formatList(exportOmissions) }}. A cópia será parcial.</p>
      </div>
      <button class="task-primary-button" type="button" :disabled="exporting || state.loading" @click="exportCopy">
        {{ exporting ? 'Preparando…' : 'Exportar cópia' }}
      </button>
    </div>

    <div class="task-backup-card task-backup-import">
      <div class="task-backup-copy">
        <h3>Adicionar dados de uma cópia</h3>
        <p>A importação não substitui registros existentes. Itens iguais são ignorados; qualquer conflito bloqueia o arquivo inteiro.</p>
      </div>
      <label class="task-field task-backup-file-field">
        <span>Arquivo JSON ou pacote ZIP do Hub</span>
        <input ref="fileInput" type="file" accept=".json,.zip,application/json,application/zip" :disabled="working" @change="selectFile">
        <small>JSON: até 5 MiB. ZIP com capas: até 100 MiB no total e 8 MiB por imagem. Revise o conteúdo antes de adicionar.</small>
      </label>
      <div class="task-backup-actions">
        <button class="task-secondary-button" type="button" :disabled="!selectedFile || working" @click="reviewFile">
          {{ working && !preview ? 'Validando…' : 'Validar e revisar' }}
        </button>
        <button v-if="preview" class="task-secondary-button" type="button" :disabled="working" @click="clearReview">Cancelar prévia</button>
      </div>

      <section v-if="preview" class="task-backup-preview" aria-live="polite" aria-labelledby="task-backup-preview-title">
        <h4 id="task-backup-preview-title">Revisão da cópia</h4>
        <p>Versão {{ preview.formatVersion }} · Gerada em {{ formatGeneratedAt(preview.generatedAt) }} · {{ preview.totalTasks }} tarefas · {{ preview.totalReadingItems }} conteúdos de leitura<span v-if="preview.formatVersion >= 8"> · {{ preview.totalHabits }} hábitos</span><span v-if="preview.formatVersion >= 9"> · {{ preview.totalFocusSessions }} sessões de foco</span><span v-if="preview.formatVersion >= 10"> · {{ preview.notes?.totalNotes || 0 }} notas</span></p>
        <p v-if="previewOmissions.length" class="task-backup-partial-warning" role="status" aria-live="polite">Cópia parcial: este arquivo não inclui {{ formatList(previewOmissions) }}. Importar continua aditivo; áreas omitidas não removem dados existentes.</p>
        <p v-if="preview.formatVersion >= 5">{{ preview.totalCovers }} capas no pacote · {{ preview.newCovers }} novas · {{ preview.identicalCovers }} já existentes e idênticas.</p>
        <p v-else>Esta cópia JSON antiga não contém imagens de capa; os registros importados ficarão sem capa.</p>
        <dl class="task-backup-counts">
          <div><dt>Novas</dt><dd>{{ preview.newTasks }}</dd></div>
          <div><dt>Já existentes</dt><dd>{{ preview.identicalTasks }}</dd></div>
          <div><dt>Conflitantes</dt><dd>{{ preview.conflictingTasks }}</dd></div>
        </dl>
        <dl v-if="preview.formatVersion >= 7" class="task-backup-counts">
          <div><dt>Autores no catálogo</dt><dd>{{ preview.totalAuthors }}</dd></div>
          <div><dt>Novos autores</dt><dd>{{ preview.newAuthors }}</dd></div>
          <div><dt>Conflitos de autor</dt><dd>{{ preview.conflictingAuthors }}</dd></div>
        </dl>
        <dl class="task-backup-counts">
          <div><dt>Novos conteúdos</dt><dd>{{ preview.newReadingItems }}</dd></div>
          <div><dt>Já existentes</dt><dd>{{ preview.identicalReadingItems }}</dd></div>
          <div><dt>Conflitantes</dt><dd>{{ preview.conflictingReadingItems }}</dd></div>
        </dl>
        <dl v-if="preview.formatVersion >= 8" class="task-backup-counts">
          <div><dt>Novos hábitos</dt><dd>{{ preview.newHabits }}</dd></div>
          <div><dt>Já existentes</dt><dd>{{ preview.identicalHabits }}</dd></div>
          <div><dt>Conflitantes</dt><dd>{{ preview.conflictingHabits }}</dd></div>
        </dl>
        <dl v-if="preview.formatVersion >= 9" class="task-backup-counts">
          <div><dt>Novas sessões</dt><dd>{{ preview.newFocusSessions }}</dd></div>
          <div><dt>Já existentes</dt><dd>{{ preview.identicalFocusSessions }}</dd></div>
          <div><dt>Conflitantes</dt><dd>{{ preview.conflictingFocusSessions }}</dd></div>
        </dl>
        <dl v-if="preview.formatVersion >= 10" class="task-backup-counts">
          <div><dt>Novas notas</dt><dd>{{ preview.notes?.newNotes || 0 }}</dd></div>
          <div><dt>Já existentes</dt><dd>{{ preview.notes?.identicalNotes || 0 }}</dd></div>
          <div><dt>Conflitantes</dt><dd>{{ preview.notes?.conflictingNotes || 0 }}</dd></div>
          <div v-if="preview.notes?.notesWithUnavailableTaskLinks"><dt>Vínculos indisponíveis</dt><dd>{{ preview.notes.notesWithUnavailableTaskLinks }}</dd></div>
        </dl>
        <p v-if="preview.notes?.limitExceeded" class="task-backup-conflicts task-form-error" role="alert">A cópia ultrapassaria o limite de 5.000 notas. Nenhum registro será importado.</p>
        <ul v-if="preview.readingSamples.length" class="task-backup-samples" aria-label="Amostra de conteúdos novos">
          <li v-for="sample in preview.readingSamples" :key="sample.id">{{ sample.title }} · {{ sample.type }}<span v-if="sample.archived"> · Arquivado</span></li>
        </ul>
        <p v-if="preview.repeatedIdenticalIds">{{ preview.repeatedIdenticalIds }} duplicatas idênticas dentro do arquivo serão consolidadas.</p>
        <ul v-if="preview.samples.length" class="task-backup-samples" aria-label="Amostra de tarefas novas">
          <li v-for="sample in preview.samples" :key="sample.id">{{ sample.title }}<span v-if="sample.dueDate"> · {{ sample.dueDate }}</span><span v-else> · Sem data</span></li>
        </ul>
        <div v-if="preview.conflictingTasks" class="task-backup-conflicts" role="alert">
          <p class="task-form-error">Há tarefas com o mesmo identificador e conteúdo diferente. Nenhuma tarefa será importada.</p>
          <ul class="task-backup-samples" aria-label="Tarefas conflitantes">
            <li v-for="conflict in preview.conflicts" :key="conflict.id">{{ conflict.title }} · {{ conflict.id }}</li>
          </ul>
          <p v-if="preview.conflictIds.length > preview.conflicts.length">Há mais conflitos além dos listados.</p>
        </div>
        <div v-if="preview.conflictingReadingItems" class="task-backup-conflicts" role="alert">
          <p class="task-form-error">Há conteúdos ou autores com identificadores ou nomes conflitantes. Nenhum registro será importado.</p>
          <ul class="task-backup-samples" aria-label="Conteúdos conflitantes">
            <li v-for="conflict in preview.readingConflicts" :key="conflict.id">{{ conflict.title }} · {{ conflict.type }} · {{ conflict.id }}</li>
          </ul>
          <p v-if="preview.readingConflictIds.length > preview.readingConflicts.length">Há mais conflitos além dos listados.</p>
        </div>
        <p v-if="preview.conflictingHabits" class="task-backup-conflicts task-form-error" role="alert">Há hábitos com o mesmo identificador e conteúdo diferente. Nenhum registro será importado.</p>
        <p v-if="preview.conflictingFocusSessions" class="task-backup-conflicts task-form-error" role="alert">Há sessões de foco com o mesmo identificador e dados diferentes. Nenhum registro será importado.</p>
        <p v-if="preview.notes?.conflictingNotes" class="task-backup-conflicts task-form-error" role="alert">Há notas com o mesmo identificador e conteúdo diferente. Nenhum registro será importado.</p>
        <p v-if="preview.notes?.limitExceeded" class="task-backup-conflicts task-form-error" role="alert">A importação ultrapassaria o limite de 5.000 notas. Remova notas antes de importar esta cópia.</p>
        <p v-if="preview.focusSlotConflict" class="task-backup-conflicts task-form-error" role="alert">Já existe uma sessão ativa ou pausada. Encerre-a ou retome-a antes de importar; nenhum dado será importado parcialmente.</p>
        <p v-if="!newRecordCount && !preview.conflictingTasks && !preview.conflictingReadingItems && !preview.conflictingHabits && !preview.conflictingFocusSessions && !preview.focusSlotConflict && !preview.notes?.conflictingNotes && !preview.notes?.limitExceeded" class="task-backup-note">Não há registros novos para adicionar.</p>
        <button v-if="preview.canImport" class="task-primary-button" type="button" :disabled="working" @click="confirmImport">
          {{ working ? 'Adicionando…' : `Adicionar ${newRecordCount} registros` }}
        </button>
      </section>
    </div>

    <p v-if="error" class="task-form-error" role="alert">{{ error }}</p>
    <p v-if="status" class="task-backup-status" role="status">{{ status }}</p>
  </section>
</template>
