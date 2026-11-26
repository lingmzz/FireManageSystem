<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ChevronRightIcon, PlusIcon } from '@lucide/vue'
import { api, dateTime } from '../api'
import ModalDialog from '../components/ModalDialog.vue'

const type = ref('WORKBENCH')
const types = ref([])
const configurations = reactive({})
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const showing = ref('')
const settings = computed(() => configurations[type.value])
const draft = computed(() => settings.value?.draft)
const step = computed(() => draft.value?.steps.find(option => option.key === settings.value.selected))
const alternatives = computed(() => draft.value?.steps.filter(option => option.key !== step.value?.key) ?? [])
const path = computed(() => route(draft.value))

function route(definition) {
  if (!definition) return []
  const byKey = new Map(definition.steps.map(option => [option.key, option]))
  const seen = new Set()
  const ordered = []
  for (let current = byKey.get(definition.startKey); current && !seen.has(current.key); current = byKey.get(current.passTo)) {
    ordered.push(current)
    seen.add(current.key)
  }
  return ordered
}

function label(option) {
  return option?.name?.trim() || '未命名步骤'
}

function accept(key, response, localDraft = response.draft) {
  const selected = configurations[key]?.selected
  configurations[key] = {
    ...response, draft: localDraft,
    selected: localDraft.steps.some(option => option.key === selected) ? selected : localDraft.startKey ?? localDraft.steps[0]?.key
  }
  types.value = response.types
}

async function load(key = type.value, reload = false) {
  if (loading.value || saving.value) return
  type.value = key
  error.value = ''
  notice.value = ''
  if (!reload && configurations[key]) return
  loading.value = true
  try {
    accept(key, await api('/api/approval-flows/' + key))
  } catch (failure) {
    error.value = failure.message
  } finally {
    loading.value = false
  }
}

onMounted(() => load())

function addStep() {
  const steps = draft.value.steps
  const preceding = step.value ?? steps.at(-1)
  const key = crypto.randomUUID().replaceAll('-', '').slice(0, 16)
  const added = {
    key, name: '', roleId: null, unitScope: settings.value.scopes[0].value,
    assigneeOnly: false, passLabel: '通过', remarkRequired: false, passTo: preceding?.passTo ?? null, returnTo: null
  }
  if (preceding) {
    steps.splice(steps.indexOf(preceding) + 1, 0, added)
    preceding.passTo = key
  } else {
    steps.push(added)
  }
  draft.value.startKey ??= key
  settings.value.selected = key
}

function removeStep() {
  const removed = step.value
  const remaining = draft.value.steps.filter(option => option.key !== removed.key)
  for (const option of remaining) {
    if (option.passTo === removed.key) option.passTo = removed.passTo === option.key ? null : removed.passTo
    if (option.returnTo === removed.key) option.returnTo = null
  }
  draft.value.steps = remaining
  if (draft.value.startKey === removed.key) {
    draft.value.startKey = remaining.find(option => option.key === removed.passTo)?.key ?? remaining[0]?.key ?? null
  }
  settings.value.selected = draft.value.startKey ?? remaining[0]?.key
}

function useExample() {
  const example = settings.value.example
  settings.value.draft = { startKey: example.startKey, steps: example.steps.map(option => ({ ...option })) }
  settings.value.selected = example.startKey
}

