# 同类问题排查报告

> 排查时间：2026-10-17
> 起因：小程序选学校是硬编码的、且选完不生效 —— 怀疑还有同类问题
> 结论：**找到 7 个同类问题，其中 3 个是会造成线上故障的真 bug**
> 状态：**7 个已全部修复**（2026-10-17），详见文末「修复记录」

---

## 问题总览

| # | 问题 | 严重度 | 影响 |
|---|---|---|---|
| 1 | 二手发布图片**从未上传** | 🔴 严重 | 发出的商品图片全部失效 |
| 2 | 发帖地点硬编码，含**外校地点** | 🔴 严重 | 内蒙古大学用户能选「紫荆操场」（清华的） |
| 3 | 圈子分类硬编码，后端统计接口**无人调用** | 🟠 中等 | 新分类的圈子在小程序筛不出来 |
| 4 | Banner 管理端可增删改，小程序**无人读取** | 🟠 中等 | 运营配的轮播永远不显示 |
| 5 | 学校作息配置小程序**不读** | 🟠 中等 | 管理端配了作息，学生看到的是硬编码 5 节 |
| 6 | 学校 logo 管理端可填，小程序**不显示** | 🟡 轻微 | 功能缺失 |
| 7 | 二手分类硬编码（3 处重复） | 🟡 轻微 | 分类调整要改 3 个文件 |

---

## 🔴 问题 1：二手发布图片从未上传

### 现象

`pages/market-publish/market-publish.js` 用 `wx.chooseMedia` 选图后，**直接把本地临时路径当图片地址提交**。

```js
// market-publish.js:10
wx.chooseMedia({ ... success: res => {
  const added = (res.tempFiles || []).map(file => file.tempFilePath)  // ← wxfile://tmp_xxx
  this.setData({ images: this.data.images.concat(added).slice(0, 6) })
}})

// market-publish.js:29 —— 直接把这些临时路径提交
const result = await api.publishGoods({
  images: this.data.images,          // ← 本地临时路径
  image: this.data.images[0]
})
```

### 对比：其他两个发布页是对的

| 发布页 | 图片处理 |
|---|---|
| `publish.js` | ✅ `await api.uploadImages(media)` 先上传 |
| `circle-publish.js` | ✅ 上传，失败还有提示 |
| `market-publish.js` | ❌ **没上传** |

### 实测证据

```bash
POST /api/goods  {"image":"wxfile://tmp_abc123.jpg", ...}
# → HTTP 200, code=0, id=20

SELECT image FROM goods WHERE id=20;
# → wxfile://tmp_abc123.jpg     ← 后端照单全收
```

**后端没有校验图片路径合法性**，脏数据直接进库。

### 后果

- 商品发出去，别人**看不到图**
- 自己重启小程序也看不到 —— 微信临时文件早已被系统清理
- 数据库里永久留着一堆失效路径

### 修复方向

参照 `publish.js` 的 `uploadMedia()`：

```js
async uploadMedia(media) {
  if (!media.length) return []
  try { return await api.uploadImages(media) }
  catch (e) { popup.toast(this, e.message || '图片上传失败', { tone: 'warn' }); return [] }
}
```

发布前先上传，拿回 `/uploads/xxx.jpg` 再提交。

**顺带建议**：后端 `publishGood` 应校验 image 字段 —— 不是 `/uploads/` 或 `http` 开头就拒绝，防止脏数据再次进库。

---

## 🔴 问题 2：发帖地点硬编码，含外校地点

### 现象

两处重复的硬编码数组：

```js
// circle-publish.js:69 和 publish.js:96 完全相同
const locations = ['内蒙古大学', '紫荆操场', '逸夫图书馆', '东区篮球场', '学生公寓']
```

### 问题

- **「紫荆操场」是清华大学的**，内蒙古大学用户能选它
- **「内蒙古大学」被当成地点**（它是学校名，不是校内地点）
- 多校铺开后，每所学校的地点完全不同，硬编码根本没法用

### 修复方向

1. 短期：改成「校内地点」这类通用选项，去掉跨校污染
2. 正确做法：后端加 `school_location` 表（school_id + name），管理端可维护，小程序按当前选中学校拉取

---

## 🟠 问题 3：圈子分类硬编码

### 现象

```js
// discover.js:7
const CATEGORIES = ['全部', '游戏', '学习', '运动', '兴趣', '生活']
```

而后端**早就有**真实的分类统计：

```bash
GET /api/circles/categories
# → [{"category":"学习","total":3},{"category":"运动","total":3},
#     {"category":"兴趣","total":2},{"category":"生活","total":2},
#     {"category":"游戏","total":1}]
```

`api.js` 也写好了 `getCircleCategories()`，但：

```
❌ 没有任何页面调用它
```

### 后果

管理端新建一个「音乐」分类的圈子后，小程序分类栏里**没有「音乐」这一项**，用户筛不到这个圈子。

### 修复方向

