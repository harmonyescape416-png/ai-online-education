import { defineStore } from 'pinia'
import { authApi, tokenStore } from '../api'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: tokenStore.get(),
    profile: null,
    /** 权限角色：ADMIN / STAFF / TEACHER / STUDENT，来自后端，不信任前端自行声明 */
    roles: [],
    loading: false
  }),
  getters: {
    isLogin: (s) => Boolean(s.token),
    roleName: (s) => s.profile?.roleName || '',
    hasRole: (s) => (code) => s.roles.includes(code),
    /** 是否可进入管理后台（与后端 @RequireRoles 保持一致） */
    canManage: (s) => s.roles.includes('ADMIN') || s.roles.includes('STAFF'),
    /** M8：管理员（Prompt 模板管理仅 ADMIN） */
    isAdmin: (s) => s.roles.includes('ADMIN'),
    /** M3：教师端能力（管理员可代为处理课程） */
    isTeacher: (s) => s.roles.includes('TEACHER') || s.roles.includes('ADMIN'),
    /** M4：学员端能力（下单与查看订单） */
    isStudent: (s) => s.roles.includes('STUDENT') || s.roles.includes('ADMIN')
  },
  actions: {
    async login(username, password) {
      this.loading = true
      try {
        const data = await authApi.login({ username, password })
        this.token = data.token
        this.roles = data.roles || []
        tokenStore.set(data.token)
        await this.fetchProfile()
        return data
      } finally {
        this.loading = false
      }
    },
    async fetchProfile() {
      if (!this.token) return null
      this.profile = await authApi.me()
      if (this.profile?.roles?.length) {
        this.roles = this.profile.roles
      }
      return this.profile
    },
    async logout() {
      try {
        await authApi.logout()
      } finally {
        this.token = ''
        this.profile = null
        this.roles = []
        tokenStore.clear()
      }
    }
  }
})
