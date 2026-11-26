<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'

const props = defineProps({ id: Number, version: Number, flow: Object })
const busy = defineModel('busy', { type: Boolean, default: false })
const emit = defineEmits(['saved'])
const action = ref('PASS')
const assigneeId = ref(null)
const keyword = ref('')
const assignees = ref([])
const error = ref('')
const remark = ref('')
const choosing = computed(() => action.value === 'PASS' && props.flow.needsAssignee)
const path = '/api/approval-flows/tasks/' + props.id

async function findAssignees() {
  error.value = ''
  try {
    assignees.value = await api(path + '/assignees?' + new URLSearchParams({ keyword: keyword.value }))
    if (!assignees.value.some(user => user.id === assigneeId.value)) assigneeId.value = null
  } catch (failure) {
    error.value = failure.message
  }
}

onMounted(() => {
  if (choosing.value) findAssignees()
})

async function save(report) {
  if (busy.value) return
  busy.value = true
  try {
    await api(path + '/actions', 'POST', {
      action: action.value, version: props.version, remark: remark.value,
      assigneeId: choosing.value ? assigneeId.value : null
    })
    emit('saved')
  } catch (failure) {
    report(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <UiValidatedForm class="action-box" @submit="save">
    <UiAlert v-if="error" role="alert" variant="destructive"><UiAlertDescription>{{ error }}</UiAlertDescription></UiAlert>
    <UiFieldGroup layout="grid">
      <UiFormField name="action" label="办理操作" :class="{ 'form-span': !choosing }" v-slot="{ input }">
        <UiNativeSelect v-bind="input" v-model="action" :disabled="busy">
          <UiNativeSelectOption value="PASS">{{ flow.passLabel }}</UiNativeSelectOption>
          <UiNativeSelectOption v-if="flow.canReturn" value="RETURN">退回</UiNativeSelectOption>
        </UiNativeSelect>
      </UiFormField>
      <UiFormField v-if="choosing" name="assigneeId" label="下一步办理人" required v-slot="{ input }">
        <UiNativeSelect v-bind="input" v-model="assigneeId" :disabled="busy">
          <UiNativeSelectOption :value="null" disabled>请选择办理人</UiNativeSelectOption>
          <UiNativeSelectOption v-for="user in assignees" :key="user.id" :value="user.id">
            {{ user.displayName }}（{{ user.username }}）
          </UiNativeSelectOption>
        </UiNativeSelect>
        <div class="inline-field">
          <UiInput v-model.trim="keyword" maxlength="80" placeholder="搜索办理人" aria-label="搜索办理人" />
          <UiButton type="button" variant="outline" :disabled="busy" @click="findAssignees">搜索</UiButton>
        </div>
      </UiFormField>
      <UiFormField name="remark" :label="action === 'RETURN' ? '退回原因' : '办理说明'" class="form-span"
                   :required="action === 'RETURN' || flow.remarkRequired" v-slot="{ input }">
        <UiTextarea v-bind="input" v-model.trim="remark" rows="3" maxlength="3000" :disabled="busy" />
      </UiFormField>
    </UiFieldGroup>
    <div class="form-actions"><UiButton type="submit" :disabled="busy">提交办理</UiButton></div>
  </UiValidatedForm>
</template>
