/** 统一后端响应结构（与后端 ApiResponse 对齐） */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  requestId?: string
  timestamp?: number
}

/** 登录参数 */
export interface LoginParams {
  account: string
  password: string
}

/** 登录返回 */
export interface LoginResult {
  token: string
  name: string
  role: string
  tenantCode?: string
}
