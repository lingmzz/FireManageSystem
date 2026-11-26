import { shallowRef } from 'vue'

export const deletionRequest = shallowRef(null)

export function confirmDeletion(message) {
  if (deletionRequest.value) return Promise.resolve(false)
  return new Promise(resolve => {
    deletionRequest.value = { message, resolve }
  })
}

export function finishDeletion(confirmed) {
  const request = deletionRequest.value
  deletionRequest.value = null
  request?.resolve(confirmed)
}
