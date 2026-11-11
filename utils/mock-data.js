// 演示数据里的图片统一走 asset()：已迁到服务端的图返回远端地址，
// 没迁的（图标、头像、还没压缩的图）原样返回包内路径，所以部分迁移期间也不会白图。
// 详见 utils/remote-assets.js。
const { asset } = require('./remote-assets')

const discoveryPosts = [
  {
    id: 1,
    author: '林知夏',
    avatar: asset('/assets/social/profile-avatar.webp'),
    badge: '校园摄影师',
    time: '12分钟前',
    title: '图书馆靠窗的位置，原来下午这么治愈',
    content: '阳光、绿树和刚刚写完的课堂笔记。期中周也可以有一点松弛感。',
    image: asset('/assets/social/library-study.webp'),
    location: '逸夫图书馆',
    likes: 236,
    comments: 28,
    liked: false,
    followed: false,
    tag: '校园日常',
    circleId: 5
  },
  {
    id: 2,
    author: '周予安',
    avatarText: '周',
    avatarColor: '#7EB6F6',
    badge: '闲置达人',
    time: '35分钟前',
    title: '周末草坪市集，好多宝藏旧物',
    content: '淘到了一盏很有氛围感的小台灯，还有绝版社团纪念册。下午四点前都在。',
    image: asset('/assets/social/flea-market.webp'),
    location: '紫荆操场',
    likes: 188,
    comments: 42,
    liked: true,
    followed: true,
    tag: '校园市集',
    circleId: 4
  },
  {
    id: 3,
    author: '陈一川',
    avatarText: '陈',
    avatarColor: '#FFB56A',
    badge: '体育部',
    time: '1小时前',
    title: '今晚七点，东操篮球场缺一位控卫',
    content: '友谊局，不限年级。打完一起去吃夜宵，有兴趣直接评论区集合。',
    image: asset('/assets/social/basketball.webp'),
    location: '东区篮球场',
    likes: 96,
    comments: 31,
    liked: false,
    followed: false,
    tag: '运动组队',
    circleId: 3
  },
  {
    id: 4,
    author: '春日音乐社',
    avatarText: '音',
    avatarColor: '#F39AB8',
    badge: '认证社团',
    time: '2小时前',
    title: '樱花草坪音乐会，本周六见',
    content: '带上野餐垫和好心情。现场有不插电演出，也欢迎大家带乐器来即兴。',
    image: asset('/assets/social/spring-picnic.webp'),
    location: '大礼堂草坪',
    likes: 521,
    comments: 67,
    liked: false,
    followed: true,
    tag: '社团活动',
    circleId: 5
  },
  {
    id: 5,
    author: '苏晚',
    avatarText: '苏',
    avatarColor: '#9BD4C0',
    badge: '学习搭子',
    time: '3小时前',
    title: '有没有人一起准备高数期中？',
    content: '每天晚上图书馆三楼，互相讲题。人多了开个群，资料可以共享。',
    image: asset('/assets/social/library-study.webp'),
    location: '逸夫图书馆',
    likes: 142,
    comments: 53,
    liked: false,
    followed: false,
    tag: '学习',
    circleId: 2
  },
  {
    id: 6,
    author: '顾言',
    avatarText: '顾',
    avatarColor: '#B39DDB',
    badge: '摄影同好',
    time: '5小时前',
    title: '银杏大道的晨光，随手一拍就很出片',
    content: '早上七点前光线最好，人少。参数放在评论区了，新手也能拍。',
    image: asset('/assets/social/spring-picnic.webp'),
    location: '银杏大道',
    likes: 267,
    comments: 38,
    liked: true,
    followed: false,
    tag: '摄影',
    circleId: 5
  },
  {
    id: 7,
    author: '沈亦辰',
    avatarText: '沈',
    avatarColor: '#FF9E80',
    badge: '游戏搭子',
    time: '6小时前',
    title: '晚上八点开黑，来个会游走的',
    content: '缺一位，语音在游戏里。输了不骂人，开心最重要。',
    image: asset('/assets/social/basketball.webp'),
    location: '线上',
    likes: 88,
    comments: 46,
    liked: false,
    followed: false,
    tag: '开黑',
    circleId: 1
  },
  {
    id: 8,
    author: '林知夏',
    avatarText: '林',
    avatarColor: '#7EB6F6',
    badge: '考研人',
    time: '8小时前',
    title: '英语真题二刷计划，一起打卡吗',
    content: '每天一套，晚上十点互相报进度。坚持一个月看看效果。',
    image: asset('/assets/social/library-study.webp'),
    location: '自习室',
    likes: 176,
    comments: 61,
    liked: false,
    followed: true,
    tag: '学习',
    circleId: 2
  }
]

