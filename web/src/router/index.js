import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from '../api'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: () => import('../layouts/AdminLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '数据看板', icon: '◉' } },
      { path: 'schools', name: 'schools', component: () => import('../views/Schools.vue'), meta: { title: '学校管理', icon: '⌂', superOnly: true } },
      { path: 'admins', name: 'admins', component: () => import('../views/Admins.vue'), meta: { title: '管理员', icon: '⚙', superOnly: true } },
      { path: 'users', name: 'users', component: () => import('../views/Users.vue'), meta: { title: '用户管理', icon: '♙' } },
      { path: 'posts', name: 'posts', component: () => import('../views/Posts.vue'), meta: { title: '内容管理', icon: '✓' } },
      { path: 'circles', name: 'circles', component: () => import('../views/Circles.vue'), meta: { title: '圈子管理', icon: '◌' } },
      { path: 'locations', name: 'locations', component: () => import('../views/Locations.vue'), meta: { title: '地点管理', icon: '⌖' } },
      { path: 'ai-bot', name: 'ai-bot', component: () => import('../views/AiBot.vue'), meta: { title: 'AI 助手', icon: '✦', superOnly: true } },
      { path: 'shops', name: 'shops', component: () => import('../views/Shops.vue'), meta: { title: '外卖管理', icon: '⌁' } },
      { path: 'orders', name: 'Orders', component: () => import('../views/Orders.vue'), meta: { title: '订单管理', icon: '☰', superOnly: false } },
      { path: 'payments', name: 'Payments', component: () => import('../views/Payments.vue'), meta: { title: '支付流水', icon: '¥', superOnly: false } },
      { path: 'idol', name: 'idol', component: () => import('../views/Idol.vue'), meta: { title: '校园评选', icon: '☆', superOnly: true } },
      { path: 'academics', name: 'academics', component: () => import('../views/Academics.vue'), meta: { title: '教务管理', icon: '▣' } },
      { path: 'business', name: 'business', component: () => import('../views/Business.vue'), meta: { title: '业务管理', icon: '▤' } },
      { path: 'logs', name: 'logs', component: () => import('../views/Logs.vue'), meta: { title: '操作日志', icon: '◷' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({ history: createWebHashHistory(), routes })
router.beforeEach((to) => {
  const authed = !!getToken()
  if (!to.meta.public && !authed) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.public && authed) return { path: '/dashboard' }
  return true
})
export default router
