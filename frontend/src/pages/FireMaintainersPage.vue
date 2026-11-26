<script setup>
import { confirmDeletion } from '../confirmation'
import { computed, onMounted, ref } from 'vue'
import { api, pageSize, session } from '../api'
import { params, unitOptions } from '../fire'
import ModalDialog from '../components/ModalDialog.vue'
import PageControls from '../components/PageControls.vue'

const units = ref([])
const unitId = ref('')
const keyword = ref('')
const page = ref(1)
const result = ref({ items: [], total: 0 })
const form = ref(null)
const details = ref(null)
const busy = ref(false)
const saving = ref(false)
const error = ref('')
const editorError = ref('')
const searchUnit = ref(null)
const deviceKeyword = ref('')
const candidates = ref([])
const selectedUnits = computed(() => units.value.filter(unit => form.value?.unitIds.includes(unit.id)))

onMounted(async () => {
  try { units.value = unitOptions(await api('/api/fire/units')) }
  catch (failure) { error.value = failure.message }
  await load()
})

async function load(next = 1) {
  busy.value = true
  error.value = ''
  page.value = next
  try {
    result.value = await api('/api/fire/maintainers?' + params({ unitId: unitId.value, keyword: keyword.value, page: next, size: pageSize }))
  } catch (failure) { error.value = failure.message }
  finally { busy.value = false }
}

function edit(item = null) {
  editorError.value = ''
  form.value = item
    ? { ...item.maintainer, unitIds: item.units.map(unit => unit.id), devices: [...item.devices] }
    : { name: '', contact: '', phone: '', unitIds: [], devices: [] }
  searchUnit.value = form.value.unitIds[0] ?? null
  deviceKeyword.value = ''
  candidates.value = []
  if (searchUnit.value) searchDevices()
}

function unitsChanged() {
  form.value.devices = form.value.devices.filter(device => form.value.unitIds.includes(device.unitId))
  if (!form.value.unitIds.includes(searchUnit.value)) searchUnit.value = form.value.unitIds[0] ?? null
  searchDevices()
}

async function searchDevices() {
  candidates.value = []
  if (!searchUnit.value) return
  const requestedUnit = searchUnit.value
  try {
    const rows = await api('/api/fire/devices?' + params({ unitId: requestedUnit, keyword: deviceKeyword.value, size: 50 }))
    if (searchUnit.value === requestedUnit) candidates.value = rows.items.map(row => row.device)
      .filter(device => device.unitId === requestedUnit)
  } catch (failure) { editorError.value = failure.message }
}

function toggleDevice(device, selected) {
  if (selected && !form.value.devices.some(row => row.id === device.id)) form.value.devices.push(device)
  if (!selected) form.value.devices = form.value.devices.filter(row => row.id !== device.id)
}

async function save(report) {
  saving.value = true
  editorError.value = ''
  try {
    const { id, name, contact, phone, unitIds, devices, version } = form.value
    await api('/api/fire/maintainers' + (id ? '/' + id : ''), id ? 'PUT' : 'POST',
      { name, contact, phone, unitIds, deviceIds: devices.map(device => device.id), version })
    form.value = null
    await load(id ? page.value : 1)
  } catch (failure) { report(failure) }
  finally { saving.value = false }
}

async function remove(item) {
  const confirmed = await confirmDeletion('删除维保单位「' + item.maintainer.name + '」？')
  if (!confirmed) return
  try {
    await api('/api/fire/maintainers/' + item.maintainer.id, 'DELETE')
    await load()
  } catch (failure) { error.value = failure.message }
}
</script>

