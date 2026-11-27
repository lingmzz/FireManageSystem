<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Breadcrumb, BreadcrumbItem, BreadcrumbList, BreadcrumbPage, BreadcrumbSeparator } from '@/components/ui/breadcrumb'
import { Empty, EmptyHeader, EmptyTitle } from '@/components/ui/empty'
import { Separator } from '@/components/ui/separator'
import { SidebarInset, SidebarProvider, SidebarTrigger } from '@/components/ui/sidebar'
import { Skeleton } from '@/components/ui/skeleton'
import { logout, refreshSession, session } from './api'
import AccountMenu from './components/AccountMenu.vue'
import AppSidebar from './components/AppSidebar.vue'
import DeletionDialog from './components/DeletionDialog.vue'
import ProfileDialog from './components/ProfileDialog.vue'
import LoginPage from './pages/LoginPage.vue'
import UserPage from './pages/UserPage.vue'
import RolePage from './pages/RolePage.vue'
import MenuPage from './pages/MenuPage.vue'
import ApprovalPage from './pages/ApprovalPage.vue'
import FireDevicesPage from './pages/FireDevicesPage.vue'
import FireDashboardPage from './pages/FireDashboardPage.vue'
import FireMaintainersPage from './pages/FireMaintainersPage.vue'
import FireDrillsPage from './pages/FireDrillsPage.vue'
import FireHazardsPage from './pages/FireHazardsPage.vue'
import FireUnitsPage from './pages/FireUnitsPage.vue'
import ApprovalFlowsPage from './pages/ApprovalFlowsPage.vue'

const views = {
  'fire-dashboard': { name: '消防大屏', permission: 'fire:dashboard', page: FireDashboardPage },
  'fire-devices': { name: '消防设备台账', permission: 'fire:device-read', page: FireDevicesPage },
  'fire-maintainers': { name: '维保单位', permission: 'fire:maint-read', page: FireMaintainersPage },
  'fire-drills': { name: '应急演练台账', permission: 'fire:drill-read', page: FireDrillsPage },
  'fire-hazards': { name: '隐患问题库', permission: 'fire:hazard-read', page: FireHazardsPage },
  'fire-units': { name: '消防单位与授权', permission: 'fire:org', page: FireUnitsPage },
  approvals: { name: '工作台', permission: 'approvals:read', page: ApprovalPage },
  'approval-flows': { name: '审批流程配置', permission: 'workflows:manage', page: ApprovalFlowsPage },
  users: { name: '用户管理', permission: 'users:manage', page: UserPage },
  roles: { name: '角色权限', permission: 'roles:manage', page: RolePage },
  menus: { name: '菜单管理', permission: 'menus:manage', page: MenuPage }
}

