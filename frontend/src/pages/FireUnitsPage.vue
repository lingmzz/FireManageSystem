<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { params, roleByUnit, unitKinds, unitOptions } from '../fire'
import ModalDialog from '../components/ModalDialog.vue'

const section = ref('units')
const units = ref([])
const users = ref([])
const keyword = ref('')
const activeUser = ref(null)
const binding = ref(null)
const form = ref(null)
const error = ref('')
const saved = ref('')
const busy = ref(false)
const saving = ref(false)
const labeled = computed(() => unitOptions(units.value))
const candidateParents = computed(() => {
  const kind = form.value?.kind === 'STATION' ? 'DEPARTMENT' : 'SEGMENT'
  return labeled.value.filter(unit => unit.kind === kind)
})
const eligible = computed(() => labeled.value
  .filter(unit => binding.value?.roles.includes(roleByUnit[unit.kind])))

onMounted(async () => {
  try { units.value = await api('/api/fire/units') }
  catch (failure) { error.value = failure.message }
})

watch(section, value => {
  error.value = ''
  saved.value = ''
  if (value === 'bindings' && !users.value.length) loadUsers()
})

async function loadUsers() {
  busy.value = true
  error.value = ''
  try { users.value = await api('/api/fire/units/users?' + params({ keyword: keyword.value })) }
  catch (failure) { error.value = failure.message }
  finally { busy.value = false }
}

async function chooseUser(user) {
  activeUser.value = user
  binding.value = null
  saved.value = ''
  error.value = ''
  try {
    const result = await api('/api/fire/units/users/' + user.id)
    if (activeUser.value?.id === user.id) binding.value = result
  } catch (failure) { error.value = failure.message }
}

function edit(unit = null) {
  form.value = unit ? { ...unit } : { code: '', name: '', kind: 'DEPARTMENT', parentId: null }
  if (!unit && candidateParents.value.length === 1) form.value.parentId = candidateParents.value[0].id
}

function changeKind(kind) {
  form.value.kind = kind
  form.value.parentId = candidateParents.value.length === 1 && form.value.kind !== 'SEGMENT'
    ? candidateParents.value[0].id : null
}

async function saveUnit(report) {
  saving.value = true
  try {
    const { id, code, name, kind, parentId } = form.value
    await api('/api/fire/units' + (id ? '/' + id : ''), id ? 'PUT' : 'POST',
      { code, name, kind, parentId: kind === 'SEGMENT' ? null : parentId })
    form.value = null
    units.value = await api('/api/fire/units')
  } catch (failure) { report(failure) }
  finally { saving.value = false }
}

async function saveBinding(report) {
  saving.value = true
  saved.value = ''
  error.value = ''
  try {
    await api('/api/fire/units/users/' + activeUser.value.id, 'PUT', { unitIds: binding.value.unitIds })
    saved.value = '用户的消防管理范围已保存。'
  } catch (failure) { report(failure) }
  finally { saving.value = false }
}
</script>

