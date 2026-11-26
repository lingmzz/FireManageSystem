<script setup>
import { ref } from 'vue'
import { api, session } from '../api'
import { profileDraft, profilePayload } from '../userProfile'
import ModalDialog from './ModalDialog.vue'
import ProfileFields from './ProfileFields.vue'

const emit = defineEmits(['close', 'saved'])
const user = session.user
const displayName = ref(user.displayName)
const profile = ref(profileDraft(user.profile))
const saving = ref(false)

async function save(report) {
  saving.value = true
  try {
    session.user = await api('/api/auth/profile', 'PUT', {
      displayName: displayName.value,
      profile: await profilePayload(profile.value)
    })
    emit('saved')
  } catch (failure) {
    report(failure)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <ModalDialog title="个人信息" wide :busy="saving" @close="emit('close')">
    <dl class="detail-meta">
      <div><dt>用户名</dt><dd class="break-all">{{ user.username }}</dd></div>
      <div><dt>所属单位</dt><dd>{{ user.profile?.unitName || '未分配' }}</dd></div>
    </dl>
    <UiValidatedForm @submit="save">
      <UiFieldGroup layout="grid">
        <UiFormField name="displayName" label="姓名" required class="form-span" v-slot="{ input }">
          <UiInput v-bind="input" v-model.trim="displayName" maxlength="80" autocomplete="name" />
        </UiFormField>
        <ProfileFields v-model="profile" :name="displayName" :saved-avatar="user.profile?.avatarUrl" />
      </UiFieldGroup>
      <div class="form-actions">
        <UiButton type="button" variant="outline" :disabled="saving" @click="emit('close')">取消</UiButton>
        <UiButton type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存' }}</UiButton>
      </div>
    </UiValidatedForm>
  </ModalDialog>
</template>
