<script setup>
import { confirmDeletion } from '../confirmation'
import { onMounted, ref } from 'vue'
import { api, dateTime, pageSize, session } from '../api'
import { download, params, unitOptions } from '../fire'
import ModalDialog from '../components/ModalDialog.vue'
import PageControls from '../components/PageControls.vue'
import { FieldError } from '@/components/ui/field'

const units = ref([])
const unitId = ref('')
const keyword = ref('')
const page = ref(1)
const result = ref({ items: [], total: 0 })
const busy = ref(false)
const saving = ref(false)
const error = ref('')
const editorError = ref('')
const form = ref(null)
const details = ref(null)
const summaryFile = ref(null)
const photos = ref([])
const uploadIssues = ref({})

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
    result.value = await api('/api/fire/drills?' + params({ unitId: unitId.value, keyword: keyword.value, page: next, size: pageSize }))
  } catch (failure) { error.value = failure.message }
  finally { busy.value = false }
}

function edit(drill = null) {
  details.value = null
  editorError.value = ''
  summaryFile.value = null
  photos.value = []
  form.value = drill ? { ...drill, drilledAt: drill.drilledAt.slice(0, 16) } : {
    unitId: units.value.length === 1 ? units.value[0].id : null, title: '', drilledAt: '', participantCount: 1, summary: ''
  }
}

async function show(id) {
  if (details.value?.item.drill.id !== id) {
    editorError.value = ''
    uploadIssues.value = {}
  }
  try { details.value = await api('/api/fire/drills/' + id) }
  catch (failure) { error.value = failure.message }
}

async function upload(id, kind, file) {
  const data = new FormData()
  data.append('kind', kind)
  data.append('file', file)
  await api('/api/fire/drills/' + id + '/files', 'POST', data)
}

function attachmentError(file) {
  if (file && (!file.size || file.size > 8 * 1024 * 1024)) return file.name + ' 的大小须在 8 MB 以内'
  return ''
}

async function save(report) {
  saving.value = true
  editorError.value = ''
  let id = form.value.id
  try {
    const { unitId, title, drilledAt, participantCount, summary, version } = form.value
    const pending = [...(summaryFile.value ? [{ kind: 'SUMMARY', file: summaryFile.value }] : []),
      ...photos.value.map(file => ({ kind: 'PHOTO', file }))]
    const response = await api('/api/fire/drills' + (id ? '/' + id : ''), id ? 'PUT' : 'POST',
      { unitId, title, drilledAt, participantCount, summary, version })
    id ||= response.id
    const issues = {}
    for (const { kind, file } of pending) {
      try {
        await upload(id, kind, file)
      } catch (failure) {
        const messages = issues[kind] ??= []
        messages.push(file.name + ' · ' + failure.message)
      }
    }
    form.value = null
    await load(page.value)
    if (pending.length) await show(id)
    if (Object.keys(issues).length) {
      uploadIssues.value = issues
      editorError.value = '演练记录已保存，附件上传结果见下方。'
    }
  } catch (failure) {
    report(failure)
  } finally { saving.value = false }
}

function filePath(attachment) {
  return '/api/fire/drills/' + details.value.item.drill.id + '/files/' + attachment.id
}

async function getFile(attachment) {
  try { await download(filePath(attachment), attachment.name) }
  catch (failure) { editorError.value = failure.message }
}

async function removeFile(attachment) {
  const confirmed = await confirmDeletion('删除演练附件「' + attachment.name + '」？')
  if (!confirmed) return
  try {
    await api(filePath(attachment), 'DELETE')
    await show(details.value.item.drill.id)
  } catch (failure) { editorError.value = failure.message }
}

async function appendFiles(event, kind) {
  const input = event.target
  saving.value = true
  editorError.value = ''
  delete uploadIssues.value[kind]
  const id = details.value.item.drill.id
  try {
    for (const file of input.files) {
      const error = attachmentError(file)
      if (error) throw new Error(error)
      await upload(id, kind, file)
    }
  } catch (failure) { uploadIssues.value[kind] = [failure.message] }
  finally {
    await show(id)
    input.value = ''
    saving.value = false
  }
}
</script>

