import { createRouter, createWebHashHistory } from 'vue-router'
import { getProfile, getToken } from '../api'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { public: true, title: '登录' } },
  { path: '/merchant/apply', name: 'MerchantApply', component: () => import('../views/MerchantApply.vue'), meta: { public: true, title: '商家入驻' } },
  {
    path: '/merchant',
    component: () => import('../layouts/MerchantLayout.vue'),
    children: [
      { path: '', name: 'merchant-home', component: () => import('../views/merchant/Home.vue'), meta: { merchantOnly: true, title: '商家首页' } },
      { path: 'shop', name: 'merchant-shop', component: () => import('../views/merchant/Shop.vue'), meta: { merchantOnly: true, title: '店铺设置' } },
      { path: 'goods', name: 'merchant-goods', component: () => import('../views/merchant/Goods.vue'), meta: { merchantOnly: true, title: '菜品管理' } },
      { path: 'orders', name: 'merchant-orders', component: () => import('../views/merchant/Orders.vue'), meta: { merchantOnly: true, title: '订单管理' } }
    ]
  },
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
      { path: 'shops', name: 'shops', component: () => import('../views/Shops.vue'), meta: { title: '外卖管理', icon: '⌂' } },
      { path: 'orders', name: 'Orders', component: () => import('../views/Orders.vue'), meta: { title: '订单管理', icon: '☰', superOnly: false } },
      { path: 'payments', name: 'Payments', component: () => import('../views/Payments.vue'), meta: { title: '支付流水', icon: '¥', superOnly: false } },
      { path: 'merchant-audit', name: 'merchant-audit', component: () => import('../views/MerchantAudit.vue'), meta: { title: '商家入驻审核', icon: '⊞' } },
      { path: 'idol', name: 'idol', component: () => import('../views/Idol.vue'), meta: { title: '校园评选', icon: '★', superOnly: true } },
      { path: 'academics', name: 'academics', component: () => import('../views/Academics.vue'), meta: { title: '教务管理', icon: '▤' } },
      { path: 'business', name: 'business', component: () => import('../views/Business.vue'), meta: { title: '业务管理', icon: '▦' } },
      // 运营配置（Banner）暂时隐藏：小程序轮播位已改成「热门圈子」，banner 没有展示位。
      // 后端 /api/banners 与 Ops.vue 都保留着，将来需要展示位时把这一行放回来即可。
      // { path: 'ops', name: 'ops', component: () => import('../views/Ops.vue'), meta: { title: '运营配置', icon: '✦' } },
      { path: 'logs', name: 'logs', component: () => import('../views/Logs.vue'), meta: { title: '操作日志', icon: '❐' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({ history: createWebHashHistory(), routes })
router.beforeEach((to) => {
  const authed = !!getToken()
  const role = getProfile()?.role
  const home = role === 'merchant' ? '/merchant' : '/dashboard'
  if (to.name === 'login' && authed) return home
  if (to.meta.public) return true
  if (!to.meta.public && !authed) return { path: '/login', query: { redirect: to.fullPath } }
  if (role === 'merchant' && !to.path.startsWith('/merchant')) return '/merchant'
  if (role !== 'merchant' && to.path.startsWith('/merchant')) return '/dashboard'
  return true
})
export default router
