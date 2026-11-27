<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  ActivityIcon, AlertTriangleIcon, ArrowLeftIcon, ArrowUpRightIcon, Building2Icon,
  CheckCircle2Icon, ClipboardCheckIcon, FlameIcon, HandshakeIcon, MaximizeIcon,
  MinimizeIcon, PhoneIcon, RefreshCwIcon, ShieldCheckIcon, SirenIcon
} from '@lucide/vue'
import { Skeleton } from '@/components/ui/skeleton'
import { api, dateTime, session } from '../api'
import { deviceTypes, noticeLabels, params, unitKinds, unitOptions } from '../fire'

defineProps({ homeHref: String, homeLabel: String, accountError: String, accountNotice: String })

const loading = ref(true)
const error = ref('')
const selectedUnitId = ref('')
const data = ref(null)
const fullscreen = ref(false)
const fullscreenPending = ref(false)
const fullscreenError = ref('')
const fullscreenUnavailable = '当前窗口无法切换网页全屏，可使用浏览器菜单里的全屏功能。'
let mounted = false

const donutPalette = [
  'var(--primary)',
  'var(--information)',
  'var(--warning)',
  'var(--success)',
  'oklch(0.74 0.12 297)',
  'oklch(0.71 0.11 215)',
  'var(--muted-foreground)'
]

async function load(unitId = selectedUnitId.value) {
  loading.value = true
  error.value = ''
  selectedUnitId.value = unitId == null ? '' : String(unitId)
  try {
    const query = params({ unitId: selectedUnitId.value })
    data.value = await api('/api/fire/dashboard' + (query.toString() ? '?' + query : ''))
  } catch (failure) {
    selectedUnitId.value = String(data.value?.selectedUnitId ?? '')
    error.value = failure.message
  } finally {
    loading.value = false
  }
}

function syncFullscreen() {
  fullscreen.value = document.fullscreenElement === document.documentElement
}

function exitDashboardFullscreen() {
  if (document.fullscreenElement === document.documentElement) {
    void document.exitFullscreen().catch(() => {})
  }
}

async function toggleFullscreen() {
  if (fullscreenPending.value) return
  fullscreenError.value = ''
  fullscreenPending.value = true
  try {
    if (fullscreen.value) {
      await document.exitFullscreen()
    } else if (document.fullscreenEnabled) {
      // Fullscreen the document so menus and dialogs rendered into body remain accessible.
      await document.documentElement.requestFullscreen()
      if (!mounted) exitDashboardFullscreen()
    } else {
      fullscreenError.value = fullscreenUnavailable
    }
  } catch {
    fullscreenError.value = fullscreenUnavailable
  } finally {
    fullscreenPending.value = false
  }
}

onMounted(() => {
  mounted = true
  window.scrollTo(0, 0)
  syncFullscreen()
  document.addEventListener('fullscreenchange', syncFullscreen)
  load('')
})

onBeforeUnmount(() => {
  mounted = false
  document.removeEventListener('fullscreenchange', syncFullscreen)
  exitDashboardFullscreen()
})

const summary = computed(() => data.value?.summary ?? null)
const capabilities = computed(() => data.value?.capabilities ?? {})
const allUnits = computed(() => unitOptions(data.value?.units ?? []))

const quickFilterUnits = computed(() => {
  const units = data.value?.units ?? []
  const depts = units.filter(u => u.kind === 'DEPARTMENT')
  if (depts.length > 0 && depts.length <= 5) return depts
  return []
})

const scopeTitle = computed(() => {
  if (!data.value) return '消防安全态势总览'
  if (data.value.selectedUnitId != null) {
    const match = (data.value.units ?? []).find(u => u.id === data.value.selectedUnitId)
    if (match) return match.name + ' · 消防态势总览'
  }
  const seg = (data.value.units ?? []).find(u => u.kind === 'SEGMENT')
  if (seg) return seg.name + '消防安全态势总览'
  if ((data.value.units ?? []).length === 1) return data.value.units[0].name + '消防态势总览'
  return '消防安全态势总览'
})