<template>
  <div class="page-heading">
    <h1>维保单位</h1>
    <UiButton v-if="session.user.permissions.includes('fire:maint-write')"  @click="edit()">新增维保单位</UiButton>
  </div>
  <UiCard class="panel">
    <UiCardContent class="table-panel-content px-0">
      <form class="toolbar fire-filters" @submit.prevent="load()">
        <UiNativeSelect :model-value="unitId" aria-label="按维保范围筛选"
                        @update:model-value="value => { unitId = value; load() }">
          <UiNativeSelectOption value="">全部范围</UiNativeSelectOption>
          <UiNativeSelectOption v-for="unit in units" :key="unit.id" :value="unit.id">{{ unit.label }}</UiNativeSelectOption>
        </UiNativeSelect>
        <UiInput v-model.trim="keyword" placeholder="搜索维保单位名称" aria-label="搜索维保单位" maxlength="160" />
        <UiButton :disabled="busy" variant="outline">查询</UiButton>
      </form>
      <UiAlert v-if="error" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ error }}</UiAlertDescription></UiAlert>
      <div class="table-wrap">
        <UiTable>
          <UiTableHeader><UiTableRow><UiTableHead>维保单位</UiTableHead><UiTableHead>联系人</UiTableHead><UiTableHead>联系电话</UiTableHead><UiTableHead>维保范围</UiTableHead><UiTableHead>关联设备</UiTableHead><UiTableHead>操作</UiTableHead></UiTableRow></UiTableHeader>
          <UiTableBody>
            <UiTableRow v-for="item in result.items" :key="item.maintainer.id">
              <UiTableCell class="strong">{{ item.maintainer.name }}</UiTableCell><UiTableCell>{{ item.maintainer.contact }}</UiTableCell>
              <UiTableCell class="nowrap">{{ item.maintainer.phone }}</UiTableCell><UiTableCell>{{ item.units.map(unit => unit.name).join('、') }}</UiTableCell>
              <UiTableCell>{{ item.devices.length }} 台</UiTableCell>
              <UiTableCell class="row-actions">
                <UiButton @click="details = item" variant="ghost" size="sm">详情</UiButton>
                <template v-if="item.editable">
                  <UiButton @click="edit(item)" variant="ghost" size="sm">编辑</UiButton>
                  <UiButton class="text-destructive" @click="remove(item)" variant="ghost" size="sm">删除</UiButton>
                </template>
              </UiTableCell>
            </UiTableRow>
            <UiTableRow v-if="!result.items.length"><UiTableCell colspan="6" class="empty-cell">
              <UiEmpty class="empty-state"><UiEmptyHeader><UiEmptyTitle>{{ busy ? '正在加载…' : '暂无维保单位' }}</UiEmptyTitle></UiEmptyHeader></UiEmpty>
            </UiTableCell></UiTableRow>
          </UiTableBody>
        </UiTable>
      </div>
      <PageControls :page="page" :total="result.total" :busy="busy" @change="load" />
    </UiCardContent>
  </UiCard>
  <ModalDialog v-if="details" :title="details.maintainer.name" wide @close="details = null">
    <dl class="detail-meta">
      <div><dt>联系人</dt><dd>{{ details.maintainer.contact }}</dd></div>
      <div><dt>联系电话</dt><dd>{{ details.maintainer.phone }}</dd></div>
      <div><dt>维保范围</dt><dd>{{ details.units.map(unit => unit.name).join('、') }}</dd></div>
    </dl>
    <section class="history">
      <h3>关联设备</h3>
      <p v-if="!details.devices.length" class="subtle">暂无关联设备</p>
      <ul v-else><li v-for="device in details.devices" :key="device.id">{{ device.unitName }} · {{ device.code }} · {{ device.itemName }}</li></ul>
    </section>
  </ModalDialog>
  <ModalDialog v-if="form" :title="form.id ? '编辑维保单位' : '新增维保单位'" wide :busy="saving" @close="form = null">
    <UiValidatedForm @submit="save">
      <UiAlert v-if="editorError" role="alert" variant="destructive"><UiAlertDescription>{{ editorError }}</UiAlertDescription></UiAlert>
      <UiFieldGroup layout="grid">
        <UiFormField name="name" label="单位名称" required class="form-span" v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.name" maxlength="160" />
        </UiFormField>
        <UiFormField name="contact" label="联系人" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.contact" maxlength="60" />
        </UiFormField>
        <UiFormField name="phone" label="联系电话" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.phone" maxlength="40" type="tel" />
        </UiFormField>
      </UiFieldGroup>
      <UiFormField name="unitIds" label="维保车站和单位" group required
                   :check="() => form.unitIds.length ? '' : '请选择至少一个维保单位'" v-slot="{ clear }">
        <div class="choices fire-checklist">
          <UiFieldLabel v-for="unit in units" :key="unit.id" class="option">
            <UiCheckbox v-model="form.unitIds" :value="unit.id" @change="clear(); unitsChanged()" />{{ unit.label }}
          </UiFieldLabel>
        </div>
      </UiFormField>
      <UiFormField v-if="selectedUnits.length" name="deviceIds" label="关联设备" group v-slot="{ clear }">
        <div class="inline-field">
          <UiNativeSelect :model-value="searchUnit" aria-label="关联设备的所属单位"
                          @update:model-value="value => { searchUnit = value; searchDevices() }">
            <UiNativeSelectOption v-for="unit in selectedUnits" :key="unit.id" :value="unit.id">{{ unit.label }}</UiNativeSelectOption>
          </UiNativeSelect>
          <UiInput v-model.trim="deviceKeyword" placeholder="设备编号或品名" maxlength="120" aria-label="搜索关联设备" />
          <UiButton type="button" @click="searchDevices" variant="outline">搜索</UiButton>
        </div>
        <div class="choices fire-checklist">
          <UiFieldLabel v-for="device in candidates" :key="device.id" class="option">
            <UiCheckbox :checked="form.devices.some(row => row.id === device.id)"
                        @change="checked => { toggleDevice(device, checked); clear() }" />{{ device.code }} · {{ device.itemName }}
          </UiFieldLabel>
        </div>
        <div class="fire-tags max-h-32 overflow-y-auto">
          <UiButton v-for="device in form.devices" :key="device.id" type="button"
                    :aria-label="'移除设备 ' + device.code" @click="toggleDevice(device, false); clear()" variant="ghost" size="sm">
            {{ device.code }} ×
          </UiButton>
        </div>
      </UiFormField>
      <div class="form-actions">
        <UiButton type="button" variant="outline" :disabled="saving" @click="form = null">取消</UiButton>
        <UiButton type="submit" :disabled="saving">保存</UiButton>
      </div>
    </UiValidatedForm>
  </ModalDialog>
</template>
