<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ChevronsUpDownIcon, LogOutIcon, UserRoundIcon } from '@lucide/vue'
import { Alert, AlertDescription } from '@/components/ui/alert'
import UserAvatar from './components/UserAvatar.vue'
import { Breadcrumb, BreadcrumbItem, BreadcrumbList, BreadcrumbPage, BreadcrumbSeparator } from '@/components/ui/breadcrumb'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem,
  DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger
} from '@/components/ui/dropdown-menu'
import { Empty, EmptyHeader, EmptyTitle } from '@/components/ui/empty'
import { Separator } from '@/components/ui/separator'
import { SidebarInset, SidebarProvider, SidebarTrigger } from '@/components/ui/sidebar'
import { Skeleton } from '@/components/ui/skeleton'
import { logout, refreshSession, session } from './api'
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
  'fire-dashboard': { name: '首页概览', permission: 'fire:dashboard', page: FireDashboardPage },
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

function firstView(items) {
  for (const item of items) {
    if (item.viewKey && allowed.value.includes(item.viewKey)) return item.viewKey
    const nested = firstView(item.children)
    if (nested) return nested
  }
  return null
}

const viewKey = computed(() => {
  if (allowed.value.includes(current.value)) return current.value
  if (allowed.value.includes('fire-dashboard')) return 'fire-dashboard'
  if (allowed.value.includes('approvals')) return 'approvals'
  return firstView(session.user?.menus ?? []) ?? allowed.value[0]
})
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
  nextTick(() => accountButton.value?.$el.focus())
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
        <DropdownMenu>
          <DropdownMenuTrigger as-child>
            <Button ref="accountButton" variant="ghost" size="sm" class="ml-auto shrink-0 gap-2" :disabled="leaving">
              <UserAvatar :name="session.user.displayName || session.user.username"
                          :src="session.user.profile?.avatarUrl" size="sm" class="size-7" />
              <span class="max-w-28 truncate">{{ session.user.displayName }}</span>
              <ChevronsUpDownIcon data-icon="inline-end" aria-hidden="true" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" class="w-48">
            <DropdownMenuGroup>
              <DropdownMenuLabel>{{ session.user.username }}</DropdownMenuLabel>
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              <DropdownMenuItem @select="editingProfile = true; actionNotice = ''">
                <UserRoundIcon data-icon="inline-start" aria-hidden="true" />
                个人信息
              </DropdownMenuItem>
              <DropdownMenuItem :disabled="leaving" @select="leave">
                <LogOutIcon data-icon="inline-start" aria-hidden="true" />
                退出登录
              </DropdownMenuItem>
            </DropdownMenuGroup>
          </DropdownMenuContent>
        </DropdownMenu>
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
    <ProfileDialog v-if="editingProfile" @close="closeProfile()" @saved="closeProfile(true)" />
  </SidebarProvider>
  <DeletionDialog />
</template>
