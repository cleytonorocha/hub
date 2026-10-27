import { getDateKey } from './calendar'

export function getNotificationWindow(now = new Date()) {
  const tomorrow = new Date(now)
  tomorrow.setDate(tomorrow.getDate() + 1)
  return { today: getDateKey(now), tomorrow: getDateKey(tomorrow) }
}

export function getPendingNotifications(tasks, now = new Date()) {
  const { tomorrow } = getNotificationWindow(now)
  return tasks.filter((task) => !task.completed && task.dueDate && task.dueDate <= tomorrow)
}
