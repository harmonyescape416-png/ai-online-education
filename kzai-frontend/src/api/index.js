import axios from 'axios'
import { ElMessage } from 'element-plus'

const TOKEN_KEY = 'kzai_token'

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY) || '',
  set: (t) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY)
}

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use((config) => {
  const token = tokenStore.get()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const body = response.data
    // 统一返回结构 R<T>：code / msg / data / requestId（系统设计说明书 5.2.2）
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 200) {
        return body.data
      }
      if (body.code === 401) {
        tokenStore.clear()
        ElMessage.error(body.msg || '登录已失效，请重新登录')
        window.location.hash = '#/login'
        return Promise.reject(new Error(body.msg))
      }
      const err = new Error(body.msg || '请求失败')
      err.notified = true
      ElMessage.error(err.message)
      return Promise.reject(err)
    }
    return body
  },
  (error) => {
    // 网关在没有可用实例时返回 Spring 的 503 JSON，没有 msg。
    // 不能把 axios 的 "Request failed with status code 503" 直接展示给页面。
    const status = error?.response?.status
    const body = error?.response?.data
    const serverMsg = body && typeof body === 'object' ? (body.msg || null) : null
    let message = serverMsg
    if (!message && (error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT'
      || /^timeout of \d+ms exceeded$/.test(error?.message || ''))) {
      message = '请求超时，请稍后重试'
    }
    if (!message && (status === 502 || status === 503 || status === 504)) {
      message = '服务暂时不可用，请稍后重试'
    }
    if (!message && typeof error?.message === 'string' && error.message.startsWith('Request failed with status code')) {
      message = '网络异常，请稍后重试'
    }
    const err = new Error(message || error?.message || '网络异常，请稍后重试')
    err.code = error?.code
    err.status = status
    err.notified = true
    if (body && typeof body === 'object' && 'code' in body && body.code === 401) {
      tokenStore.clear()
      window.location.hash = '#/login'
    }
    ElMessage.error(err.message)
    return Promise.reject(err)
  }
)

export default request

export const authApi = {
  sendSms: (data) => request.post('/auth/sms/send', data),
  changePhone: (data) => request.post('/auth/change-phone', data),
  login: (data) => request.post('/auth/login', data),
  logout: () => request.post('/auth/logout'),
  me: () => request.get('/users/me')
}

export const promptApi = {
  saveDraft: (agent, body) => request.post(`/admin/ai/prompts/${agent}/save-draft`, body),
  history: (agent) => request.get(`/admin/ai/prompts/${agent}/history`),
  rollback: (agent, v) => request.post(`/admin/ai/prompts/${agent}/rollback/${v}`),
  loadDraft: (agent) => request.get(`/admin/ai/prompts/${agent}/load-draft`)
}

export const courseApi = {
  list: (params) => request.get('/courses', { params }),
  detail: (id) => request.get(`/courses/${id}`),
  search: (keyword) => request.get('/courses/search', { params: { keyword } }),
  categories: () => request.get('/courses/categories')
}

// M2：管理员用户管理（后端 /admin/users 由 @RequireRoles 鉴权）
export const adminApi = {
  list: (params) => request.get('/admin/users', { params }),
  create: (data) => request.post('/admin/users', data),
  update: (id, data) => request.put(`/admin/users/${id}`, data),
  assignRoles: (id, roleCodes) => request.put(`/admin/users/${id}/roles`, { roleCodes }),
  updateStatus: (id, status) => request.put(`/admin/users/${id}/status`, { status }),
  resetPassword: (id, newPassword) => request.put(`/admin/users/${id}/password`, { newPassword }),
  remove: (id) => request.delete(`/admin/users/${id}`)
}

// M2：个人中心
export const profileApi = {
  updateProfile: (data) => request.put('/users/me', data),
  changePassword: (data) => request.put('/users/me/password', data),
  // M15.15：头像上传（FormData 由 axios 自动生成 boundary）
  uploadAvatar: (formData) => request.post('/users/me/avatar', formData)
}

