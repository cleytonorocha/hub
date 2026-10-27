<script setup>
const props = defineProps({
  tasks: { type: Array, default: () => [] },
  compact: { type: Boolean, default: false },
  limit: { type: Number, default: 3 },
})

const emit = defineEmits(['open', 'context'])

function isContextMenuKey(event) {
  return (event.shiftKey && event.key === 'F10') || event.key === 'ContextMenu' || event.key === 'Menu'
}

function handleTaskContextKeydown(event, task) {
  if (!isContextMenuKey(event)) return
  event.preventDefault()
  event.stopPropagation()
  emit('context', event, task)
}

function stopGroupContextKeydown(event) {
  if (isContextMenuKey(event)) event.stopPropagation()
}
</script>

<template>
  <button
    v-if="compact && props.tasks.length"
    class="calendar-task-count"
    :style="{ '--task-color': props.tasks[0].color || '#A986FF' }"
    type="button"
    :aria-label="props.tasks.length + (props.tasks.length === 1 ? ' Task nesta data' : ' Tasks nesta data')"
    @click.stop="$emit('open', props.tasks[0], $event)"
    @contextmenu.stop
    @keydown="stopGroupContextKeydown"
  >
    <span class="calendar-task-count-dot" aria-hidden="true"></span>
    <span>{{ props.tasks.length }}</span>
  </button>
  <ul v-else-if="!compact && props.tasks.length" class="calendar-task-list">
    <li
      v-for="task in props.tasks.slice(0, props.limit)"
      :key="task.id"
    ><button class="calendar-task-chip" :class="{ 'calendar-task-chip-done': task.completed }" :style="{ '--task-color': task.color || '#A986FF' }" type="button" :aria-label="task.title + ', ' + (task.category || 'Geral') + (task.dueTime ? ', às ' + task.dueTime.slice(0, 5) : '') + (task.priority === 'HIGH' ? ', prioridade alta' : '') + (task.completed ? ', concluída' : ', pendente')" @click.stop="$emit('open', task, $event)" @contextmenu.prevent.stop="emit('context', $event, task)" @keydown="handleTaskContextKeydown($event, task)"><span class="calendar-task-chip-status" aria-hidden="true">{{ task.completed ? '✓' : '•' }}</span><time v-if="task.dueTime" class="calendar-task-chip-time">{{ task.dueTime.slice(0, 5) }}</time><span>{{ task.title }}</span><span v-if="task.priority === 'HIGH'" class="task-priority-mark" aria-hidden="true">!</span></button></li>
    <li v-if="props.tasks.length > props.limit" class="calendar-task-more">+{{ props.tasks.length - props.limit }}</li>
  </ul>
</template>
