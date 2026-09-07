import { request } from './request'
import type { LoginParams, LoginResult } from '../types'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

/** 开发环境 Mock：后端联调时置 VITE_USE_MOCK=false */
function mockLogin(params: LoginParams): Promise<LoginResult> {
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      if (params.account && params.password.length >= 6) {
        resolve({
          token: 'mock-token-' + Date.now(),
          name: '张伟',
          role: '系统管理员',
          tenantCode: 'T202609020001',
        })
      } else {
        reject(new Error('账号或密码错误'))
      }
    }, 400)
  })
}

/** 账号密码登录 */
export function loginApi(data: LoginParams): Promise<LoginResult> {
  if (USE_MOCK) {
    return mockLogin(data)
  }
  return request<LoginResult>({
    url: '/auth/login',
    method: 'post',
    data,
  })
}

/** 退出登录 */
export function logoutApi(): Promise<void> {
  if (USE_MOCK) {
    return Promise.resolve()
  }
  return request<void>({
    url: '/auth/logout',
    method: 'post',
  })
}