`discover.js` 的 `CATEGORIES` 改为从 `getCircleCategories()` 拉取，前面拼「全部」，离线时回落到硬编码。

---

## 🟠 问题 4：Banner 管理端可配，小程序无人读

### 现象

| 层 | 状态 |
|---|---|
| 数据库 `banner` 表 | ✅ 存在，含 slot/theme/link_type 等完整字段 |
| 管理端 `Ops.vue` | ✅ 完整 CRUD（新增/编辑/删除/排序/启用） |
| 后端 `/api/banners` | ✅ 存在 |
| `api.js getBanners()` | ✅ 写好了 |
| 小程序页面 | ❌ **没有任何页面调用** |

### 说明

这里有个背景：**轮播位置已经按需求改成了「热门圈子」**，所以 banner 被替代了。

但问题是：**管理端的 Banner 管理页还在**，运营配了也不会显示。这属于「半截功能」——要么补上使用方，要么下线管理入口。

### 修复方向（二选一）

- **A**：发现页加一个次要位置用 banner（比如圈子列表上方的小横条）
- **B**：管理端隐藏「运营配置」菜单，避免运营做无用功

**建议 B**，除非你确实需要 banner 位置。

---

## 🟠 问题 5：学校作息配置小程序不读

上一轮刚做完管理端的可视化作息配置，但小程序课表页仍用硬编码：

```js
// timetable.js:7
const PERIODS = [
  { label: '一', time: '08:00' }, { label: '二', time: '10:00' },
  { label: '三', time: '14:00' }, { label: '四', time: '16:00' },
  { label: '五', time: '19:00' }
]
```

`school.period_config` 已经在 `/api/schools` 的返回里了（上一轮加的），但没人解析。

### 修复方向

`loadPeriods()` 改成三级优先级：

```
学校配置 periodConfig  >  学生本地 storage 覆盖  >  硬编码默认
```

---

## 🟡 问题 6：学校 logo 不显示

`school.logo` 字段管理端可填，但小程序只显示学校**名字**，从不显示 logo。

**优先级低** —— 当前 UI 设计里没有 logo 位置。要么加，要么管理端去掉这个输入框。

---

## 🟡 问题 7：二手分类硬编码三处

```js
market-list.js:15    categories: ['全部', '数码', '图书', '生活', '运动', '其他']
market-publish.js:6  categories: ['数码', '图书', '生活', '运动', '其他']
publish.js:57        const categories = ['数码', '图书', '生活', '运动', '其他']
```

三处重复，改分类要改三个文件，漏一个就不一致。

**修复方向**：抽到 `utils/constants.js`，或后端提供 `/goods/categories`。

---

## 排查方法（可复用）

这次用的五个维度，以后可以定期跑：

| 维度 | 检查方法 | 本次结果 |
|---|---|---|
| A. 硬编码数据 | grep 页面里的 `const X = [...]` | 发现 7 处可疑，3 处是问题 |
| B. 管理端配了但没人读 | 逐项核对 `DESCRIBE 表` vs 小程序 grep | 发现 4 个字段无人读 |
| C. 前端死状态 | data 字段 vs 模板/JS 使用 | 1 处误报，无真问题 |
| D. 定义了但没人调用 | api.js 导出 vs 全项目调用 | 15 个，其中 4 个是「后端有、前端没接」 |
| E. 后端端点未覆盖 | 71 个端点 vs api.js 路径 | 2 个未覆盖（均有正当理由） |

### E 的两个例外说明

| 端点 | 为什么没接 |
|---|---|
| `POST /admin/timetable/import` | 课表导入是管理端功能，小程序不该有 |
| `POST /shops/{id}/view` | 店铺浏览上报，非必要功能 |

---

## 建议修复顺序

| 顺序 | 问题 | 理由 |
|---|---|---|
| 1 | 二手图片上传 | 真 bug，用户发出去就是坏的 |
| 2 | 发帖地点跨校污染 | 真 bug，用户能选到清华的地点 |
| 3 | 圈子分类动态化 | 影响运营建圈后的可用性 |
| 4 | 作息配置接通 | 上一轮的收尾，改动小 |
| 5 | Banner 二选一 | 产品决策，不是技术问题 |
| 6 | 二手分类抽常量 | 纯代码整洁 |
| 7 | 学校 logo | 看是否真需要 |

---

## 附：本次排查未发现问题的部分

以下维度检查后**没有问题**，可以放心：

- ✅ 五个列表页的 `schoolScope` 都真的传给了接口（不是死状态）
- ✅ `community.js` 的 `postScope` 正常生效
- ✅ 学期 / 校历数据小程序正常读取
- ✅ 圈子/兼职/外卖/二手/活动/帖子 的 `schoolId` 上一轮已全部接通
- ✅ 管理端 26 个端点前端全部有调用
- ✅ 没有发现「setData 了但完全没用」的死字段

---

## 修复记录（2026-10-17）

7 个问题全部修复，130 个自动化测试通过。

### #1 二手图片上传 ✅

