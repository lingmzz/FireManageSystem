<script setup>
import { computed, ref, watch } from 'vue'
import UserAvatar from './UserAvatar.vue'

const profile = defineModel({ type: Object, required: true })
const props = defineProps({ name: String, savedAvatar: String, prefix: { type: String, default: 'profile' } })
const preview = ref(null)
const fileInput = ref(null)
const src = computed(() => profile.value.avatar === '' ? '' : preview.value || props.savedAvatar)

function fileError(file) {
  if (!file) return ''
  const supported = file.type ? ['image/jpeg', 'image/png'].includes(file.type) : /\.jpe?g$|\.png$/i.test(file.name)
  if (!supported) return '请选择 PNG 或 JPEG 图片'
  if (!file.size || file.size > 2 * 1024 * 1024) return '头像大小须在 2 MB 以内'
  return ''
}

watch(() => profile.value.avatar, (file, _, onCleanup) => {
  preview.value = null
  if (!(file instanceof File) || fileError(file)) return
  const url = URL.createObjectURL(file)
  preview.value = url
  onCleanup(() => URL.revokeObjectURL(url))
})

function selectFile(event, setError) {
  const file = event.target.files[0]
  profile.value.avatar = file ?? null
  const error = fileError(file)
  event.target.setCustomValidity(error)
  setError(error)
}

function remove(clear) {
  fileInput.value.value = ''
  fileInput.value.setCustomValidity('')
  profile.value.avatar = ''
  clear()
}
</script>

<template>
  <UiFormField :name="prefix + '.phone'" label="联系电话" v-slot="{ input }">
    <UiInput v-bind="input" v-model.trim="profile.phone" type="tel" autocomplete="tel" maxlength="40" />
  </UiFormField>
  <UiFormField :name="prefix + '.email'" label="电子邮箱" v-slot="{ input }">
    <UiInput v-bind="input" v-model.trim="profile.email" type="email" autocomplete="email" maxlength="254" />
  </UiFormField>
  <UiFormField :name="prefix + '.avatar'" label="头像" class="form-span" v-slot="{ input, clear, setError }">
    <div class="profile-avatar-editor">
      <UserAvatar :name="name" :src="src" class="size-14" />
      <div class="min-w-0 flex-1">
        <input v-bind="input" ref="fileInput" type="file" class="file-input" accept="image/jpeg,image/png"
               @change="selectFile($event, setError)">
        <p class="mt-1.5 text-xs text-muted-foreground">PNG 或 JPEG，最大 2 MB</p>
      </div>
      <UiButton v-if="src || profile.avatar" variant="ghost" size="sm" type="button" @click="remove(clear)">
        移除头像
      </UiButton>
    </div>
  </UiFormField>
</template>
