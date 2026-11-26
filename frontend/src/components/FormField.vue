<script setup>
import { computed, inject, onMounted, onUnmounted, ref, useId } from 'vue'
import { Field, FieldError, FieldLabel, FieldLegend, FieldSet } from '@/components/ui/field'

const props = defineProps({
  name: { type: String, required: true },
  label: { type: String, required: true },
  required: Boolean,
  group: Boolean,
  check: Function,
  patternMessage: String
})

const id = useId()
const field = ref(null)
const form = inject('validatedForm')
const error = computed(() => form.errors.value[props.name])
const input = computed(() => ({
  id,
  name: props.name,
  required: props.required || undefined,
  'aria-invalid': error.value ? 'true' : undefined,
  'aria-describedby': error.value ? id + '-error' : undefined
}))

function setError(message) {
  form.setError(props.name, message)
}

function clear() {
  setError('')
}

function control() {
  return document.getElementById(id)
}

function validate() {
  const element = control()
  if (element?.willValidate) {
    const { validity } = element
    if (validity.customError) return element.validationMessage
    if (validity.badInput) return '请填写有效的' + props.label
    if (validity.valueMissing || (element.required && !element.value.trim())) {
      const choose = element.matches('select, [type=date], [type=datetime-local], [type=file]')
      return (choose ? '请选择' : '请填写') + props.label
    }
    if (validity.typeMismatch) return '请填写有效的' + props.label
    if (validity.patternMismatch) return props.patternMessage || '请检查' + props.label + '的格式'
    if (validity.tooShort) return '至少填写 ' + element.minLength + ' 个字符'
    if (validity.tooLong) return '最多填写 ' + element.maxLength + ' 个字符'
    if (validity.rangeUnderflow) return '不能小于 ' + element.min
    if (validity.rangeOverflow) return '不能大于 ' + element.max
    if (validity.stepMismatch) return '请填写有效的' + props.label
  }
  return props.check?.() || ''
}

onMounted(() => {
  form.fields.set(props.name, {
    validate,
    focus: () => {
      const element = control()
      const root = field.value?.$el
      const target = element && !element.disabled ? element
        : root?.querySelector('button:not(:disabled), input:not(:disabled), select:not(:disabled)') ?? root
      target?.focus()
    }
  })
})
onUnmounted(() => {
  form.fields.delete(props.name)
  clear()
})
</script>

<template>
  <component :is="group ? FieldSet : Field" ref="field" class="min-w-0 gap-1.5"
             :tabindex="group ? -1 : undefined"
             :data-invalid="Boolean(error) || undefined"
             :aria-describedby="group ? input['aria-describedby'] : undefined"
             @input="clear">
    <FieldLegend v-if="group" variant="label">
      {{ label }} <span v-if="required" class="text-destructive" aria-hidden="true">*</span>
    </FieldLegend>
    <FieldLabel v-else :for="id">
      {{ label }} <span v-if="required" class="text-destructive" aria-hidden="true">*</span>
    </FieldLabel>
    <slot :input="input" :clear="clear" :set-error="setError" />
    <FieldError v-if="error" :id="id + '-error'" :errors="[error]" class="text-xs leading-relaxed" />
  </component>
</template>
