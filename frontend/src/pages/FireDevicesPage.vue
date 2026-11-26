<script setup>
import { confirmDeletion } from '../confirmation'
import { computed, onMounted, ref } from 'vue'
import { api, dateTime, session } from '../api'
import { deviceStates, deviceTypes, download, noticeLabels, params, unitOptions } from '../fire'
import ModalDialog from '../components/ModalDialog.vue'

const stateTabs = [['', '全部设备'], [1, '在用'], [2, '库存管理'], [0, '已报废']]
const opLabel = {
  CREATE: '新增入库', EDIT: '修改资料', DELETE: '删除', MOVE: '变更存放地点', INSPECT: '记录年检',
  ISSUE: '领用', REPLACE: '更换设备', REPLACE_OUT: '换下入库', REPLACE_IN: '换上使用', SCRAP: '报废'
}
const allUnits = ref([])
const units = computed(() => unitOptions(allUnits.value))
const filters = ref({ unitId: '', category: '', state: '', notice: '', keyword: '' })
const page = ref(1)
const size = ref(12)
const result = ref({ items: [], total: 0 })
const selected = ref([])
const busy = ref(true)
const saving = ref(false)
const error = ref('')
const success = ref('')
const modal = ref(null)
const modalError = ref('')
const details = ref(null)
const form = ref({})
const importFile = ref(null)
const replacementKeyword = ref('')
const replacements = ref([])
const focused = ref(null)
const permits = code => session.user?.permissions.includes(code)
const checkedRows = computed(() => result.value.items.filter(row => selected.value.includes(row.device.id)))
const batchable = computed(() => result.value.items.filter(row => row.operate && row.device.state !== 0))

onMounted(async () => {
  try { allUnits.value = await api('/api/fire/units') }
  catch (failure) { error.value = failure.message }
  await load()
})

async function load(next = 1) {
  busy.value = true
  error.value = ''
  page.value = next
  selected.value = []
  try {
    result.value = await api('/api/fire/devices?' + params({ ...filters.value, page: next, size: size.value }))
  } catch (failure) { error.value = failure.message }
  finally { busy.value = false }
}

function selectState(state) {
  filters.value.state = state
  load()
}

function selectAll(checked) {
  selected.value = checked ? batchable.value.map(row => row.device.id) : []
}

function edit(device = null) {
  modalError.value = ''
  form.value = device ? { ...device } : {
    unitId: units.value.length === 1 ? units.value[0].id : null,
    category: '', code: '', itemName: '', modelType: '', specification: '',
    useSite: '', storageLocation: '', manufacturedOn: '', inspectedOn: '', expiresOn: ''
  }
  modal.value = 'edit'
}

async function save(report) {
  saving.value = true
  modalError.value = ''
  const { id, ...fields } = form.value
  for (const key of ['manufacturedOn', 'inspectedOn', 'expiresOn']) fields[key] ||= null
  try {
    await api('/api/fire/devices' + (id ? '/' + id : ''), id ? 'PUT' : 'POST', fields)
    success.value = id ? '设备资料已更新。' : '设备已新增至库存。'
    modal.value = null
    await load(id ? page.value : 1)
  } catch (failure) { report(failure) }
  finally { saving.value = false }
}

async function show(device) {
  error.value = ''
  modalError.value = ''
  try {
    details.value = await api('/api/fire/devices/' + device.id)
    modal.value = 'detail'
  } catch (failure) { error.value = failure.message }
}

function operation(action, device = null) {
  focused.value = device
  replacements.value = []
  replacementKeyword.value = ''
  modalError.value = ''
  form.value = {
    action, version: device?.version, inspectionDate: new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }),
    storageLocation: action === 'MOVE' ? device?.storageLocation : '',
    useSite: action === 'ISSUE' ? device?.useSite : '', replacementId: null, remark: ''
  }
  modal.value = 'action'
  if (action === 'REPLACE') findReplacements()
}

async function findReplacements() {
  try {
    const device = focused.value
    const batch = await api('/api/fire/devices?' + params({
      unitId: device.unitId, category: device.category, keyword: replacementKeyword.value, state: 2, size: 100
    }))
    replacements.value = batch.items.map(row => row.device).filter(row => row.unitId === device.unitId)
  } catch (failure) { modalError.value = failure.message }
}