// M3：分类（学员端仅启用分类，管理端全量）
export const categoryApi = {
  tree: () => request.get('/categories/tree'),
  adminTree: () => request.get('/admin/categories/tree'),
  create: (data) => request.post('/admin/categories', data),
  update: (id, data) => request.put(`/admin/categories/${id}`, data),
  updateStatus: (id, status) => request.put(`/admin/categories/${id}/status`, null, { params: { status } }),
  remove: (id) => request.delete(`/admin/categories/${id}`)
}

// M3：教师课程
export const teacherApi = {
  list: (params) => request.get('/teacher/courses', { params }),
  create: (data) => request.post('/teacher/courses', data),
  detail: (id) => request.get(`/teacher/courses/${id}`),
  update: (id, data) => request.put(`/teacher/courses/${id}`, data),
  submit: (id) => request.post(`/teacher/courses/${id}/submit`),
  audit: (id) => request.get(`/teacher/courses/${id}/audit`)
}

// M3：章节
export const chapterApi = {
  list: (courseId) => request.get(`/teacher/courses/${courseId}/chapters`),
  add: (courseId, data) => request.post(`/teacher/courses/${courseId}/chapters`, data),
  update: (id, data) => request.put(`/teacher/chapters/${id}`, data),
  remove: (id) => request.delete(`/teacher/chapters/${id}`),
  reorder: (courseId, chapterIds) => request.put(`/teacher/courses/${courseId}/chapters/order`, { chapterIds }),
  // M5：章节试看设置（教师显式设置，未购学员只能访问明确标记的试看章节）
  preview: (id, data) => request.put(`/teacher/chapters/${id}/preview`, data)
}

// M3：资料与文件
export const resourceApi = {
  list: (courseId) => request.get(`/teacher/courses/${courseId}/resources`),
  bind: (courseId, data) => request.post(`/teacher/courses/${courseId}/resources`, data),
  remove: (id) => request.delete(`/teacher/resources/${id}`),
  unbound: () => request.get('/teacher/resources/unbound'),
  replaceRes: (id, file) => {
    const form = new FormData()
    form.append('file', file)
    return request.post(`/teacher/resources/${id}/replace`, form, { headers: { 'Content-Type': 'multipart/form-data' } })
  },
  history: (id) => request.get(`/teacher/resources/${id}/history`),
  submitKb: (id) => request.post(`/teacher/resources/${id}/submit-kb`),
  retryKb: (id) => request.post(`/teacher/resources/${id}/retry-kb`),
  upload: (file) => {
    const form = new FormData()
    form.append('file', file)
    return request.post('/files/upload', form, { headers: { 'Content-Type': 'multipart/form-data' } })
  }
}

// M3：管理员课程审核
export const auditApi = {
  pendingList: (params) => request.get('/admin/courses/audits', { params }),
  preview: (id) => request.get(`/admin/courses/${id}/preview`),
  approve: (id) => request.post(`/admin/courses/${id}/audit/approve`),
  reject: (id, reason) => request.post(`/admin/courses/${id}/audit/reject`, { reason }),
  offline: (id, reason) => request.post(`/admin/courses/${id}/offline`, { reason })
}

// M4：订单（交易服务）
export const orderApi = {
  prePlace: (data) => request.post('/orders/pre-place', data),
  list: (params) => request.get('/orders', { params }),
  detail: (id) => request.get(`/orders/${id}`),
  cancel: (id) => request.post(`/orders/${id}/cancel`),
  pay: (id, body) => request.post(`/orders/${id}/pay`, body),
  payment: (id) => request.get(`/orders/${id}/payment`),
  retryEnroll: (id) => request.post(`/orders/${id}/retry-enroll`),
  applyRefund: (id, detailId, body) => request.post(`/orders/${id}/details/${detailId}/refunds`, body),
  refunds: (id) => request.get(`/orders/${id}/refunds`)
}

export const pointApi = {
  today: () => request.get('/learning/checkins/today'),
  month: (month) => request.get('/learning/checkins', { params: { month } }),
  checkin: () => request.post('/learning/checkins'),
  summary: () => request.get('/learning/points/summary'),
  ledger: (params) => request.get('/learning/points/ledger', { params }),
  ranks: (params) => request.get('/learning/ranks', { params })
}

