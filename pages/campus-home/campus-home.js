/**
 * 校园首页
 *
 * 关键约束：
 * - 小院与悬浮宠物**共享同一份外观数据**，不各自维护
 * - 悬浮入口与展开态不同时跑动画（展开时收起悬浮球）
 * - 悬浮入口避开底部导航与安全区
 *
 * 交互设计：点宠物卡片**原地展开**大图互动，不跳转页面
 * （之前是 navigateTo 到互动页，用户会觉得“跳一下”）
 */
const petApi = require('../../utils/pet-api')
const petProfile = require('../../utils/pet-profile')
const { normalizeAppearance, DEFAULT_APPEARANCE } = require('../../utils/pet-appearance')
const { request } = require('../../utils/api')
const Toast = require('tdesign-miniprogram/toast/index')

const GREETINGS = ['早上好', '上午好', '中午好', '下午好', '晚上好']

/** 与引擎动作一一对应 */
const ACTIONS = [
  { key: 'idle',      name: '待机', icon: '◡' },
  { key: 'happy',     name: '开心', icon: '◠' },
  { key: 'sad',       name: '委屈', icon: '⌒' },
  { key: 'sleepy',    name: '困了', icon: '☾' },
  { key: 'celebrate', name: '庆祝', icon: '✦' }
]

const TIPS = {
  idle: '它在发呆，偶尔眨眨眼',
  happy: '尾巴摇起来了',
  sad: '它有点小情绪',
  sleepy: '打了个哈欠',
  celebrate: '跳起来了！'
}