export function getDateKey(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return year + '-' + month + '-' + day
}

export function getCurrentWeek(now = new Date()) {
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  start.setDate(start.getDate() - ((start.getDay() + 6) % 7))
  const days = Array.from({ length: 7 }, (_, index) => {
    const date = new Date(start)
    date.setDate(start.getDate() + index)
    return date
  })
  const end = new Date(start)
  end.setDate(start.getDate() + 7)
  return { start, end, days }
}

export function formatDateLabel(date) {
  return new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'short' }).format(date).replace('.', '')
}

export function formatWeekLabel(start, end) {
  const startMonth = new Intl.DateTimeFormat('pt-BR', { month: 'short' }).format(start).replace('.', '')
  const endMonth = new Intl.DateTimeFormat('pt-BR', { month: 'short' }).format(end).replace('.', '')
  const startYear = start.getFullYear()
  const endYear = end.getFullYear()
  if (startMonth === endMonth && startYear === endYear) {
    return start.getDate() + ' — ' + end.getDate() + ' ' + endMonth + ' ' + endYear
  }
  return start.getDate() + ' ' + startMonth + ' — ' + end.getDate() + ' ' + endMonth + ' ' + endYear
}
