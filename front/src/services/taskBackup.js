const MAX_FILE_BYTES = 100 * 1024 * 1024

async function responsePayload(response) {
  if (response.ok) return response
  let payload = {}
  try { payload = await response.json() } catch { /* Keep the generic message. */ }
  throw new Error(payload.detail || payload.message || 'Não foi possível concluir a operação.')
}

async function upload(path, file, token) {
  const isZip = file.name.toLowerCase().endsWith('.zip')
  const headers = { 'Content-Type': isZip ? 'application/zip' : 'application/json' }
  if (token) headers['X-Task-Import-Preview'] = token
  const response = await fetch(path, { method: 'POST', headers, body: file })
  return responsePayload(response)
}

export async function exportTaskCopy() {
  const response = await responsePayload(await fetch('/api/tasks/portable-copy/export'))
  const blob = await response.blob()
  const zipped = response.headers.get('Content-Type')?.includes('application/zip')
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = zipped ? 'hub-portable-copy.zip' : 'hub-portable-copy.json'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  return blob.size
}

export function previewTaskCopy(file) {
  return upload('/api/tasks/portable-copy/preview', file).then((response) => response.json())
}

export function importTaskCopy(file, token) {
  return upload('/api/tasks/portable-copy/import', file, token).then((response) => response.json())
}

export { MAX_FILE_BYTES }
