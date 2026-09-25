import { request } from './request'
import { getToken } from '../utils/auth'
import type {
  CaptchaResult,
  ChangePasswordParams,
  LoginParams,
  LoginResult,
  MeResult,
  RegisterParams,
  RegisterResult,
} from '../types'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'
const MOCK_GUIDE_KEY = 'yunti_mock_enterprise_guide'
const MOCK_MEMBERS_KEY = 'yunti_mock_members'
const MOCK_MEMBER_SESSION_KEY = 'yunti_mock_member_session'

function mockMember(account: string): { userId: string; userNo: string; name: string; phone?: string; email?: string } | undefined {
  try {
    const members = JSON.parse(localStorage.getItem(MOCK_MEMBERS_KEY) || '[]') as Array<{
      userId: string; userNo: string; name: string; phone?: string; email?: string
    }>
    return members.find((member) => member.email?.toLowerCase() === account.toLowerCase() || member.phone === account)
  } catch { return undefined }
}

function mockTenantCode(): string {
  try {
    const guide = JSON.parse(localStorage.getItem(MOCK_GUIDE_KEY) || '{}') as { tenantCode?: string }
    return guide.tenantCode || 'PLATFORM'
  } catch { return 'PLATFORM' }
}

/** 开发环境 Mock：后端联调时置 VITE_USE_MOCK=false */
function mockLogin(params: LoginParams): Promise<LoginResult> {
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      if (params.account && params.password.length >= 6) {
        const platform = params.account.trim().toLowerCase() === 'admin@yunti.example.com'
        const member = mockMember(params.account.trim())
        if (member) localStorage.setItem(MOCK_MEMBER_SESSION_KEY, member.userId)
        else localStorage.removeItem(MOCK_MEMBER_SESSION_KEY)
        resolve({
          token: `mock-token-${platform ? 'admin' : member ? 'member' : 'enterprise'}-${Date.now()}`,
          userId: member?.userId || (platform ? '325036800000001099' : '325036800000001001'),
          userNo: member?.userNo || (platform ? 'U90000000000000001' : 'U00000000000000001'),
          name: member?.name || (platform ? '平台管理员' : '张伟'),
          userType: platform ? 1 : 2,
          tenantCode: platform ? 'PLATFORM' : member ? 'T000000000000001' : mockTenantCode(),
        })
      } else {
        reject(new Error('账号或密码错误'))
      }
    }, 400)
  })
}

/** 获取图形验证码（登录 / 注册共用） */
export function captchaApi(): Promise<CaptchaResult> {
  if (USE_MOCK) {
    const imageBase64 = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="118" height="40"><rect width="118" height="40" fill="#eef4ff"/><text x="32" y="29" font-size="24" fill="#1d4ed8">1234</text></svg>')}`
    return Promise.resolve({ captchaId: 'mock', imageBase64, debugCode: '1234' })
  }
  return request<CaptchaResult>({
    url: '/user/auth/captcha',
    method: 'get',
  })
}

/** 企业账号注册 */
export function registerApi(data: RegisterParams): Promise<RegisterResult> {
  return request<RegisterResult>({
    url: '/user/auth/register',
    method: 'post',
    data,
  })
}

/** 账号密码登录 */
export function loginApi(data: LoginParams): Promise<LoginResult> {
  if (USE_MOCK) {
    return mockLogin(data)
  }
  return request<LoginResult>({
    url: '/user/auth/login',
    method: 'post',
    data,
  })
}

/** 当前登录用户（恢复会话 / 判断引导状态） */
export function meApi(): Promise<MeResult> {
  if (USE_MOCK) {
    const platform = getToken().startsWith('mock-token-admin-')
    const memberId = localStorage.getItem(MOCK_MEMBER_SESSION_KEY)
    let member: ReturnType<typeof mockMember>
    try {
      const members = JSON.parse(localStorage.getItem(MOCK_MEMBERS_KEY) || '[]') as Array<NonNullable<ReturnType<typeof mockMember>>>
      member = members.find((item) => item.userId === memberId)
    } catch { member = undefined }
    return Promise.resolve({
      userId: member?.userId || (platform ? '325036800000001099' : '325036800000001001'),
      userNo: member?.userNo || (platform ? 'U90000000000000001' : 'U00000000000000001'),
      name: member?.name || (platform ? '平台管理员' : '张伟'),
      userType: platform ? 1 : 2,
      tenantCode: platform ? 'PLATFORM' : member ? 'T000000000000001' : mockTenantCode(),
    })
  }
  return request<MeResult>({
    url: '/user/auth/me',
    method: 'get',
  })
}

/** 退出登录 */
export function logoutApi(): Promise<void> {
  if (USE_MOCK) {
    localStorage.removeItem(MOCK_MEMBER_SESSION_KEY)
    return Promise.resolve()
  }
  return request<void>({
    url: '/auth/logout',
    method: 'post',
  })
}

/** 修改当前登录用户密码 */
export function changePasswordApi(data: ChangePasswordParams): Promise<void> {
  return request<void>({
    url: '/user/auth/password',
    method: 'put',
    data,
  })
}
