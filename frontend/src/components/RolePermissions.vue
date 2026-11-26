<script setup>
import { computed, ref } from 'vue'
import { CheckIcon, ChevronRightIcon } from '@lucide/vue'
import { Checkbox } from '@/components/ui/checkbox'

const selected = defineModel({ type: Array, required: true })
const props = defineProps({ groups: { type: Array, required: true }, readOnly: Boolean })
const current = ref('')
const granted = computed(() => new Set(selected.value))

function count(group) {
  return group.permissions.filter(option => granted.value.has(option.code)).length
}

const menus = computed(() => props.readOnly ? props.groups.filter(group => count(group)) : props.groups)
const active = computed(() => menus.value.find(group => group.key === current.value)
  ?? menus.value.find(group => count(group)) ?? menus.value[0])
const options = computed(() => props.readOnly
  ? active.value?.permissions.filter(option => granted.value.has(option.code)) ?? []
  : active.value?.permissions ?? [])
const grantable = computed(() => options.value.filter(option => option.grantable))
const allChecked = computed(() => grantable.value.length > 0 && grantable.value.every(option => granted.value.has(option.code)))
const someChecked = computed(() => grantable.value.some(option => granted.value.has(option.code)))

function toggleAll(checked) {
  const next = new Set(selected.value)
  for (const option of grantable.value) {
    if (checked === true) next.add(option.code)
    else next.delete(option.code)
  }
  selected.value = [...next]
}
</script>

<template>
  <div v-if="active" class="permission-picker">
    <section class="permission-menus" aria-label="权限菜单">
      <button v-for="group in menus" :key="group.key" class="permission-menu"
              type="button" :aria-pressed="active.key === group.key" @click="current = group.key">
        <span class="min-w-0">
          <span v-if="group.menuPath.length > 1" class="block truncate text-xs text-muted-foreground">
            {{ group.menuPath.slice(0, -1).join(' / ') }}
          </span>
          <span class="block truncate font-medium">{{ group.menuPath.at(-1) }}</span>
        </span>
        <span class="ml-auto shrink-0 text-xs tabular-nums text-muted-foreground">
          {{ readOnly ? count(group) + ' 项' : count(group) + ' / ' + group.permissions.length }}
        </span>
        <ChevronRightIcon class="size-4 shrink-0" aria-hidden="true" />
      </button>
    </section>
    <section class="permission-actions" :aria-label="active.menuPath.at(-1) + '操作权限'">
      <div class="permission-actions-heading">
        <h3 class="text-sm font-semibold">{{ active.menuPath.at(-1) }}</h3>
        <UiFieldLabel v-if="!readOnly && grantable.length > 1" class="option">
          <Checkbox :model-value="allChecked ? true : someChecked ? 'indeterminate' : false"
                    @update:model-value="toggleAll" />
          全选
        </UiFieldLabel>
      </div>
      <ul class="permission-options">
        <li v-for="option in options" :key="option.code">
          <div v-if="readOnly" class="flex items-start gap-2 text-sm">
            <CheckIcon class="mt-0.5 size-4 shrink-0 text-success" aria-hidden="true" />{{ option.label }}
          </div>
          <UiFieldLabel v-else class="option">
            <UiCheckbox v-model="selected" :value="option.code" :disabled="!option.grantable" />{{ option.label }}
          </UiFieldLabel>
        </li>
      </ul>
    </section>
  </div>
  <p v-else class="rounded-md border bg-muted/35 p-4 text-sm text-muted-foreground">暂无操作权限</p>
</template>
