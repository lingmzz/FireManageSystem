<script setup>
import { computed } from 'vue'
import { Checkbox } from '@/components/ui/checkbox'

const props = defineProps({
  modelValue: { type: [Boolean, Array], default: undefined },
  checked: Boolean,
  value: { type: [String, Number], default: undefined },
})
const emit = defineEmits(['update:modelValue', 'change'])

const selected = computed(() => Array.isArray(props.modelValue)
  ? props.modelValue.includes(props.value)
  : props.modelValue ?? props.checked)

function update(next) {
  const checked = next === true
  if (Array.isArray(props.modelValue)) {
    const remaining = props.modelValue.filter(value => value !== props.value)
    emit('update:modelValue', checked ? [...remaining, props.value] : remaining)
  } else {
    emit('update:modelValue', checked)
  }
  emit('change', checked)
}
</script>

<template>
  <Checkbox :model-value="selected" @update:model-value="update" />
</template>
