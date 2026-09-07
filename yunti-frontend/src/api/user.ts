import { request } from './request'
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

/** 开发环境 Mock：后端联调时置 VITE_USE_MOCK=false */
function mockLogin(params: LoginParams): Promise<LoginResult> {
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      if (params.account && params.password.length >= 6) {
        resolve({
          token: 'mock-token-' + Date.now(),
          userId: 1,
          userNo: 'U00000000000000001',
          name: '张伟',
          userType: 1,
          tenantCode: 'T202609020001',
        })
      } else {
        reject(new Error('账号或密码错误'))
      }
    }, 400)
  })
}

/** 获取图形验证码（登录 / 注册共用） */
export function captchaApi(): Promise<CaptchaResult> {
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
  return request<MeResult>({
    url: '/user/auth/me',
    method: 'get',
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

/** 修改当前登录用户密码 */
export function changePasswordApi(data: ChangePasswordParams): Promise<void> {
  return request<void>({
    url: '/user/auth/password',
    method: 'put',
    data,
  })
}
