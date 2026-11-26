<script setup>
import { Input } from '@/components/ui/input'

const value = defineModel({ type: String, default: '' })
defineProps({ withTime: Boolean })

function openPicker(event) {
  const input = event.currentTarget
  if (!input.showPicker || input.readOnly) return
  try {
    input.showPicker()
    event.preventDefault()
  } catch {
    // The browser can still open the picker through its native keyboard or icon.
  }
}
</script>

<template>
  <Input v-model="value" :type="withTime ? 'datetime-local' : 'date'"
         class="date-input" @click="openPicker" />
</template>
