<script setup>
import { computed } from 'vue'
import { pageSize } from '../api'

const props = defineProps({ page: Number, total: Number, busy: Boolean })
defineEmits(['change'])
const pages = computed(() => Math.max(1, Math.ceil(props.total / pageSize)))
</script>

<template>
  <div class="pagination">
    <span>共 {{ total }} 条</span>
    <div>
      <UiButton :disabled="busy || page <= 1"
              @click="$emit('change', page - 1)" variant="outline" size="sm">上一页</UiButton>
      <span class="page-index">{{ page }} / {{ pages }}</span>
      <UiButton :disabled="busy || page >= pages"
              @click="$emit('change', page + 1)" variant="outline" size="sm">下一页</UiButton>
    </div>
  </div>
</template>
