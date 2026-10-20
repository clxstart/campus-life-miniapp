# GPT 任务提示词 · 补齐 campus-life-miniapp 未完成功能

> 用法：把这个 zip 发给 GPT，然后**把下面「提示词正文」整段复制粘贴**给它。
> 如果你只发提示词不发 zip，GPT 没有代码上下文，什么也做不了。

---

## 提示词正文（从下一行开始复制到最后）

---

你是一位资深微信小程序 + Spring Boot 全栈工程师。我给你一个**原生微信小程序**项目（校园生活 App）的完整源代码压缩包，包含前端 21 个页面和 Spring Boot 后端。项目已经有相当高的完成度，但存在一批**已知的未完成项**。

你的任务：**把这些未完成项补齐，交付可直接运行的代码。**

---

### 一、先读这些文件（按顺序）

1. `AI_HANDOFF.md` —— 项目总说明，**必读**，包含关键架构约定和已知问题清单
2. `docs/FEATURE_STATUS.md` —— 完整的功能状态清单，P0/P1/P2/P3 分级，每条都带文件行号
3. `utils/api.js` —— 接口适配层，理解数据流向的关键
4. `pages/index/index.js` + `pages/index/index.wxml` —— 本次改动的主战场
5. `backend/src/main/java/com/campuslife/controller/ForumController.java` —— 后端风格参考

**重要**：`AI_HANDOFF.md` 和 `docs/FEATURE_STATUS.md` 是我（用户）之前整理的，**以代码实际行为为准** —— 如果你发现文档和代码不一致，以代码为准，并在报告中指出文档错误。

---

### 二、必须完成的任务（不可跳过）

以下 3 项是**硬性要求**，必须全部做完。每项完成后说明改了哪些文件。

#### 任务 1：重构首页路由表（P0-1）

**现状问题**：`pages/index/index.js` 的 `openFeature()` 是一串 `if (label === 'xxx')` 白名单，末尾用 `popup.toast(this, \`${label}功能开发中\`)` 兜底。任何未登记的入口都会**静默降级成一句 toast**，不报错、不提示开发者。

**要求**：
1. 改成**表驱动**（路由映射对象），新增入口只需加一行
2. 未命中的 key 要有**明确的开发期反馈** —— 不要静默吞掉。可以用 `console.warn` 提示开发者"该入口未配置路由"，同时给用户一个合理提示
3. **保持现有已接通的路由行为完全不变**（校园论坛、外卖、二手市场、兼职、季节活动、作业答疑、论文指导这 7 条）
4. 注意 `pages/index/index.wxml` 里的 `data-label` 值，路由表的 key 必须和它们**精确匹配**

**验证**：列出 `index.wxml` 中所有 `data-label` 的值，确认每一个都能在路由表中找到对应项或明确落入兜底。

---

#### 任务 2：实现搜索功能（P0-2）

**现状**：搜索框能输入，但点了只弹一句 toast。**后端没有搜索接口，前端没有搜索结果页。**

**要求分三层全部实现**：

**① 后端** —— 新增搜索接口
- 在 `ForumController`（或合适的 Controller）中新增 `GET /api/search`
- 参数：`keyword`（必填）、`page`、`size`、`userId`（默认 1）
- 搜索范围：帖子的 `title` 和 `content`，用 `LIKE %keyword%`
- **返回格式必须复用现有约定**：`ApiResponse.ok(data)`，`data` 结构与 `GET /api/posts` **保持一致**
- **必须做参数校验**：`keyword` 为空时返回什么、超长时怎么处理、`size` 上限多少 —— 参考 `ForumController` 现有端点的校验风格（比如 `size` 被 `Math.min(Math.max(size,1),50)` 夹取）
- **注意 SQL 注入**：必须用 MyBatis-Plus 的参数绑定，不要字符串拼接
- 在 `docs/API_INTEGRATION.md` 中补充这个接口的文档（含请求示例和返回示例）