export const couponApi = {
  center: () => request.get('/coupons/center'),
  claim: (id) => request.post(`/coupons/${id}/claim`),
  redeem: (code) => request.post('/coupons/redeem', { code }),
  mine: () => request.get('/coupons/mine'),
  mineOne: (id) => request.get(`/coupons/mine/${id}`),
  prices: (id, courseIds) => request.post(`/coupons/mine/${id}/prices`, { courseIds }),
  quote: (courseIds) => request.post('/coupons/quote', { courseIds })
}

export const couponAdminApi = {
  list: () => request.get('/admin/coupons'),
  create: (body) => request.post('/admin/coupons', body),
  update: (id, body) => request.put(`/admin/coupons/${id}`, body),
  publish: (id) => request.post(`/admin/coupons/${id}/publish`),
  stop: (id) => request.post(`/admin/coupons/${id}/stop`)
}

export const refundAdminApi = {
  list: () => request.get('/admin/refunds'),
  approve: (id) => request.post(`/admin/refunds/${id}/approve`),
  reject: (id, reason) => request.post(`/admin/refunds/${id}/reject`, { reason })
}

// M5：学习（学习服务，用户身份与权益一律由服务端判定）
export const learningApi = {
  list: (params) => request.get('/learning/lessons', { params }),
  enroll: (courseId) => request.post('/learning/lessons', { courseId }),
  detail: (courseId) => request.get(`/learning/lessons/${courseId}`),
  progress: (courseId, data) => request.put(`/learning/lessons/${courseId}/progress`, data),
  access: (courseId) => request.get(`/learning/courses/${courseId}/access`),
  chapters: (courseId) => request.get(`/learning/courses/${courseId}/chapters`),
  chapter: (courseId, chapterId) => request.get(`/learning/courses/${courseId}/chapters/${chapterId}`),
  complete: (courseId, chapterId) => request.post(`/learning/courses/${courseId}/chapters/${chapterId}/complete`),
  plans: () => request.get('/learning/plans'),
  joinPlan: (courseId, weeklyGoal) => request.post('/learning/plans', { courseId, weeklyGoal }),
  changePlanGoal: (id, weeklyGoal) => request.put(`/learning/plans/${id}/goal`, { weeklyGoal }),
  pausePlan: (id) => request.post(`/learning/plans/${id}/pause`),
  resumePlan: (id) => request.post(`/learning/plans/${id}/resume`),
  quitPlan: (id) => request.post(`/learning/plans/${id}/quit`)
}

export const noteApi = {
  mine: (params) => request.get('/learning/notes/mine', { params }),
  publicList: (params) => request.get('/learning/notes/public', { params }),
  detail: (id) => request.get(`/learning/notes/${id}`),
  create: (body) => request.post('/learning/notes', body),
  update: (id, body) => request.put(`/learning/notes/${id}`, body),
  remove: (id) => request.delete(`/learning/notes/${id}`),
  like: (id) => request.post(`/learning/notes/${id}/like`),
  unlike: (id) => request.delete(`/learning/notes/${id}/like`),
  comments: (id, params) => request.get(`/learning/notes/${id}/comments`, { params }),
  comment: (id, content) => request.post(`/learning/notes/${id}/comments`, { content }),
  removeComment: (id, commentId) => request.delete(`/learning/notes/${id}/comments/${commentId}`)
}

// M6：AI 会话与对话（aigc 服务，经网关 /session、/chat 路由）
export const aiApi = {
  createSession: () => request.post('/session'),
  history: () => request.get('/session/history'),
  detail: (sessionId) => request.get(`/session/${sessionId}`),
  rename: (sessionId, title) => request.put('/session/history', { sessionId, title }),
  remove: (sessionId) => request.delete('/session/history', { data: { sessionId } }),
  hot: () => request.get('/session/hot'),
  text: (data) => request.post('/chat/text', data),
  stop: (sessionId) => request.post('/chat/stop', { sessionId }),
  templates: () => request.post('/chat/templates')
}

// M5：教师课程学情统计
export const teacherStatsApi = {
  stats: (params) => request.get('/teacher/learning-stats', { params }),
  workbenchSummary: () => request.get('/teacher/learning-stats/workbench-summary'),
  exportCsv: (params) => request.get('/teacher/learning-stats/export', { params, responseType: 'blob' })
}
