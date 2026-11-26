<script setup>
import {
  BoxesIcon, Building2Icon, ClipboardCheckIcon, FlameIcon, HandshakeIcon,
  LayoutDashboardIcon, LayoutGridIcon, ListTreeIcon, ShieldIcon, SirenIcon, UsersRoundIcon, WorkflowIcon
} from '@lucide/vue'
import {
  SidebarGroup, SidebarGroupContent, SidebarGroupLabel, SidebarMenu, SidebarMenuButton,
  SidebarMenuItem, useSidebar
} from '@/components/ui/sidebar'

defineProps({ items: Array, active: String })
const { setOpenMobile } = useSidebar()
const icons = {
  'fire-dashboard': LayoutDashboardIcon,
  'fire-devices': BoxesIcon,
  'fire-maintainers': HandshakeIcon,
  'fire-drills': SirenIcon,
  'fire-hazards': FlameIcon,
  'fire-units': Building2Icon,
  approvals: ClipboardCheckIcon,
  'approval-flows': WorkflowIcon,
  users: UsersRoundIcon,
  roles: ShieldIcon,
  menus: ListTreeIcon,
}
</script>

<template>
  <SidebarMenu>
    <SidebarMenuItem v-for="item in items" :key="item.id">
      <SidebarMenuButton v-if="item.viewKey" as-child :is-active="active === item.viewKey"
                         :tooltip="item.name" class="group-data-[collapsible=icon]:mx-auto">
        <a :href="'#/' + item.viewKey" :aria-label="item.name" :aria-current="active === item.viewKey ? 'page' : undefined"
           @click="setOpenMobile(false)">
          <component :is="icons[item.viewKey] || LayoutGridIcon" aria-hidden="true" />
          <span class="group-data-[collapsible=icon]:hidden">{{ item.name }}</span>
        </a>
      </SidebarMenuButton>
      <SidebarGroup v-if="item.children.length" class="group-data-[collapsible=icon]:mx-0 group-data-[collapsible=icon]:px-0"
                    :class="item.viewKey ? 'ml-2 py-1' : 'px-0 py-1'">
        <SidebarGroupLabel v-if="!item.viewKey" class="group-data-[collapsible=icon]:hidden">{{ item.name }}</SidebarGroupLabel>
        <SidebarGroupContent>
          <MenuTree :items="item.children" :active="active" />
        </SidebarGroupContent>
      </SidebarGroup>
    </SidebarMenuItem>
  </SidebarMenu>
</template>