**② 前端页面** —— 新建搜索结果页
- 新建 `pages/search/search`（.js/.wxml/.wxss/.json 四件套）
- 视觉风格必须与现有页面**完全一致**：复用 `components/ui-popup`、`components/bottom-nav`（如需），导航条用 `utils/navigation.js` 的 `getNavigationMetrics()`
- 功能：展示搜索结果、支持分页/加载更多、点击结果跳 `post-detail`、空结果有友好提示、加载中有状态、请求失败有错误态
- **必须注册到 `app.json` 的 `pages` 数组**

**③ 接线** —— 把现有的假搜索接上
- `pages/index/index.js` 的 `submitSearch()` 改为跳转到搜索结果页（关键词要 `encodeURIComponent`）
- `pages/discover/discover.js` 的 `openSearch()` 同样处理
- 在 `utils/api.js` 中新增 `searchPosts()` 方法，**遵循该文件既有的写法**：`request()` + `.catch()` 离线兜底

**关于离线兜底**：搜索接口的兜底请对 `mock.discoveryPosts` 做和 `mockForum` 同样的 `LIKE` 过滤，保证后端未启动时搜索也能演示。

---

#### 任务 3：接通 9 个占位入口（P1）

以下入口目前点击只弹 toast，请**逐个接通到真实目标**：

| 入口 | 建议目标 |
|---|---|
| 开学优惠 | 跳活动页 `/pages/activities/activities` |
| 学习好物 | 跳二手市场并带分类 `/pages/market-list/market-list?category=学习好物` |
| 奶茶优惠 | 跳外卖店铺 `/pages/food-shop/food-shop?id=1` |
| 闲置台灯 | 跳二手详情（找一个合理的 id） |
| 联想小新校园优惠 | 广告位，可跳专题页或保留外链占位 |
| 通知铃铛 | 跳 `/pages/messages/messages`（**那里已经有真实通知数据**） |
| 换一批 | 要么接入推荐接口，要么至少真正重排本地数据（不能只弹 toast） |

**要求**：
- 跳转前请**核实目标页面真实存在**，且参数名与目标页 `onLoad(options)` 读取的字段一致
- 如果某入口的跳转目标确实不存在（比如二手详情需要真实 id），**不要硬编造**，请在设计里注明并给出你的处理方式
- `pages/index/index.js` 的 `switchTab()` 方法疑似死代码（底部导航实际走 `components/bottom-nav` 组件）—— 请**先确认它是否真的从未被调用**，再决定删除还是保留

---

### 三、自主判断的任务（你来排优先级）

以下问题**你知道就行，自己判断要不要做、做多少**。如果时间/篇幅允许，按你认为的价值排序推进；如果做不到，请在报告中明确说明**哪些没做、为什么**。

- **P2** 假数据模块：聊天消息持久化（`pages/chat/chat.js` 里 5 条消息硬编码在 `data` 中）、会话列表与通知列表（`utils/api.js` 里 `getConversations()`/`getNotifications()` 连 `request()` 都没调）、课表、日历
- **P3-1** `followUser` 前后端参数位置不一致（前端发 body，后端收 `@RequestParam`）—— 请核实后端兼容逻辑是否真的能通，给出结论
- **P3-2** 根目录 `index.js` / `api.js` 是死文件（`app.json` 未注册，且内容比 `pages/index/` 下的正式版更旧）—— 建议删除，但**删除前请先确认真的没有引用**
- **P3-4** 后端未启动时代码静默降级到 mock，用户无法分辨演示数据 —— 建议加全局离线提示
- **P3-5** `README_BACKEND.md` 说"五张表"，实际 `schema.sql` 已有 21 张表 —— 顺手同步

**排好优先级后，用一句话告诉我你的推进顺序和理由，然后开始做。**

---

### 四、硬性约束（违反即视为失败）

