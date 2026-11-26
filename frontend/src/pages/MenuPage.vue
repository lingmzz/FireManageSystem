<script setup>
import { confirmDeletion } from '../confirmation'
import { computed, onMounted, ref } from 'vue'
import { api, refreshSession } from '../api'
import ModalDialog from '../components/ModalDialog.vue'

const menus = ref([])
const options = ref({ views: [], permissions: [] })
const loading = ref(true)
const saving = ref(false)
const form = ref(null)
const error = ref('')

const ordered = computed(() => {
  const branches = new Map()
  for (const menu of menus.value) {
    const parent = menu.parentId ?? null
    if (!branches.has(parent)) branches.set(parent, [])
    branches.get(parent).push(menu)
  }
  const flatten = (parent, depth) => (branches.get(parent) ?? [])
    .flatMap(menu => [{ ...menu, depth }, ...flatten(menu.id, depth + 1)])
  return flatten(null, 0)
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [items, choices] = await Promise.all([api('/api/menus'), api('/api/menus/options')])
    menus.value = items
    options.value = choices
  } catch (failure) {
    error.value = failure.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

function edit(menu = null) {
  form.value = {
    id: menu?.id,
    name: menu?.name ?? '',
    parentId: menu?.parentId ?? null,
    viewKey: menu?.viewKey ?? '',
    permissionCode: menu?.permissionCode ?? '',
    sortOrder: menu?.sortOrder ?? 10,
    enabled: menu?.enabled ?? true
  }
}

async function save(report) {
  saving.value = true
  try {
    const { id, ...data } = form.value
    await api(id ? '/api/menus/' + id : '/api/menus', id ? 'PUT' : 'POST', {
      ...data, viewKey: data.viewKey || null, permissionCode: data.permissionCode || null
    })
    form.value = null
    await refreshSession()
    await load()
  } catch (failure) {
    report(failure)
  } finally {
    saving.value = false
  }
}

async function remove(menu) {
  const confirmed = await confirmDeletion('删除菜单「' + menu.name + '」？')
  if (!confirmed) return
  error.value = ''
  try {
    await api('/api/menus/' + menu.id, 'DELETE')
    await refreshSession()
    await load()
  } catch (failure) {
    error.value = failure.message
  }
}

function displayOption(list, value, fallback) {
  return list.find(item => item.value === value)?.label ?? fallback
}
</script>

<template>
  <div class="page-heading">
    <h1>菜单管理</h1>
    <UiButton :disabled="loading" @click="edit()">新建菜单</UiButton>
  </div>
  <UiCard class="panel">
    <UiCardContent class="table-panel-content px-0">
      <UiAlert v-if="error" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ error }}</UiAlertDescription></UiAlert>
      <div class="table-wrap">
        <UiTable>
          <UiTableHeader><UiTableRow><UiTableHead>菜单</UiTableHead><UiTableHead>页面</UiTableHead><UiTableHead>附加权限</UiTableHead><UiTableHead>排序</UiTableHead><UiTableHead>状态</UiTableHead><UiTableHead>操作</UiTableHead></UiTableRow></UiTableHeader>
          <UiTableBody>
            <UiTableRow v-for="menu in ordered" :key="menu.id">
              <UiTableCell class="strong" :style="{ paddingLeft: 16 + menu.depth * 20 + 'px' }">{{ menu.name }}</UiTableCell>
              <UiTableCell>{{ displayOption(options.views, menu.viewKey, '目录') }}</UiTableCell>
              <UiTableCell>{{ displayOption(options.permissions, menu.permissionCode, '—') }}</UiTableCell>
              <UiTableCell class="mono">{{ menu.sortOrder }}</UiTableCell>
              <UiTableCell><UiBadge :variant="menu.enabled ? 'ok' : 'muted'">{{ menu.enabled ? '显示' : '隐藏' }}</UiBadge></UiTableCell>
              <UiTableCell class="row-actions">
                <UiButton @click="edit(menu)" variant="ghost" size="sm">编辑</UiButton>
                <UiButton class="text-destructive" @click="remove(menu)" variant="ghost" size="sm">删除</UiButton>
              </UiTableCell>
            </UiTableRow>
            <UiTableRow v-if="!ordered.length"><UiTableCell colspan="6" class="empty-cell">
              <UiEmpty class="empty-state"><UiEmptyHeader><UiEmptyTitle>{{ loading ? '正在加载…' : '暂无菜单' }}</UiEmptyTitle></UiEmptyHeader></UiEmpty>
            </UiTableCell></UiTableRow>
          </UiTableBody>
        </UiTable>
      </div>
    </UiCardContent>
  </UiCard>
  <ModalDialog v-if="form" :title="form.id ? '编辑菜单' : '新建菜单'" wide :busy="saving" @close="form = null">
    <UiValidatedForm @submit="save">
      <UiFieldGroup layout="grid">
        <UiFormField name="name" label="菜单名称" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.name" maxlength="60" />
        </UiFormField>
        <UiFormField name="parentId" label="父菜单" v-slot="{ input }">
          <UiNativeSelect v-bind="input" v-model="form.parentId">
            <UiNativeSelectOption :value="null">顶层菜单</UiNativeSelectOption>
            <UiNativeSelectOption v-for="option in ordered.filter(item => item.id !== form.id)" :key="option.id" :value="option.id">
              {{ '　'.repeat(option.depth) + option.name }}
            </UiNativeSelectOption>
          </UiNativeSelect>
        </UiFormField>
        <UiFormField name="viewKey" label="页面" v-slot="{ input }">
          <UiNativeSelect v-bind="input" v-model="form.viewKey">
            <UiNativeSelectOption value="">目录</UiNativeSelectOption>
            <UiNativeSelectOption v-for="option in options.views" :key="option.value" :value="option.value">{{ option.label }}</UiNativeSelectOption>
          </UiNativeSelect>
        </UiFormField>
        <UiFormField name="permissionCode" label="附加权限" v-slot="{ input }">
          <UiNativeSelect v-bind="input" v-model="form.permissionCode">
            <UiNativeSelectOption value="">沿用页面权限</UiNativeSelectOption>
            <UiNativeSelectOption v-for="option in options.permissions" :key="option.value" :value="option.value">{{ option.label }}</UiNativeSelectOption>
          </UiNativeSelect>
        </UiFormField>
        <UiFormField name="sortOrder" label="排序" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.number="form.sortOrder" type="number" step="1" />
        </UiFormField>
        <UiFormField name="enabled" label="菜单状态" group v-slot="{ clear }">
          <UiFieldLabel class="option"><UiCheckbox v-model="form.enabled" @update:model-value="clear" />显示菜单</UiFieldLabel>
        </UiFormField>
      </UiFieldGroup>
      <div class="form-actions">
        <UiButton type="button" :disabled="saving" @click="form = null" variant="outline">取消</UiButton>
        <UiButton type="submit" :disabled="saving">保存</UiButton>
      </div>
    </UiValidatedForm>
  </ModalDialog>
</template>
