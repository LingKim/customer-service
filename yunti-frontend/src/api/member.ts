import { request } from './request'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'
const INVITES_KEY = 'yunti_mock_member_invites'
const MEMBERS_KEY = 'yunti_mock_members'
const SESSION_KEY = 'yunti_mock_member_session'

interface MockInvite extends InviteItem {
  email: string
  expireTime: string
}

function readList<T>(key: string): T[] {
  try { return JSON.parse(localStorage.getItem(key) || '[]') as T[] } catch { return [] }
}

function saveList<T>(key: string, items: T[]): void {
  localStorage.setItem(key, JSON.stringify(items))
}

function mockCurrentMember(): MemberItem | undefined {
  const id = localStorage.getItem(SESSION_KEY)
  return readList<MemberItem>(MEMBERS_KEY).find((member) => member.userId === id)
}

function mockInvites(): MockInvite[] {
  return readList<MockInvite>(INVITES_KEY).map((invite) => {
    if (invite.status === 1 && new Date(invite.expireTime).getTime() < Date.now()) {
      return { ...invite, status: 3, statusText: '已失效' }
    }
    return invite
  })
}

export interface MemberRoleOption {
  code: string
  name: string
}

export interface MemberItem {
  userId: string
  userNo: string
  name: string
  phone?: string
  email?: string
  avatar?: string
  roleCode: string
  roleName: string
  status: number
  lastLoginTime?: string
  joinTime?: string
}

export interface InviteItem {
  id: string
  inviteCode: string
  phone?: string
  email?: string
  roleCode?: string
  roleName?: string
  inviterName?: string
  usedByName?: string
  status: number
  statusText: string
  expireTime?: string
  createTime?: string
}

export interface CreateInviteResult {
  id: string
  inviteCode: string
  roleCode: string
  roleName: string
  expireTime: string
  phone?: string
  email?: string
  emailSent: boolean
}

export interface InvitePreview {
  inviteCode: string
  roleCode?: string
  roleName?: string
  inviterName?: string
  phoneMasked?: string
  emailMasked?: string
  expireTime?: string
}

export interface AcceptResult {
  userId: string
  userNo: string
  name: string
  phone?: string
  email?: string
  roleCode: string
  roleName: string
  tenantCode: string
}

export interface MemberAccess {
  canManage: boolean
  roleCode?: string
  roleName?: string
}

/** 当前企业用户是否可管理成员及角色信息 */
export function fetchMemberAccess(): Promise<MemberAccess> {
  if (USE_MOCK) {
    const member = mockCurrentMember()
    return Promise.resolve(member
      ? { canManage: false, roleCode: member.roleCode, roleName: member.roleName }
      : { canManage: true, roleCode: 'ADMIN', roleName: '企业管理员' })
  }
  return request<MemberAccess>({ url: '/user/members/access', method: 'get' })
}

/** 可邀请角色 */
export function listMemberRoles(): Promise<MemberRoleOption[]> {
  if (USE_MOCK) return Promise.resolve([
    { code: 'SUPERVISOR', name: '客服主管' },
    { code: 'SENIOR_AGENT', name: '高级客服' },
    { code: 'AGENT', name: '客服专员' },
    { code: 'QUALITY', name: '质检专员' },
    { code: 'AI_OPERATOR', name: 'AI运营' },
  ])
  return request<MemberRoleOption[]>({ url: '/user/members/roles', method: 'get' })
}

/** 企业成员列表 */
export function listMembers(): Promise<MemberItem[]> {
  if (USE_MOCK) return Promise.resolve([
    { userId: '325036800000001001', userNo: 'U00000000000000001', name: '张伟', roleCode: 'ADMIN', roleName: '企业管理员', status: 1 },
    ...readList<MemberItem>(MEMBERS_KEY),
  ])
  return request<MemberItem[]>({ url: '/user/members', method: 'get' })
}

/** 邀请记录 */
export function listMemberInvites(): Promise<InviteItem[]> {
  if (USE_MOCK) return Promise.resolve(mockInvites())
  return request<InviteItem[]>({ url: '/user/members/invites', method: 'get' })
}

