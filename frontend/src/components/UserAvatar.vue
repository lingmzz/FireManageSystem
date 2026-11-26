<script setup>
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'

defineProps({ name: String, src: String })
</script>

<template>
  <Avatar class="shrink-0 rounded-md">
    <AvatarImage v-if="src" :key="src" :src="src" alt="" />
    <AvatarFallback class="rounded-md">{{ Array.from(name || '用户')[0] }}</AvatarFallback>
  </Avatar>
</template>
