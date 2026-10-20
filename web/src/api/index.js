import axios from 'axios'

// 管理端 API 客户端
// 与小程序不同：这里 token 失效必须跳登录，不能静默降级
const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

const TOKEN_KEY = 'campus_admin_token'
const ADMIN_KEY = 'campus_admin_profile'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}
export function setAuth(token, profile) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  if (profile) localStorage.setItem(ADMIN_KEY, JSON.stringify(profile))
}
export function getProfile() {
  try { return JSON.parse(localStorage.getItem(ADMIN_KEY) || 'null') } catch { return null }
}
export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ADMIN_KEY)
}

http.interceptors.request.use(config => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  response => {
    const body = response.data || {}
    if (body.code === 0) return body.data
    return Promise.reject(new Error(body.msg || '请求失败'))
  },
  error => {
    const status = error.response?.status
    const msg = error.response?.data?.msg
    if (status === 401) {
      // 登录失效：清掉本地态并跳登录（用 hash 避免循环依赖 router）
      clearAuth()
      if (!location.hash.startsWith('#/login')) location.hash = '#/login'
      return Promise.reject(new Error(msg || '登录已过期，请重新登录'))
    }
    if (status === 403) return Promise.reject(new Error(msg || '无权限执行该操作'))
    return Promise.reject(new Error(msg || error.message || '网络异常'))
  }
)

export default http

// ---- 认证 ----
export const adminApi = {
  login: (username, password) => http.post('/admin/login', { username, password }),
  profile: () => http.get('/admin/profile'),
  dashboard: () => http.get('/admin/dashboard'),
  admins: () => http.get('/admin/admins'),
  createAdmin: (payload) => http.post('/admin/admins', payload),
  setAdminStatus: (id, status) => http.post(`/admin/admins/${id}/status`, { status }),
  logs: (params) => http.get('/admin/logs', { params })
}
