import { reactive } from 'vue'

export const session = reactive({ user: null, ready: false, error: '' })

export async function api(path, method = 'GET', data) {
  const headers = { Accept: 'application/json' }
  if (method !== 'GET') {
    const tokenResponse = await fetch('/api/auth/csrf', { cache: 'no-store' })
    if (!tokenResponse.ok) {
      throw new Error('无法建立登录会话，请稍后重试')
    }
    const csrf = await tokenResponse.json()
    headers[csrf.header] = csrf.token
  }
  const multipart = data instanceof FormData
  if (data !== undefined && !multipart) headers['Content-Type'] = 'application/json'

  const response = await fetch(path, {
    method,
    headers,
    body: data === undefined || multipart ? data : JSON.stringify(data),
    cache: 'no-store'
  })
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    if (response.status === 401 && path !== '/api/auth/login') session.user = null
    throw Object.assign(new Error(error.detail || '请求失败，请稍后重试'), { fields: error.fieldErrors })
  }
  return response.status === 204 ? null : response.json()
}

export async function refreshSession() {
  try {
    session.user = await api('/api/auth/me')
    session.error = ''
  } catch (error) {
    session.user = null
    session.error = error.message === '请先登录' || error.message.includes('登录已失效') ? '' : error.message
  } finally {
    session.ready = true
  }
}

export async function login(username, password) {
  session.user = await api('/api/auth/login', 'POST', { username, password })
  session.error = ''
}

export async function logout() {
  await api('/api/auth/logout', 'POST')
  session.user = null
}

export function dateTime(value) {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}

export const pageSize = 12
