import { request } from '../request'

export interface SkillGroupMember {
  userId: string
  name: string
}

export interface SkillGroupChannel {
  id: string
  name: string
}

export interface SkillGroupItem {
  id: string
  name: string
  description?: string | null
  isDefault: boolean
  /** 排队超过这个秒数就升级（放宽技能组限制），0 表示不升级 */
  overflowAfterSeconds: number
  members: SkillGroupMember[]
  channels: SkillGroupChannel[]
}

export function listSkillGroups(): Promise<SkillGroupItem[]> {
  return request<SkillGroupItem[]>({ url: '/customer/skill-groups', method: 'get' })
}

export function createSkillGroup(data: {
  name: string
  description?: string
  overflowAfterSeconds?: number
}): Promise<SkillGroupItem> {
  return request<SkillGroupItem>({ url: '/customer/skill-groups', method: 'post', data })
}

export function updateSkillGroup(
  groupId: string,
  data: { description?: string; overflowAfterSeconds?: number },
): Promise<SkillGroupItem> {
  return request<SkillGroupItem>({ url: `/customer/skill-groups/${groupId}`, method: 'put', data })
}

/** 覆盖式设置组内坐席 */
export function setSkillGroupMembers(groupId: string, userIds: string[]): Promise<SkillGroupItem> {
  return request<SkillGroupItem>({
    url: `/customer/skill-groups/${groupId}/members`,
    method: 'put',
    data: { userIds },
  })
}

/** 渠道绑定技能组（skillGroupId 传 null 表示解绑） */
export function bindChannelSkillGroup(channelId: string, skillGroupId: string | null): Promise<void> {
  return request<void>({
    url: '/customer/skill-groups/bind-channel',
    method: 'post',
    data: { channelId, skillGroupId },
  })
}
