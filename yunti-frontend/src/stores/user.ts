import { defineStore } from 'pinia'
import { loginApi, logoutApi, meApi } from '../api/user'
import { clearToken, getToken, setTenantCode, setToken } from '../utils/auth'
import type { LoginParams, LoginResult } from '../types'

interface UserState {
  token: string
  name: string
  userId: string
  userNo: string
  userType: number
  tenantCode: string
}

export const useUserStore = defineStore('user', {
  state: (): UserState => ({
    token: getToken(),
    name: '',
    userId: '',
    userNo: '',
    userType: 0,
    tenantCode: '',
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
  },
  actions: {
    async login(params: LoginParams) {
      const res = await loginApi(params)
      this.applyLoginResult(res)
    },
    applyLoginResult(res: LoginResult) {
      this.token = res.token
      this.userId = res.userId
      this.userNo = res.userNo
      this.name = res.name
      this.userType = res.userType
      this.tenantCode = res.tenantCode || ''
      setToken(res.token)
      setTenantCode(res.tenantCode || '')
    },
    async logout() {
      try {
        await logoutApi()
      } catch {
        // 后端会话接口暂缺时也不阻塞本地退出
      } finally {
        this.reset()
      }
    },
    /** 恢复会话：调用 /me 刷新当前用户信息 */
    async fetchProfile() {
      const me = await meApi()
      this.userId = me.userId
      this.userNo = me.userNo
      this.name = me.name
      this.userType = me.userType
      this.tenantCode = me.tenantCode || ''
      setTenantCode(me.tenantCode || '')
    },
    reset() {
      this.token = ''
      this.name = ''
      this.userId = ''
      this.userNo = ''
      this.userType = 0
      this.tenantCode = ''
      clearToken()
    },
  },
})
