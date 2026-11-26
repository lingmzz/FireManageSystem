<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  ActivityIcon, AlertTriangleIcon, ArrowUpRightIcon, BoxesIcon, Building2Icon,
  CheckCircle2Icon, ClipboardCheckIcon, FlameIcon, HandshakeIcon, PhoneIcon,
  RefreshCwIcon, ShieldCheckIcon, SirenIcon
} from '@lucide/vue'
import { Skeleton } from '@/components/ui/skeleton'
import { api, dateTime, session } from '../api'
import { deviceTypes, noticeLabels, params, unitKinds, unitOptions } from '../fire'

const loading = ref(true)
const error = ref('')
const selectedUnitId = ref('')
const data = ref(null)

const donutPalette = [
  'var(--primary)',
  'var(--information)',
  'var(--warning)',
  'var(--success)',
  'oklch(0.55 0.08 205)',
  'oklch(0.60 0.09 145)',
  'var(--muted-foreground)'
]

async function load(unitId = selectedUnitId.value) {
  loading.value = true
  error.value = ''
  selectedUnitId.value = unitId === null || unitId === undefined ? '' : String(unitId)
  try {
    const query = params({ unitId: selectedUnitId.value })
    data.value = await api('/api/fire/dashboard' + (query.toString() ? '?' + query : ''))
  } catch (failure) {
    error.value = failure.message
  } finally {
    loading.value = false
  }
}

