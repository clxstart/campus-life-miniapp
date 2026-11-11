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
      { path: 'dispatch', name: 'dispatch', component: () => import('../views/Dispatch.vue'), meta: { title: '配送调度', icon: '⇢' } },
      { path: 'verification', name: 'verification', component: () => import('../views/Verification.vue'), meta: { title: '认证审核', icon: '☑' } },
      // 宿舍域：按学校隔离，校管管理本校；解散只改状态、历史签到记录保留
      { path: 'dorms', name: 'dorms', component: () => import('../views/Dorms.vue'), meta: { title: '宿舍管理', icon: '▥' } },
      // 积分体系：任务目录与段位配置是平台一套，改一个 P 值影响所有学校 → 仅超管
      { path: 'points-tasks', name: 'points-tasks', component: () => import('../views/PointsTasks.vue'), meta: { title: '积分任务', icon: '❖', superOnly: true } },
      // 兑换商品池：价格/库存/限购全平台生效 → 仅超管
      { path: 'points-rewards', name: 'points-rewards', component: () => import('../views/PointsRewards.vue'), meta: { title: '兑换商品', icon: '▣', superOnly: true } },
      // 论坛榜单只算本校，校管可查看与补跑结算（后端仍按 school 范围限制）
      { path: 'points-rank', name: 'points-rank', component: () => import('../views/PointsRank.vue'), meta: { title: '论坛榜单', icon: '⇅' } },
      { path: 'jobs', name: 'jobs', component: () => import('../views/Jobs.vue'), meta: { title: '兼职管理', icon: '⚒' } },
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
      // 运营设置：咨询二维码、微信号、入口开关（小程序「论文指导」卡片点进去的内容）
      { path: 'settings', name: 'settings', component: () => import('../views/Settings.vue'), meta: { title: '运营设置', icon: '⚙' } },
      // 系统状态：服务健康/内存/连接池/磁盘/慢请求，出问题先看这里
      { path: 'system', name: 'system', component: () => import('../views/System.vue'), meta: { title: '系统状态', icon: '◉' } }
    ]
  },
  // 骑手端：骑手自己的工作台，走 /api/rider/**（RiderInterceptor 只认 role=rider）。
  // 与管理端的「骑手管理」(#/riders) 是两回事：那边是管理员维护骑手账号，这边是骑手干活。
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

const router = createRouter({ history: createWebHashHistory(), routes })
router.beforeEach((to) => {
  const authed = !!getToken()
  const role = getProfile()?.role
  // 每种角色只有一个「家」：骑手 /rider、商家 /merchant、管理员 /dashboard。
  // 骑手以前被放进 dashboard，结果菜单错位、每个接口都 403，所以这里必须分开。
  const home = role === 'rider' ? '/rider' : role === 'merchant' ? '/merchant' : '/dashboard'
  if (to.meta.public) {
    return authed ? { path: home } : true
  }
  if (!authed) return { path: '/login', query: { redirect: to.fullPath } }
  // 商家只能进展商家端，骑手只能进骑手端，管理员两端都不能进
  if (role === 'merchant' && !to.path.startsWith('/merchant')) return home
  if (role === 'rider' && !to.path.startsWith('/rider')) return home
  if (role !== 'merchant' && to.path.startsWith('/merchant')) return home
  if (role !== 'rider' && to.path.startsWith('/rider')) return home
  return true
})
export default router