const conversations = [
  { id: 2, name: '周予安', avatarText: '周', color: '#7EB6F6', preview: '好的，那晚上七点见！', time: '刚刚', unread: 3, online: false },
  { id: 4, name: '春日音乐社', avatarText: '音', color: '#F39AB8', preview: '欢迎加入！本周四晚上第一次排练', time: '18:42', unread: 1, online: false },
  { id: 3, name: '陈一川', avatarText: '陈', color: '#FFB56A', preview: '好的，东区二号场见', time: '昨天', unread: 1, online: false }
]
const chatMessages = {
  2: [
    { id: 1, mine: false, type: 'text', content: '你好，看到你收藏了我的台灯～' },
    { id: 2, mine: true, type: 'text', content: '你好，请问台灯还在吗？晚上方便自提吗？' },
    { id: 3, mine: false, type: 'text', content: '还在的，今晚可以宿舍楼下自提。' },
    { id: 4, mine: false, type: 'image', image: asset('/assets/home/recommend-lamp.jpg') },
    { id: 5, mine: true, type: 'text', content: '好的，那晚上七点见！' }
  ],
  4: [{ id: 6, mine: false, type: 'text', content: '欢迎加入！本周四晚上第一次排练' }],
  3: [{ id: 7, mine: false, type: 'text', content: '好的，东区二号场见' }]
}

const notifications = [
  { id: 1, title: '校园市集提醒', content: '你收藏的校园市集将在明天下午开始', time: '今天 18:30', tone: 'green', category: 'system', unread: true },
  { id: 2, title: '内容审核通过', content: '你发布的“几乎全新护眼台灯”已经展示', time: '今天 12:08', tone: 'blue', category: 'system', unread: true },
  { id: 3, title: '系统维护通知', content: '本周日凌晨将进行短时服务升级', time: '昨天 20:15', tone: 'yellow', category: 'system', unread: true }
]

const profile = {
  name: '林知夏',
  school: '内蒙古大学',
  major: '新闻与传播学院 · 2024级',
  studentId: '校园号 10248',
  avatar: asset('/assets/social/profile-avatar.webp'),
  posts: 18,
  following: 46,
  followers: 392
}

// 学期配置：课表与校历页、发现页横幅共用
const termInfo = {
  termLabel: '2026-2027学年 第一学期',
  termStart: '2026-08-31',
  termEnd: '2027-01-22',
  midtermStart: '2026-11-02',
  midtermEnd: '2026-11-06',
  finalStart: '2027-01-04'
}

// 课表：day 0=周一至周日；period 0-4 对应 1-5 大节
const timetableCourses = [
  { name: '新闻学概论', room: '博学楼 B203', day: 0, period: 0, theme: 'blue' },
  { name: '大学英语', room: '外国语楼 305', day: 0, period: 1, theme: 'green' },
  { name: '传播学原理', room: '博学楼 A110', day: 1, period: 1, theme: 'amber' },
  { name: '新闻摄影', room: '实验楼 402', day: 2, period: 0, theme: 'pink' },
  { name: '体育·羽毛球', room: '体育馆', day: 2, period: 3, theme: 'purple' },
  { name: '新媒体运营', room: '博学楼 B305', day: 3, period: 1, theme: 'blue' },
  { name: '形势与政策', room: '图书馆报告厅', day: 4, period: 2, theme: 'amber' }
]

// 校历事件（calendarEvents）已随「校历」功能下线移除

