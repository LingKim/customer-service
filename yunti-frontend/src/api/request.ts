import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearToken, getToken, getTenantCode } from '../utils/auth'
import type { ApiResponse } from '../types'

const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000,
})

/** 请求配置：silent=true 时失败不弹全局提示（用于权限探测等可预期的失败） */
export interface RequestConfig extends AxiosRequestConfig {
  silent?: boolean
}

let unauthorizedHandling = false

/** 登录失效统一处理：专业提示 → 清空登录态 → 跳转登录页 */
function handleUnauthorized(message?: string) {
  if (unauthorizedHandling) return
  unauthorizedHandling = true
  ElMessageBox.alert(
    message === '未认证或登录已过期' ? '当前登录状态已过期或账号已在其他设备登录，请重新登录后继续使用。' : (message || '登录状态已失效，请重新登录'),
    '登录状态已失效',
    {
      type: 'warning',
      confirmButtonText: '重新登录',
      closeOnClickModal: false,
      showClose: false,
    },
  )
    .finally(() => {
      clearToken()
      localStorage.removeItem('yunti_admin_tenant')
      localStorage.removeItem('yunti_onboard_done')
      window.location.href = '/login'
    })
}

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
    if (error?.response?.status === 401 && !(error?.config as RequestConfig | undefined)?.silent) {
      handleUnauthorized()
      return Promise.reject(error)
    }
    const message = error?.response?.data?.message || error?.message || '网络异常，请稍后重试'
    if (!(error?.config as RequestConfig | undefined)?.silent) {
      ElMessage.error(message)
    }
    return Promise.reject(error)
  },
)

/** 统一请求方法：自动解包业务码，成功返回 data */
export async function request<T = unknown>(config: RequestConfig): Promise<T> {
  const response = await service.request<ApiResponse<T>>(config)
  const body = response.data
  if (body.code !== 0) {
    if ((body.code === 40100 || body.code === 40110) && !config.silent) {
      handleUnauthorized(body.message)
      throw new Error(body.message || 'request failed')
    }
    if (!config.silent) {
      ElMessage.error(body.message || '请求失败')
    }
    throw new Error(body.message || 'request failed')
  }
  return body.data
}

export default service
