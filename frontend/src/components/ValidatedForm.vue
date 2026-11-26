<script setup>
import { nextTick, provide, ref } from 'vue'

const emit = defineEmits(['submit'])
const fields = new Map()
const errors = ref({})
const message = ref('')

function setError(name, value) {
  if (value) errors.value[name] = value
  else delete errors.value[name]
}

provide('validatedForm', { fields, errors, setError })

function showErrors(next) {
  errors.value = next
  nextTick(() => fields.get(Object.keys(next)[0])?.focus())
}

function report(failure) {
  const next = {}
  const other = []
  for (const [name, detail] of Object.entries(failure.fields ?? {})) {
    if (fields.has(name)) next[name] = detail
    else other.push(detail)
  }
  message.value = other.length ? other.join('。') : Object.keys(next).length ? '' : failure.message
  showErrors(next)
}

function submit() {
  message.value = ''
  const next = {}
  for (const [name, field] of fields) {
    const error = field.validate()
    if (error) next[name] = error
  }
  if (Object.keys(next).length) showErrors(next)
  else {
    errors.value = {}
    emit('submit', report)
  }
}
</script>

<template>
  <form novalidate @submit.prevent="submit">
    <UiFieldGroup layout="compact">
      <UiAlert v-if="message" variant="destructive" role="alert">
        <UiAlertDescription>{{ message }}</UiAlertDescription>
      </UiAlert>
      <slot />
    </UiFieldGroup>
  </form>
</template>
