async function request(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
  })
  if (!response.ok) {
    let payload = {}
    try { payload = await response.json() } catch { /* Keep the generic message. */ }
    throw new Error(payload.detail || payload.message || 'Não foi possível carregar a biblioteca de leitura.')
  }
  return response.json()
}

export function listReadingItems(criteria = {}) {
  const params = new URLSearchParams()
  params.set('q', criteria.query || '')
  params.set('archived', String(Boolean(criteria.archived)))
  params.set('page', String(criteria.page || 0))
  params.set('size', String(criteria.size || 20))
  if (criteria.status) params.set('status', criteria.status)
  if (criteria.type) params.set('type', criteria.type)
  if (criteria.authorId) params.set('authorId', criteria.authorId)
  return request(`/api/reading-items?${params.toString()}`)
}

export function createReadingItem(item) {
  return request('/api/reading-items', {
    method: 'POST',
    body: JSON.stringify({
      title: item.title,
      type: item.type,
      authorOrSource: item.authorOrSource,
      url: item.url,
      status: item.status,
      progressMarker: item.progressMarker,
      totalPages: item.totalPages,
      currentPage: item.currentPage,
      note: item.note,
      tags: item.tags,
      authorIds: item.authorIds || item.authors?.map((author) => author.id) || [],
    }),
  })
}

export function getReadingItem(id) {
  return request(`/api/reading-items/${encodeURIComponent(id)}`)
}

export function updateReadingItem(item) {
  return request(`/api/reading-items/${encodeURIComponent(item.id)}`, {
    method: 'PUT',
    body: JSON.stringify({
      title: item.title,
      type: item.type,
      authorOrSource: item.authorOrSource,
      url: item.url,
      status: item.status,
      progressMarker: item.progressMarker,
      totalPages: item.totalPages,
      currentPage: item.currentPage,
      pageProgressProvided: true,
      note: item.note,
      tags: item.tags,
      archived: item.archived,
      revision: item.revision,
      authorIds: item.authorIds || item.authors?.map((author) => author.id) || [],
    }),
  })
}

export function uploadReadingCover(id, revision, file) {
  const body = new FormData()
  body.append('file', file)
  return fetch(`/api/reading-items/${encodeURIComponent(id)}/cover?revision=${encodeURIComponent(revision)}`, {
    method: 'PUT',
    body,
  }).then(parseResponse)
}

export function removeReadingCover(id, revision) {
  return request(`/api/reading-items/${encodeURIComponent(id)}/cover?revision=${encodeURIComponent(revision)}`, {
    method: 'DELETE',
  })
}

export function listReadingAuthors() { return request('/api/reading-items/authors') }

export function createReadingAuthor(name) {
  return request('/api/reading-items/authors', { method: 'POST', body: JSON.stringify({ name }) })
}

export function importReadingCoverFromUrl(id, revision, url) {
  return request(`/api/reading-items/${encodeURIComponent(id)}/cover/import`, {
    method: 'POST', body: JSON.stringify({ revision, url }),
  })
}

async function parseResponse(response) {
  if (!response.ok) {
    let payload = {}
    try { payload = await response.json() } catch { /* Keep the generic message. */ }
    throw new Error(payload.detail || payload.message || 'Não foi possível atualizar a imagem de capa.')
  }
  return response.json()
}
