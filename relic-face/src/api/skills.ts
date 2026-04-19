import type { SkillImportResponse, SkillsSnapshot, SkillUpdateResponse } from '@/types'

const BASE = '/api'

export async function listSkills(): Promise<SkillsSnapshot> {
  const response = await fetch(`${BASE}/skills`)
  if (!response.ok) {
    throw new Error(`获取 Skills 失败: HTTP ${response.status}`)
  }
  return response.json()
}

export async function setSkillEnabled(skillKey: string, enabled: boolean): Promise<SkillUpdateResponse> {
  const response = await fetch(`${BASE}/skills/${encodeURIComponent(skillKey)}/enabled`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled })
  })
  if (!response.ok) {
    throw new Error(`更新 Skill 状态失败: HTTP ${response.status}`)
  }
  return response.json()
}

export async function importSkill(source: string): Promise<SkillImportResponse> {
  const response = await fetch(`${BASE}/skills/import`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ source })
  })
  if (!response.ok) {
    throw new Error(`导入 Skill 失败: HTTP ${response.status}`)
  }
  return response.json()
}
