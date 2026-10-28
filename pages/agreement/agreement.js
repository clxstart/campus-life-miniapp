const school = require('../../utils/school')

// 协议正文放在 JS 里而不是接口里：协议必须离线可读，
// 后端挂了用户也要能看到自己同意了什么。
const DOCS = {
  privacy: {
    title: '隐私政策',
    updatedAt: '2026-10-27',
    sections: [
      {
        heading: '一、我们收集哪些信息',
        items: [
          '微信登录信息：微信昵称、头像、openid。用于识别你的账号、展示你的昵称与头像。',
          '你主动填写的信息：学校、手机号、收货地址、二手商品图文、帖子与评论、活动报名表单与材料。',
          '使用过程中产生的信息：浏览记录、点赞收藏关注关系、订单与支付记录。',
          '设备与日志信息：为排查故障，服务端会记录请求时间、接口路径与错误信息。'
        ]
      },
      {
        heading: '二、我们为什么收集',
        items: [
          '完成你请求的功能：下单、发布、报名、私信、课表等。',
          '保障安全与合规：内容审核（识别违规内容）、异常登录识别、风控与反作弊。',
          '改进产品：统计功能使用情况，优化体验。我们不会用你的内容做与上述无关的用途。'
        ]
      },
      {
        heading: '三、信息如何保存与保护',
        items: [
          '数据保存在校内/国内服务器，传输过程使用 HTTPS 加密。',
          '手机号等敏感字段在非必要场景下脱敏展示。',
          '账号密码（管理员/商家）使用不可逆加密存储，任何人（包括我们）都无法还原明文。',
          '仅在实现功能所必需的期限内保留；超出期限或你注销账号后，我们会删除或匿名化处理。'
        ]
      },
      {
        heading: '四、会不会提供给第三方',
        items: [
          '微信支付：支付时会把订单号与金额提供给微信支付以完成交易。',
          '微信内容安全接口：发布内容时会提交文本/图片用于违规检测。',
          '除上述为实现功能所必需、或法律法规要求外，我们不会把你的个人信息提供给第三方。'
        ]
      },
      {
        heading: '五、你的权利',
        items: [
          '查看与更正：在小程序「我的」里查看并修改你的资料。',
          '删除与注销：可申请注销账号，注销后账号数据将被删除或匿名化（交易与审计记录按法规要求保留）。',
          '撤回同意：不再使用相关功能即视为撤回该功能对应的授权。'
        ]
      },
      {
        heading: '六、未成年人',
        items: [
          '本服务面向高校在校学生。若你未满 18 周岁，请在监护人指导下使用。'
        ]
      },
      {
        heading: '七、联系我们',
        items: [
          '对隐私政策有疑问，可通过小程序「我的 → 帮助与反馈」联系我们。'
        ]
      }
    ]
  },
  terms: {
    title: '用户协议',
    updatedAt: '2026-10-27',
    sections: [
      {
        heading: '一、服务说明',
        items: [
          '校园生活为在校学生提供校园信息与生活服务，包括论坛交流、二手交易信息、外卖点单、活动报名等。',
          '本服务仅提供信息发布与撮合，线下交易由双方自行完成并承担相应责任。'
        ]
      },
      {
        heading: '二、账号',
        items: [
          '你需要通过微信登录使用本服务，并对账号下的行为负责。',
          '不得将账号转借他人，不得使用自动化脚本批量注册或批量操作。'
        ]
      },
      {
        heading: '三、发布规范',
        items: [
          '禁止发布违法违规、色情低俗、诈骗、赌博、暴力、侵犯他人权利的内容。',
          '禁止发布与校园生活无关的广告、刷单、贷款、代理加盟等信息。',
          '发布内容会经过内容安全检测；违规内容将被拦截或下架，情节严重者封禁账号。',
          '你对自己发布的内容负责，并授权我们在提供服务所必需的范围内展示该内容。'
        ]
      },
      {
        heading: '四、交易规则',
        items: [
          '二手交易、外卖点单为撮合服务，商品质量与履约由发布方负责。',
          '发生纠纷时请优先与对方协商；平台可协助提供必要的信息记录。',
          '餐品、活动的退款按发布方规则与平台退款流程处理。'
        ]
      },
      {
        heading: '五、违规处理',
        items: [
          '视情节轻重，我们可采取：内容拦截、内容下架、限制发布、限制功能、封禁账号。',
          '对处理结果有异议，可通过「帮助与反馈」申诉。'
        ]
      },
      {
        heading: '六、服务变更与中断',
        items: [
          '因系统维护、升级或不可抗力导致服务中断，我们会尽快恢复。',
          '我们可能调整功能；重大变更会在小程序内公告。'
        ]
      },
      {
        heading: '七、其他',
        items: [
          '继续使用本服务即视为你已阅读并同意本协议与《隐私政策》。'
        ]
      }
    ]
  }
}

Page({
  data: {
    type: 'privacy',
    title: '',
    updatedAt: '',
    sections: [],
    schoolName: ''
  },

  onLoad(query) { this._alive = true;
    const type = query && query.type === 'terms' ? 'terms' : 'privacy'
    const doc = DOCS[type]
    this.setData({
      type,
      title: doc.title,
      updatedAt: doc.updatedAt,
      sections: doc.sections,
      schoolName: school.getSelectedName() || ''
    })
    wx.setNavigationBarTitle({ title: doc.title })
  },

  onUnload() { this._alive = false },

  // 在隐私政策底部引导去看用户协议，两篇互相可达
  switchDoc() {
    const next = this.data.type === 'privacy' ? 'terms' : 'privacy'
    wx.redirectTo({ url: `/pages/agreement/agreement?type=${next}` })
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.reLaunch({ url: '/pages/profile/profile' })
  }
})