async function runOperation(report) {
  saving.value = true
  modalError.value = ''
  const { action, version, remark, inspectionDate, useSite, storageLocation, replacementId } = form.value
  const inspectedOn = action === 'INSPECT' ? inspectionDate : null
  try {
    if (focused.value) {
      await api('/api/fire/devices/' + focused.value.id + '/actions', 'POST',
        { action, version, remark, inspectedOn, useSite, storageLocation, replacementId })
      success.value = '设备操作已保存。'
    } else {
      const devices = checkedRows.value.map(row => ({ id: row.device.id, version: row.device.version }))
      const response = await api('/api/fire/devices/batch', 'POST', { action, inspectedOn, devices, remark })
      success.value = '已处理 ' + response.count + ' 台设备。'
    }
    modal.value = null
    await load(page.value)
  } catch (failure) { report(failure) }
  finally { saving.value = false }
}

async function remove(device) {
  const confirmed = await confirmDeletion('将设备「' + device.code + '」从台账中删除？已保存的操作记录会保留。')
  if (!confirmed) return
  try {
    await api('/api/fire/devices/' + device.id, 'DELETE')
    success.value = '设备已删除。'
    modal.value = null
    await load()
  } catch (failure) { modalError.value = failure.message }
}

function showImport() {
  importFile.value = null
  modalError.value = ''
  modal.value = 'import'
}

async function importDevices(report) {
  saving.value = true
  modalError.value = ''
  try {
    const file = new FormData()
    file.append('file', importFile.value)
    const response = await api('/api/fire/devices/import', 'POST', file)
    success.value = '已导入 ' + response.count + ' 台设备至库存。'
    modal.value = null
    await load()
  } catch (failure) { report(failure) }
  finally { saving.value = false }
}

async function saveFile(path, name, scoped = false) {
  try { await download(path + (scoped ? '?' + params(filters.value) : ''), name) }
  catch (failure) {
    if (modal.value) modalError.value = failure.message
    else error.value = failure.message
  }
}
</script>

