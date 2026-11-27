<script setup>
import { computed, onMounted, ref } from 'vue'
import { MonitorIcon } from '@lucide/vue'
import { api, dateTime, pageSize, session } from '../api'
import ModalDialog from '../components/ModalDialog.vue'
import PageControls from '../components/PageControls.vue'
import WorkflowActionForm from '../components/WorkflowActionForm.vue'

const views = [
  ['todo', '我的待办'],
  ['mine', '我发起的'],
  ['all', '全部可见']
]
const statuses = {
  PENDING_ASSIGNMENT: ['待指派', 'info'],
  PENDING_RECTIFICATION: ['待整改', 'attention'],
  PENDING_REVIEW: ['待复核', 'info'],
  IN_WORKFLOW: ['流程办理中', 'info'],
  CLOSED: ['已销项', 'ok']
}
const actionLabels = {
  CREATE: '上报隐患', ASSIGN: '指派整改', SUBMIT_RECTIFICATION: '提交整改',
  APPROVE: '复核通过', RETURN: '退回整改'
}
const view = ref('todo')
const filter = ref('')
const keyword = ref('')
const page = ref(1)
const data = ref({ items: [], total: 0 })
const loading = ref(true)
const error = ref('')
const saving = ref(false)
const dialogError = ref('')
const model = ref(null)
const record = ref(null)
const editor = ref({})
const assignees = ref([])
const assigneeSearch = ref('')
const canReport = computed(() => session.user?.permissions.includes('approvals:create'))
const canOpenDashboard = computed(() => session.user?.permissions.includes('fire:dashboard'))

async function load(next = 1) {
  loading.value = true
  error.value = ''
  page.value = next
  try {
    const params = new URLSearchParams({ view: view.value, keyword: keyword.value, page: next, size: pageSize })
    if (filter.value) params.set('status', filter.value)
    data.value = await api('/api/approvals?' + params)
  } catch (failure) {
    error.value = failure.message
  } finally {
    loading.value = false
  }
}

onMounted(() => load())

function changeView(next) {
  view.value = next
  filter.value = ''
  load()
}

function create() {
  editor.value = { title: '', location: '', description: '' }
  dialogError.value = ''
  model.value = 'create'
}

async function open(id) {
  error.value = ''
  dialogError.value = ''
  try {
    record.value = await api('/api/approvals/' + id)
    editor.value = { action: record.value.actions[0] ?? '', assigneeId: null, remark: '' }
    assigneeSearch.value = ''
    assignees.value = []
    model.value = 'detail'
    if (editor.value.action === 'ASSIGN') await findAssignees()
  } catch (failure) {
    error.value = failure.message
  }
}

async function findAssignees() {
  try {
    assignees.value = await api('/api/approvals/assignees?' + new URLSearchParams({ keyword: assigneeSearch.value }))
  } catch (failure) {
    dialogError.value = failure.message
  }
}

async function submit(report) {
  saving.value = true
  dialogError.value = ''
  try {
    if (model.value === 'create') {
      await api('/api/approvals', 'POST', editor.value)
      view.value = 'mine'
      filter.value = ''
      keyword.value = ''
      model.value = null
      await load()
    } else {
      const item = record.value.approval
      await api('/api/approvals/' + item.id + '/actions', 'POST', {
        ...editor.value, version: item.version
      })
      await open(item.id)
      await load(page.value)
    }
  } catch (failure) {
    report(failure)
  } finally {
    saving.value = false
  }
}

async function reloadWorkflow() {
  await open(record.value.approval.id)
  await load(page.value)
}
</script>

