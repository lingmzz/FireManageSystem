<script setup>
import { onMounted, ref } from 'vue'
import { api, dateTime, pageSize, session } from '../api'
import { params, unitOptions } from '../fire'
import ModalDialog from '../components/ModalDialog.vue'
import PageControls from '../components/PageControls.vue'
import WorkflowActionForm from '../components/WorkflowActionForm.vue'

const statuses = {
  PENDING_RECTIFICATION: ['待整改上报', 'attention'],
  PENDING_DEPARTMENT_REVIEW: ['待科室确认', 'info'],
  PENDING_SECTION_REVIEW: ['待段级销号', 'info'],
  IN_WORKFLOW: ['流程办理中', 'info'],
  CLOSED: ['已销号', 'ok']
}
const actionLabels = {
  CREATE: '录入隐患', REPORT_CLOSE: '整改销号上报', CONFIRM_CLOSE: '科室确认',
  FINAL_CLOSE: '段级总销号', RETURN: '退回整改'
}
const units = ref([])
const filters = ref({ unitId: '', status: '', keyword: '' })
const result = ref({ items: [], total: 0 })
const busy = ref(false)
const saving = ref(false)
const error = ref('')
const page = ref(1)
const form = ref(null)
const details = ref(null)
const action = ref('')
const remark = ref('')

onMounted(async () => {
  try { units.value = unitOptions(await api('/api/fire/units')) }
  catch (failure) { error.value = failure.message }
  await load()
})

async function load(next = 1) {
  error.value = ''
  busy.value = true
  page.value = next
  try {
    result.value = await api('/api/fire/hazards?' + params({ ...filters.value, page: next, size: pageSize }))
  } catch (failure) { error.value = failure.message }
  finally { busy.value = false }
}

function create() {
  form.value = { unitId: units.value.length === 1 ? units.value[0].id : null, title: '', location: '', description: '' }
}

async function save(report) {
  saving.value = true
  try {
    const result = await api('/api/fire/hazards', 'POST', form.value)
    form.value = null
    await load()
    await show(result.id)
  } catch (failure) { report(failure) }
  finally { saving.value = false }
}

async function show(id) {
  try {
    details.value = await api('/api/fire/hazards/' + id)
    action.value = details.value.item.actions[0] ?? ''
    remark.value = ''
  } catch (failure) { error.value = failure.message }
}

async function submit(report) {
  saving.value = true
  const { id, version } = details.value.item.hazard
  try {
    await api('/api/fire/hazards/' + id + '/actions', 'POST', { action: action.value, version, remark: remark.value })
    await show(id)
    await load(page.value)
  } catch (failure) { report(failure) }
  finally { saving.value = false }
}

async function reloadWorkflow() {
  await show(details.value.item.hazard.id)
  await load(page.value)
}
</script>