// 校草校花选手（离线兜底）：gender 1=校草组 2=校花组
const idolContestants = [
  { id: 1, name: '苏晚', gender: 2, college: '新闻与传播学院', photo: asset('/assets/social/spring-picnic.webp'), slogan: '把春天穿在身上', voteCount: 862, voted: false },
  { id: 2, name: '林小满', gender: 2, college: '外国语学院', photo: asset('/assets/social/library-study.webp'), slogan: '图书馆常驻选手', voteCount: 645, voted: false },
  { id: 3, name: '陈知意', gender: 2, college: '经济管理学院', photo: asset('/assets/social/flea-market.webp'), slogan: '市集淘物专家', voteCount: 531, voted: false },
  { id: 4, name: '白若曦', gender: 2, college: '艺术学院', photo: asset('/assets/social/profile-avatar.webp'), slogan: '画室里的小太阳', voteCount: 418, voted: false },
  { id: 5, name: '陆知远', gender: 1, college: '计算机学院', photo: asset('/assets/social/basketball.webp'), slogan: '球场见真章', voteCount: 774, voted: false },
  { id: 6, name: '沈亦辰', gender: 1, college: '体育学院', photo: asset('/assets/home/hero-campus.webp'), slogan: '七点东操等我', voteCount: 590, voted: false },
  { id: 7, name: '顾言', gender: 1, college: '文学院', photo: asset('/assets/home/thesis-books.webp'), slogan: '会写诗的投手', voteCount: 456, voted: false },
  { id: 8, name: '周叙白', gender: 1, college: '建筑学院', photo: asset('/assets/home/spring-event.webp'), slogan: '画图到天亮', voteCount: 387, voted: false }
]

module.exports = { discoveryPosts, chatMessages, conversations, notifications, profile, termInfo, timetableCourses, idolContestants }

// 圈子离线兜底：与 module-circles.sql 的种子保持一致。
const circles = [
  { id:1, name:'王者荣耀', slug:'wzry', category:'游戏', description:'开黑上分、英雄攻略、段位交流', cover:asset('/assets/social/basketball.webp'), theme:'blue', memberCount:4, postCount:1, hot:1, sort:1, joined:true },
  { id:2, name:'考研上岸', slug:'kaoyan', category:'学习', description:'备考经验、资料共享、院校信息', cover:asset('/assets/social/library-study.webp'), theme:'green', memberCount:3, postCount:2, hot:1, sort:2, joined:true },
  { id:3, name:'篮球场', slug:'basketball', category:'运动', description:'约球组队、球技交流、赛事讨论', cover:asset('/assets/social/basketball.webp'), theme:'amber', memberCount:3, postCount:1, hot:1, sort:3, joined:true },
  { id:4, name:'二手闲置', slug:'flea', category:'生活', description:'闲置转让、求购、以物换物', cover:asset('/assets/social/flea-market.webp'), theme:'pink', memberCount:2, postCount:0, hot:1, sort:4, joined:true },
  { id:5, name:'摄影社', slug:'photo', category:'兴趣', description:'校园写真、约拍、后期交流', cover:asset('/assets/social/spring-picnic.webp'), theme:'purple', memberCount:2, postCount:2, hot:1, sort:5, joined:true },
  { id:6, name:'编程学习', slug:'code', category:'学习', description:'算法刷题、项目实战、技术分享', cover:asset('/assets/social/library-study.webp'), theme:'blue', memberCount:0, postCount:0, hot:0, sort:6, joined:false },
  { id:7, name:'美食探店', slug:'food', category:'生活', description:'食堂测评、周边美食、深夜放毒', cover:asset('/assets/social/spring-picnic.webp'), theme:'amber', memberCount:0, postCount:0, hot:0, sort:7, joined:false },
  { id:8, name:'日语角', slug:'japanese', category:'学习', description:'五十音、考级、日剧动漫交流', cover:asset('/assets/social/library-study.webp'), theme:'green', memberCount:0, postCount:0, hot:0, sort:8, joined:false },
  { id:9, name:'羽毛球', slug:'badminton', category:'运动', description:'约场、装备、技巧', cover:asset('/assets/social/basketball.webp'), theme:'amber', memberCount:0, postCount:0, hot:0, sort:9, joined:false },
  { id:10, name:'桌游剧本杀', slug:'boardgame', category:'兴趣', description:'组局、剧本推荐、约本', cover:asset('/assets/social/flea-market.webp'), theme:'purple', memberCount:0, postCount:0, hot:0, sort:10, joined:false }
]
module.exports.circles = circles


