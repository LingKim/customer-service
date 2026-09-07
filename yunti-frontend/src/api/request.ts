import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, getTenantCode } from '../utils/auth'
import type { ApiResponse } from '../types'

const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000,
})

// 请求拦截：携带 token 与租户上下文
service.interceptors.request.use((config) => {
  const token = getToken()
  const tenantCode = getTenantCode()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  if (tenantCode) {
    config.headers['X-Tenant-Code'] = tenantCode
  }
  return config
})

// 响应拦截：仅处理 HTTP 层错误
service.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '网络异常，请稍后重试'
    ElMessage.error(message)
    return Promise.reject(error)
  },
)

/** 统一请求方法：自动解包业务码，成功返回 data */
export async function request<T = unknown>(config: AxiosRequestConfig): Promise<T> {
  const response = await service.request<ApiResponse<T>>(config)
  const body = response.data
  if (body.code !== 0) {
    ElMessage.error(body.message || '请求失败')
    throw new Error(body.message || 'request failed')
  }
  return body.data
}

export default service