<template>
  <div class="page-heading">
    <h1>隐患问题库</h1>
    <UiButton v-if="session.user.permissions.includes('fire:hazard-add')"  @click="create">录入隐患</UiButton>
  </div>
  <UiCard class="panel">
    <UiCardContent class="table-panel-content px-0">
      <form class="toolbar fire-filters" @submit.prevent="load()">
        <UiNativeSelect :model-value="filters.unitId" aria-label="按隐患单位筛选"
                        @update:model-value="value => { filters.unitId = value; load() }">
          <UiNativeSelectOption value="">全部单位</UiNativeSelectOption>
          <UiNativeSelectOption v-for="unit in units" :key="unit.id" :value="unit.id">{{ unit.label }}</UiNativeSelectOption>
        </UiNativeSelect>
        <UiNativeSelect :model-value="filters.status" aria-label="按销号状态筛选"
                        @update:model-value="value => { filters.status = value; load() }">
          <UiNativeSelectOption value="">全部状态</UiNativeSelectOption>
          <UiNativeSelectOption v-for="([label], key) in statuses" :key="key" :value="key">{{ label }}</UiNativeSelectOption>
        </UiNativeSelect>
        <UiInput v-model.trim="filters.keyword" placeholder="搜索隐患标题或地点" maxlength="120" aria-label="搜索隐患" />
        <UiButton :disabled="busy" variant="outline">查询</UiButton>
      </form>
      <UiAlert v-if="error" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ error }}</UiAlertDescription></UiAlert>
      <div class="table-wrap">
        <UiTable>
          <UiTableHeader><UiTableRow><UiTableHead>隐患</UiTableHead><UiTableHead>所属单位</UiTableHead><UiTableHead>地点</UiTableHead><UiTableHead>录入人</UiTableHead><UiTableHead>销号状态</UiTableHead><UiTableHead>更新时间</UiTableHead><UiTableHead>操作</UiTableHead></UiTableRow></UiTableHeader>
          <UiTableBody>
            <UiTableRow v-for="item in result.items" :key="item.hazard.id">
              <UiTableCell class="strong">{{ item.hazard.title }}</UiTableCell><UiTableCell>{{ item.hazard.unitName }}</UiTableCell><UiTableCell>{{ item.hazard.location }}</UiTableCell>
              <UiTableCell>{{ item.hazard.reporterName }}</UiTableCell>
              <UiTableCell><UiBadge :variant="statuses[item.hazard.status]?.[1]">{{ item.flow?.stepName ?? statuses[item.hazard.status]?.[0] }}</UiBadge></UiTableCell>
              <UiTableCell class="nowrap">{{ dateTime(item.hazard.updatedAt) }}</UiTableCell>
              <UiTableCell><UiButton @click="show(item.hazard.id)" variant="ghost" size="sm">{{ item.actions.length || item.flow?.canAct ? '办理' : '详情' }}</UiButton></UiTableCell>
            </UiTableRow>
            <UiTableRow v-if="!result.items.length"><UiTableCell colspan="7" class="empty-cell">
              <UiEmpty class="empty-state"><UiEmptyHeader><UiEmptyTitle>{{ busy ? '正在加载…' : '暂无隐患记录' }}</UiEmptyTitle></UiEmptyHeader></UiEmpty>
            </UiTableCell></UiTableRow>
          </UiTableBody>
        </UiTable>
      </div>
      <PageControls :page="page" :total="result.total" :busy="busy" @change="load" />
    </UiCardContent>
  </UiCard>

  <ModalDialog v-if="form" title="录入消防隐患" wide :busy="saving" @close="form = null">
    <UiValidatedForm @submit="save">
      <UiFieldGroup layout="grid">
        <UiFormField name="title" label="隐患标题" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.title" maxlength="120" />
        </UiFormField>
        <UiFormField name="unitId" label="所属单位" required v-slot="{ input }">
          <UiNativeSelect v-bind="input" v-model="form.unitId">
            <UiNativeSelectOption :value="null" disabled>请选择</UiNativeSelectOption>
            <UiNativeSelectOption v-for="unit in units" :key="unit.id" :value="unit.id">{{ unit.label }}</UiNativeSelectOption>
          </UiNativeSelect>
        </UiFormField>
        <UiFormField name="location" label="隐患地点" required class="form-span" v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.location" maxlength="120" />
        </UiFormField>
        <UiFormField name="description" label="问题描述" required class="form-span" v-slot="{ input }">
          <UiTextarea v-bind="input" v-model.trim="form.description" maxlength="3000" rows="3" />
        </UiFormField>
      </UiFieldGroup>
      <div class="form-actions">
        <UiButton type="button" variant="outline" :disabled="saving" @click="form = null">取消</UiButton>
        <UiButton type="submit" :disabled="saving">录入</UiButton>
      </div>
    </UiValidatedForm>
  </ModalDialog>

  <ModalDialog v-if="details" :title="details.item.hazard.title" wide :busy="saving" @close="details = null">
    <dl class="detail-meta">
      <div><dt>单位</dt><dd>{{ details.item.hazard.unitName }}</dd></div>
      <div><dt>地点</dt><dd>{{ details.item.hazard.location }}</dd></div>
      <div><dt>状态</dt><dd>{{ details.item.flow?.stepName ?? statuses[details.item.hazard.status]?.[0] }}</dd></div>
      <div><dt>录入人</dt><dd>{{ details.item.hazard.reporterName }}</dd></div>
      <div v-if="details.item.flow"><dt>流程版本</dt><dd>第 {{ details.item.flow.version }} 版</dd></div>
      <div v-if="details.item.flow?.roleName"><dt>当前办理角色</dt><dd>{{ details.item.flow.roleName }}</dd></div>
    </dl>
    <p class="detail-description">{{ details.item.hazard.description }}</p>
    <section class="history">
      <h3>整改销号记录</h3>
      <ol>
        <li v-for="(event, index) in details.history" :key="index">
          <div class="event-meta"><strong>{{ event.actionLabel || actionLabels[event.action] }}</strong>
            <span>{{ event.actorName }} · {{ dateTime(event.createdAt) }}</span></div>
          <p v-if="event.toStep" class="subtle">{{ event.fromStep ? event.fromStep + ' → ' : '' }}{{ event.toStep }}</p>
          <p v-if="event.remark">{{ event.remark }}</p>
        </li>
      </ol>
    </section>
    <WorkflowActionForm v-if="details.item.flow?.canAct" :key="'flow-' + details.item.hazard.version"
                        :id="details.item.hazard.id" :version="details.item.hazard.version" :flow="details.item.flow"
                        v-model:busy="saving" @saved="reloadWorkflow" />
    <UiValidatedForm v-else-if="details.item.actions.length" :key="details.item.hazard.version" class="action-box" @submit="submit">
      <UiFormField name="action" label="办理动作" v-slot="{ input }">
        <UiNativeSelect v-bind="input" v-model="action">
          <UiNativeSelectOption v-for="option in details.item.actions" :key="option" :value="option">
            {{ actionLabels[option] }}
          </UiNativeSelectOption>
        </UiNativeSelect>
      </UiFormField>
      <UiFormField name="remark" :label="action === 'RETURN' ? '退回原因' : action === 'REPORT_CLOSE' ? '整改完成说明' : '办理意见'"
                   :required="action === 'RETURN' || action === 'REPORT_CLOSE'" v-slot="{ input }">
        <UiTextarea v-bind="input" v-model.trim="remark" maxlength="3000" rows="3" />
      </UiFormField>
      <div class="form-actions"><UiButton type="submit" :disabled="saving">确认办理</UiButton></div>
    </UiValidatedForm>
  </ModalDialog>
</template>
