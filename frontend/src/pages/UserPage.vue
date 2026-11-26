<script setup>
import { onMounted, ref, watch } from 'vue'
import { api, dateTime, pageSize, refreshSession, session } from '../api'
import { unitOptions } from '../fire'
import { profileDraft, profilePayload } from '../userProfile'
import ModalDialog from '../components/ModalDialog.vue'
import PageControls from '../components/PageControls.vue'
import ProfileFields from '../components/ProfileFields.vue'
import UserAvatar from '../components/UserAvatar.vue'

const keyword = ref('')
const page = ref(1)
const result = ref({ items: [], total: 0 })
const roles = ref([])
const units = ref([])
const loading = ref(true)
const error = ref('')
const saving = ref(false)
const loadingOptions = ref(false)
const mode = ref(null)
const record = ref(null)
const form = ref({})

async function load(next = 1) {
  loading.value = true
  error.value = ''
  page.value = next
  try {
    const params = new URLSearchParams({ page: next, size: pageSize, keyword: keyword.value })
    result.value = await api('/api/users?' + params)
  } catch (failure) {
    error.value = failure.message
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
watch(() => session.user, user => {
  const row = result.value.items.find(item => item.id === user?.id)
  if (row) {
    row.displayName = user.displayName
    row.profile = user.profile
  }
})

async function edit(user = null) {
  if (loadingOptions.value) return
  loadingOptions.value = true
  error.value = ''
  try {
    const [roleChoices, unitChoices] = await Promise.all([api('/api/roles'), api('/api/users/units')])
    roles.value = roleChoices
    units.value = unitOptions(unitChoices)
    record.value = user
    form.value = {
      username: user?.username ?? '',
      displayName: user?.displayName ?? '',
      password: '',
      enabled: user?.enabled ?? true,
      roleIds: user?.roles.map(role => role.id) ?? [],
      unitId: user?.profile?.unitId ?? null,
      personal: profileDraft(user?.profile)
    }
    mode.value = user ? 'edit' : 'new'
  } catch (failure) {
    error.value = failure.message
  } finally {
    loadingOptions.value = false
  }
}

function resetPassword(user) {
  record.value = user
  form.value = { password: '' }
  mode.value = 'password'
}

function passwordError() {
  const password = form.value.password
  if (password.length < 8) return '密码至少填写 8 个字符'
  if (new TextEncoder().encode(password).length > 72) return '密码长度不能超过 72 字节'
  return ''
}

function rolesError() {
  if (!form.value.roleIds.length) return '请选择至少一个角色'
  if (form.value.roleIds.length > 25) return '最多选择 25 个角色'
  return ''
}

async function save(report) {
  saving.value = true
  try {
    const { username, displayName, password, enabled, roleIds, unitId, personal } = form.value
    const editingSelf = mode.value === 'edit' && record.value.id === session.user?.id
    if (mode.value === 'password') {
      await api('/api/users/' + record.value.id + '/password', 'PUT', { password })
    } else {
      const payload = { displayName, enabled, roleIds, profile: { unitId, personal: await profilePayload(personal) } }
      if (mode.value === 'new') await api('/api/users', 'POST', { ...payload, username, password })
      else await api('/api/users/' + record.value.id, 'PUT', payload)
    }
    mode.value = null
    if (editingSelf) await refreshSession()
    if (session.user?.permissions.includes('users:manage')) await load(page.value)
  } catch (failure) {
    report(failure)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page-heading">
    <h1>用户管理</h1>
    <UiButton :disabled="loadingOptions" @click="edit()">新建账号</UiButton>
  </div>
  <UiCard class="panel">
    <UiCardContent class="table-panel-content px-0">
      <form class="toolbar" @submit.prevent="load()">
        <UiInput v-model.trim="keyword" placeholder="搜索用户名或姓名" aria-label="搜索用户名或姓名" maxlength="80" />
        <UiButton :disabled="loading" variant="outline">搜索</UiButton>
      </form>
      <UiAlert v-if="error" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ error }}</UiAlertDescription></UiAlert>
      <div class="table-wrap">
        <UiTable>
          <UiTableHeader><UiTableRow><UiTableHead>用户</UiTableHead><UiTableHead>所属单位</UiTableHead>
            <UiTableHead>联系方式</UiTableHead><UiTableHead>角色</UiTableHead><UiTableHead>状态</UiTableHead>
            <UiTableHead>创建时间</UiTableHead><UiTableHead>操作</UiTableHead></UiTableRow></UiTableHeader>
          <UiTableBody>
            <UiTableRow v-for="user in result.items" :key="user.id">
              <UiTableCell>
                <div class="flex items-center gap-2.5">
                  <UserAvatar :src="user.profile?.avatarUrl" :name="user.displayName" />
                  <div class="min-w-0">
                    <div class="strong break-all">{{ user.displayName }}</div>
                    <div class="mono subtle break-all">{{ user.username }}</div>
                  </div>
                </div>
              </UiTableCell>
              <UiTableCell>{{ user.profile?.unitName || '—' }}</UiTableCell>
              <UiTableCell>
                <div class="break-all" v-if="user.profile?.phone">{{ user.profile.phone }}</div>
                <div class="subtle break-all" v-if="user.profile?.email">{{ user.profile.email }}</div>
                <span class="subtle" v-if="!user.profile?.phone && !user.profile?.email">—</span>
              </UiTableCell>
              <UiTableCell><div class="cell-badges">
                <UiBadge v-for="role in user.roles" :key="role.id" variant="secondary">{{ role.name }}</UiBadge>
              </div></UiTableCell>
              <UiTableCell><UiBadge :variant="user.enabled ? 'ok' : 'muted'">{{ user.enabled ? '启用' : '停用' }}</UiBadge></UiTableCell>
              <UiTableCell class="nowrap">{{ dateTime(user.createdAt) }}</UiTableCell>
              <UiTableCell class="row-actions">
                <UiButton :disabled="loadingOptions" @click="edit(user)" variant="ghost" size="sm">编辑</UiButton>
                <UiButton @click="resetPassword(user)" variant="ghost" size="sm">重置密码</UiButton>
              </UiTableCell>
            </UiTableRow>
            <UiTableRow v-if="!result.items.length"><UiTableCell colspan="7" class="empty-cell">
              <UiEmpty class="empty-state"><UiEmptyHeader><UiEmptyTitle>{{ loading ? '正在加载…' : '暂无匹配用户' }}</UiEmptyTitle></UiEmptyHeader></UiEmpty>
            </UiTableCell></UiTableRow>
          </UiTableBody>
        </UiTable>
      </div>
      <PageControls :page="page" :total="result.total" :busy="loading" @change="load" />
    </UiCardContent>
  </UiCard>

  <ModalDialog v-if="mode" :title="mode === 'new' ? '新建账号' : mode === 'password' ? '重置密码' : '编辑账号'"
               :wide="mode !== 'password'" :busy="saving" @close="mode = null">
    <UiValidatedForm @submit="save">
      <UiFieldGroup :layout="mode === 'password' ? 'compact' : 'grid'">
        <UiFormField v-if="mode !== 'password'" name="username" label="用户名" :required="mode === 'new'"
                     pattern-message="用户名使用 3 至 40 位字母、数字或 . _ -" v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.username" :readonly="mode === 'edit'" maxlength="40"
                   pattern="[A-Za-z0-9_.\-]{3,40}" autocomplete="off" />
        </UiFormField>
        <UiFormField v-if="mode !== 'password'" name="displayName" label="姓名" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.displayName" maxlength="80" />
        </UiFormField>
        <UiFormField v-if="mode !== 'edit'" name="password" :label="mode === 'password' ? '新密码' : '初始密码'"
                     required :check="passwordError" v-slot="{ input }">
          <UiInput v-bind="input" v-model="form.password" type="password" minlength="8" maxlength="72" autocomplete="new-password" />
        </UiFormField>
        <UiFormField v-if="mode !== 'password'" name="profile.unitId" label="所属单位" v-slot="{ input }">
          <UiNativeSelect v-bind="input" v-model="form.unitId">
            <UiNativeSelectOption :value="null">未分配</UiNativeSelectOption>
            <UiNativeSelectOption v-for="unit in units" :key="unit.id" :value="unit.id">{{ unit.label }}</UiNativeSelectOption>
          </UiNativeSelect>
        </UiFormField>
        <UiFormField v-if="mode === 'edit'" name="enabled" label="账号状态" group v-slot="{ clear }">
          <UiFieldLabel class="option">
            <UiCheckbox v-model="form.enabled" @update:model-value="clear" />启用账号
          </UiFieldLabel>
        </UiFormField>
        <ProfileFields v-if="mode !== 'password'" v-model="form.personal" :name="form.displayName"
                       :saved-avatar="record?.profile?.avatarUrl" prefix="profile.personal" />
        <UiFormField v-if="mode !== 'password'" name="roleIds" label="角色" group required :check="rolesError"
                     class="form-span" v-slot="{ clear }">
          <div class="choices fire-checklist">
            <UiFieldLabel v-for="role in roles" :key="role.id" class="option">
              <UiCheckbox v-model="form.roleIds" :value="role.id" @update:model-value="clear" />{{ role.name }}
            </UiFieldLabel>
          </div>
        </UiFormField>
        <UiFormField v-if="mode === 'new'" name="enabled" label="账号状态" group v-slot="{ clear }">
          <UiFieldLabel class="option">
            <UiCheckbox v-model="form.enabled" @update:model-value="clear" />启用账号
          </UiFieldLabel>
        </UiFormField>
      </UiFieldGroup>
      <div class="form-actions">
        <UiButton type="button" :disabled="saving" @click="mode = null" variant="outline">取消</UiButton>
        <UiButton type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存' }}</UiButton>
      </div>
    </UiValidatedForm>
  </ModalDialog>
</template>