const scopeSubtitle = computed(() => {
  const s = summary.value
  if (!s) return ''
  const parts = []
  if (s.departmentCount > 0) parts.push(`${s.departmentCount} 个科室`)
  if (s.stationCount > 0) parts.push(`${s.stationCount} 座车站`)
  if (!parts.length) parts.push(`${s.totalUnitCount} 个管辖单位`)
  return parts.join(' · ')
})

const inspectionTotal = computed(() => {
  const s = summary.value
  return s ? s.uninspectedCount + s.inspectionDueCount : 0
})

const drillCoverageRate = computed(() => {
  const s = summary.value
  if (!s || !s.drillTargetUnits) return 0
  return Math.round((s.drillCoveredUnits * 100) / s.drillTargetUnits)
})

const donutSegments = computed(() => {
  const list = data.value?.categories ?? []
  const total = list.reduce((sum, item) => sum + item.count, 0)
  const circumference = 2 * Math.PI * 42
  let offset = 0
  return list.map((item, idx) => {
    const length = total > 0 ? (item.count / total) * circumference : 0
    const segment = {
      ...item,
      color: donutPalette[idx % donutPalette.length],
      dasharray: `${length.toFixed(2)} ${circumference.toFixed(2)}`,
      dashoffset: (-offset).toFixed(2)
    }
    offset += length
    return segment
  })
})

const funnelRows = computed(() => {
  const s = summary.value
  if (!s) return []
  const base = Math.max(s.hazardTotal, 1)
  const rows = [
    { key: 'total', label: '累计录入隐患', count: s.hazardTotal, pct: s.hazardTotal ? 100 : 0, tone: 'neutral' },
    { key: 'rect', label: '待整改上报', count: s.hazardPendingRectification, pct: Math.round((s.hazardPendingRectification * 100) / base), tone: 'warning' },
    { key: 'dept', label: '待科室确认', count: s.hazardPendingDepartment, pct: Math.round((s.hazardPendingDepartment * 100) / base), tone: 'info' },
    { key: 'sect', label: '待段级总销号', count: s.hazardPendingSection, pct: Math.round((s.hazardPendingSection * 100) / base), tone: 'primary' }
  ]
  if (s.hazardInWorkflow > 0) {
    rows.push({ key: 'flow', label: '流程办理中', count: s.hazardInWorkflow, pct: Math.round((s.hazardInWorkflow * 100) / base), tone: 'info' })
  }
  rows.push({ key: 'closed', label: '已销号闭环', count: s.hazardClosed, pct: Math.round((s.hazardClosed * 100) / base), tone: 'success' })
  return rows
})