// 外卖离线数据：与本模块 SQL 的店铺、菜单、费用规则一致。
const foodShops = [
  { id:1, name:'茶言观色奶茶', cover:asset('/assets/home/food-milktea.webp'), category:'奶茶饮品', rating:4.9, monthlySales:1268, minPrice:12, deliveryFee:2, deliveryTime:30, discount:'满20减8', notice:'紫荆食堂旁 · 茶底每日现泡，甜度需求请写在备注里。' },
  { id:2, name:'二食堂快餐', cover:asset('/assets/home/food-fresh.webp'), category:'快餐便当', rating:4.8, monthlySales:2386, minPrice:15, deliveryFee:1, deliveryTime:25, discount:'满30减5', notice:'二食堂一楼 · 热饭热菜现做，午高峰请提前下单。' },
  { id:3, name:'果然鲜果切', cover:asset('/assets/home/food-fruit.webp'), category:'水果轻食', rating:4.9, monthlySales:856, minPrice:10, deliveryFee:2, deliveryTime:30, discount:'满25减4', notice:'南区生活广场 · 当日鲜切，不添加糖浆。' },
  { id:4, name:'逸夫小超市', cover:asset('/assets/home/food-snacks.webp'), category:'零食', rating:4.7, monthlySales:1680, minPrice:8, deliveryFee:1, deliveryTime:20, discount:'满20减3', notice:'逸夫图书馆西侧 · 自习间隙的小补给，配送至楼下。' }
]
const foodMenus = {
  1: [
    { category:'招牌奶茶', goods:[{id:101,name:'招牌珍珠奶茶',price:12,monthlySales:628,image:asset('/assets/home/food-milktea.webp')},{id:102,name:'茉莉鲜奶茶',price:14,monthlySales:436,image:asset('/assets/home/food-milktea.webp')}] },
    { category:'清爽果茶', goods:[{id:103,name:'柠檬绿茶',price:10,monthlySales:312,image:asset('/assets/home/food-milktea.webp')},{id:104,name:'桃桃乌龙',price:16,monthlySales:286,image:asset('/assets/home/food-milktea.webp')}] }
  ],
  2: [
    { category:'人气便当', goods:[{id:201,name:'照烧鸡腿饭',price:18,monthlySales:812,image:asset('/assets/home/food-fresh.webp')},{id:202,name:'番茄鸡蛋饭',price:15,monthlySales:526,image:asset('/assets/home/food-fresh.webp')}] },
    { category:'加餐小菜', goods:[{id:203,name:'时蔬小碗',price:6,monthlySales:318,image:asset('/assets/home/food-fresh.webp')},{id:204,name:'米饭',price:2,monthlySales:452,image:asset('/assets/home/food-fresh.webp')}] }
  ],
  3: [
    { category:'每日鲜切', goods:[{id:301,name:'四季缤纷果切',price:16,monthlySales:428,image:asset('/assets/home/food-fruit.webp')},{id:302,name:'清甜西瓜杯',price:10,monthlySales:366,image:asset('/assets/home/food-fruit.webp')}] },
    { category:'轻食搭配', goods:[{id:303,name:'酸奶水果碗',price:18,monthlySales:216,image:asset('/assets/home/food-fruit.webp')}] }
  ],
  4: [
    { category:'自习补给', goods:[{id:401,name:'香脆薯片',price:8,monthlySales:586,image:asset('/assets/home/food-snacks.webp')},{id:402,name:'黄油可颂',price:6,monthlySales:348,image:asset('/assets/home/food-snacks.webp')}] },
    { category:'清凉饮料', goods:[{id:403,name:'柠檬气泡水',price:5,monthlySales:416,image:asset('/assets/home/food-snacks.webp')}] }
  ]
}
const foodAddresses = [{id:1,contact:'林知夏',phone:'138****1024',detail:'学生公寓 6 号楼 312'},{id:2,contact:'林知夏',phone:'138****1024',detail:'南区宿舍 2 栋 508'}]
function makeFoodAddress(detail) { return {id:Date.now(),contact:'我',phone:'',detail} }
function makeFoodOrder(payload) {
  const food = require('./food')
  const shop = foodShops.find(s => s.id === Number(payload.shopId))
  if (!shop) throw new Error('店铺不存在')
  if (!payload.address || !String(payload.address).trim() || String(payload.address).length > 128) throw new Error('请填写有效收货地址')
  if (String(payload.remark || '').length > 200) throw new Error('备注不能超过200字')
  const items = food.orderItems(foodMenus[shop.id], payload.items)
  const fees = food.bill(shop, items)
  if (Number(fees.missing) > 0) throw new Error('未达到起送金额')
  return {orderId:'local-'+Date.now(),total:Number(fees.total),offline:true}
}
Object.assign(module.exports, { foodShops, foodMenus, foodAddresses, makeFoodAddress, makeFoodOrder })

