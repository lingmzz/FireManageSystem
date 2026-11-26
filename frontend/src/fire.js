export const unitKinds = { SEGMENT: '车务段', DEPARTMENT: '科室', STATION: '车站' }
export const roleByUnit = { SEGMENT: 'FIRE_SEGMENT', DEPARTMENT: 'FIRE_DEPARTMENT', STATION: 'FIRE_STATION' }
export const deviceTypes = {
  EXTINGUISHER: '灭火器',
  HYDRANT: '消火栓',
  SMOKE_ALARM: '烟感报警器',
  HOSE: '消防水带',
  EMERGENCY_CABINET: '消防应急柜',
  RESPIRATOR: '防毒面罩',
  OTHER: '其它'
}
export const deviceStates = { 0: '已报废', 1: '在用', 2: '库存' }
export const noticeLabels = { 2: '6个月内到期', 3: '3个月内到期', 9: '已过期', 5: '年检提示', 6: '待年检' }

export function unitOptions(units) {
  const byId = new Map(units.map(unit => [unit.id, unit]))
  return units.map(unit => {
    const parts = [unit.name]
    for (let parent = byId.get(unit.parentId); parent; parent = byId.get(parent.parentId)) {
      parts.unshift(parent.name)
    }
    return { ...unit, label: parts.join(' / ') }
  })
}

export function params(values) {
  return new URLSearchParams(Object.entries(values)
    .filter(([, value]) => value !== null && value !== '' && value !== undefined))
}

export async function download(path, name) {
  const response = await fetch(path, { cache: 'no-store' })
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    throw new Error(error.detail || '下载失败，请稍后重试')
  }
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}
