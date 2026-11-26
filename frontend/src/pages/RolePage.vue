<script setup>
import { confirmDeletion } from '../confirmation'
import { onMounted, ref } from 'vue'
import { api, refreshSession } from '../api'
import ModalDialog from '../components/ModalDialog.vue'
import RolePermissions from '../components/RolePermissions.vue'

const roles = ref([])
const groups = ref([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const form = ref(null)
const details = ref(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [items, permissions] = await Promise.all([api('/api/roles'), api('/api/roles/permission-groups')])
    roles.value = items
    groups.value = permissions
  } catch (failure) {
    error.value = failure.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

function edit(role = null) {
  form.value = { id: role?.id, code: role?.code ?? '', name: role?.name ?? '', permissions: [...(role?.permissions ?? [])] }
}

async function save(report) {
  saving.value = true
  try {
    const { id, code, name, permissions } = form.value
    if (id) await api('/api/roles/' + id, 'PUT', { name, permissions })
    else await api('/api/roles', 'POST', { code, name, permissions })
    form.value = null
    await refreshSession()
    await load()
  } catch (failure) {
    report(failure)
  } finally {
    saving.value = false
  }
}

async function remove(role) {
  const confirmed = await confirmDeletion('删除角色「' + role.name + '」？')
  if (!confirmed) return
  error.value = ''
  try {
    await api('/api/roles/' + role.id, 'DELETE')
    await load()
  } catch (failure) {
    error.value = failure.message
  }
}

function roleMenus(role) {
  return groups.value.filter(group => group.permissions.some(option => role.permissions.includes(option.code)))
}
</script>

<template>
  <div class="page-heading">
    <h1>角色权限</h1>
    <UiButton :disabled="loading" @click="edit()">新建角色</UiButton>
  </div>
  <UiCard class="panel">
    <UiCardContent class="table-panel-content px-0">
      <UiAlert v-if="error" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ error }}</UiAlertDescription></UiAlert>
      <div class="table-wrap">
        <UiTable>
          <UiTableHeader><UiTableRow><UiTableHead>角色</UiTableHead><UiTableHead>编码</UiTableHead><UiTableHead>操作权限</UiTableHead><UiTableHead>操作</UiTableHead></UiTableRow></UiTableHeader>
          <UiTableBody>
            <UiTableRow v-for="role in roles" :key="role.id">
              <UiTableCell class="strong">{{ role.name }}</UiTableCell>
              <UiTableCell class="mono">{{ role.code }}</UiTableCell>
              <UiTableCell>
                <div class="cell-badges">
                  <UiBadge v-if="role.code === 'ADMIN'" variant="ok">全部权限</UiBadge>
                  <template v-else>
                    <UiBadge v-for="group in roleMenus(role).slice(0, 2)" :key="group.key" variant="secondary">
                      {{ group.menuPath.at(-1) }}
                    </UiBadge>
                  </template>
                  <UiButton v-if="role.permissions.length" @click="details = role" variant="ghost" size="sm"
                            :aria-label="'查看' + role.name + '的权限'">查看 {{ role.permissions.length }} 项</UiButton>
                  <span v-else class="subtle">—</span>
                </div>
              </UiTableCell>
              <UiTableCell class="row-actions">
                <span v-if="role.code === 'ADMIN'" class="subtle">内置角色</span>
                <template v-else>
                  <UiButton @click="edit(role)" variant="ghost" size="sm">编辑</UiButton>
                  <UiButton class="text-destructive" @click="remove(role)" variant="ghost" size="sm">删除</UiButton>
                </template>
              </UiTableCell>
            </UiTableRow>
            <UiTableRow v-if="!roles.length"><UiTableCell colspan="4" class="empty-cell">
              <UiEmpty class="empty-state"><UiEmptyHeader><UiEmptyTitle>{{ loading ? '正在加载…' : '暂无角色' }}</UiEmptyTitle></UiEmptyHeader></UiEmpty>
            </UiTableCell></UiTableRow>
          </UiTableBody>
        </UiTable>
      </div>
    </UiCardContent>
  </UiCard>
  <ModalDialog v-if="details" :title="details.name + ' · 操作权限'" wide @close="details = null">
    <RolePermissions :model-value="details.permissions" :groups="groups" read-only />
  </ModalDialog>
  <ModalDialog v-if="form" :title="form.id ? '编辑角色' : '新建角色'" wide :busy="saving" @close="form = null">
    <UiValidatedForm @submit="save">
      <UiFieldGroup layout="grid">
        <UiFormField name="name" label="角色名称" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.name" maxlength="80" />
        </UiFormField>
        <UiFormField name="code" label="角色编码" :required="!form.id"
                     pattern-message="角色编码须以字母开头，使用 2 至 40 位字母、数字或下划线" v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.code" :readonly="!!form.id" maxlength="40"
                   pattern="[A-Za-z][A-Za-z0-9_]{1,39}" autocomplete="off" />
        </UiFormField>
      </UiFieldGroup>
      <UiFormField name="permissions" label="操作权限" group v-slot="{ clear }">
        <RolePermissions v-model="form.permissions" :groups="groups" @update:model-value="clear" />
      </UiFormField>
      <div class="form-actions">
        <UiButton type="button" :disabled="saving" @click="form = null" variant="outline">取消</UiButton>
        <UiButton type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存' }}</UiButton>
      </div>
    </UiValidatedForm>
  </ModalDialog>
</template>
