const TOKEN_KEY = 'yunti_admin_token'
const TENANT_KEY = 'yunti_admin_tenant'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export function getTenantCode(): string {
  return localStorage.getItem(TENANT_KEY) || ''
}

export function setTenantCode(code: string): void {
  localStorage.setItem(TENANT_KEY, code)
}
