<script setup>
import { ref } from 'vue'
import { ChevronsUpDownIcon, LogOutIcon, UserRoundIcon } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem,
  DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger
} from '@/components/ui/dropdown-menu'
import { session } from '../api'
import UserAvatar from './UserAvatar.vue'

defineProps({ busy: Boolean })
const emit = defineEmits(['profile', 'logout'])
const trigger = ref(null)

defineExpose({ focus: () => trigger.value?.$el.focus() })
</script>

<template>
  <DropdownMenu>
    <DropdownMenuTrigger as-child>
      <Button ref="trigger" variant="ghost" size="sm" class="gap-2" :disabled="busy">
        <UserAvatar :name="session.user.displayName || session.user.username"
                    :src="session.user.profile?.avatarUrl" size="sm" class="size-7" />
        <span class="max-w-28 truncate">{{ session.user.displayName || session.user.username }}</span>
        <ChevronsUpDownIcon data-icon="inline-end" aria-hidden="true" />
      </Button>
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end" class="w-48">
      <DropdownMenuGroup>
        <DropdownMenuLabel>{{ session.user.username }}</DropdownMenuLabel>
      </DropdownMenuGroup>
      <DropdownMenuSeparator />
      <DropdownMenuGroup>
        <DropdownMenuItem @select="emit('profile')">
          <UserRoundIcon data-icon="inline-start" aria-hidden="true" />
          个人信息
        </DropdownMenuItem>
        <DropdownMenuItem :disabled="busy" @select="emit('logout')">
          <LogOutIcon data-icon="inline-start" aria-hidden="true" />
          退出登录
        </DropdownMenuItem>
      </DropdownMenuGroup>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
