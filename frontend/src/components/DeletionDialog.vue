<script setup>
import {
  AlertDialog, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter,
  AlertDialogHeader, AlertDialogTitle
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { deletionRequest, finishDeletion } from '../confirmation'
</script>

<template>
  <AlertDialog :open="Boolean(deletionRequest)" @update:open="open => { if (!open) finishDeletion(false) }">
    <AlertDialogContent v-if="deletionRequest">
      <AlertDialogHeader>
        <AlertDialogTitle>确认删除</AlertDialogTitle>
        <AlertDialogDescription>{{ deletionRequest.message }}</AlertDialogDescription>
      </AlertDialogHeader>
      <AlertDialogFooter>
        <AlertDialogCancel>取消</AlertDialogCancel>
        <Button type="button" variant="destructive" @click="finishDeletion(true)">确认删除</Button>
      </AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
</template>
