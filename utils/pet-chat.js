/**
 * 校园搭子对话适配器
 *
 * 设计要求：
 * - 真实适配器与 Mock 适配器分开，Mock 必须在消息上明确标记
 * - 模型名/地址/密钥放服务端配置，这里不写死任何密钥
 * - 思考开关等供应商特有参数不在此硬编码
 *
 * 当前实现：Mock 适配器（未配置服务端模型时使用）。
 * 接真实模型时，只需把 mode 切到 'server' 并让服务端代理转发。
 */
const { request } = require('./api')

const MODE = 'mock'   // 'server' | 'mock'

/** 校园问题 → 可答的本地知识（Mock 用） */
const KNOWLEDGE = [
  { match: ['食堂', '吃什么', '饭'], reply: '二食堂今天有番茄鸡蛋饭和照烧鸡腿饭，三楼窗口新上了麻辣香锅。想吃得清淡点可以去一楼。' },
  { match: ['快递', '取件', '菜鸟'], reply: '菜鸟驿站在南区生活广场，营业到晚上九点。记得带取件码，高峰期要排一会儿队。' },
  { match: ['自习', '图书馆', '看书'], reply: '图书馆三楼靠窗的位置阳光好，人也相对少。四楼有讨论区，可以小声说话。' },
  { match: ['二手', '卖', '闲置'], reply: '二手市场里最近有不少人在出教材和台灯，你可以先逛逛看有没有中意的。' },
  { match: ['活动', '社团', '讲座'], reply: '这周有三个活动：周三下午的读书会、周四晚的编程分享、周六的校园夜跑。' },
  { match: ['累', '烦', '难过', '压力'], reply: '辛苦啦。要不要先出去走走？我陪你在操场边上坐一会儿也行。' }
]

const FALLBACK = [
  '这个我还不太清楚，不过你可以去校园问答里问问同学们。',
  '嗯……让我想想。你可以先告诉我具体是哪个楼或者哪个时间吗？',
  '我暂时没查到相关信息，换个说法再问一次试试？'
]

/**
 * 发送消息
 * @returns {Promise<{content: string, mock: boolean, result?: object}>}
 */
function chat({ userId, text }) {
  if (MODE === 'server') {
    return request('/pet/chat', 'POST', { userId, content: text }).then(res => ({
      content: (res && res.content) || '',
      mock: false,
      result: (res && res.result) || null
    }))
  }
  return mockChat(text)
}

function mockChat(text) {
  return new Promise((resolve) => {
    // 模拟网络延迟，让「发送中」状态可见
    setTimeout(() => {
      const value = String(text || '')
      const hit = KNOWLEDGE.find(k => k.match.some(m => value.includes(m)))
      if (hit) {
        resolve({ content: hit.reply, mock: true, result: maybeResult(value) })
      } else {
        const pick = FALLBACK[Math.floor(Math.random() * FALLBACK.length)]
        resolve({ content: pick, mock: true })
      }
    }, 520)
  })
}

/** 命中校园数据类问题时，附带一张结果卡片（示意，带来源与时间） */
function maybeResult(text) {
  if (/二手|闲置/.test(text)) {
    return {
      type: 'goods',
      title: '二手市场 · 教材专区',
      summary: '当前有 12 件在售，最近更新在 20 分钟内',
      source: '校园生活平台 · 二手市场',
      updatedAt: new Date().toISOString(),
      targetId: null   // Mock 不跳转，避免进入空页面
    }
  }
  return null
}

module.exports = { chat, MODE }
