<script setup>
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'

const props = defineProps({ title: String, busy: Boolean, wide: Boolean })
const emit = defineEmits(['close'])
const previousFocus = document.activeElement

function close() {
  if (!props.busy) emit('close')
}

function restoreFocus() {
  if (previousFocus instanceof HTMLElement && previousFocus.isConnected) {
    previousFocus.focus({ preventScroll: true })
  }
}
</script>

<template>
  <Dialog :open="true" @update:open="open => { if (!open) close() }">
    <DialogContent
      :class="['app-dialog gap-4', wide ? 'sm:max-w-[760px]' : 'sm:max-w-[480px]']"
      :show-close-button="false"
      :aria-describedby="undefined"
      @interact-outside.prevent
      @escape-key-down.prevent="close"
      @close-auto-focus.prevent="restoreFocus"
    >
      <DialogHeader class="flex-row items-center justify-between gap-3 text-left">
        <DialogTitle>{{ title }}</DialogTitle>
        <Button type="button" variant="ghost" size="sm" :disabled="busy" @click="close">关闭</Button>
      </DialogHeader>
      <slot />
    </DialogContent>
  </Dialog>
</template>