<template>
  <div class="page-heading">
    <h1>消防设备台账</h1>
    <div class="fire-actions">
      <UiButton @click="saveFile('/api/fire/devices/export', '消防设备台账.xlsx', true)" variant="outline">导出 Excel</UiButton>
      <UiButton v-if="permits('fire:device-import')"  @click="showImport" variant="outline">导入</UiButton>
      <UiButton v-if="permits('fire:device-add')"  @click="edit()">新增入库</UiButton>
    </div>
  </div>
  <UiCard class="panel">
    <UiCardContent class="table-panel-content px-0">
      <UiTabs :model-value="filters.state === '' ? 'all' : String(filters.state)" class="page-tabs"
              @update:model-value="value => selectState(value === 'all' ? '' : Number(value))">
        <UiTabsList aria-label="设备库存状态" variant="line">
          <UiTabsTrigger v-for="[value, label] in stateTabs" :key="label" :value="value === '' ? 'all' : String(value)">
            {{ label }}
          </UiTabsTrigger>
        </UiTabsList>
      </UiTabs>
      <form class="toolbar fire-filters" @submit.prevent="load()">
        <UiNativeSelect :model-value="filters.unitId" aria-label="按所属单位筛选"
                        @update:model-value="value => { filters.unitId = value; load() }">
          <UiNativeSelectOption value="">全部单位</UiNativeSelectOption>
          <UiNativeSelectOption v-for="unit in units" :key="unit.id" :value="unit.id">{{ unit.label }}</UiNativeSelectOption>
        </UiNativeSelect>
        <UiNativeSelect :model-value="filters.category" aria-label="按设备类别筛选"
                        @update:model-value="value => { filters.category = value; load() }">
          <UiNativeSelectOption value="">全部类别</UiNativeSelectOption>
          <UiNativeSelectOption v-for="(label, key) in deviceTypes" :key="key" :value="key">{{ label }}</UiNativeSelectOption>
        </UiNativeSelect>
        <UiNativeSelect :model-value="filters.notice" aria-label="按设备提醒筛选"
                        @update:model-value="value => { filters.notice = value; load() }">
          <UiNativeSelectOption value="">全部提示</UiNativeSelectOption>
          <UiNativeSelectOption v-for="(label, key) in noticeLabels" :key="key" :value="key">{{ label }}</UiNativeSelectOption>
        </UiNativeSelect>
        <UiInput v-model.trim="filters.keyword" maxlength="120" placeholder="设备编号、品名或地点" aria-label="搜索设备" />
        <UiButton :disabled="busy" variant="outline">查询</UiButton>
      </form>
      <div v-if="selected.length" class="toolbar">
        <span>已选 {{ selected.length }} 台</span>
        <UiButton @click="operation('INSPECT')" variant="outline">批量年检</UiButton>
        <UiButton @click="operation('SCRAP')" variant="destructive">批量报废</UiButton>
      </div>
      <UiAlert v-if="error" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ error }}</UiAlertDescription></UiAlert>
      <UiAlert v-if="success" class="page-feedback" role="status"><UiAlertDescription>{{ success }}</UiAlertDescription></UiAlert>
      <div class="table-wrap">
        <UiTable>
          <UiTableHeader><UiTableRow>
            <UiTableHead v-if="permits('fire:device-operate')"><UiCheckbox aria-label="全选本页"
                :checked="batchable.length > 0 && selected.length === batchable.length" :disabled="!batchable.length" @change="selectAll" /></UiTableHead>
            <UiTableHead>所属单位</UiTableHead><UiTableHead>设备编号</UiTableHead><UiTableHead>品名</UiTableHead><UiTableHead>类别</UiTableHead><UiTableHead>存放地点</UiTableHead><UiTableHead>最新年检</UiTableHead>
            <UiTableHead>保质期至</UiTableHead><UiTableHead>状态与提示</UiTableHead><UiTableHead>操作</UiTableHead>
          </UiTableRow></UiTableHeader>
          <UiTableBody>
            <UiTableRow v-for="row in result.items" :key="row.device.id">
              <UiTableCell v-if="permits('fire:device-operate')"><UiCheckbox v-if="row.operate && row.device.state !== 0" v-model="selected"
                   :value="row.device.id" :aria-label="'选择 ' + row.device.code" /></UiTableCell>
              <UiTableCell>{{ row.device.unitName }}</UiTableCell><UiTableCell class="mono">{{ row.device.code }}</UiTableCell><UiTableCell class="strong">{{ row.device.itemName }}</UiTableCell>
              <UiTableCell>{{ deviceTypes[row.device.category] }}</UiTableCell><UiTableCell>{{ row.device.storageLocation }}</UiTableCell>
              <UiTableCell class="nowrap">{{ row.device.inspectedOn || '—' }}</UiTableCell><UiTableCell class="nowrap">{{ row.device.expiresOn || '—' }}</UiTableCell>
              <UiTableCell><div class="fire-tags">
                <UiBadge :variant="row.device.state === 1 ? 'ok' : 'muted'">{{ deviceStates[row.device.state] }}</UiBadge>
                <UiBadge v-for="notice in row.notices" :key="notice"  :variant="notice === 9 || notice === 3 ? 'attention' : 'info'">{{ noticeLabels[notice] }}</UiBadge>
              </div></UiTableCell>
              <UiTableCell><UiButton @click="show(row.device)" variant="ghost" size="sm">详情</UiButton></UiTableCell>
            </UiTableRow>
            <UiTableRow v-if="!result.items.length"><UiTableCell :colspan="permits('fire:device-operate') ? 10 : 9" class="empty-cell">
              <UiEmpty class="empty-state"><UiEmptyHeader><UiEmptyTitle>{{ busy ? '正在加载…' : '暂无符合条件的设备' }}</UiEmptyTitle></UiEmptyHeader></UiEmpty>
            </UiTableCell></UiTableRow>
          </UiTableBody>
        </UiTable>
      </div>
      <div class="pagination">
        <span>共 {{ result.total }} 台</span>
        <div>
          <UiNativeSelect :model-value="size" aria-label="每页设备数"
                          @update:model-value="value => { size = Number(value); load() }">
            <UiNativeSelectOption v-for="n in [12, 25, 50, 100]" :key="n" :value="n">{{ n }} 台/页</UiNativeSelectOption>
          </UiNativeSelect>
          <UiButton :disabled="page === 1 || busy" @click="load(page - 1)" variant="outline" size="sm">上一页</UiButton>
          <span class="page-index">{{ page }} / {{ Math.max(1, Math.ceil(result.total / size)) }}</span>
          <UiButton :disabled="page * size >= result.total || busy" @click="load(page + 1)" variant="outline" size="sm">下一页</UiButton>
        </div>
      </div>
    </UiCardContent>
  </UiCard>

  <ModalDialog v-if="modal === 'edit'" :title="form.id ? '修改设备资料' : '新增消防设备入库'" wide :busy="saving" @close="modal = null">
    <UiValidatedForm @submit="save">
      <UiFieldGroup layout="grid">
        <UiFormField name="unitId" label="所属单位" required v-slot="{ input }">
          <UiNativeSelect v-bind="input" v-model="form.unitId" :disabled="!!form.id">
            <UiNativeSelectOption :value="null" disabled>选择车站或所属单位</UiNativeSelectOption>
            <UiNativeSelectOption v-for="unit in units" :key="unit.id" :value="unit.id">{{ unit.label }}</UiNativeSelectOption>
          </UiNativeSelect>
        </UiFormField>
        <UiFormField name="code" label="设备编号" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.code" maxlength="64" />
        </UiFormField>
        <UiFormField name="itemName" label="品名" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.itemName" maxlength="100" />
        </UiFormField>
        <UiFormField name="category" label="设备类别" required v-slot="{ input }">
          <UiNativeSelect v-bind="input" v-model="form.category">
            <UiNativeSelectOption value="" disabled>请选择</UiNativeSelectOption>
            <UiNativeSelectOption v-for="(label, key) in deviceTypes" :key="key" :value="key">{{ label }}</UiNativeSelectOption>
          </UiNativeSelect>
        </UiFormField>
        <UiFormField name="modelType" label="类型" v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.modelType" maxlength="100" />
        </UiFormField>
        <UiFormField name="specification" label="规格" v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.specification" maxlength="100" />
        </UiFormField>
        <UiFormField name="useSite" label="使用场所" v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.useSite" maxlength="180" />
        </UiFormField>
        <UiFormField name="storageLocation" label="存放地点" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.storageLocation" maxlength="180" />
        </UiFormField>
      </UiFieldGroup>
      <UiFieldGroup layout="grid" :class="{ 'sm:grid-cols-3': !form.id }">
        <UiFormField name="manufacturedOn" label="出厂日期" v-slot="{ input }">
          <UiDateInput v-bind="input" v-model="form.manufacturedOn" />
        </UiFormField>
        <UiFormField name="expiresOn" label="保质期截止日期" v-slot="{ input }">
          <UiDateInput v-bind="input" v-model="form.expiresOn" />
        </UiFormField>
        <UiFormField v-if="!form.id" name="inspectedOn" label="最近年检日期" v-slot="{ input }">
          <UiDateInput v-bind="input" v-model="form.inspectedOn" />
        </UiFormField>
      </UiFieldGroup>
      <div class="form-actions">
        <UiButton type="button" variant="outline" :disabled="saving" @click="modal = null">取消</UiButton>
        <UiButton type="submit" :disabled="saving">保存</UiButton>
      </div>
    </UiValidatedForm>
  </ModalDialog>

  <ModalDialog v-if="modal === 'detail' && details" :title="details.item.device.code + ' · ' + details.item.device.itemName"
               wide @close="modal = null">
    <UiAlert v-if="modalError" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ modalError }}</UiAlertDescription></UiAlert>
    <dl class="detail-meta">
      <div><dt>所属单位</dt><dd>{{ details.item.device.unitName }}</dd></div>
      <div><dt>类别 / 类型 / 规格</dt><dd>{{ deviceTypes[details.item.device.category] }} / {{ details.item.device.modelType || '—' }} / {{ details.item.device.specification || '—' }}</dd></div>
      <div><dt>使用场所</dt><dd>{{ details.item.device.useSite || '—' }}</dd></div>
      <div><dt>存放地点</dt><dd>{{ details.item.device.storageLocation }}</dd></div>
      <div><dt>出厂 / 最新年检 / 保质期至</dt><dd>{{ details.item.device.manufacturedOn || '—' }} / {{ details.item.device.inspectedOn || '—' }} / {{ details.item.device.expiresOn || '—' }}</dd></div>
      <div><dt>状态</dt><dd>{{ deviceStates[details.item.device.state] }}</dd></div>
      <div><dt>新增角色</dt><dd>{{ details.item.device.createdRole || '—' }}</dd></div>
      <div><dt>创建时间</dt><dd>{{ dateTime(details.item.device.createdAt) }}</dd></div>
    </dl>
    <section v-if="details.maintainers.length" class="history">
      <h3>维保单位</h3>
      <p v-for="unit in details.maintainers" :key="unit.id">{{ unit.name }} · {{ unit.contact }} · {{ unit.phone }}</p>
    </section>
    <section class="history">
      <h3>操作及年检记录</h3>
      <ol>
        <li v-for="(event, index) in details.history" :key="index">
          <div class="event-meta"><strong>{{ opLabel[event.operation] }}</strong>
            <span>{{ event.actorName }} · {{ dateTime(event.createdAt) }}</span></div>
          <p v-if="event.inspectedOn || event.relatedCode || event.remark">
            {{ [event.inspectedOn, event.relatedCode, event.remark].filter(Boolean).join(' · ') }}
          </p>
        </li>
      </ol>
    </section>
    <div class="fire-actions">
      <UiButton v-if="details.item.fullEdit && details.item.device.state !== 0"  @click="edit(details.item.device)" variant="outline">修改资料</UiButton>
      <template v-if="details.item.operate && details.item.device.state !== 0">
        <UiButton @click="operation('MOVE', details.item.device)" variant="outline">变更存放地点</UiButton>
        <UiButton @click="operation('INSPECT', details.item.device)" variant="outline">记录年检</UiButton>
        <UiButton @click="operation(details.item.device.state === 2 ? 'ISSUE' : 'REPLACE', details.item.device)" variant="outline">
          {{ details.item.device.state === 2 ? '领用' : '更换设备' }}
        </UiButton>
        <UiButton class="text-destructive" @click="operation('SCRAP', details.item.device)" variant="ghost" size="sm">报废</UiButton>
      </template>
      <UiButton v-if="details.item.fullEdit" class="text-destructive" @click="remove(details.item.device)" variant="ghost" size="sm">删除</UiButton>
    </div>
  </ModalDialog>

  <ModalDialog v-if="modal === 'action'" :title="(focused ? '' : '批量') + opLabel[form.action]" :busy="saving" @close="modal = null">
    <UiValidatedForm @submit="runOperation">
        <UiAlert v-if="modalError" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ modalError }}</UiAlertDescription></UiAlert>
        <p v-if="!focused">已选 {{ checkedRows.length }} 台设备。</p>
        <p v-if="form.action === 'SCRAP'">确认将所选设备标记为已报废。</p>
        <UiFormField v-if="form.action === 'ISSUE'" name="useSite" label="使用场所" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.useSite" maxlength="180" />
        </UiFormField>
        <UiFormField v-if="['MOVE', 'ISSUE', 'REPLACE'].includes(form.action)" name="storageLocation"
                     :label="form.action === 'REPLACE' ? '换下设备的入库地点' : '存放地点'"
                     :required="form.action === 'MOVE'" v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.storageLocation" maxlength="180" />
        </UiFormField>
        <UiFormField v-if="form.action === 'INSPECT'" name="inspectedOn" label="本次年检日期" required v-slot="{ input }">
          <UiDateInput v-bind="input" v-model="form.inspectionDate" />
        </UiFormField>
        <template v-if="form.action === 'REPLACE'">
          <div class="inline-field">
            <UiInput v-model.trim="replacementKeyword" placeholder="搜索同类库存设备" aria-label="搜索库存设备" maxlength="120" />
            <UiButton type="button"  @click="findReplacements" variant="outline">搜索</UiButton>
          </div>
          <UiFormField name="replacementId" label="替换设备" required v-slot="{ input }">
            <UiNativeSelect v-bind="input" v-model="form.replacementId">
              <UiNativeSelectOption :value="null" disabled>请选择</UiNativeSelectOption>
              <UiNativeSelectOption v-for="row in replacements" :key="row.id" :value="row.id">{{ row.code }} · {{ row.itemName }}</UiNativeSelectOption>
            </UiNativeSelect>
          </UiFormField>
        </template>
        <UiFormField name="remark" label="备注" v-slot="{ input }">
          <UiTextarea v-bind="input" v-model.trim="form.remark" maxlength="500" rows="2" />
        </UiFormField>
        <div class="form-actions"><UiButton :disabled="saving">确认{{ opLabel[form.action] }}</UiButton></div>
    </UiValidatedForm>
  </ModalDialog>

  <ModalDialog v-if="modal === 'import'" title="Excel 批量导入" :busy="saving" @close="modal = null">
    <UiValidatedForm @submit="importDevices">
        <UiAlert v-if="modalError" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ modalError }}</UiAlertDescription></UiAlert>
        <p>按模板填写设备字段和单位编号，导入的设备会进入库存。</p>
        <UiButton type="button"  @click="saveFile('/api/fire/devices/template', '消防设备导入模板.xlsx')" variant="outline">下载模板</UiButton>
        <UiFormField name="file" label="设备台账 Excel" required v-slot="{ input }">
          <input v-bind="input" type="file" accept=".xlsx" class="file-input" @change="importFile = $event.target.files[0]">
        </UiFormField>
        <div class="form-actions"><UiButton :disabled="saving">导入</UiButton></div>
    </UiValidatedForm>
  </ModalDialog>
</template>
