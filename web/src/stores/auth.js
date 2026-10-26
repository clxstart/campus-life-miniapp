import { defineStore } from 'pinia'
import { adminApi, setAuth, getProfile, clearAuth, getToken } from '../api'


// 负责验证服务是否到达
export const useAuthStore = defineStore('auth', {
  state: () => ({
    profile: getProfile(),
    ready: !!getToken()
  }),

  getters: {
    isSuper: (state) => state.profile?.role === 'super',
    isLoggedIn: (state) => !!state.profile,
    displayName: (state) => state.profile?.realName || state.profile?.username || '',
    roleLabel: (state) => ({ super: '超级管理员', school: '学校管理员', merchant: '商家' }[state.profile?.role] || '账号')
  },

  actions: {
    async login(username, password) {
      const data = await adminApi.login(username, password)
      setAuth(data.token, data)
      this.profile = data
      this.ready = true
      return data
    },

    async refresh() {
      if (!getToken()) return null
      try {
        const data = await adminApi.profile()
        // profile 只返回 id/role/schoolId/realName，补上本地已有的 username
        const merged = { ...this.profile, ...data }
        setAuth(null, merged)
        this.profile = merged
        return merged
      } catch (error) {
        this.logout()
        throw error
      }
    },

    logout() {
      clearAuth()
      this.profile = null
      this.ready = false
    }
  }
})
