// 编码与后端一致；这里仅维护学习专区的展示内容与离线也可读的入门建议。
const categories = [
  { key: 'daike', label: '课程互助', short: '互助', hint: '难题一起解，知识一起学', guide: '把提问变得更有效', badge: '同伴学习', tips: ['写清课程、知识点和你已经尝试的解法。', '附上具体卡点，一起讨论思路与推导。', '交流学习方法，保留独立完成作业的过程。'] },
  { key: 'paper', label: '论文指导', short: '论文', hint: '从一个好问题，开始你的论文', guide: '从选题到初稿，慢慢来', badge: '写作灵感', tips: ['先写清研究问题、研究对象和范围。', '阅读原始文献，记录出处与自己的理解。', '用提纲组织论证，再逐段完善初稿。'] },
  { key: 'job', label: '就业实习', short: '实习', hint: '让每一次尝试，更靠近理想', guide: '整理你的第一份简历', badge: '职业探索', tips: ['根据目标岗位整理相关课程与项目。', '用具体行动和真实成果描述经历。', '通过企业官方渠道核实岗位与招聘信息。'] },
  { key: 'kaoyan', label: '考研', short: '考研', hint: '找对节奏，和伙伴一起向前', guide: '找到适合自己的备考节奏', badge: '备考同行', tips: ['从目标院校官方渠道核对招生要求。', '把长期目标拆成每周可完成的小任务。', '定期复盘错题，为休息和运动留出时间。'] },
  { key: 'kaogong', label: '考公', short: '考公', hint: '一步一步，把准备落到实处', guide: '从了解考试开始准备', badge: '备考同行', tips: ['以招录单位官方公告核实报考条件。', '先了解题型，再根据薄弱项安排练习。', '整理错题与表达素材，定期回顾。'] }
]
function findCategory(key) { return categories.find(item => item.key === key) || categories[1] }
module.exports = { categories, findCategory }