#### 1. 保持「读兜底、写不兜底」原则 —— 最重要

`utils/api.js` 有一个**刻意的设计**，不要破坏：

```js
// 读接口：网络失败 → 退回 mock，保证无后端也能演示
getForumPosts().catch(() => wait(mockForum(...)))

// 写接口：只有【网络失败】才兜底
createFoodOrder().catch(error => {
  if (error.server) throw error   // ← 服务端业务错误（拒单等）必须抛给用户看
  return wait(mock.makeFoodOrder(payload))
})
```

**新增接口时必须区分这两类。** 写接口（搜索算读接口，但要遵守既有风格）如果把服务端错误也吞掉，用户会以为操作成功了，实际服务端拒绝了。

#### 2. 只出 SQL 脚本，不要试图执行

新增数据库表/字段时：
- **产出去重可重复执行的 SQL 脚本**（用 `CREATE TABLE IF NOT EXISTS`、`ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 等等价写法，或明确说明需人工判断）
- 脚本放在 `backend/src/main/resources/db/` 下，命名参考现有文件风格
- **在报告中单独列出「需要你手动执行的 SQL」**
- 项目原 `schema.sql` 的运行方式见 `README_BACKEND.md`，请与之一致

#### 3. 不要重写已有功能

这个项目**已经完成度很高**：论坛、外卖、二手、兼职、活动、校草校花、个人中心全都是前后端打通的。**你的任务是补缺口，不是重构**。除非某个已有实现直接阻碍你完成任务，否则不要动它。

#### 4. 严格遵守现有代码风格

- 前端：原生小程序（**不是** uni-app/Taro），页面通过 `utils/api.js` 取数，弹层一律走 `utils/ui-popup-helper`
- 后端：Spring Boot 3.2 + MyBatis-Plus，统一响应 `ApiResponse.ok(data)`，Controller 方法保持项目现有的紧凑风格
- 中文注释风格与现有代码保持一致
- **不要引入新的第三方依赖**（除非有强理由，且需在报告中说明）

#### 5. 不要编造

- 不确定项目里有没有某个文件/函数，**就说你不确定**，不要假装看过
- 不要凭空发明后端已有的接口
- 如果某个任务因为信息不足无法完成，**明确说明缺什么**，不要糊弄过去

---

### 五、交付格式

请按以下结构输出：

**1. 开工前的确认**
- 你对项目的理解（3-5 句）
- 你发现的**文档与代码不一致**之处（如有）
- 你的推进顺序和理由

**2. 逐任务实现**
每个任务给出：
- 涉及的文件（完整路径）
- 完整代码（新增文件给全文，修改文件给改动后的完整函数或清晰 diff）
- 为什么这样改

**3. 需要你手动执行的 SQL**（单独成节，可直接复制执行）

**4. 最终报告**
- ✅ 完成了什么
- ⏭️ 没做什么，为什么
- ⚠️ 你不确定的地方 / 需要人工验证的点
- 🔍 你发现的**新问题**（我清单里没提到的）

---

### 六、特别注意

1. **`pages/index/index.js` 和根目录的 `index.js` 是两个不同文件** —— 前者是正式版（`app.json` 已注册），后者是旧版死文件。**改的时候别看错文件。**
2. `app.json` 里 `navigationStyle: "custom"`，所以**每个页面都要自己实现导航条**，参考 `utils/navigation.js` 的 `getNavigationMetrics()`。
3. 前端 `BASE_URL` 是 `http://127.0.0.1:8080/api`，后端未启动时所有读接口自动走本地 mock —— **这是特性不是 bug**，便于演示。
4. 项目 AppID 是测试号 `touristappid`，不需要真实 AppID。
5. **如果发现我的问题清单有误**（比如某个"未实现"其实已实现），请直接指出 —— 我欢迎被纠正，这比盲目执行更有价值。

现在开始。先给我「开工前的确认」部分。
