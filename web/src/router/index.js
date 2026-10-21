import { createRouter, createWebHashHistory } from 'vue-router'
import { getProfile, getToken } from '../api'

// 管理端路由：全部在 AdminLayout 下，除登录页
const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: () => import('../layouts/AdminLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '数据看板', icon: '◍' } },
      { path: 'schools', name: 'schools', component: () => import('../views/Schools.vue'), meta: { title: '学校管理', icon: '⌂', superOnly: true } },
      { path: 'admins', name: 'admins', component: () => import('../views/Admins.vue'), meta: { title: '管理员', icon: '☖', superOnly: true } },
      { path: 'users', name: 'users', component: () => import('../views/Users.vue'), meta: { title: '用户管理', icon: '☺' } },
      { path: 'posts', name: 'posts', component: () => import('../views/Posts.vue'), meta: { title: '内容管理', icon: '✎' } },
      { path: 'circles', name: 'circles', component: () => import('../views/Circles.vue'), meta: { title: '圈子管理', icon: '◎' } },
      { path: 'locations', name: 'locations', component: () => import('../views/Locations.vue'), meta: { title: '地点管理', icon: '⌖' } },
      { path: 'ai-bot', name: 'ai-bot', component: () => import('../views/AiBot.vue'), meta: { title: 'AI 助手', icon: '✦', superOnly: true } },
      { path: 'shops', name: 'shops', component: () => import('../views/Shops.vue'), meta: { title: '外卖管理', icon: '⌂' } },
      { path: 'riders', name: 'riders', component: () => import('../views/Riders.vue'), meta: { title: '骑手管理', icon: '♧' } },
      { path: 'idol', name: 'idol', component: () => import('../views/Idol.vue'), meta: { title: '校园评选', icon: '★', superOnly: true } },
      { path: 'academics', name: 'academics', component: () => import('../views/Academics.vue'), meta: { title: '教务管理', icon: '▤' } },
      { path: 'business', name: 'business', component: () => import('../views/Business.vue'), meta: { title: '业务管理', icon: '▦' } },
      // 运营配置（Banner）暂时隐藏：小程序轮播位已改成「热门圈子」，banner 没有展示位。
      // 后端 /api/banners 与 Ops.vue 都保留着，将来需要展示位时把这一行放回来即可。
      // { path: 'ops', name: 'ops', component: () => import('../views/Ops.vue'), meta: { title: '运营配置', icon: '✦' } },
      { path: 'logs', name: 'logs', component: () => import('../views/Logs.vue'), meta: { title: '操作日志', icon: '❐' } }
    ]
  },
  {
    path: '/rider',
    component: () => import('../layouts/RiderLayout.vue'),
    meta: { riderOnly: true },
    children: [
      { path: '', name: 'rider-home', component: () => import('../views/rider/Home.vue'), meta: { title: '工作台' } },
      { path: 'pool', name: 'rider-pool', component: () => import('../views/rider/Pool.vue'), meta: { title: '待抢订单' } },
      { path: 'mine', name: 'rider-mine', component: () => import('../views/rider/Mine.vue'), meta: { title: '我的配送' } },
      { path: 'history', name: 'rider-history', component: () => import('../views/rider/History.vue'), meta: { title: '配送记录' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  const authed = !!getToken()
  const role = getProfile()?.role
  if (!to.meta.public && !authed) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.public && authed) return { path: role === 'rider' ? '/rider' : role === 'merchant' ? '/merchant' : '/dashboard' }
  if (role === 'rider' && !to.path.startsWith('/rider')) return '/rider'
  if (role !== 'rider' && to.path.startsWith('/rider')) return role === 'merchant' ? '/merchant' : '/dashboard'
  return true
})

export default router
