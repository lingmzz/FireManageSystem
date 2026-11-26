export function profileDraft(profile) {
  return { phone: profile?.phone ?? '', email: profile?.email ?? '', avatar: null }
}

export async function profilePayload({ phone, email, avatar }) {
  if (avatar instanceof File) {
    avatar = await new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result).split(',')[1])
      reader.onerror = () => reject(new Error('头像文件读取失败，请重新选择'))
      reader.readAsDataURL(avatar)
    })
  }
  return { phone, email, avatar }
}
