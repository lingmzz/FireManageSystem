<script setup>
import { ShieldCheckIcon } from '@lucide/vue'
import {
  Sidebar, SidebarContent, SidebarHeader, SidebarMenu, SidebarMenuButton, SidebarMenuItem, useSidebar
} from '@/components/ui/sidebar'
import MenuTree from './MenuTree.vue'

defineProps({ items: Array, active: String })
const { setOpenMobile } = useSidebar()
</script>

<template>
  <Sidebar collapsible="icon" class="border-sidebar-border">
    <SidebarHeader class="border-b border-sidebar-border px-3 group-data-[collapsible=icon]:px-2">
      <SidebarMenu>
        <SidebarMenuItem>
          <SidebarMenuButton as-child size="lg" tooltip="消防管理系统"
                             class="group-data-[collapsible=icon]:mx-auto group-data-[collapsible=icon]:my-2 group-data-[collapsible=icon]:p-0!">
            <a href="#/" aria-label="消防管理系统" @click="setOpenMobile(false)">
              <span class="flex size-8 shrink-0 items-center justify-center rounded-lg bg-sidebar-primary text-sidebar-primary-foreground">
                <ShieldCheckIcon aria-hidden="true" />
              </span>
              <span class="truncate font-semibold group-data-[collapsible=icon]:hidden">消防管理系统</span>
            </a>
          </SidebarMenuButton>
        </SidebarMenuItem>
      </SidebarMenu>
    </SidebarHeader>
    <SidebarContent class="px-2 py-2">
      <nav aria-label="主菜单">
        <MenuTree :items="items" :active="active" />
      </nav>
    </SidebarContent>
  </Sidebar>
</template>