async function update(operation, report) {
  if (saving.value) return
  const key = type.value
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const revision = settings.value.revision
    const body = operation === 'built-in' ? { revision } : { revision, draft: draft.value }
    const response = await api('/api/approval-flows/' + key + '/' + operation,
      operation === 'publish' ? 'POST' : 'PUT', body)
    accept(key, response, operation === 'built-in' ? draft.value : response.draft)
    showing.value = ''
    notice.value = operation === 'publish' ? '第 ' + response.published.version + ' 版已发布，新单开始使用此版本。'
      : operation === 'draft' ? '草稿已保存。' : '新单已切回系统内置流程。'
  } catch (failure) {
    if (report && failure.fields) {
      const field = Object.keys(failure.fields ?? {}).find(name => name.startsWith('step.'))
      const failedStep = field?.split('.')[1]
      if (failedStep && draft.value.steps.some(option => option.key === failedStep)) {
        settings.value.selected = failedStep
        await nextTick()
      }
      report(failure)
    } else {
      error.value = failure.message
      showing.value = ''
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page-heading">
    <h1>审批流程配置</h1>
  </div>

  <UiCard class="panel">
    <UiCardContent class="px-0">
      <UiTabs v-if="types.length" class="page-tabs" :model-value="type" @update:model-value="load">
        <UiTabsList variant="line" aria-label="配置审批类型">
          <UiTabsTrigger v-for="option in types" :key="option.value" :value="option.value"
                         :disabled="loading || saving">{{ option.label }}</UiTabsTrigger>
        </UiTabsList>
      </UiTabs>
      <div class="workflow-config">
        <UiAlert v-if="error" role="alert" variant="destructive">
          <UiAlertDescription>{{ error }}</UiAlertDescription>
        </UiAlert>
        <p v-if="loading" class="subtle" role="status">正在加载流程…</p>
        <template v-else-if="settings">
          <div class="workflow-config-header">
            <div class="flex flex-wrap items-center gap-2">
              <UiBadge :variant="settings.published ? 'ok' : 'secondary'">
                {{ settings.published ? '生效 · 第 ' + settings.published.version + ' 版' : '系统内置流程' }}
              </UiBadge>
              <span v-if="settings.published" class="text-xs text-muted-foreground">
                {{ dateTime(settings.published.at) }} · {{ settings.published.by }}
              </span>
            </div>
            <div class="fire-actions">
              <UiButton v-if="settings.published" type="button" variant="outline" size="sm"
                        :disabled="saving" @click="showing = 'published'">查看生效版本</UiButton>
              <UiButton v-if="settings.published" type="button" variant="ghost" size="sm"
                        :disabled="saving" @click="showing = 'restore'">恢复系统流程</UiButton>
              <UiButton v-if="error" type="button" variant="ghost" size="sm" :disabled="saving"
                        @click="load(type, true)">重新加载</UiButton>
            </div>
          </div>
          <UiAlert v-if="notice" role="status"><UiAlertDescription>{{ notice }}</UiAlertDescription></UiAlert>
          <UiValidatedForm :key="type + ':' + settings.revision" @submit="report => update('publish', report)">
            <UiFieldGroup layout="grid">
              <UiFormField name="startKey" label="首个办理步骤" required v-slot="{ input }">
                <UiNativeSelect v-bind="input" v-model="draft.startKey" :disabled="saving">
                  <UiNativeSelectOption :value="null" disabled>请选择</UiNativeSelectOption>
                  <UiNativeSelectOption v-for="option in draft.steps" :key="option.key" :value="option.key">
                    {{ label(option) }}
                  </UiNativeSelectOption>
                </UiNativeSelect>
              </UiFormField>
              <div class="workflow-path" aria-label="通过路径">
                <span class="text-sm font-medium">通过路径</span>
                <div v-if="path.length" class="fire-actions">
                  <template v-for="(option, index) in path" :key="option.key">
                    <ChevronRightIcon v-if="index" class="size-3.5 text-muted-foreground" aria-hidden="true" />
                    <UiBadge variant="secondary" class="max-w-full whitespace-normal">{{ label(option) }}</UiBadge>
                  </template>
                  <template v-if="!path.at(-1).passTo">
                    <ChevronRightIcon class="size-3.5 text-muted-foreground" aria-hidden="true" />
                    <UiBadge variant="ok">办结</UiBadge>
                  </template>
                </div>
                <span v-else class="text-xs text-muted-foreground">请选择首个步骤</span>
              </div>
            </UiFieldGroup>
            <div class="workflow-builder">
              <section class="workflow-steps" aria-label="办理步骤">
                <div class="workflow-steps-heading">
                  <h2 class="text-sm font-semibold">办理步骤</h2>
                  <UiButton type="button" size="sm" variant="ghost" :disabled="saving || draft.steps.length >= 20"
                            @click="addStep"><PlusIcon data-icon="inline-start" />添加</UiButton>
                </div>
                <ul class="workflow-step-list">
                  <li v-for="option in draft.steps" :key="option.key">
                    <button type="button" class="workflow-step-button" :aria-pressed="step?.key === option.key"
                            :disabled="saving" @click="settings.selected = option.key">
                      <span class="min-w-0 truncate font-medium">{{ label(option) }}</span>
                      <UiBadge v-if="option.key === draft.startKey" variant="outline" class="ml-auto">首步</UiBadge>
                    </button>
                  </li>
                </ul>
                <p v-if="!draft.steps.length" class="px-3 pb-3 text-sm text-muted-foreground">添加一个办理步骤</p>
              </section>
              <section v-if="step" :key="step.key" class="workflow-step-editor" aria-label="步骤设置">
                <UiFieldGroup layout="grid">
                  <UiFormField :name="'step.' + step.key + '.name'" label="步骤名称" required v-slot="{ input }">
                    <UiInput v-bind="input" v-model.trim="step.name" maxlength="80" :disabled="saving" />
                  </UiFormField>
                  <UiFormField :name="'step.' + step.key + '.roleId'" label="办理角色" required v-slot="{ input }">
                    <UiNativeSelect v-bind="input" v-model="step.roleId" :disabled="saving">
                      <UiNativeSelectOption :value="null" disabled>请选择</UiNativeSelectOption>
                      <UiNativeSelectOption v-for="role in settings.roles" :key="role.id" :value="role.id">
                        {{ role.name }}
                      </UiNativeSelectOption>
                    </UiNativeSelect>
                  </UiFormField>
                  <UiFormField v-if="settings.scopes.length > 1" :name="'step.' + step.key + '.unitScope'"
                               label="单位范围" required v-slot="{ input }">
                    <UiNativeSelect v-bind="input" v-model="step.unitScope" :disabled="saving">
                      <UiNativeSelectOption v-for="option in settings.scopes" :key="option.value" :value="option.value">
                        {{ option.label }}
                      </UiNativeSelectOption>
                    </UiNativeSelect>
                  </UiFormField>
                  <UiFormField :name="'step.' + step.key + '.passLabel'" label="通过按钮文字" v-slot="{ input }">
                    <UiInput v-bind="input" v-model.trim="step.passLabel" maxlength="30" placeholder="通过" :disabled="saving" />
                  </UiFormField>
                  <UiFormField :name="'step.' + step.key + '.passTo'" label="通过后去向" v-slot="{ input }">
                    <UiNativeSelect v-bind="input" v-model="step.passTo" :disabled="saving">
                      <UiNativeSelectOption :value="null">办结</UiNativeSelectOption>
                      <UiNativeSelectOption v-for="option in alternatives" :key="option.key" :value="option.key">
                        {{ label(option) }}
                      </UiNativeSelectOption>
                    </UiNativeSelect>
                  </UiFormField>
                  <UiFormField :name="'step.' + step.key + '.returnTo'" label="退回后去向" v-slot="{ input }">
                    <UiNativeSelect v-bind="input" v-model="step.returnTo" :disabled="saving">
                      <UiNativeSelectOption :value="null">不允许退回</UiNativeSelectOption>
                      <UiNativeSelectOption v-for="option in alternatives" :key="option.key" :value="option.key">
                        {{ label(option) }}
                      </UiNativeSelectOption>
                    </UiNativeSelect>
                  </UiFormField>
                </UiFieldGroup>
                <UiFormField :name="'step.' + step.key + '.assigneeOnly'" label="办理要求" group v-slot="{ clear }">
                  <UiFieldLabel class="option">
                    <UiCheckbox v-model="step.remarkRequired" :disabled="saving" />通过时填写办理说明
                  </UiFieldLabel>
                  <UiFieldLabel v-if="type === 'WORKBENCH'" class="option">
                    <UiCheckbox v-model="step.assigneeOnly" :disabled="saving" @update:model-value="clear" />
                    由上一步指定办理人
                  </UiFieldLabel>
                </UiFormField>
                <div>
                  <UiButton type="button" variant="ghost" size="sm" class="text-destructive"
                            :disabled="saving" @click="removeStep">移除该步骤</UiButton>
                </div>
              </section>
            </div>
            <div class="workflow-config-footer">
              <p class="text-sm text-muted-foreground">发布后，新建单据使用本版流程。</p>
              <div class="fire-actions">
                <UiButton type="button" variant="ghost" :disabled="saving" @click="useExample">载入系统步骤</UiButton>
                <UiButton type="button" variant="outline" :disabled="saving" @click="update('draft')">保存草稿</UiButton>
                <UiButton type="submit" :disabled="saving">发布流程</UiButton>
              </div>
            </div>
          </UiValidatedForm>
        </template>
        <UiButton v-else variant="outline" @click="load()">重新加载</UiButton>
      </div>
    </UiCardContent>
  </UiCard>

  <ModalDialog v-if="showing === 'published'" :title="'生效流程 · 第 ' + settings.published.version + ' 版'" wide @close="showing = ''">
    <ol class="grid gap-2">
      <li v-for="option in route(settings.published.definition)" :key="option.key" class="rounded-md border px-3 py-2">
        <div class="font-medium">{{ label(option) }}</div>
        <p class="mt-1 text-xs text-muted-foreground">
          {{ settings.roles.find(role => role.id === option.roleId)?.name ?? '角色编号 ' + option.roleId }}
          · {{ settings.scopes.find(scope => scope.value === option.unitScope)?.label }}
          <template v-if="option.returnTo">
            · 退回至{{ label(settings.published.definition.steps.find(step => step.key === option.returnTo)) }}
          </template>
        </p>
      </li>
    </ol>
  </ModalDialog>
  <ModalDialog v-if="showing === 'restore'" title="恢复系统流程" :busy="saving" @close="showing = ''">
    <p>恢复后，新建单据使用系统内置流程。已经受理的单据会按各自版本继续办理。</p>
    <div class="form-actions">
      <UiButton type="button" variant="outline" :disabled="saving" @click="showing = ''">取消</UiButton>
      <UiButton type="button" :disabled="saving" @click="update('built-in')">恢复系统流程</UiButton>
    </div>
  </ModalDialog>
</template>
