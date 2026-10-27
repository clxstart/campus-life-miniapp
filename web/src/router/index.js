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
      { path: 'audit', name: 'Audit', component: () => import('../views/Audit.vue'), meta: { title: '内容审核', icon: '✓', superOnly: false } },
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '数据看板', icon: '◍' } },
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
      { path: 'riders', name: 'riders', component: () => import('../views/Riders.vue'), meta: { title: '骑手管理', icon: '♧' } },
      { path: 'idol', name: 'idol', component: () => import('../views/Idol.vue'), meta: { title: '校园评选', icon: '★', superOnly: true } },
      { path: 'academics', name: 'academics', component: () => import('../views/Academics.vue'), meta: { title: '教务管理', icon: '▤' } },
      { path: 'business', name: 'business', component: () => import('../views/Business.vue'), meta: { title: '业务管理', icon: '▦' } },
      // 报名审核：只有「填表+材料」模式的活动会有待审记录
      { path: 'enrollments', name: 'enrollments', component: () => import('../views/Enrollments.vue'), meta: { title: '报名审核', icon: '☑' } },
      // 内容举报：用户举报进队列，人工判断后处理
      { path: 'reports', name: 'reports', component: () => import('../views/Reports.vue'), meta: { title: '内容举报', icon: '⚑' } },
      // 运营配置（Banner）暂时隐藏：小程序轮播位已改成「热门圈子」，banner 没有展示位。
      // 后端 /api/banners 与 Ops.vue 都保留着，将来需要展示位时把这一行放回来即可。
      // { path: 'ops', name: 'ops', component: () => import('../views/Ops.vue'), meta: { title: '运营配置', icon: '✦' } },
      { path: 'logs', name: 'logs', component: () => import('../views/Logs.vue'), meta: { title: '操作日志', icon: '❐' } },
      // 系统状态：服务健康/内存/连接池/磁盘/慢请求，出问题先看这里
      { path: 'system', name: 'system', component: () => import('../views/System.vue'), meta: { title: '系统状态', icon: '◉' } }
    ]
  },
  // 骑手端 4 个页面（工作台/待抢订单/我的配送/配送记录）已移除：
  // 骑手配送这条链路在小程序端没有对应入口，管理端也没有派单闭环，
  // 留着只是一个点进去看不到数据、也没法操作的空壳。
  // 管理端的「骑手管理」(#/riders) 保留，用于维护骑手账号。
  { path: '/rider/:pathMatch(.*)*', redirect: '/dashboard' },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({ history: createWebHashHistory(), routes })
router.beforeEach((to) => {
  const authed = !!getToken()
  const role = getProfile()?.role
  // 商家和骑手各有自己的入口：商家进 /merchant；骑手端已移除，骑手账号登录后同样进 /merchant 无权限，
  // 所以直接回到 dashboard（管理端会按角色隐藏无权菜单）。
  const home = role === 'merchant' ? '/merchant' : '/dashboard'
  if (to.meta.public) {
    return authed ? { path: home } : true
  }
  if (!authed) return { path: '/login', query: { redirect: to.fullPath } }
  // 商家只能进 /merchant，管理员不能进商家端
  if (role === 'merchant' && !to.path.startsWith('/merchant')) return '/merchant'
  if (role !== 'merchant' && to.path.startsWith('/merchant')) return home
  return true
})
export default router
