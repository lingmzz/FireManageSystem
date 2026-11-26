<script setup>
import { ref } from 'vue'
import { ShieldCheckIcon } from '@lucide/vue'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { login, session } from '../api'

const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  busy.value = true
  error.value = ''
  try {
    await login(username.value, password.value)
  } catch (failure) {
    error.value = failure.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="login-shell">
    <div class="login-layout">
      <div class="login-brand">
        <span class="flex size-9 items-center justify-center rounded-lg bg-primary text-primary-foreground">
          <ShieldCheckIcon aria-hidden="true" />
        </span>
        <span>消防管理系统</span>
      </div>
      <Card>
        <CardHeader>
          <CardTitle class="text-xl">登录工作台</CardTitle>
        </CardHeader>
        <CardContent>
          <form @submit.prevent="submit">
            <FieldGroup layout="compact">
              <Alert v-if="error || session.error" variant="destructive">
                <AlertDescription>{{ error || session.error }}</AlertDescription>
              </Alert>
              <Field :data-invalid="Boolean(error) || undefined">
                <FieldLabel for="login-username">用户名</FieldLabel>
                <Input id="login-username" v-model.trim="username" name="username" autocomplete="username"
                       :aria-invalid="Boolean(error) || undefined" required maxlength="40" />
              </Field>
              <Field :data-invalid="Boolean(error) || undefined">
                <FieldLabel for="login-password">密码</FieldLabel>
                <Input id="login-password" v-model="password" type="password" name="password"
                       autocomplete="current-password" :aria-invalid="Boolean(error) || undefined" required maxlength="72" />
              </Field>
              <Button type="submit" class="mt-1 w-full" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</Button>
            </FieldGroup>
          </form>
        </CardContent>
      </Card>
    </div>
  </main>
</template>