// 模块二至四离线数据：与 module-market-jobs-activities.sql 的种子保持一致。
const seedGoods = [
  { id: 1, mode: 1, title: '九成新护眼台灯', price: 45, conditionLevel: '九成新', category: '数码', image: asset('/assets/home/recommend-lamp.jpg'), description: '宿舍自用半年，亮度可调，晚上看书很舒服。', seller: { id: 1, nickname: '林知夏' }, createdAt: '2026-10-09 18:20:00' },
  { id: 2, mode: 1, title: '高数教材全套', price: 25, conditionLevel: '九成新', category: '图书', image: asset('/assets/home/thesis-books.webp'), description: '课本和习题册一套，笔记很少，适合期中复习。', seller: { id: 2, nickname: '周予安' }, createdAt: '2026-10-09 17:10:00' },
  { id: 3, mode: 1, title: '羽毛球拍', price: 60, conditionLevel: '九成新', category: '运动', image: asset('/assets/social/basketball.webp'), description: '轻量拍，打过几次，送一只备用手胶。', seller: { id: 3, nickname: '陈一川' }, createdAt: '2026-10-09 16:05:00' },
  { id: 4, mode: 1, title: '联想小新笔记本', price: 3200, conditionLevel: '七成新及以下', category: '数码', image: asset('/assets/home/laptop-silver.webp'), description: '正常使用痕迹，功能完好，校园内当面验机。', seller: { id: 1, nickname: '林知夏' }, createdAt: '2026-10-08 20:40:00' },
  { id: 5, mode: 1, title: '春日野餐垫', price: 15, conditionLevel: '九成新', category: '生活', image: asset('/assets/social/spring-picnic.webp'), description: '折叠方便，适合草坪活动，欢迎自提。', seller: { id: 4, nickname: '春日音乐社' }, createdAt: '2026-10-08 15:30:00' },
  { id: 6, mode: 2, title: '求购二手吉他', price: 300, conditionLevel: '九成新', category: '其他', image: asset('/assets/social/flea-market.webp'), description: '预算300元左右，民谣吉他即可，校内面交。', seller: { id: 2, nickname: '周予安' }, createdAt: '2026-10-08 12:00:00' }
]
let storedGoods = []
try { const rows = wx.getStorageSync('mock_goods'); if (Array.isArray(rows)) storedGoods = rows } catch (error) {}
const goods = storedGoods.concat(seedGoods)
const jobs = [
  { id: 1, title: '图书馆图书整理员', daySalary: 100, settle: '日结', location: '逸夫图书馆', timeDesc: '周一到周五 14:00-17:00', status: '招聘中' },
  { id: 2, title: '奶茶店店员', daySalary: 90, settle: '日结', location: '紫荆食堂旁', timeDesc: '周末两班轮换', status: '招聘中' },
  { id: 3, title: '家教·初二数学', daySalary: 150, settle: '周结', location: '校外·学生家', timeDesc: '每周六上午', status: '招聘中' },
  { id: 4, title: '运动会志愿者', daySalary: 80, settle: '日结', location: '东区田径场', timeDesc: '10.24-10.25', status: '招聘中' }
]
const activities = [
  { id: 1, title: '樱花草坪音乐会', poster: asset('/assets/social/spring-picnic.webp'), timeDesc: '本周六 15:00', location: '大礼堂草坪', enrolled: 268, enrolledByMe: false },
  { id: 2, title: '秋季运动会', poster: asset('/assets/home/hero-campus.webp'), timeDesc: '10.24-10.25', location: '东区田径场', enrolled: 512, enrolledByMe: false },
  { id: 3, title: '校园歌手大赛决赛', poster: asset('/assets/home/spring-event.webp'), timeDesc: '12.05 19:00', location: '大礼堂', enrolled: 341, enrolledByMe: false }
]
function makeGoods(payload) {
  const row = { id: 'local-' + Date.now(), mode: 1, ...payload, price: Number(payload.price), seller: { id: 1, nickname: '林知夏' }, createdAt: '刚刚' }
  goods.unshift(row)
  try { wx.setStorageSync('mock_goods', goods.filter(item => String(item.id).startsWith('local-'))) } catch (error) { goods.shift(); throw new Error('本机物品保存失败') }
  return row
}
function enrollActivity(id) {
  const row = activities.find(item => Number(item.id) === Number(id))
  if (!row) throw new Error('活动不存在')
  if (row.enrolledByMe) throw new Error('已经报名过啦')
  row.enrolledByMe = true
  row.enrolled += 1
  return { enrolled: true }
}
Object.assign(module.exports, { goods, jobs, activities, makeGoods, enrollActivity })