<template>
  <div class="page-heading">
    <h1>应急演练台账</h1>
    <UiButton v-if="session.user.permissions.includes('fire:drill-write')"  @click="edit()">新增演练</UiButton>
  </div>
  <UiCard class="panel">
    <UiCardContent class="table-panel-content px-0">
      <form class="toolbar fire-filters" @submit.prevent="load()">
        <UiNativeSelect :model-value="unitId" aria-label="按演练单位筛选"
                        @update:model-value="value => { unitId = value; load() }">
          <UiNativeSelectOption value="">全部单位</UiNativeSelectOption>
          <UiNativeSelectOption v-for="unit in units" :key="unit.id" :value="unit.id">{{ unit.label }}</UiNativeSelectOption>
        </UiNativeSelect>
        <UiInput v-model.trim="keyword" placeholder="搜索演练名称" aria-label="搜索演练" maxlength="160" />
        <UiButton :disabled="busy" variant="outline">查询</UiButton>
      </form>
      <UiAlert v-if="error" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ error }}</UiAlertDescription></UiAlert>
      <div class="table-wrap">
        <UiTable>
          <UiTableHeader><UiTableRow><UiTableHead>演练名称</UiTableHead><UiTableHead>所属单位</UiTableHead><UiTableHead>演练时间</UiTableHead><UiTableHead>参加人数</UiTableHead><UiTableHead>操作</UiTableHead></UiTableRow></UiTableHeader>
          <UiTableBody>
            <UiTableRow v-for="item in result.items" :key="item.drill.id">
              <UiTableCell class="strong">{{ item.drill.title }}</UiTableCell><UiTableCell>{{ item.drill.unitName }}</UiTableCell>
              <UiTableCell class="nowrap">{{ dateTime(item.drill.drilledAt) }}</UiTableCell><UiTableCell>{{ item.drill.participantCount }}</UiTableCell>
              <UiTableCell class="row-actions">
                <UiButton @click="editorError = ''; show(item.drill.id)" variant="ghost" size="sm">详情</UiButton>
                <UiButton v-if="item.editable"  @click="edit(item.drill)" variant="ghost" size="sm">编辑</UiButton>
              </UiTableCell>
            </UiTableRow>
            <UiTableRow v-if="!result.items.length"><UiTableCell colspan="5" class="empty-cell">
              <UiEmpty class="empty-state"><UiEmptyHeader><UiEmptyTitle>{{ busy ? '正在加载…' : '暂无演练记录' }}</UiEmptyTitle></UiEmptyHeader></UiEmpty>
            </UiTableCell></UiTableRow>
          </UiTableBody>
        </UiTable>
      </div>
      <PageControls :page="page" :total="result.total" :busy="busy" @change="load" />
    </UiCardContent>
  </UiCard>
  <ModalDialog v-if="form" :title="form.id ? '编辑演练' : '新增演练'" wide :busy="saving" @close="form = null">
    <UiValidatedForm @submit="save">
      <UiFieldGroup layout="grid">
        <UiFormField name="unitId" label="所属单位" required v-slot="{ input }">
          <UiNativeSelect v-bind="input" v-model="form.unitId" :disabled="!!form.id">
            <UiNativeSelectOption :value="null" disabled>请选择</UiNativeSelectOption>
            <UiNativeSelectOption v-for="unit in units" :key="unit.id" :value="unit.id">{{ unit.label }}</UiNativeSelectOption>
          </UiNativeSelect>
        </UiFormField>
        <UiFormField name="title" label="演练名称" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="form.title" maxlength="160" />
        </UiFormField>
        <UiFormField name="drilledAt" label="演练时间" required v-slot="{ input }">
          <UiDateInput v-bind="input" v-model="form.drilledAt" with-time />
        </UiFormField>
        <UiFormField name="participantCount" label="参加人数" required v-slot="{ input }">
          <UiInput v-bind="input" v-model.number="form.participantCount" type="number" min="1" step="1" />
        </UiFormField>
        <UiFormField name="summary" label="演练总结" class="form-span" v-slot="{ input }">
          <UiTextarea v-bind="input" v-model.trim="form.summary" rows="4" maxlength="15000" />
        </UiFormField>
        <UiFormField name="summaryFile" label="总结附件" :check="() => attachmentError(summaryFile)" v-slot="{ input }">
          <input v-bind="input" type="file" accept=".doc,.docx,.pdf,.txt" class="file-input"
                 @change="summaryFile = $event.target.files[0]">
        </UiFormField>
        <UiFormField name="photos" label="演练图片" :check="() => photos.map(attachmentError).find(Boolean)" v-slot="{ input }">
          <input v-bind="input" type="file" multiple accept=".jpg,.jpeg,.png,.webp" class="file-input"
                 @change="photos = Array.from($event.target.files)">
        </UiFormField>
      </UiFieldGroup>
      <div class="form-actions">
        <UiButton type="button" variant="outline" :disabled="saving" @click="form = null">取消</UiButton>
        <UiButton type="submit" :disabled="saving">保存</UiButton>
      </div>
    </UiValidatedForm>
  </ModalDialog>
  <ModalDialog v-if="details" :title="details.item.drill.title" wide :busy="saving" @close="details = null">
    <UiAlert v-if="editorError" class="page-feedback" role="alert" variant="destructive"><UiAlertDescription>{{ editorError }}</UiAlertDescription></UiAlert>
    <dl class="detail-meta">
      <div><dt>所属单位</dt><dd>{{ details.item.drill.unitName }}</dd></div>
      <div><dt>演练时间</dt><dd>{{ dateTime(details.item.drill.drilledAt) }}</dd></div>
      <div><dt>参加人数</dt><dd>{{ details.item.drill.participantCount }} 人</dd></div>
    </dl>
    <p class="detail-description">{{ details.item.drill.summary || '暂无文字总结' }}</p>
    <section class="history">
      <h3>总结和演练图片</h3>
      <p v-if="!details.attachments.length" class="subtle">暂无附件</p>
      <div class="fire-attachments">
        <div v-for="file in details.attachments" :key="file.id" class="fire-attachment">
          <img v-if="file.kind === 'PHOTO'" :src="filePath(file)" :alt="file.name" loading="lazy">
          <div class="fire-actions">
            <UiButton @click="getFile(file)" variant="ghost" size="sm">{{ file.name }}</UiButton>
            <UiButton v-if="details.item.editable" class="text-destructive" :disabled="saving" @click="removeFile(file)" variant="ghost" size="sm">删除</UiButton>
          </div>
        </div>
      </div>
    </section>
    <UiFieldGroup v-if="details.item.editable" layout="grid">
      <UiField class="min-w-0 gap-1.5" :data-invalid="Boolean(uploadIssues.SUMMARY) || undefined">
        <UiFieldLabel for="fire-drills-page-field-8">补传总结</UiFieldLabel>
        <input type="file" :disabled="saving" accept=".doc,.docx,.pdf,.txt" @change="appendFiles($event, 'SUMMARY')"
               id="fire-drills-page-field-8" class="file-input" :aria-invalid="Boolean(uploadIssues.SUMMARY) || undefined"
               :aria-describedby="uploadIssues.SUMMARY ? 'drill-summary-error' : undefined">
        <FieldError id="drill-summary-error" :errors="uploadIssues.SUMMARY" class="text-xs leading-relaxed" />
      </UiField>
      <UiField class="min-w-0 gap-1.5" :data-invalid="Boolean(uploadIssues.PHOTO) || undefined">
        <UiFieldLabel for="fire-drills-page-field-9">补传图片</UiFieldLabel>
        <input type="file" :disabled="saving" multiple accept=".jpg,.jpeg,.png,.webp" @change="appendFiles($event, 'PHOTO')"
               id="fire-drills-page-field-9" class="file-input" :aria-invalid="Boolean(uploadIssues.PHOTO) || undefined"
               :aria-describedby="uploadIssues.PHOTO ? 'drill-photo-error' : undefined">
        <FieldError id="drill-photo-error" :errors="uploadIssues.PHOTO" class="text-xs leading-relaxed" />
      </UiField>
    </UiFieldGroup>
  </ModalDialog>
</template>
