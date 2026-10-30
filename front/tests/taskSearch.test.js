import test from 'node:test'
import assert from 'node:assert/strict'
import { createSearchFilters, filterTasks, invalidDateRange, normalizeSearchText } from '../src/utils/taskSearch.js'

const tasks = [
  { id: '1', title: 'Reunião do projeto', category: 'Trabalho', notes: 'Trazer orçamento', completed: false, dueDate: '2026-11-03', dueTime: '09:00', priority: 'HIGH' },
  { id: '2', title: 'Comprar pão', category: 'Casa', notes: 'Reunião familiar', completed: true, dueDate: '2026-11-04', dueTime: null, priority: 'NORMAL' },
  { id: '3', title: 'Planejar', category: 'Trabalho', notes: '', completed: false, dueDate: null, dueTime: null, priority: 'LOW' },
  { id: '4', title: 'Reunião do projeto', category: 'Trabalho', notes: '', completed: false, dueDate: '2026-11-03', dueTime: '09:00', priority: 'HIGH' },
  { id: '5', title: 'Ler', category: 'Trabalho', notes: '', completed: false, dueDate: '2026-11-03', dueTime: null, priority: 'NORMAL' },
]

test('normaliza acentos e caixa sem remover espaços', () => {
  assert.equal(normalizeSearchText('REUNIÃO Ágil'), 'reuniao agil')
})

test('busca título, categoria ou notas separadamente', () => {
  const filters = createSearchFilters()
  assert.deepEqual(filterTasks(tasks, 'REUNIAO', filters).map((task) => task.id), ['1', '4', '2'])
  assert.deepEqual(filterTasks(tasks, 'orçamento', filters).map((task) => task.id), ['1'])
  assert.deepEqual(filterTasks(tasks, 'casa', filters).map((task) => task.id), ['2'])
  assert.deepEqual(filterTasks(tasks, 'projeto trabalho', filters), [])
})

test('combina texto, estado, categoria, prioridade e intervalo inclusivo', () => {
  const filters = { ...createSearchFilters(), status: 'pending', category: 'Trabalho', priority: 'HIGH', period: 'range', startDate: '2026-11-03', endDate: '2026-11-03' }
  assert.deepEqual(filterTasks(tasks, 'reuniao', filters).map((task) => task.id), ['1', '4'])
  filters.startDate = '2026-11-04'
  assert.equal(invalidDateRange(filters), true)
  assert.deepEqual(filterTasks(tasks, '', filters), [])
})

test('distingue apenas sem data e permite limites abertos do intervalo', () => {
  const filters = { ...createSearchFilters(), period: 'undated' }
  assert.deepEqual(filterTasks(tasks, '', filters).map((task) => task.id), ['3'])
  filters.period = 'range'
  filters.startDate = '2026-11-04'
  assert.deepEqual(filterTasks(tasks, '', filters).map((task) => task.id), ['2'])
})

test('ordena pendentes, datas, horários, títulos e identidade sem mutar o acervo', () => {
  const original = tasks.map((task) => task.id)
  assert.deepEqual(filterTasks(tasks, '', createSearchFilters()).map((task) => task.id), ['1', '4', '5', '3', '2'])
  assert.deepEqual(tasks.map((task) => task.id), original)
})