<template>
  <div class="page-heading">
    <h1>消防单位与授权</h1>
    <UiButton v-if="section === 'units'"  @click="edit()">新增单位</UiButton>
  </div>
  <UiCard class="panel">
    <UiCardContent class="table-panel-content px-0">
      <UiTabs v-model="section" class="page-tabs">
        <UiTabsList aria-label="消防单位与授权" variant="line">
          <UiTabsTrigger value="units">单位台账</UiTabsTrigger>
          <UiTabsTrigger value="bindings">用户范围</UiTabsTrigger>
        </UiTabsList>
      </UiTabs>
      <UiAlert v-if="error" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ error }}</UiAlertDescription></UiAlert>
      <div v-if="section === 'units'" class="table-wrap">
        <UiTable>
          <UiTableHeader><UiTableRow><UiTableHead>所属单位</UiTableHead><UiTableHead>编号</UiTableHead><UiTableHead>层级</UiTableHead><UiTableHead>操作</UiTableHead></UiTableRow></UiTableHeader>
          <UiTableBody>
            <UiTableRow v-for="unit in labeled" :key="unit.id">
              <UiTableCell class="strong">{{ unit.label }}</UiTableCell><UiTableCell class="mono">{{ unit.code }}</UiTableCell>
              <UiTableCell>{{ unitKinds[unit.kind] }}</UiTableCell><UiTableCell><UiButton @click="edit(unit)" variant="ghost" size="sm">编辑</UiButton></UiTableCell>
            </UiTableRow>
            <UiTableRow v-if="!labeled.length"><UiTableCell colspan="4" class="empty-cell">
              <UiEmpty class="empty-state"><UiEmptyHeader><UiEmptyTitle>暂无消防单位</UiEmptyTitle></UiEmptyHeader></UiEmpty>
            </UiTableCell></UiTableRow>
          </UiTableBody>
        </UiTable>
      </div>
      <div v-else class="fire-bindings">
        <div>
          <form class="toolbar" @submit.prevent="loadUsers">
            <UiInput v-model.trim="keyword" placeholder="搜索用户" aria-label="搜索用户" maxlength="80" />
            <UiButton :disabled="busy" variant="outline">搜索</UiButton>
          </form>
          <ul class="fire-picker">
            <li v-for="user in users" :key="user.id">
              <UiButton :variant="activeUser?.id === user.id ? 'secondary' : 'ghost'"
                      :aria-pressed="activeUser?.id === user.id" @click="chooseUser(user)">
                {{ user.displayName }}<span class="subtle mono">{{ user.username }}</span>
              </UiButton>
            </li>
          </ul>
        </div>
        <div class="fire-binding-form">
          <UiValidatedForm v-if="binding" @submit="saveBinding">
            <h3>{{ activeUser.displayName }}的消防管理范围</h3>
            <p v-if="!eligible.length">请先给该用户分配消防管理员角色，再选择授权单位。</p>
            <UiFormField v-else name="unitIds" label="授权单位" group v-slot="{ clear }">
              <div class="choices">
                <UiFieldLabel v-for="unit in eligible" :key="unit.id" class="option">
                  <UiCheckbox v-model="binding.unitIds" :value="unit.id" @update:model-value="clear" />{{ unit.label }}
                </UiFieldLabel>
              </div>
            </UiFormField>
            <UiAlert v-if="saved" class="page-feedback" role="status"><UiAlertDescription>{{ saved }}</UiAlertDescription></UiAlert>
            <div class="form-actions"><UiButton :disabled="saving">保存范围</UiButton></div>
          </UiValidatedForm>
          <p v-else class="subtle">选择用户后可以编辑其消防管理范围。</p>
        </div>
      </div>
    </UiCardContent>
  </UiCard>
  <ModalDialog v-if="form" :title="form.id ? '编辑消防单位' : '新增消防单位'" wide :busy="saving" @close="form = null">
    <UiValidatedForm @submit="saveUnit">
      <UiFieldGroup layout="grid">
        <UiFormField name="name" label="单位名称" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.name" maxlength="100" />
        </UiFormField>
        <UiFormField name="code" label="单位编号" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.code" maxlength="40" />
        </UiFormField>
        <UiFormField name="kind" label="单位层级" required v-slot="{ input }">
          <UiNativeSelect v-bind="input" :model-value="form.kind" :disabled="!!form.id" @update:model-value="changeKind">
            <UiNativeSelectOption v-for="(label, key) in unitKinds" :key="key" :value="key">{{ label }}</UiNativeSelectOption>
          </UiNativeSelect>
        </UiFormField>
        <UiFormField v-if="form.kind !== 'SEGMENT'" name="parentId" label="上级单位" required v-slot="{ input }">
          <UiNativeSelect v-bind="input" v-model="form.parentId">
            <UiNativeSelectOption :value="null" disabled>请选择上级单位</UiNativeSelectOption>
            <UiNativeSelectOption v-for="unit in candidateParents" :key="unit.id" :value="unit.id">{{ unit.label }}</UiNativeSelectOption>
          </UiNativeSelect>
        </UiFormField>
      </UiFieldGroup>
      <div class="form-actions">
        <UiButton type="button" variant="outline" :disabled="saving" @click="form = null">取消</UiButton>
        <UiButton type="submit" :disabled="saving">保存</UiButton>
      </div>
    </UiValidatedForm>
  </ModalDialog>
</template>