<template>
  <div class="page-heading">
    <h1>工作台</h1>
    <div class="flex flex-wrap items-center gap-2">
      <UiButton v-if="canOpenDashboard" as-child variant="outline">
        <a href="#/fire-dashboard">
          <MonitorIcon data-icon="inline-start" aria-hidden="true" />
          消防大屏
        </a>
      </UiButton>
      <UiButton v-if="canReport" @click="create">上报隐患</UiButton>
    </div>
  </div>
  <UiCard class="panel">
    <UiCardContent class="table-panel-content px-0">
      <UiTabs :model-value="view" class="page-tabs" @update:model-value="changeView">
        <UiTabsList aria-label="审批列表" variant="line">
          <UiTabsTrigger v-for="[key, label] in views" :key="key" :value="key" :disabled="loading">
            {{ label }}
          </UiTabsTrigger>
        </UiTabsList>
      </UiTabs>
      <form class="toolbar" @submit.prevent="load()">
        <UiNativeSelect :model-value="filter" aria-label="按审批状态筛选"
                        @update:model-value="value => { filter = value; load() }">
          <UiNativeSelectOption value="">全部状态</UiNativeSelectOption>
          <UiNativeSelectOption v-for="([label], value) in statuses" :key="value" :value="value">{{ label }}</UiNativeSelectOption>
        </UiNativeSelect>
        <UiInput v-model.trim="keyword" placeholder="搜索隐患或场所" aria-label="搜索隐患或场所" maxlength="120" />
        <UiButton :disabled="loading" variant="outline">搜索</UiButton>
      </form>
      <UiAlert v-if="error" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ error }}</UiAlertDescription></UiAlert>
      <div class="table-wrap">
        <UiTable>
          <UiTableHeader><UiTableRow><UiTableHead>隐患</UiTableHead><UiTableHead>场所</UiTableHead><UiTableHead>状态</UiTableHead><UiTableHead>办理人</UiTableHead><UiTableHead>更新时间</UiTableHead><UiTableHead>操作</UiTableHead></UiTableRow></UiTableHeader>
          <UiTableBody>
            <UiTableRow v-for="item in data.items" :key="item.approval.id">
              <UiTableCell class="strong">{{ item.approval.title }}</UiTableCell>
              <UiTableCell>{{ item.approval.location }}</UiTableCell>
              <UiTableCell>
                <UiBadge :variant="statuses[item.approval.status]?.[1]">
                  {{ item.flow?.stepName ?? statuses[item.approval.status]?.[0] }}
                </UiBadge>
              </UiTableCell>
              <UiTableCell>{{ item.approval.assigneeName || '—' }}</UiTableCell>
              <UiTableCell class="nowrap">{{ dateTime(item.approval.updatedAt) }}</UiTableCell>
              <UiTableCell><UiButton @click="open(item.approval.id)" variant="ghost" size="sm">{{ item.actions.length || item.flow?.canAct ? '处理' : '查看' }}</UiButton></UiTableCell>
            </UiTableRow>
            <UiTableRow v-if="!data.items.length">
              <UiTableCell colspan="6" class="empty-cell">
                <UiEmpty class="empty-state"><UiEmptyHeader><UiEmptyTitle>{{ loading ? '正在加载…' : view === 'todo' ? '暂无待办' : '暂无符合条件的记录' }}</UiEmptyTitle></UiEmptyHeader></UiEmpty>
              </UiTableCell>
            </UiTableRow>
          </UiTableBody>
        </UiTable>
      </div>
      <PageControls :page="page" :total="data.total" :busy="loading" @change="load" />
    </UiCardContent>
  </UiCard>

  <ModalDialog v-if="model === 'create'" title="上报隐患" wide :busy="saving" @close="model = null">
    <UiValidatedForm @submit="submit">
      <UiFieldGroup layout="grid">
        <UiFormField name="title" label="隐患标题" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="editor.title" maxlength="120" />
        </UiFormField>
        <UiFormField name="location" label="场所或位置" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="editor.location" maxlength="120" />
        </UiFormField>
        <UiFormField name="description" label="隐患说明" required class="form-span" v-slot="{ input }">
          <UiTextarea v-bind="input" v-model.trim="editor.description" rows="4" maxlength="3000" />
        </UiFormField>
      </UiFieldGroup>
      <div class="form-actions">
        <UiButton type="button" :disabled="saving" @click="model = null" variant="outline">取消</UiButton>
        <UiButton type="submit" :disabled="saving">提交上报</UiButton>
      </div>
    </UiValidatedForm>
  </ModalDialog>

  <ModalDialog v-if="model === 'detail' && record" :title="record.approval.title" :busy="saving" wide
               @close="model = null">
    <dl class="detail-meta">
      <div><dt>场所</dt><dd>{{ record.approval.location }}</dd></div>
      <div><dt>状态</dt><dd>
        <UiBadge :variant="statuses[record.approval.status]?.[1]">{{ record.flow?.stepName ?? statuses[record.approval.status]?.[0] }}</UiBadge>
      </dd></div>
      <div><dt>上报人</dt><dd>{{ record.approval.reporterName }}</dd></div>
      <div><dt>办理人</dt><dd>{{ record.approval.assigneeName || '—' }}</dd></div>
      <div v-if="record.flow"><dt>流程版本</dt><dd>第 {{ record.flow.version }} 版</dd></div>
      <div v-if="record.flow?.roleName"><dt>当前办理角色</dt><dd>{{ record.flow.roleName }}</dd></div>
    </dl>
    <p class="detail-description">{{ record.approval.description }}</p>
    <section class="history">
      <h3>处理记录</h3>
      <ol>
        <li v-for="(event, position) in record.history" :key="position">
          <div class="event-meta"><strong>{{ event.actionLabel || actionLabels[event.action] }}</strong>
            <span>{{ event.actorName }} · {{ dateTime(event.createdAt) }}</span></div>
          <p v-if="event.toStep" class="subtle">{{ event.fromStep ? event.fromStep + ' → ' : '' }}{{ event.toStep }}</p>
          <p v-if="event.remark">{{ event.remark }}</p>
        </li>
      </ol>
    </section>
    <WorkflowActionForm v-if="record.flow?.canAct" :key="'flow-' + record.approval.version"
                        :id="record.approval.id" :version="record.approval.version" :flow="record.flow"
                        v-model:busy="saving" @saved="reloadWorkflow" />
    <UiValidatedForm v-else-if="record.actions.length" :key="record.approval.version" class="action-box" @submit="submit">
      <UiAlert v-if="dialogError" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ dialogError }}</UiAlertDescription></UiAlert>
      <UiFieldGroup layout="grid">
        <UiFormField name="action" label="办理操作" :class="{ 'form-span': editor.action !== 'ASSIGN' }" v-slot="{ input }">
          <UiNativeSelect v-bind="input" :model-value="editor.action"
                          @update:model-value="value => { editor.action = value; if (value === 'ASSIGN') findAssignees() }">
            <UiNativeSelectOption v-for="action in record.actions" :key="action" :value="action">{{ actionLabels[action] }}</UiNativeSelectOption>
          </UiNativeSelect>
        </UiFormField>
        <UiFormField v-if="editor.action === 'ASSIGN'" name="assigneeId" label="办理人" required v-slot="{ input }">
          <UiNativeSelect v-bind="input" v-model="editor.assigneeId">
            <UiNativeSelectOption :value="null" disabled>请选择办理人</UiNativeSelectOption>
            <UiNativeSelectOption v-for="user in assignees" :key="user.id" :value="user.id">{{ user.displayName }}（{{ user.username }}）</UiNativeSelectOption>
          </UiNativeSelect>
          <div class="inline-field">
            <UiInput v-model.trim="assigneeSearch" placeholder="搜索办理人" aria-label="搜索办理人" maxlength="80" />
            <UiButton type="button" @click="findAssignees" variant="outline">搜索</UiButton>
          </div>
        </UiFormField>
        <UiFormField name="remark" class="form-span"
                     :label="editor.action === 'RETURN' ? '退回原因' : editor.action === 'SUBMIT_RECTIFICATION' ? '整改说明' : '备注'"
                     :required="editor.action === 'RETURN' || editor.action === 'SUBMIT_RECTIFICATION'" v-slot="{ input }">
          <UiTextarea v-bind="input" v-model.trim="editor.remark" rows="3" maxlength="3000" />
        </UiFormField>
      </UiFieldGroup>
      <div class="form-actions"><UiButton type="submit" :disabled="saving">提交办理</UiButton></div>
    </UiValidatedForm>
  </ModalDialog>
</template>