function sparklinePoints(values) {
  if (!values || !values.length) return '2,26 62,26'
  const max = Math.max(...values, 1)
  const step = values.length > 1 ? 60 / (values.length - 1) : 0
  return values.map((v, i) => {
    const x = 2 + i * step
    const y = 26 - (v / max) * 20
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

const drillSpark = computed(() => sparklinePoints((data.value?.trends ?? []).map(t => t.drills)))

const trendMax = computed(() => {
  const list = data.value?.trends ?? []
  return Math.max(1, ...list.map(t => Math.max(t.hazardsCreated, t.hazardsClosed)))
})

function navigate(viewKey) {
  window.location.hash = '#/' + viewKey
}

function toggleUnitFocus(unitId) {
  const next = String(unitId) === selectedUnitId.value ? '' : String(unitId)
  load(next)
}

function urgentBadge(device) {
  if (device.notices.includes(9)) {
    const overdue = device.daysRemaining !== null ? Math.abs(device.daysRemaining) : null
    return { label: overdue ? `已过期 ${overdue} 天` : '已过期', variant: 'destructive' }
  }
  if (device.notices.includes(3)) {
    return { label: device.daysRemaining !== null ? `剩 ${device.daysRemaining} 天到期` : '3个月内到期', variant: 'attention' }
  }
  if (device.notices.includes(6)) return { label: '待年检', variant: 'info' }
  if (device.notices.includes(5)) return { label: '年检提示', variant: 'info' }
  return { label: noticeLabels[device.notices[0]] ?? '提醒', variant: 'muted' }
}

function unitLevelMeta(level) {
  if (level === 'DANGER') return { label: '紧急', dotClass: 'dash-dot-danger', cardClass: 'dash-unit-danger' }
  if (level === 'WARNING') return { label: '预警', dotClass: 'dash-dot-warning', cardClass: 'dash-unit-warning' }
  return { label: '正常', dotClass: 'dash-dot-ok', cardClass: 'dash-unit-ok' }
}

function activityDotClass(tone) {
  if (tone === 'destructive') return 'bg-destructive'
  if (tone === 'attention') return 'bg-warning'
  if (tone === 'ok') return 'bg-primary'
  return 'bg-information'
}

const canNavigate = perm => session.user?.permissions.includes(perm)
</script>

<template>
  <main class="dash-shell dark">
    <header class="dash-command-bar">
      <div class="flex min-w-0 items-center gap-3 sm:gap-4">
        <span class="dash-brand-emblem" aria-hidden="true">
          <ShieldCheckIcon class="size-6" />
        </span>
        <div class="min-w-0">
          <h1 class="dash-title">{{ scopeTitle }}</h1>
          <p v-if="scopeSubtitle" class="mt-1 text-sm text-muted-foreground">{{ scopeSubtitle }}</p>
        </div>
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <UiButton v-if="homeHref" as-child variant="outline">
          <a :href="homeHref">
            <ArrowLeftIcon data-icon="inline-start" aria-hidden="true" />
            {{ homeLabel }}
          </a>
        </UiButton>
        <UiButton type="button" :disabled="fullscreenPending" @click="toggleFullscreen">
          <component :is="fullscreen ? MinimizeIcon : MaximizeIcon" data-icon="inline-start" aria-hidden="true" />
          {{ fullscreen ? '退出全屏' : '全屏显示' }}
        </UiButton>
        <slot name="account" />
      </div>
    </header>

    <div class="dash-toolbar">
      <div class="flex min-w-0 flex-wrap items-center gap-2">
        <div v-if="quickFilterUnits.length" class="dash-scope-pills" role="group" aria-label="按科室快速筛选">
          <button type="button" class="dash-scope-pill" :aria-pressed="selectedUnitId === ''"
                  :disabled="loading" @click="load('')">
            全辖区
          </button>
          <button v-for="dept in quickFilterUnits" :key="dept.id" type="button" class="dash-scope-pill"
                  :aria-pressed="selectedUnitId === String(dept.id)" :disabled="loading" @click="load(dept.id)">
            {{ dept.name }}
          </button>
        </div>

        <UiNativeSelect v-if="allUnits.length > 1" :model-value="selectedUnitId" aria-label="按消防单位切换态势范围"
                        class="w-44" size="sm" :disabled="loading" @update:model-value="load">
          <UiNativeSelectOption value="">全部管辖单位</UiNativeSelectOption>
          <UiNativeSelectOption v-for="unit in allUnits" :key="unit.id" :value="String(unit.id)">
            {{ unit.label }}
          </UiNativeSelectOption>
        </UiNativeSelect>
      </div>

      <div class="ml-auto flex flex-wrap items-center gap-x-3 gap-y-2">
        <p v-if="data?.generatedAt" class="text-xs text-muted-foreground" role="status">
          数据更新于 <time :datetime="data.generatedAt" class="tabular-nums">{{ dateTime(data.generatedAt) }}</time>
        </p>
        <UiButton type="button" variant="outline" size="sm" :disabled="loading" @click="load()">
          <RefreshCwIcon data-icon="inline-start" :class="{ 'motion-safe:animate-spin': loading }" aria-hidden="true" />
          刷新
        </UiButton>
      </div>
    </div>

    <UiAlert v-if="fullscreenError" variant="destructive" role="alert">
      <UiAlertDescription>{{ fullscreenError }}</UiAlertDescription>
    </UiAlert>
    <UiAlert v-if="accountError" variant="destructive" role="alert">
      <UiAlertDescription>{{ accountError }}</UiAlertDescription>
    </UiAlert>
    <UiAlert v-if="accountNotice" role="status">
      <UiAlertDescription>{{ accountNotice }}</UiAlertDescription>
    </UiAlert>

    <UiAlert v-if="error" variant="destructive" role="alert">
      <UiAlertDescription>{{ error }}</UiAlertDescription>
    </UiAlert>

    <div v-if="loading && !data" class="dash-grid-surface" role="status" aria-label="正在加载消防态势">
      <div class="dash-kpis">
        <Skeleton v-for="n in 5" :key="n" class="h-32 rounded-xl" />
      </div>
      <div class="dash-columns">
        <Skeleton v-for="n in 3" :key="n" class="h-80 rounded-xl" />
      </div>
    </div>

    <div v-else-if="data && summary" class="dash-grid-surface" :aria-busy="loading">
      <section class="dash-kpis" aria-label="核心态势指标">
        <button type="button" class="dash-kpi-card" :disabled="!capabilities.devices" @click="navigate('fire-devices')">
          <span class="dash-kpi-label">
            <ShieldCheckIcon class="size-4 text-primary" aria-hidden="true" />
            消防设备完好率
          </span>
          <span class="dash-kpi-value text-primary">{{ capabilities.devices ? summary.healthRate.toFixed(1) + '%' : '—' }}</span>
          <span v-if="capabilities.devices" class="dash-kpi-detail">
            在用 {{ summary.deviceInUse }} · 库存 {{ summary.deviceStock }} · 报废 {{ summary.deviceScrapped }}
          </span>
        </button>

        <button type="button" class="dash-kpi-card"
                :class="{ 'dash-kpi-alert': summary.expiredCount > 0 }"
                :disabled="!capabilities.devices" @click="navigate('fire-devices')">
          <span class="dash-kpi-label">
            <AlertTriangleIcon class="size-4" :class="summary.expiredCount > 0 ? 'text-destructive' : 'text-warning'" aria-hidden="true" />
            设备保质期预警
          </span>
          <span class="dash-kpi-value" :class="{ 'text-destructive': summary.expiredCount > 0 }">
            {{ capabilities.devices ? summary.expiredCount : '—' }}
            <span v-if="capabilities.devices" class="dash-kpi-unit">台已过期</span>
          </span>
          <span v-if="capabilities.devices" class="dash-kpi-detail">
            3 个月内 {{ summary.within3MonthsCount }} 台 · 6 个月内 {{ summary.within6MonthsCount }} 台
          </span>
        </button>

        <button type="button" class="dash-kpi-card" :disabled="!capabilities.devices" @click="navigate('fire-devices')">
          <span class="dash-kpi-label">
            <ClipboardCheckIcon class="size-4 text-information" aria-hidden="true" />
            在用年检待办
          </span>
          <span class="dash-kpi-value text-information">
            {{ capabilities.devices ? inspectionTotal : '—' }}
            <span v-if="capabilities.devices" class="dash-kpi-unit">台</span>
          </span>
          <span v-if="capabilities.devices" class="dash-kpi-detail">
            待年检 {{ summary.uninspectedCount }} · 年检提示 {{ summary.inspectionDueCount }}
          </span>
        </button>

        <button type="button" class="dash-kpi-card" :disabled="!capabilities.hazards" @click="navigate('fire-hazards')">
          <span class="dash-kpi-label">
            <FlameIcon class="size-4 text-warning" aria-hidden="true" />
            未销号消防隐患
          </span>
          <span class="dash-kpi-value text-warning">
            {{ capabilities.hazards ? summary.hazardOpen : '—' }}
            <span v-if="capabilities.hazards" class="dash-kpi-unit">项</span>
          </span>
          <span v-if="capabilities.hazards" class="dash-kpi-detail">
            待总销号 {{ summary.hazardPendingSection }} · 已闭环 {{ summary.hazardClosed }}
          </span>
        </button>

        <button type="button" class="dash-kpi-card" :disabled="!capabilities.drills" @click="navigate('fire-drills')">
          <span class="dash-kpi-label">
            <SirenIcon class="size-4 text-primary" aria-hidden="true" />
            年度演练与维保
          </span>
          <span class="flex flex-wrap items-end justify-between gap-x-2">
            <span class="dash-kpi-value">
              {{ capabilities.drills ? summary.drillTotalYear : '—' }}
              <span v-if="capabilities.drills" class="dash-kpi-unit">场 · 覆盖 {{ drillCoverageRate }}%</span>
            </span>
            <svg v-if="capabilities.drills" class="h-7 w-13 shrink-0" viewBox="0 0 64 30" role="img" aria-label="近 6 个月演练场次走势">
              <polyline fill="none" stroke="var(--success)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
                        :points="drillSpark" />
            </svg>
          </span>
          <span v-if="capabilities.drills || capabilities.maintainers" class="dash-kpi-detail">
            <span v-if="capabilities.drills">参演 {{ summary.drillParticipantsYear }} 人次</span>
            <span v-if="capabilities.drills && capabilities.maintainers"> · </span>
            <span v-if="capabilities.maintainers">维保 {{ summary.maintainerCount }} 家</span>
          </span>
        </button>
      </section>

      <div class="dash-columns">
        <div class="dash-column">
          <UiCard class="dash-panel gap-0 py-0 ring-0">
            <div class="dash-panel-header">
              <div class="flex items-center gap-2">
                <span class="size-2 rounded-full" :class="data.urgentDevices.length ? 'bg-destructive' : 'bg-muted-foreground'" aria-hidden="true"></span>
                <h2>紧急设备告警队列</h2>
              </div>
              <button v-if="capabilities.devices" type="button" class="dash-link-btn" @click="navigate('fire-devices')">
                全部台账
                <ArrowUpRightIcon class="size-3.5" aria-hidden="true" />
              </button>
            </div>
            <div class="dash-panel-body">
              <UiEmpty v-if="!capabilities.devices" class="empty-state">
                <UiEmptyHeader><UiEmptyTitle>当前账号无设备台账查看权限</UiEmptyTitle></UiEmptyHeader>
              </UiEmpty>
              <div v-else-if="data.urgentDevices.length" class="flex flex-col gap-2">
                <div v-for="device in data.urgentDevices" :key="device.id"
                     class="dash-urgent-row"
                     :class="device.notices.includes(9) ? 'dash-urgent-danger' : device.notices.includes(3) ? 'dash-urgent-warn' : 'dash-urgent-info'">
                  <div class="min-w-0 flex-1">
                    <div class="flex items-center gap-1.5">
                      <span class="mono font-semibold text-foreground">{{ device.code }}</span>
                      <span class="truncate text-xs font-medium text-foreground">{{ device.itemName }}</span>
                    </div>
                    <div class="mt-0.5 truncate text-[11px] text-muted-foreground">
                      {{ device.unitName }} · {{ device.storageLocation || device.useSite || '未登记存放地点' }}
                    </div>
                  </div>
                  <UiBadge :variant="urgentBadge(device).variant" class="shrink-0">
                    {{ urgentBadge(device).label }}
                  </UiBadge>
                </div>
              </div>
              <div v-else class="dash-all-clear">
                <CheckCircle2Icon class="size-5 text-success" aria-hidden="true" />
                <div>
                  <div class="text-sm font-medium text-foreground">当前无紧急设备预警</div>
                  <div class="text-xs text-muted-foreground">管辖范围内无已过期、3个月内到期或逾期未年检器材</div>
                </div>
              </div>
            </div>
          </UiCard>

          <UiCard class="dash-panel gap-0 py-0 ring-0">
            <div class="dash-panel-header">
              <h2>消防器材品类构成</h2>
              <span class="text-xs text-muted-foreground">在用 + 库存</span>
            </div>
            <div class="dash-panel-body">
              <UiEmpty v-if="!capabilities.devices" class="empty-state">
                <UiEmptyHeader><UiEmptyTitle>当前账号无设备台账查看权限</UiEmptyTitle></UiEmptyHeader>
              </UiEmpty>
              <div v-else class="dash-device-mix">
                <svg class="dash-device-donut" viewBox="0 0 112 112" role="img" aria-label="消防器材品类分布环形图">
                  <circle cx="56" cy="56" r="42" fill="none" stroke="var(--muted)" stroke-width="14" />
                  <circle v-for="seg in donutSegments" :key="seg.code"
                          cx="56" cy="56" r="42" fill="none"
                          :stroke="seg.color" stroke-width="14"
                          :stroke-dasharray="seg.dasharray"
                          :stroke-dashoffset="seg.dashoffset"
                          transform="rotate(-90 56 56)" />
                  <text x="56" y="53" text-anchor="middle" fill="var(--foreground)" font-size="18" font-weight="700" class="tabular-nums">
                    {{ summary.deviceTotal }}
                  </text>
                  <text x="56" y="69" text-anchor="middle" fill="var(--muted-foreground)" font-size="10">
                    有效设备(台)
                  </text>
                </svg>

                <div class="flex w-full min-w-0 flex-1 flex-col gap-1.5 text-xs tabular-nums">
                  <div v-for="seg in donutSegments" :key="seg.code" class="flex items-center justify-between gap-2">
                    <span class="flex min-w-0 items-center gap-2 text-foreground">
                      <span class="size-2.5 shrink-0 rounded-xs" :style="{ background: seg.color }" aria-hidden="true"></span>
                      <span class="truncate">{{ deviceTypes[seg.code] || seg.label }}</span>
                    </span>
                    <span class="shrink-0 font-medium text-muted-foreground">
                      <strong class="text-foreground">{{ seg.count }}</strong>
                      <span class="ml-1 text-[11px]">({{ seg.ratio.toFixed(0) }}%)</span>
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </UiCard>
        </div>

        <div class="dash-column">
          <UiCard class="dash-panel gap-0 py-0 ring-0">
            <div class="dash-panel-header">
              <div class="flex items-center gap-2">
                <Building2Icon class="size-4 text-primary" aria-hidden="true" />
                <h2>段—科—站安全态势网格</h2>
              </div>
              <div class="flex flex-wrap items-center gap-x-2.5 gap-y-1 text-xs text-muted-foreground">
                <span class="flex items-center gap-1"><i class="size-2 rounded-full bg-success"></i>正常</span>
                <span class="flex items-center gap-1"><i class="size-2 rounded-full bg-warning"></i>预警</span>
                <span class="flex items-center gap-1"><i class="size-2 rounded-full bg-destructive"></i>紧急</span>
              </div>
            </div>
            <div class="dash-panel-body">
              <div v-if="data.unitStatuses.length" class="grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-3">
                <button v-for="unit in data.unitStatuses.slice(0, 9)" :key="unit.unitId" type="button"
                        class="dash-unit-card text-left"
                        :class="unitLevelMeta(unit.level).cardClass"
                        :aria-pressed="selectedUnitId === String(unit.unitId)" :disabled="loading"
                        @click="toggleUnitFocus(unit.unitId)">
                  <div class="flex items-center justify-between gap-2">
                    <span class="truncate text-xs font-semibold text-foreground">{{ unit.name }}</span>
                    <span class="size-2 shrink-0 rounded-full" :class="unitLevelMeta(unit.level).dotClass"
                          :title="unitLevelMeta(unit.level).label"></span>
                  </div>
                  <div class="mt-0.5 truncate text-[11px] text-muted-foreground">
                    {{ unit.parentName || unitKinds[unit.kind] || unit.kind }}<template v-if="capabilities.devices"> · {{ unit.deviceTotal }} 台</template>
                  </div>
                  <div v-if="capabilities.devices || capabilities.hazards" class="mt-2 flex flex-wrap items-center justify-between gap-x-2 border-t border-border/70 pt-1.5 text-[11px] tabular-nums">
                    <template v-if="capabilities.devices">
                      <span v-if="unit.expiredCount > 0" class="font-medium text-destructive">过期 {{ unit.expiredCount }}</span>
                      <span v-else-if="unit.expiringCount + unit.inspectionAlertCount > 0" class="font-medium text-warning">
                        预警 {{ unit.expiringCount + unit.inspectionAlertCount }}
                      </span>
                      <span v-else class="text-success">器材正常</span>
                    </template>

                    <span v-if="capabilities.hazards" :class="unit.openHazardCount > 0 ? 'font-medium text-warning' : 'text-muted-foreground'">
                      隐患 {{ unit.openHazardCount }}
                    </span>
                  </div>
                </button>
              </div>
              <UiEmpty v-else class="empty-state">
                <UiEmptyHeader><UiEmptyTitle>当前范围暂无下属消防单位</UiEmptyTitle></UiEmptyHeader>
              </UiEmpty>
              <div v-if="data.unitStatuses.length > 9" class="mt-3 flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
                <span>已优先展示风险最高的 9 个单位，共 {{ data.unitStatuses.length }} 个单位</span>
                <button v-if="canNavigate('fire:org')" type="button" class="dash-link-btn" @click="navigate('fire-units')">
                  查看全部单位
                  <ArrowUpRightIcon class="size-3.5" aria-hidden="true" />
                </button>
              </div>
            </div>
          </UiCard>

          <UiCard class="dash-panel gap-0 py-0 ring-0">
            <div class="dash-panel-header">
              <h2>隐患整改销号闭环流转</h2>
              <UiBadge v-if="capabilities.hazards" variant="ok">闭环率 {{ summary.closureRate.toFixed(1) }}%</UiBadge>
            </div>
            <div class="dash-panel-body">
              <UiEmpty v-if="!capabilities.hazards" class="empty-state">
                <UiEmptyHeader><UiEmptyTitle>当前账号无隐患问题库查看权限</UiEmptyTitle></UiEmptyHeader>
              </UiEmpty>
              <div v-else class="flex flex-col gap-3">
                <div class="flex flex-col gap-1.5 text-xs">
                  <div v-for="row in funnelRows" :key="row.key" class="flex items-center gap-3">
                    <span class="w-20 shrink-0 text-muted-foreground">{{ row.label }}</span>
                    <div class="h-2 flex-1 overflow-hidden rounded-full bg-muted">
                      <div class="dash-funnel-bar" :class="'dash-funnel-' + row.tone"
                           :style="{ width: row.pct + '%' }"></div>
                    </div>
                    <span class="w-10 shrink-0 text-right font-medium text-foreground tabular-nums">{{ row.count }}</span>
                  </div>
                </div>

                <div class="border-t border-border pt-3">
                  <div class="mb-2 flex flex-wrap items-center justify-between gap-x-2 gap-y-1 text-xs text-muted-foreground">
                    <span>近 6 个月隐患录入与销号趋势</span>
                    <span class="flex items-center gap-3">
                      <span class="flex items-center gap-1"><i class="size-2 rounded-xs bg-information/70"></i>新增</span>
                      <span class="flex items-center gap-1"><i class="size-2 rounded-xs bg-primary"></i>销号</span>
                    </span>
                  </div>
                  <div class="grid grid-cols-6 gap-2 pt-1">
                    <div v-for="point in data.trends" :key="point.label" class="flex flex-col items-center gap-1">
                      <div class="flex h-16 w-full items-end justify-center gap-1">
                        <span class="w-2.5 rounded-t-xs bg-information/70"
                              :style="{ height: (point.hazardsCreated / trendMax * 100) + '%' }"
                              :title="`${point.label} 新增 ${point.hazardsCreated} 项`"></span>
                        <span class="w-2.5 rounded-t-xs bg-primary"
                              :style="{ height: (point.hazardsClosed / trendMax * 100) + '%' }"
                              :title="`${point.label} 销号 ${point.hazardsClosed} 项`"></span>
                      </div>
                      <span class="text-[11px] text-muted-foreground">{{ point.label }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </UiCard>
        </div>

        <div class="dash-column">
          <UiCard class="dash-panel gap-0 py-0 ring-0">
            <div class="dash-panel-header">
              <div class="flex items-center gap-2">
                <ActivityIcon class="size-4 text-primary" aria-hidden="true" />
                <h2>近期业务动态</h2>
              </div>
            </div>
            <div class="dash-panel-body">
              <ol v-if="data.recentActivities.length" class="dash-timeline">
                <li v-for="(item, index) in data.recentActivities" :key="index" class="dash-timeline-item">
                  <span class="dash-timeline-dot" :class="activityDotClass(item.tone)" aria-hidden="true"></span>
                  <div class="text-xs font-medium text-foreground">{{ item.title }}</div>
                  <div class="mt-0.5 truncate text-xs text-muted-foreground" :title="item.detail">{{ item.detail }}</div>
                  <div class="mt-0.5 text-[11px] text-muted-foreground/85 tabular-nums">
                    {{ item.unitName }} · {{ item.actorName }} · {{ dateTime(item.occurredAt).slice(5) }}
                  </div>
                </li>
              </ol>
              <UiEmpty v-else class="empty-state">
                <UiEmptyHeader><UiEmptyTitle>暂无近期业务动态</UiEmptyTitle></UiEmptyHeader>
              </UiEmpty>
            </div>
          </UiCard>

          <UiCard class="dash-panel gap-0 py-0 ring-0">
            <div class="dash-panel-header">
              <div class="flex items-center gap-2">
                <HandshakeIcon class="size-4 text-primary" aria-hidden="true" />
                <h2>维保单位值守</h2>
              </div>
              <button v-if="capabilities.maintainers" type="button" class="dash-link-btn" @click="navigate('fire-maintainers')">
                管理
                <ArrowUpRightIcon class="size-3.5" aria-hidden="true" />
              </button>
            </div>
            <div class="dash-panel-body">
              <div v-if="data.maintainers.length" class="flex flex-col gap-2">
                <div v-for="m in data.maintainers" :key="m.id" class="dash-maint-card">
                  <div class="flex items-center justify-between gap-2">
                    <span class="truncate text-xs font-semibold text-foreground">{{ m.name }}</span>
                    <UiBadge variant="muted">{{ m.unitCount }} 单位</UiBadge>
                  </div>
                  <div class="mt-1.5 flex flex-wrap items-center justify-between gap-x-2 text-xs text-muted-foreground tabular-nums">
                    <span class="flex min-w-0 items-center gap-1">
                      <PhoneIcon class="size-3 shrink-0 text-primary" aria-hidden="true" />
                      <span class="wrap-anywhere">{{ m.contact }} · {{ m.phone }}</span>
                    </span>
                    <span>{{ m.deviceCount }} 台</span>
                  </div>
                </div>
              </div>
              <UiEmpty v-else class="empty-state">
                <UiEmptyHeader><UiEmptyTitle>{{ capabilities.maintainers ? '暂无签约维保单位' : '当前账号无维保单位查看权限' }}</UiEmptyTitle></UiEmptyHeader>
              </UiEmpty>
            </div>
          </UiCard>
        </div>
      </div>
    </div>
  </main>
</template>