/** 创建邀请 */
export function createMemberInvite(data: {
  phone?: string
  email?: string
  roleCode: string
  expireDays?: number
}): Promise<CreateInviteResult> {
  if (USE_MOCK) {
    const code = `INV${Math.random().toString(36).slice(2, 11).toUpperCase()}`
    const expireTime = new Date(Date.now() + (data.expireDays || 7) * 86400000).toISOString()
    const invite: MockInvite = {
      id: String(Date.now()), inviteCode: code, phone: data.phone, email: data.email || '',
      roleCode: data.roleCode,
      roleName: ({ SUPERVISOR: '客服主管', SENIOR_AGENT: '高级客服', AGENT: '客服专员', QUALITY: '质检专员', AI_OPERATOR: 'AI运营' } as Record<string, string>)[data.roleCode] || '客服专员',
      inviterName: '张伟',
      status: 1, statusText: '待接受', expireTime, createTime: new Date().toISOString(),
    }
    saveList(INVITES_KEY, [invite, ...mockInvites()])
    return Promise.resolve({
      id: invite.id, inviteCode: invite.inviteCode, roleCode: data.roleCode,
      roleName: invite.roleName || '客服专员', expireTime,
      phone: invite.phone, email: invite.email, emailSent: false,
    })
  }
  return request<CreateInviteResult>({ url: '/user/members/invites', method: 'post', data })
}

/** 撤销邀请 */
export function revokeMemberInvite(inviteCode: string): Promise<void> {
  if (USE_MOCK) {
    saveList(INVITES_KEY, mockInvites().map((invite) => invite.inviteCode === inviteCode
      ? { ...invite, status: 3, statusText: '已失效' } : invite))
    return Promise.resolve()
  }
  return request<void>({ url: `/user/members/invites/${inviteCode}/revoke`, method: 'post' })
}

/** 重发邀请邮件 */
export function resendMemberInvite(inviteCode: string): Promise<boolean> {
  if (USE_MOCK) return Promise.resolve(false)
  return request<boolean>({ url: `/user/members/invites/${inviteCode}/resend`, method: 'post' })
}

/** 公共邀请预览 */
export function fetchInvitePreview(code: string): Promise<InvitePreview> {
  if (USE_MOCK) {
    const invite = mockInvites().find((item) => item.inviteCode === code && item.status === 1)
    if (!invite) return Promise.reject(new Error('邀请链接已失效'))
    return Promise.resolve({
      inviteCode: code, roleCode: invite.roleCode, roleName: invite.roleName,
      inviterName: invite.inviterName, emailMasked: invite.email.replace(/^(.{2}).*(@.*)$/, '$1***$2'),
      expireTime: invite.expireTime,
    })
  }
  return request<InvitePreview>({ url: `/user/members/invites/${code}/preview`, method: 'get' })
}

/** 接受邀请 */
export function acceptMemberInvite(
  code: string,
  data: { name: string; password: string; phone?: string; email?: string },
): Promise<AcceptResult> {
  if (USE_MOCK) {
    const invites = mockInvites()
    const invite = invites.find((item) => item.inviteCode === code && item.status === 1)
    if (!invite) return Promise.reject(new Error('邀请链接已失效'))
    if (invite.email.toLowerCase() !== (data.email || '').trim().toLowerCase()) {
      return Promise.reject(new Error('邮箱与邀请时填写的不一致'))
    }
    const member: MemberItem = {
      userId: String(Date.now()), userNo: `U${Date.now()}`, name: data.name,
      phone: data.phone, email: data.email, roleCode: invite.roleCode || 'AGENT',
      roleName: invite.roleName || '客服专员', status: 1, joinTime: new Date().toISOString(),
    }
    saveList(MEMBERS_KEY, [...readList<MemberItem>(MEMBERS_KEY), member])
    saveList(INVITES_KEY, invites.map((item) => item.inviteCode === code
      ? { ...item, status: 2, statusText: '已加入', usedByName: member.name } : item))
    return Promise.resolve({ ...member, tenantCode: 'T000000000000001' })
  }
  return request<AcceptResult>({ url: `/user/members/invites/${code}/accept`, method: 'post', data })
}
