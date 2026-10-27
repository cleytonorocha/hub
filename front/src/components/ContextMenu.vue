<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  x: { type: Number, default: 0 },
  y: { type: Number, default: 0 },
  items: { type: Array, default: () => [] },
  focusTarget: { type: Object, default: null },
  restoreFocus: { type: Boolean, default: false },
})
const emit = defineEmits(['select', 'close'])
const menu = ref(null)
const placement = ref({ left: 0, top: 0 })
const style = computed(() => ({ left: placement.value.left + 'px', top: placement.value.top + 'px' }))

function placeMenu() {
  const element = menu.value
  if (!element) return
  const bounds = element.getBoundingClientRect()
  placement.value = {
    left: Math.max(8, Math.min(props.x, window.innerWidth - bounds.width - 8)),
    top: Math.max(8, Math.min(props.y, window.innerHeight - bounds.height - 8)),
  }
}

function close(restore = false) {
  emit('close')
  if (restore && props.restoreFocus) nextTick(() => props.focusTarget?.focus?.())
}

function closeOnOutsidePointer(event) {
  if (!menu.value?.contains(event.target)) close()
}

function closeOnScroll() { close() }
function reposition() { nextTick(placeMenu) }

function handleKeydown(event) {
  const items = [...(menu.value?.querySelectorAll('[role="menuitem"]') || [])]
  const currentIndex = items.indexOf(document.activeElement)
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    close(true)
  } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    const step = event.key === 'ArrowDown' ? 1 : -1
    items[(currentIndex + step + items.length) % items.length]?.focus()
  } else if (event.key === 'Home') {
    event.preventDefault()
    items[0]?.focus()
  } else if (event.key === 'End') {
    event.preventDefault()
    items.at(-1)?.focus()
  } else if (event.key === 'Tab') {
    const exitsMenu = event.shiftKey ? currentIndex <= 0 : currentIndex === items.length - 1
    if (exitsMenu) window.setTimeout(() => close(), 0)
  }
}

watch(() => props.open, async (isOpen) => {
  if (!isOpen) return
  await nextTick()
  placeMenu()
  menu.value?.querySelector('[role="menuitem"]')?.focus()
  document.addEventListener('pointerdown', closeOnOutsidePointer, true)
  document.addEventListener('scroll', closeOnScroll, true)
  window.addEventListener('resize', reposition)
}, { flush: 'post' })

watch(() => [props.x, props.y], reposition)
watch(() => props.open, (isOpen, wasOpen) => {
  if (isOpen || !wasOpen) return
  document.removeEventListener('pointerdown', closeOnOutsidePointer, true)
  document.removeEventListener('scroll', closeOnScroll, true)
  window.removeEventListener('resize', reposition)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeOnOutsidePointer, true)
  document.removeEventListener('scroll', closeOnScroll, true)
  window.removeEventListener('resize', reposition)
})
</script>

<template>
  <Teleport to="body">
    <div v-if="open" ref="menu" class="app-context-menu" :style="style" role="menu" aria-label="Ações do contexto" @keydown="handleKeydown">
      <button v-for="item in items" :key="item.id" type="button" role="menuitem" tabindex="0" :class="{ 'context-menu-item-danger': item.danger }" :disabled="item.disabled" @click="emit('select', item.id)">{{ item.label }}</button>
    </div>
  </Teleport>
</template>