| 层 | 改动 |
|---|---|
| `utils/api.js` | `uploadImages` 拿不到服务端地址时**抛错**，不再静默退回本地路径 |
| `pages/market-publish` | 新增 `uploadMedia()`，提交前先上传；上传失败中止发布 |
| `pages/publish` | 去掉 3 处「上传失败就退回本地路径」的兜底 |
| `BizService` | 新增 `isServableImage()`，发布商品/活动时拒绝非服务端地址 |
| `ForumService` | 新增 `validImage()`，发帖同样校验 |

**校验矩阵（实测 8/8 符合预期）**：

| 输入 | 结果 |
|---|---|
| `wxfile://tmp_x.jpg` | 拒绝 |
| `http://tmp/y.jpg` | 拒绝 |
| `https://tmp/z.jpg` | 拒绝 |
| `blob:abc` | 拒绝 |
| `相对路径.jpg` | 拒绝 |
| `/uploads/real.jpg` | 通过 |
| `/assets/home/lamp.jpg` | 通过 |
| `https://cdn.example.com/a.jpg` | 通过 |

> **踩过的坑**：第一版校验只写了「以 http 开头就放行」，结果 `http://tmp/y.jpg` 被放过了 ——
> 而 `http://tmp/` 正是微信小程序的本地临时路径。必须显式拒绝，不能靠前缀推断。

### #2 + #7 地点与分类抽常量 ✅

新建 `utils/constants.js`：

```js
const GOODS_CATEGORIES = ['数码', '图书', '生活', '运动', '其他']
const GOODS_CATEGORY_FILTERS = ['全部'].concat(GOODS_CATEGORIES)
const GOODS_CONDITIONS = ['全新', '九成新', '七成新', '七成新及以下']
const JOB_SETTLES = ['日结', '周结', '月结']
const CAMPUS_LOCATIONS = ['图书馆', '教学楼', '食堂', '操场', '宿舍', '校门口', '其他']
const CIRCLE_CATEGORIES_FALLBACK = ['游戏', '学习', '运动', '兴趣', '生活']
```

- 地点从 `['内蒙古大学','紫荆操场','逸夫图书馆','东区篮球场','学生公寓']`（含清华场所 + 学校名当地点）改成**校内通用场所**
- 二手分类从 3 处硬编码收敛到 1 处

### #3 圈子分类动态化 ✅

`discover.js` 新增 `loadCategories()`，从 `GET /circles/categories` 拉取；
当前选中分类仍存在就保持，否则回到「全部」。拉不到时用兜底常量。

### #5 作息配置接通 ✅

优先级：**学生本地覆盖 > 学校配置 > 内置默认**

| 层 | 改动 |
|---|---|
| `utils/school.js` | 新增 `parsePeriodConfig()`（兼容数组/对象两种格式）、`getSelectedPeriodTimes()` |
| `pages/timetable` | 新增 `syncSchoolPeriods()`、`resetPeriods()`；课表页加「恢复默认作息」按钮 |
| 后端 | `/api/schools` 已返回 `periodConfig` |

### #6 学校 logo ✅

`/api/schools` 返回 `logo`；首页学校名旁按需显示（`wx:if="{{schoolLogo}}"`，没填不占位）。

### #4 Banner ✅

按产品决策**隐藏管理入口**：`web/src/router/index.js` 里 `/ops` 路由注释掉，
后端 `/api/banners` 与 `Ops.vue` 都保留，将来需要展示位时放开那一行即可。

### #8 每校地点管理 ✅（后续补充）

针对地点硬编码问题，已新增 `school_location` 表和完整链路：

| 层 | 实现 |
|---|---|
| 数据库 | `school_location(school_id, name, sort, status)`，同校地点名唯一 |
| 后端公开接口 | `GET /api/schools/{id}/locations`，小程序按学校读取启用地点 |
| 后端管理接口 | `GET/POST/PUT/DELETE /api/admin/school-locations` |
| Web 管理端 | 新增「地点管理」页面，超管可切学校，校管只能管理本校 |
| 小程序 | `publish.js`、`circle-publish.js` 使用当前学校地点；接口失败回落通用地点 |
| 数据 | 现有 3 所学校各初始化 7 个通用地点 |

新增测试：后端 15 个、小程序 8 个。

### 回归结果

| 套件 | 结果 |
|---|---|
| 小程序行为 | 45 / 45 |
| 小程序选学校 | 12 / 12 |
| 同类问题修复（新增） | 15 / 15 |
| 作息配置 | 15 / 15 |
| 后端帖子隔离 | 4 / 4 |
| 后端二手隔离 | 5 / 5 |
| 后端学校隔离 | 11 / 11 |
| 后端管理端分页 | 9 / 9 |
| 后端选学校 | 15 / 15 |
| 后端地点 CRUD | 15 / 15 |
| 小程序地点接入 | 8 / 8 |
| 静态校验 | PASS |
| **合计** | **158 个测试全绿** |
