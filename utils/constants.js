// 小程序共用的枚举常量
//
// 为什么要抽出来：
//   1. 二手分类原来在 market-list / market-publish / publish 三处各写一遍，
//      改一次要改三个文件，漏一个就不一致。
//   2. 发帖地点原来是 ['内蒙古大学','紫荆操场','逸夫图书馆',...] ——
//      「紫荆操场」是清华的，「内蒙古大学」是学校名不是地点，
//      多校铺开后这些硬编码根本没法用。
//
// 地点改成【校内通用场所】：与具体学校无关，任何学校的学生都能用。
// 若将来要做「每校自定义地点」，再加 school_location 表由管理端维护。

/** 二手/闲置分类（列表页与发布页共用） */
const GOODS_CATEGORIES = ['数码', '图书', '生活', '运动', '其他']

/** 二手列表的分类筛选项（多一个「全部」） */
const GOODS_CATEGORY_FILTERS = ['全部'].concat(GOODS_CATEGORIES)

/** 二手成色 */
const GOODS_CONDITIONS = ['全新', '九成新', '七成新', '七成新及以下']

/** 兼职结算方式 */
const JOB_SETTLES = ['日结', '周结', '月结']

/** 校内地点：通用场所，不绑定任何学校 */
const CAMPUS_LOCATIONS = ['图书馆', '教学楼', '食堂', '操场', '宿舍', '校门口', '其他']

/** 圈子分类兜底（后端 /circles/categories 不可用时使用） */
const CIRCLE_CATEGORIES_FALLBACK = ['游戏', '学习', '运动', '兴趣', '生活']

module.exports = {
  GOODS_CATEGORIES,
  GOODS_CATEGORY_FILTERS,
  GOODS_CONDITIONS,
  JOB_SETTLES,
  CAMPUS_LOCATIONS,
  CIRCLE_CATEGORIES_FALLBACK
}
