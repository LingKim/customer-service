import { defineStore } from 'pinia'
import { loginApi, logoutApi } from '../api/user'
import { clearToken, getToken, setTenantCode, setToken } from '../utils/auth'
import type { LoginParams } from '../types'

interface UserState {
  token: string
  name: string
  role: string
}

export const useUserStore = defineStore('user', {
  state: (): UserState => ({
    token: getToken(),
    name: '',
    role: '',
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
  },
  actions: {
    async login(params: LoginParams) {
      const res = await loginApi(params)
      this.token = res.token
      this.name = res.name
      this.role = res.role
      setToken(res.token)
      if (res.tenantCode) {
        setTenantCode(res.tenantCode)
      }
    },
    async logout() {
      try {
        await logoutApi()
      } finally {
        this.reset()
      }
    },
    reset() {
      this.token = ''
      this.name = ''
      this.role = ''
      clearToken()
    },
  },
})