onMounted(() => load(''))

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
  if (selectedUnitId.value) {
    const match = (data.value.units ?? []).find(u => String(u.id) === selectedUnitId.value)
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
  return `段—科—站三级联防监控 · 当前范围涵盖 ${parts.join(' / ')}`
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

const hazardCreatedSpark = computed(() => sparklinePoints((data.value?.trends ?? []).map(t => t.hazardsCreated)))
const hazardClosedSpark = computed(() => sparklinePoints((data.value?.trends ?? []).map(t => t.hazardsClosed)))
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
  <div class="dash-shell">
    <!-- 第一层：大屏顶部态势指挥栏（方案一翡翠绿配色 + 方案二指挥舱结构） -->
    <section class="dash-command-bar">
      <div class="flex min-w-0 items-start gap-3">
        <span class="dash-brand-emblem" aria-hidden="true">
          <ShieldCheckIcon class="size-5" />
        </span>
        <div class="min-w-0">
          <div class="flex flex-wrap items-center gap-2">
            <h1 class="truncate text-xl font-semibold tracking-tight sm:text-2xl">{{ scopeTitle }}</h1>
            <span class="dash-live-pill">
              <span class="dash-live-dot" aria-hidden="true"></span>
              实时监控中
            </span>
          </div>
          <p class="mt-0.5 text-xs text-muted-foreground">
            {{ scopeSubtitle || '正在汇总管辖单位消防器材、隐患销号、演练与维保态势…' }}
          </p>
        </div>
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <!-- 科室快捷切换胶囊（若有2~5个科室） -->
        <div v-if="quickFilterUnits.length" class="dash-scope-pills" role="group" aria-label="按科室快速筛选">
          <button type="button" class="dash-scope-pill" :aria-pressed="selectedUnitId === ''" @click="load('')">
            全辖区
          </button>
          <button v-for="dept in quickFilterUnits" :key="dept.id" type="button" class="dash-scope-pill"
                  :aria-pressed="selectedUnitId === String(dept.id)" @click="load(dept.id)">
            {{ dept.name }}
          </button>
        </div>

        <!-- 全量单位下拉筛选 -->
        <UiNativeSelect v-if="allUnits.length > 1" :model-value="selectedUnitId" aria-label="按消防单位切换态势范围"
                        class="w-40 sm:w-44" @update:model-value="value => load(value)">
          <UiNativeSelectOption value="">全部管辖单位</UiNativeSelectOption>
          <UiNativeSelectOption v-for="unit in allUnits" :key="unit.id" :value="String(unit.id)">
            {{ unit.label }}
          </UiNativeSelectOption>
        </UiNativeSelect>

        <UiButton variant="outline" size="sm" :disabled="loading" @click="load(selectedUnitId)">
          <RefreshCwIcon class="size-3.5" :class="{ 'animate-spin': loading }" aria-hidden="true" />
          刷新
        </UiButton>
        <UiButton v-if="canNavigate('fire:device-read')" variant="outline" size="sm" @click="navigate('fire-devices')">
          <BoxesIcon class="size-3.5" aria-hidden="true" />
          设备台账
        </UiButton>
        <UiButton v-if="canNavigate('fire:hazard-read')" size="sm" @click="navigate('fire-hazards')">
          <FlameIcon class="size-3.5" aria-hidden="true" />
          隐患库
        </UiButton>
      </div>
    </section>

    <UiAlert v-if="error" variant="destructive" role="alert">
      <UiAlertDescription>{{ error }}</UiAlertDescription>
    </UiAlert>

    <!-- 骨架加载态 -->
    <div v-if="loading && !data" class="space-y-4">
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <Skeleton v-for="n in 5" :key="n" class="h-28 rounded-xl" />
      </div>
      <div class="grid grid-cols-1 gap-4 xl:grid-cols-12">
        <Skeleton class="h-96 rounded-xl xl:col-span-4" />
        <Skeleton class="h-96 rounded-xl xl:col-span-5" />
        <Skeleton class="h-96 rounded-xl xl:col-span-3" />
      </div>
    </div>

    <!-- 主大屏网格表面 -->
    <div v-else-if="data && summary" class="dash-grid-surface space-y-4">
      <!-- 第二层：5 列高密度 KPI 指标带（带 SVG Sparkline） -->
      <section class="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5" aria-label="核心态势指标">
        <!-- KPI 1: 设备完好率 -->
        <button type="button" class="dash-kpi-card group text-left" :disabled="!capabilities.devices"
                @click="capabilities.devices && navigate('fire-devices')">
          <div class="flex items-center justify-between text-xs text-muted-foreground">
            <span class="flex items-center gap-1.5 font-medium">
              <ShieldCheckIcon class="size-3.5 text-primary" aria-hidden="true" />
              消防设备完好率
            </span>
            <span class="rounded-full bg-accent px-1.5 py-0.5 text-[11px] font-medium text-accent-foreground">
              台账 {{ summary.deviceTotal }}
            </span>
          </div>
          <div class="mt-2.5 flex items-end justify-between gap-2">
            <div>
              <div class="text-2xl font-bold tracking-tight text-primary tabular-nums">
                {{ summary.healthRate.toFixed(1) }}%
              </div>
              <div class="mt-1 text-[11px] text-muted-foreground tabular-nums">
                在用 {{ summary.deviceInUse }} · 库存 {{ summary.deviceStock }} · 报废 {{ summary.deviceScrapped }}
              </div>
            </div>
            <svg class="h-8 w-16 shrink-0" viewBox="0 0 64 30" aria-hidden="true">
              <polyline fill="none" stroke="var(--primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
                        points="2,22 14,19 26,20 38,14 50,11 62,8" />
            </svg>
          </div>
        </button>

        <!-- KPI 2: 高优保质期告警 -->
        <button type="button" class="dash-kpi-card group text-left"
                :class="{ 'dash-kpi-alert': summary.expiredCount > 0 }"
                :disabled="!capabilities.devices"
                @click="capabilities.devices && navigate('fire-devices')">
          <div class="flex items-center justify-between text-xs text-muted-foreground">
            <span class="flex items-center gap-1.5 font-medium" :class="summary.expiredCount > 0 ? 'text-destructive' : ''">
              <AlertTriangleIcon class="size-3.5" :class="summary.expiredCount > 0 ? 'text-destructive' : 'text-warning'" aria-hidden="true" />
              保质期到期告警
            </span>
            <UiBadge :variant="summary.expiredCount > 0 ? 'destructive' : summary.within3MonthsCount > 0 ? 'attention' : 'ok'">
              {{ summary.expiredCount > 0 ? '高优处置' : summary.within3MonthsCount > 0 ? '临期关注' : '状态良好' }}
            </UiBadge>
          </div>
          <div class="mt-2.5 flex items-end justify-between gap-2">
            <div>
              <div class="flex items-baseline gap-1.5 tabular-nums">
                <span class="text-2xl font-bold tracking-tight" :class="summary.expiredCount > 0 ? 'text-destructive' : 'text-foreground'">
                  {{ summary.expiredCount }}
                </span>
                <span class="text-sm font-semibold text-warning">/ {{ summary.within3MonthsCount }}</span>
              </div>
              <div class="mt-1 text-[11px] text-muted-foreground tabular-nums">
                已过期 / 3月内 · 6月内 {{ summary.within6MonthsCount }} 台
              </div>
            </div>
            <svg class="h-8 w-16 shrink-0" viewBox="0 0 64 30" aria-hidden="true">
              <polyline fill="none" :stroke="summary.expiredCount > 0 ? 'var(--destructive)' : 'var(--warning)'"
                        stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
                        points="2,20 14,16 26,18 38,12 50,15 62,10" />
            </svg>
          </div>
        </button>

        <!-- KPI 3: 在用年检待办 -->
        <button type="button" class="dash-kpi-card group text-left" :disabled="!capabilities.devices"
                @click="capabilities.devices && navigate('fire-devices')">
          <div class="flex items-center justify-between text-xs text-muted-foreground">
            <span class="flex items-center gap-1.5 font-medium">
              <ClipboardCheckIcon class="size-3.5 text-information" aria-hidden="true" />
              在用年检待办
            </span>
            <span class="rounded-full bg-information/10 px-1.5 py-0.5 text-[11px] font-medium text-information">
              年度核验
            </span>
          </div>
          <div class="mt-2.5 flex items-end justify-between gap-2">
            <div>
              <div class="text-2xl font-bold tracking-tight text-information tabular-nums">
                {{ inspectionTotal }}
              </div>
              <div class="mt-1 text-[11px] text-muted-foreground tabular-nums">
                待年检 {{ summary.uninspectedCount }} · 往年提示 {{ summary.inspectionDueCount }}
              </div>
            </div>
            <svg class="h-8 w-16 shrink-0" viewBox="0 0 64 30" aria-hidden="true">
              <polyline fill="none" stroke="var(--information)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
                        points="2,23 14,20 26,16 38,17 50,12 62,11" />
            </svg>
          </div>
        </button>

        <!-- KPI 4: 未销号隐患 -->
        <button type="button" class="dash-kpi-card group text-left" :disabled="!capabilities.hazards"
                @click="capabilities.hazards && navigate('fire-hazards')">
          <div class="flex items-center justify-between text-xs text-muted-foreground">
            <span class="flex items-center gap-1.5 font-medium">
              <FlameIcon class="size-3.5 text-warning" aria-hidden="true" />
              未销号消防隐患
            </span>
            <span class="rounded-full bg-warning/10 px-1.5 py-0.5 text-[11px] font-medium text-warning tabular-nums">
              待总销号 {{ summary.hazardPendingSection }}
            </span>
          </div>
          <div class="mt-2.5 flex items-end justify-between gap-2">
            <div>
              <div class="text-2xl font-bold tracking-tight text-warning tabular-nums">
                {{ summary.hazardOpen }}
              </div>
              <div class="mt-1 text-[11px] text-muted-foreground tabular-nums">
                已闭环 {{ summary.hazardClosed }} 项 · 闭环率 {{ summary.closureRate.toFixed(1) }}%
              </div>
            </div>
            <svg class="h-8 w-16 shrink-0" viewBox="0 0 64 30" aria-hidden="true">
              <polyline fill="none" stroke="var(--warning)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
                        :points="hazardClosedSpark" />
            </svg>
          </div>
        </button>

        <!-- KPI 5: 演练与维保覆盖 -->
        <button type="button" class="dash-kpi-card group text-left" :disabled="!capabilities.drills"
                @click="capabilities.drills && navigate('fire-drills')">
          <div class="flex items-center justify-between text-xs text-muted-foreground">
            <span class="flex items-center gap-1.5 font-medium">
              <SirenIcon class="size-3.5 text-primary" aria-hidden="true" />
              年度演练与维保
            </span>
            <span class="rounded-full bg-success/10 px-1.5 py-0.5 text-[11px] font-medium text-success tabular-nums">
              覆盖 {{ summary.drillCoveredUnits }}/{{ summary.drillTargetUnits }}
            </span>
          </div>
          <div class="mt-2.5 flex items-end justify-between gap-2">
            <div>
              <div class="flex items-baseline gap-1.5 tabular-nums">
                <span class="text-2xl font-bold tracking-tight text-foreground">{{ summary.drillTotalYear }}</span>
                <span class="text-xs text-muted-foreground">场 ({{ drillCoverageRate }}%)</span>
              </div>
              <div class="mt-1 text-[11px] text-muted-foreground tabular-nums">
                参演 {{ summary.drillParticipantsYear }} 人次 · 维保 {{ summary.maintainerCount }} 家
              </div>
            </div>
            <svg class="h-8 w-16 shrink-0" viewBox="0 0 64 30" aria-hidden="true">
              <polyline fill="none" stroke="var(--success)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
                        :points="drillSpark" />
            </svg>
          </div>
        </button>
      </section>

      <!-- 第三层：12 栏三列指挥大屏主体 -->
      <section class="grid grid-cols-1 gap-4 xl:grid-cols-12">
        <!-- 左 4 栏：紧急设备告警队列 + 7 大消防器材品类环形图 -->
        <div class="space-y-4 xl:col-span-4">
          <UiCard class="dash-panel">
            <div class="dash-panel-header">
              <div class="flex items-center gap-2">
                <span class="size-2 rounded-full" :class="data.urgentDevices.length ? 'bg-destructive dash-pulse' : 'bg-success'" aria-hidden="true"></span>
                <h2>紧急设备告警队列</h2>
              </div>
              <button v-if="capabilities.devices" type="button" class="dash-link-btn" @click="navigate('fire-devices')">
                全部台账
                <ArrowUpRightIcon class="size-3.5" aria-hidden="true" />
              </button>
            </div>
            <div class="p-4">
              <div v-if="data.urgentDevices.length" class="space-y-2.5">
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

          <!-- 7 大消防器材品类构成环形图 -->
          <UiCard class="dash-panel">
            <div class="dash-panel-header">
              <h2>消防器材品类构成</h2>
              <span class="text-xs text-muted-foreground">在用 + 库存</span>
            </div>
            <div class="p-4">
              <div class="flex flex-col items-center gap-4 sm:flex-row">
                <svg class="size-32 shrink-0" viewBox="0 0 112 112" role="img" aria-label="消防器材品类分布环形图">
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

                <div class="w-full min-w-0 flex-1 space-y-1.5 text-xs tabular-nums">
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

        <!-- 中 5 栏：段—科—站三级安全态势网格 + 隐患闭环漏斗与月度趋势 -->
        <div class="space-y-4 xl:col-span-5">
          <UiCard class="dash-panel">
            <div class="dash-panel-header">
              <div class="flex items-center gap-2">
                <Building2Icon class="size-4 text-primary" aria-hidden="true" />
                <h2>段—科—站安全态势网格</h2>
              </div>
              <div class="flex items-center gap-2.5 text-[11px] text-muted-foreground">
                <span class="flex items-center gap-1"><i class="size-2 rounded-full bg-success"></i>正常</span>
                <span class="flex items-center gap-1"><i class="size-2 rounded-full bg-warning"></i>预警</span>
                <span class="flex items-center gap-1"><i class="size-2 rounded-full bg-destructive"></i>紧急</span>
              </div>
            </div>
            <div class="p-4">
              <div v-if="data.unitStatuses.length" class="grid grid-cols-1 gap-2.5 sm:grid-cols-2 lg:grid-cols-3">
                <button v-for="unit in data.unitStatuses.slice(0, 9)" :key="unit.unitId" type="button"
                        class="dash-unit-card text-left"
                        :class="[unitLevelMeta(unit.level).cardClass, selectedUnitId === String(unit.unitId) ? 'ring-2 ring-primary' : '']"
                        @click="toggleUnitFocus(unit.unitId)">
                  <div class="flex items-center justify-between gap-2">
                    <span class="truncate text-xs font-semibold text-foreground">{{ unit.name }}</span>
                    <span class="size-2 shrink-0 rounded-full" :class="unitLevelMeta(unit.level).dotClass"
                          :title="unitLevelMeta(unit.level).label"></span>
                  </div>
                  <div class="mt-0.5 truncate text-[11px] text-muted-foreground">
                    {{ unit.parentName || unitKinds[unit.kind] || unit.kind }} · {{ unit.deviceTotal }} 台
                  </div>
                  <div class="mt-2.5 flex items-center justify-between border-t border-border/70 pt-2 text-[11px] tabular-nums">
                    <span v-if="unit.expiredCount > 0" class="font-medium text-destructive">过期 {{ unit.expiredCount }}</span>
                    <span v-else-if="unit.expiringCount + unit.inspectionAlertCount > 0" class="font-medium text-warning">
                      预警 {{ unit.expiringCount + unit.inspectionAlertCount }}
                    </span>
                    <span v-else class="text-success">器材正常</span>

                    <span :class="unit.openHazardCount > 0 ? 'font-medium text-warning' : 'text-muted-foreground'">
                      隐患 {{ unit.openHazardCount }}
                    </span>
                  </div>
                </button>
              </div>
              <UiEmpty v-else class="empty-state">
                <UiEmptyHeader><UiEmptyTitle>当前范围暂无下属消防单位</UiEmptyTitle></UiEmptyHeader>
              </UiEmpty>
              <div v-if="data.unitStatuses.length > 9" class="mt-3 flex items-center justify-between text-xs text-muted-foreground">
                <span>已优先展示风险最高的 9 个单位，共 {{ data.unitStatuses.length }} 个单位</span>
                <button v-if="canNavigate('fire:org')" type="button" class="dash-link-btn" @click="navigate('fire-units')">
                  查看全部单位
                  <ArrowUpRightIcon class="size-3.5" aria-hidden="true" />
                </button>
              </div>
            </div>
          </UiCard>

          <!-- 隐患整改销号闭环流转漏斗 + 近6月对比 -->
          <UiCard class="dash-panel">
            <div class="dash-panel-header">
              <h2>隐患整改销号闭环流转</h2>
              <UiBadge variant="ok">闭环率 {{ summary.closureRate.toFixed(1) }}%</UiBadge>
            </div>
            <div class="space-y-4 p-4">
              <div class="space-y-2.5 text-xs">
                <div v-for="row in funnelRows" :key="row.key" class="flex items-center gap-3">
                  <span class="w-24 shrink-0 text-muted-foreground">{{ row.label }}</span>
                  <div class="h-5 flex-1 overflow-hidden rounded-md bg-muted/80">
                    <div class="dash-funnel-bar" :class="'dash-funnel-' + row.tone"
                         :style="{ width: row.count > 0 ? Math.max(row.pct, 10) + '%' : '0%' }">
                      <span v-if="row.count > 0" class="tabular-nums">{{ row.count }} 项</span>
                    </div>
                  </div>
                  <span class="w-10 shrink-0 text-right font-medium text-foreground tabular-nums">{{ row.count }}</span>
                </div>
              </div>

              <!-- 近 6 个月隐患录入 vs 销号迷你柱状图 -->
              <div class="border-t border-border pt-3">
                <div class="mb-2 flex items-center justify-between text-[11px] text-muted-foreground">
                  <span>近 6 个月隐患录入与销号趋势</span>
                  <span class="flex items-center gap-3">
                    <span class="flex items-center gap-1"><i class="size-2 rounded-xs bg-muted-foreground/35"></i>新增</span>
                    <span class="flex items-center gap-1"><i class="size-2 rounded-xs bg-primary"></i>销号</span>
                  </span>
                </div>
                <div class="grid grid-cols-6 gap-2 pt-1">
                  <div v-for="point in data.trends" :key="point.label" class="flex flex-col items-center gap-1">
                    <div class="flex h-16 w-full items-end justify-center gap-1">
                      <span class="w-2.5 rounded-t-xs bg-muted-foreground/30 transition-all"
                            :style="{ height: Math.max(4, Math.round((point.hazardsCreated / trendMax) * 60)) + 'px' }"
                            :title="`${point.label} 新增 ${point.hazardsCreated} 项`"></span>
                      <span class="w-2.5 rounded-t-xs bg-primary transition-all"
                            :style="{ height: Math.max(4, Math.round((point.hazardsClosed / trendMax) * 60)) + 'px' }"
                            :title="`${point.label} 销号 ${point.hazardsClosed} 项`"></span>
                    </div>
                    <span class="text-[11px] text-muted-foreground">{{ point.label }}</span>
                  </div>
                </div>
              </div>
            </div>
          </UiCard>
        </div>

        <!-- 右 3 栏：实时业务操作流 + 维保单位值守卡 -->
        <div class="space-y-4 xl:col-span-3">
          <UiCard class="dash-panel">
            <div class="dash-panel-header">
              <div class="flex items-center gap-2">
                <ActivityIcon class="size-4 text-primary" aria-hidden="true" />
                <h2>实时业务动态</h2>
              </div>
              <span class="text-[11px] text-muted-foreground tabular-nums">
                {{ dateTime(data.generatedAt).slice(11) }}
              </span>
            </div>
            <div class="p-4">
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

          <UiCard class="dash-panel">
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
            <div class="p-4">
              <div v-if="data.maintainers.length" class="space-y-2.5">
                <div v-for="m in data.maintainers" :key="m.id" class="dash-maint-card">
                  <div class="flex items-center justify-between gap-2">
                    <span class="truncate text-xs font-semibold text-foreground">{{ m.name }}</span>
                    <UiBadge variant="muted">{{ m.unitCount }} 单位</UiBadge>
                  </div>
                  <div class="mt-1.5 flex items-center justify-between text-[11px] text-muted-foreground tabular-nums">
                    <span class="flex items-center gap-1">
                      <PhoneIcon class="size-3 text-primary" aria-hidden="true" />
                      {{ m.contact }} · {{ m.phone }}
                    </span>
                    <span>{{ m.deviceCount }} 台</span>
                  </div>
                </div>
              </div>
              <UiEmpty v-else class="empty-state">
                <UiEmptyHeader><UiEmptyTitle>暂无签约维保单位</UiEmptyTitle></UiEmptyHeader>
              </UiEmpty>
            </div>
          </UiCard>
        </div>
      </section>
    </div>
  </div>
</template>