const locationKey = () => window.location.hash.replace(/^#\/?/, '')
const current = ref(locationKey())
const actionError = ref('')
const actionNotice = ref('')
const leaving = ref(false)
const editingProfile = ref(false)
const accountButton = ref(null)

function updateRoute() {
  current.value = locationKey()
  actionNotice.value = ''
}

const allowed = computed(() => {
  const permissions = session.user?.permissions ?? []
  return Object.keys(views).filter(key => permissions.includes(views[key].permission))
})

function firstWorkspaceView(items) {
  for (const item of items) {
    if (item.viewKey !== 'fire-dashboard' && allowed.value.includes(item.viewKey)) return item.viewKey
    const nested = firstWorkspaceView(item.children)
    if (nested) return nested
  }
  return null
}

const homeViewKey = computed(() => {
  if (allowed.value.includes('approvals')) return 'approvals'
  return firstWorkspaceView(session.user?.menus ?? []) ?? allowed.value.find(key => key !== 'fire-dashboard')
})
const viewKey = computed(() => allowed.value.includes(current.value)
  ? current.value
  : homeViewKey.value ?? allowed.value[0])
const activeView = computed(() => views[viewKey.value])

function menuPath(items, key) {
  for (const item of items) {
    if (item.viewKey === key) return [item.name]
    const children = menuPath(item.children, key)
    if (children.length) return [item.name, ...children]
  }
  return []
}

const breadcrumb = computed(() => {
  const path = menuPath(session.user?.menus ?? [], viewKey.value)
  return path.length ? path : [activeView.value?.name ?? '工作台']
})

onMounted(() => {
  window.addEventListener('hashchange', updateRoute)
  refreshSession()
})
onUnmounted(() => window.removeEventListener('hashchange', updateRoute))
watch(() => session.user, user => {
  if (!user) {
    editingProfile.value = false
    actionNotice.value = ''
  }
})

function closeProfile(saved = false) {
  editingProfile.value = false
  if (saved) actionNotice.value = '个人信息已保存。'
  nextTick(() => accountButton.value?.focus())
}

function openProfile() {
  editingProfile.value = true
  actionNotice.value = ''
}

async function leave() {
  if (leaving.value) return
  leaving.value = true
  actionError.value = ''
  try {
    await logout()
    window.location.hash = ''
  } catch (error) {
    actionError.value = error.message
  } finally {
    leaving.value = false
  }
}
</script>

<template>
  <div v-if="!session.ready" class="app-loading" role="status">
    <Skeleton class="h-2 w-28" />
    <p>正在连接消防管理系统…</p>
  </div>
  <LoginPage v-else-if="!session.user" />
  <FireDashboardPage v-else-if="viewKey === 'fire-dashboard'"
                     :home-href="homeViewKey ? '#/' : undefined"
                     :home-label="homeViewKey === 'approvals' ? '返回工作台' : '返回系统'"
                     :account-error="actionError" :account-notice="actionNotice">
    <template #account>
      <AccountMenu ref="accountButton" :busy="leaving" @profile="openProfile" @logout="leave" />
    </template>
  </FireDashboardPage>
  <SidebarProvider v-else>
    <AppSidebar :items="session.user.menus" :active="viewKey" />
    <SidebarInset class="min-w-0 bg-background">
      <header class="topbar">
        <SidebarTrigger aria-label="切换导航菜单" title="切换导航菜单" />
        <Separator orientation="vertical" class="h-5" />
        <Breadcrumb class="min-w-0">
          <BreadcrumbList class="flex-nowrap">
            <template v-for="(label, index) in breadcrumb" :key="index">
              <BreadcrumbSeparator v-if="index" class="hidden sm:block" />
              <BreadcrumbItem :class="{ 'hidden sm:inline-flex': index < breadcrumb.length - 1 }">
                <BreadcrumbPage v-if="index === breadcrumb.length - 1" class="truncate">{{ label }}</BreadcrumbPage>
                <span v-else class="truncate">{{ label }}</span>
              </BreadcrumbItem>
            </template>
          </BreadcrumbList>
        </Breadcrumb>
        <div class="ml-auto">
          <AccountMenu ref="accountButton" :busy="leaving" @profile="openProfile" @logout="leave" />
        </div>
      </header>
      <div class="page">
        <Alert v-if="actionError" variant="destructive" class="mb-4">
          <AlertDescription>{{ actionError }}</AlertDescription>
        </Alert>
        <Alert v-if="actionNotice" role="status" class="mb-4">
          <AlertDescription>{{ actionNotice }}</AlertDescription>
        </Alert>
        <component :is="activeView.page" v-if="activeView" :key="viewKey" />
        <Empty v-else class="border">
          <EmptyHeader><EmptyTitle>当前账号没有可访问的页面</EmptyTitle></EmptyHeader>
        </Empty>
      </div>
    </SidebarInset>
  </SidebarProvider>
  <ProfileDialog v-if="session.user && editingProfile" @close="closeProfile()" @saved="closeProfile(true)" />
  <DeletionDialog />
</template>
