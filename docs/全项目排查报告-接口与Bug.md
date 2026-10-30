# 全项目排查报告：接口缺口与 Bug

> 排查日期：2026-10-28
> 分支：`merge-all`
> 方式：静态分析 + 运行时接口实测

---

## 一、结论速览

| 排查项 | 结论 |
|---|---|
| **前端调了但后端没有的接口** | **0 个** |
| **确认的真 Bug** | **5 个**（全部已修复） |
| **死参数（传了但组件不认）** | 5 处（已清理） |
| 未读数重复请求 | 已加缓存（切 tab 请求量降 2/3） |
| 结构性风险 | 3 项（见第五节） |

### 5 个 Bug 一览

| # | Bug | 影响 | 状态 |
|---|---|---|---|
| 1 | 个人中心统计行点击无反应 | 点「发布/关注/粉丝」毫无反馈 | ✅ 已修 |
| 2 | AI 聊天页弹窗完全不显示 | 超长提示、清空确认都失效 | ✅ 已修 |
| 3 | **跨学校店铺打不开** | 切过学校后点外卖店报「店铺不存在」 | ✅ 已修 |
| 4 | **结算页同类问题** | 从跨校店进结算同样失败 | ✅ 已修 |
| 5 | **评论点赞取消不掉** | 计数只涨不跌，`liked` 不持久 | ✅ 已修 |

> Bug 3/4 是写这份报告之后，用运行时测试才发现的——
> 静态分析看不出「列表页和详情页用了不同的学校范围」。
> 这也说明**运行时验证不可替代**：静态检查全绿，真实流程仍会挂。
>
> Bug 5 更绕：两个候选假设（`setData` 写法、后端接口）都被实测否掉，
> 真凶是**开发者工具没重新编译**，`utils/api.js` 的改动根本没生效。
> 详细教训见第三节 Bug 5 末尾。

### 运行时验证覆盖

除了静态分析，还用 `miniprogram-automator` 连开发者工具做了真实操作：

| 验证项 | 结果 |
|---|---|
| 40 个页面逐个 reLaunch | **40/40 渲染正常** |
| 首页首屏请求 | 4 个接口**各 1 次**，无重复 |
| 切 3 个 tab 的未读请求 | 缓存生效，只 2 次（原来每次切都请求） |
| 外卖完整链路 | 进店→菜单→加购→结算 **6/6 通过** |
| 跨学校店铺 | 修复后正常打开，空菜单显示空态 |
| 评论点赞/取消/持久化 | **11/11 通过**（含多用户隔离） |

---

## 二、接口对账：0 个真缺失

### 数据

| 项 | 数量 |
|---|---|
| 后端端点（展开数组多路径后） | 238 |
| 小程序调用点 | 60 |
| **后端缺失** | **0** |

### 运行时实测（后端跑着时直接调用）

| 接口 | 结果 |
|---|---|
| `GET /api/food/shops` | HTTP 200 ✅ |
| `GET /api/food/shops/1/menu` | HTTP 200 ✅（2 分类 4 商品） |
| `GET /api/jobs` | HTTP 200 ✅ |
| `GET /api/pet` | HTTP 401 ✅ 存在（需登录） |
| `GET /api/circle-messages` | HTTP 401 ✅ 存在（需登录） |

**外卖不是假数据**：实测返回 4 家真实店铺、菜单 2 个分类。

### 排查中出现的 3 次误判（已自我修正，记录备查）

我的解析脚本一开始报了 61 个「缺失」，全是误报，原因是：

| 误报原因 | 例子 |
|---|---|
| 后端用**数组多路径映射** `@GetMapping({"/shops", "/food/shops"})` | `/api/food/shops` 实际存在 |
| 后端用**裸 Mapping** `@GetMapping`（无括号无路径） | `/api/pet`、`/api/circle-messages` 实际存在 |
| 前端模板字符串里 `{id}` 的花括号干扰数组解析 | `/food/shops/{id}/menu` 被误判 |

**教训**：做这类对账必须先确认解析器能覆盖所有写法，否则会得出完全相反的结论。

---

## 三、已修复的 Bug

### Bug 1：个人中心统计行点击完全无反应

**位置**：`pages/profile/profile.wxml` 第 32 / 36 / 40 行

```html
<view class="stat-item" bindtap="openItem" data-label="我的发布">
<view class="stat-item" bindtap="openItem" data-label="我的关注">
<view class="stat-item" bindtap="openItem" data-label="我的粉丝">
```

**根因**：`profile.js` 里没有 `openItem` 方法。微信会抛
`does not have a method "openItem" to handle event "tap"` —— 点了毫无反应。

**修复**：`pages/profile/profile.js` 新增 `openItem()`：

| 点击 | 行为 |
|---|---|
| 我的发布 | 跳 `/pages/user-home/user-home?id=自己` |
| 我的关注 / 我的粉丝 | 提示「关注和粉丝名单还在开发中」 |

**为什么关注/粉丝只能给提示**：

- `app.json` 里没有关注/粉丝列表页
- 后端也没有 `following` / `followers` 列表接口（只有计数）
- 硬做会变成「点了没反应」或「空白页」，不如明确告知

### Bug 2：AI 聊天页所有弹窗都不显示

**位置**：`pages/ai-chat/ai-chat.wxml` 第 69-76 行（原）

```html
<ui-popup
  uiPopup="{{uiPopup}}"        ← 组件没有这个属性
  bind:confirm="onPopupConfirm"
  ...
```

**两个叠加的根因**：

1. 只传了未声明的 `uiPopup`，没传 `visible` / `title` / `message` 等 →
   `visible` 恒为默认值 `false`，弹窗永不显示
2. **缺少 `id="ui-popup"`** → `ui-popup-helper` 的
   `page.selectComponent('#ui-popup')` 找不到组件，
   `popup.confirm()` 直接静默失败

**受影响的两处交互**：

- `ai-chat.js:134` 输入超长提示 —— 之前没反应
- `ai-chat.js:182` 清空对话确认 —— 之前点了没反应

**修复**：改成标准全量传参 + 补 `id="ui-popup"`。
校验结果：组件声明 15 个属性，页面传了 12 个，
未传的 `showIcon` / `maskClosable` / `duration` 都有合理默认值。

---

### Bug 3：跨学校店铺打不开

**现象**：用户切换过学校后，从外卖列表点进店铺，报「店铺不存在」。

**位置**：`pages/food-shop/food-shop.js` 的 `onLoad`

```js
const [shops, menu] = await Promise.all([api.getFoodShops(), api.getFoodMenu(id)])
const shop = shops.find(row => Number(row.id) === id)
if (!shop) throw new Error('店铺不存在')
```

**根因**：两个页面的「学校范围」不一致。

| 页面 | 请求范围 | 结果 |
|---|---|---|
| 列表页 | 传 `schoolId`（用户选中的学校） | 选中北大 → 只显示店铺 5 |
| 详情页 | `getFoodShops()` 不传 → 默认「账号所属学校」 | 只拿到店铺 1-4 |

`find(5)` 返回 `undefined` → 抛错。

**修复**：详情页改用 `schoolScope='all'`：

```js
// 店铺 id 已确定，这里只要它的展示信息（名称/配送费/营业状态）。
// 用 all 范围能覆盖任何入口进来的店铺（列表、推荐位、分享链接）。
api.getFoodShops('', 'all', null)
```

**验证**（automator 实测）：
- id=1（本校）：正常，2 个菜单分类
- id=5（跨校）：**不再报错**，显示「北大食堂窗口」，空菜单正确显示空态

---

### Bug 4：结算页同款问题

排查完 Bug 3 后，用脚本扫了全项目的「列表传范围、详情不传」模式，发现结算页一样：

```js
// pages/food-order/food-order.js:14（修前）
api.getFoodShops()   // 不传范围
...
if (!shop) throw new Error('店铺不存在，请重新选购')
```

从跨校店铺进结算会直接失败。同样改为 `getFoodShops('', 'all', null)`。

**排查结论**：其余两处「无范围」调用经查不是同类问题——
`community` 用的是 `postScope` 参数且没有「查单条」需求；
`activity-enroll` 已经传了 `'all'`。

**验证**（本校店完整链路 6/6 通过）：
进店 → 菜单 → 加购 2 杯（小计 ¥24、差额 ¥0）→ 跳结算页 → 店铺加载正常。

---

### Bug 5：评论点赞取消不掉，计数只涨不跌

**现象**：点评论的爱心，`0 → 1 → 2 → 3` 一路加，图标始终是空的（`♡`），再点也取消不掉。

**定位过程**（踩了三个坑，值得记下来）：

1. 先怀疑 `setData` 的数组路径写法。单独测 `comments[0].liked` 三种写法
   （单 key / 双 key / 模板字符串）——**全部生效**，排除。
2. 再怀疑后端。curl 直接打接口：`liked:true` → `likeCount:1`，
   `liked:false` → `likeCount:0`，**接口完全正常**。
3. 最后加一行 `console.log` 打印运行时状态，才抓到真凶：

```
message: "api.likeComment is not a function"
```

（这条错误来自 `popup.toast` 里残留的 `message`，不是接口报错。
 开发者工具没有重新编译 `utils/api.js`，所以改动没生效——
 **诊断期间所有结论都跑在旧编译产物上，这一点一度把我带偏。**）

**两个真 Bug 叠在一起**：

| # | 问题 | 机制 |
|---|---|---|
| 5a | **回滚基线取错** | `const item = this.data.comments[idx]` 拿的是**对象引用**。`setData` 会把 `item.likeCount` **原地改成新值**，`catch` 里再读 `item.likeCount` 拿到的是改后的值，于是「回滚」实际是**把新值又写回去**。`liked` 因为用了 `!liked` 取反所以能回滚，计数回滚失效 → 只涨不跌 |
| 5b | **`liked` 压根不持久** | 后端 `GET /api/posts/{id}/comments` **不返回 `liked` 字段**，前端 `liked:!!x.liked` 永远归一成 `false`，一刷新就丢 |

**修复 5a：回滚基线改成值快照**

```js
// 基线必须在 setData 之前抓成「值」，不能留对象引用
const item = this.data.comments[idx]
const prevLiked = !!item.liked
const prevCount = item.likeCount || 0
...
catch(error){
  this.setData({
    [`comments[${idx}].liked`]: prevLiked,
    [`comments[${idx}].likeCount`]: prevCount   // 真正的旧值
  })
}
```

**修复 5b：给评论补上真正的点赞明细表（不是本地缓存兜底）**

原来 `toggleCommentLike` 的注释写着「评论没有独立的点赞明细表，前端自己维护 liked」——
这是把状态一致性甩给了客户端。正确做法是照 `post_like` 的样子补一张表：

```sql
CREATE TABLE `comment_like` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `comment_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_user` (`comment_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

配套改动：

| 层 | 改动 |
|---|---|
| 实体/Mapper | 新增 `CommentLike` + `CommentLikeMapper` |
| `ForumService.toggleCommentLike` | 改成 `(commentId, userId, liked)`，**以明细表为准**：已存在就不重复 +1，不存在就不 -1（天然幂等） |
| `ForumService.comments` | 新增重载 `comments(id, userId)`，批量查出当前用户点过赞的评论 id，列表里带上 `liked` |
| `ForumController` | 评论列表与点赞都取 `CurrentUser.optional(request)`，**匿名不回落到默认用户**（否则会把别人的点赞状态当成自己的） |
| 前端 | 去掉本地缓存兜底，改用服务端回传的 `res.liked` |

**验证一：接口层（curl，逐条断言）**

| 场景 | 期望 | 结果 |
|---|---|---|
| 列表 | 每条评论带 `liked` 字段 | ✅ |
| 点赞 | `likeCount` +1，`liked:true` | ✅ |
| 重复点赞 | **不再 +1**（幂等） | ✅ |
| 取消点赞 | `likeCount` -1，`liked:false` | ✅ |
| 匿名点赞 | `401 请先登录` | ✅ |
| 用户 A 点赞后，用户 B 查看 | B 看到 `liked:false`（状态隔离） | ✅ |
| 用户 B 取消后，用户 A 再看 | A 仍 `liked:true`，计数正确 | ✅ |

**验证二：UI 层（automator 真实点击，11/11 通过）**

| 场景 | 断言 | 结果 |
|---|---|---|
| 初始态 | 从服务端读到 `liked=false` | ✅ |
| 点一次 | `liked=true`，计数 +1 | ✅ |
| **点赞后重进页面** | `liked` 仍为 `true`（服务端持久化，不靠本地缓存） | ✅ |
| 再点一次 | `liked=false`，计数回到原值 | ✅ |
| 取消后重进页面 | `liked` 仍为 `false` | ✅ |
| 连点两次 | 回到未点赞，计数不叠加 | ✅ |
| 点评论 B | 评论 A 的计数不受影响 | ✅ |

> **这个 Bug 的教训**：
> 1. 两个候选假设（setData 写法、后端接口）都被实测否掉了，
>    真正的线索来自「同一份源码、重新编译后行为变了」。
>    遇到「代码明明写了却说不存在」，先确认编译产物是不是最新的。
> 2. 「服务端没有明细表，前端自己维护状态」是很危险的偷懒：
>    它把幂等性、多端一致性、越权隔离全部推给了客户端。
>    凡是涉及「谁点过什么」的状态，服务端必须有一行记录。

---

## 四、清理的死参数

`pet-avatar` 组件只声明了 7 个属性：
`auto / paused / speed / action / assetBase / label / size`

但有 4 个文件传了它不认的参数，传了会被忽略：

| 文件 | 清理的参数 |
|---|---|
| `pages/adopt/adopt.wxml` | `appearance` |
| `pages/companion/companion.wxml` | `appearance` |
| `components/chat-message/index.wxml` | `appearance`、`crop` |
| `components/pet-float/index.wxml` | `appearance` |

**这意味着什么**：`utils/pet-appearance.js` 里那套
`species / tint / accessory / mood` 外观体系，
**传给渲染层后没有任何作用** —— 全站只有一种小橘猫。
数据层保留未动，只是不再传无用参数。

### 顺带澄清两处误判（不是 bug）

| 疑似问题 | 真相 |
|---|---|
| `chat-message` 收到未声明的 `appearance` | **它确实声明了**（`index.js:10`） |
| `companion` 传 `data-id` 未声明 | `data-*` 是微信内置 dataset 属性，不是组件属性 |
| `offline-banner` 收到未声明的 `local` | **它确实声明了**（单行写法，我的正则一开始没匹配到） |

---

## 五、结构性风险（不是即时 bug）

### 风险 1：mock 兜底较多，但提示覆盖是合理的

| 模块 | mock 兜底处数 |
|---|---|
| `market.js` | 25 |
| `chat.js` | 19 |
| `circle.js` | 14 |
| `forum.js` | 6 |
| `learn.js` | 2 |

**机制**：网络失败（`fail()`）才回落本地演示数据；
后端返回 4xx/5xx 时会 `server: true` 并**抛错**，不会静默造假。

**已运行时核查**（用 automator 逐页检查 ``offline-banner`` 挂载情况）：

| 页面 | banner 数量 | 说明 |
|---|---|---|
| community / learn | 1 | 挂载正常 |
| food-list / market-list / jobs / activities | 1 | 挂载正常（页面无需自己的 offline 字段，组件内部订阅 data-mode 判断） |
| **campus-home** | **0** | 见下方说明，确认不需要 |

``campus-home`` 不需要 banner 的理由（已查代码确认）：
- 宠物接口失败时用 ``.catch(() => null)`` 静默处理
- ``utils/pet-api.js`` **没有任何 mock 兜底**
- 所以结果是「宠物数据为空 → 用默认值小云 Lv.1 渲染」，属于**降级显示**，
  不是把假数据伪装成真的

剩下 5 个没挂的，都**不需要**：

| 页面 | 为什么不需要 |
|---|---|
| `maintenance` | 本身就是"服务不可用"页 |
| `companion` | 每条消息自带「演示数据」标记 |
| `campus-home` | 接口失败是静默降级（用默认值渲染），不显示假数据 |
| `activity-enroll` / `wechat-qr` | 写操作为主，失败会抛错 |

### 风险 2：宠物外观定制未实现

见第四节。若要真做，需要多套美术素材（目前只有一套小橘猫）。

### 风险 3：关注/粉丝只有数字，点不进名单

要做完整需：新增 `GET /api/users/{id}/following|followers` + 一个列表页。

---

## 六、回归验证方式

`tools/` 下已整合成**统一入口**，一条命令跑完：

```bash
cd tools

# 静态检查（5 项，不需要开发者工具，秒级完成）
node check-all.js

# 加上运行时检查（3 项，需要开发者工具）
node check-all.js --runtime
```

### 静态检查（5 项）

| 脚本 | 检查什么 |
|---|---|
| `check-static.js` | 页面/JS语法/WXML平衡/组件引用/资源引用 |
| `check-bindings.js` | WXML `bind*` 绑的方法在 JS 里是否存在 |
| `check-api.js` | 小程序调的接口后端是否都有 |
| `check-props.js` | 传给组件的属性是否已声明 |
| `verify-ai-popup.js` | ai-chat 的 ui-popup 是否传全 |

### 运行时检查（4 项）

| 脚本 | 检查什么 |
|---|---|
| `smoke-test.js` | 页面渲染 + 交互（点发布/点关注/AI弹窗） |
| `test-flows.js` | 业务流程（二手/圈子/兼职/搜索/消息） |
| `check-pages.js` | 40 个页面逐个 reLaunch |
| `check-requests.js` | 首屏请求是否有重复 |

运行时需要先开自动化端口。**两步都要显式带 `--port 38038`**，
否则 `auto` 启动的 IDE 和你后续命令连的 IDE 可能是两个不同的实例，
表现为 `evaluate`/`reLaunch` 全部超时：

```bash
cd "D:\JavaStudy\微信web开发者工具"
# 0. 先清干净，避免多实例
taskkill /F /IM wechatdevtools.exe
# 1. 开 IDE 并打开项目
cli.bat open --project "E:\goodworlk\campus-life-miniapp" --port 38038 --lang zh
# 2. 挂自动化（等 IDE 编译完再执行，约 40s）
cli.bat auto --project "E:\goodworlk\campus-life-miniapp" --port 38038 --auto-port 9420 --lang zh
```

> 自动化端口闲置约一小时会自动断开，重跑前重新执行即可。
> 注意：日志里大量 `errcode 41001 需要重新登录` **不代表**自动化不可用，
> 对照过成功跑通的日志，同样有几千条该报错 —— 不要被它带偏。

### 最近一次完整回归：11 项全通过（2026-10-30）

```
[✅] 静态完整性
[✅] WXML 绑定
[✅] 接口对账
[✅] 组件属性
[✅] 弹窗传参
[✅] popup 方法
[✅] API 导出
[✅] 冒烟测试        4 项通过 0 失败
[✅] 业务流程        5 项通过 0 失败
[✅] 全页面渲染      40 / 40 渲染正常
[✅] 首屏请求        4 个接口各 1 次，无重复
```

> 跑之前先确认后端起在 8080。后端没起时「业务流程」会失败，
> 但那不是代码问题 —— 先看 `backend=000` 还是 `200` 再判断。

### 写测试时踩过的坑（供后续参考）

| 坑 | 现象 | 解法 |
|---|---|---|
| 手动替换 `wx.request` 计数 | 页面重建后失效，出现「每个接口 2 次」假象 | 用官方的 `mockWxMethod` |
| 一次连接里连续多次 reLaunch | 撞上页面过渡动画 → `Error: timeout` | 每项检查 fork 独立子进程 |
| 用固定 `sleep` 等元素 | 渲染慢时误报「元素未渲染」 | 改用轮询 `waitFor` |
| toast 类弹窗判据用 `visible` | 等太久时 toast 已自动关，误判失败 | 改判 `message` 是否写入（toast 1600ms 后关） |
| 拿 `reLaunch` 返回的 page 句柄查元素 | 后续跳转后句柄失效 | 每次重新 `mini.currentPage()` |
| **改了 `utils/` 下的文件就立刻跑自动化** | 报 `api.xxx is not a function`，但源码里明明有 —— 开发者工具**没重新编译**，跑的是旧产物 | 改完 `utils/` 先让工具重新编译（或重开自动化连接）再测，否则所有结论都建立在幻影代码上 |
| 只打印 `comments[0]` 判断状态 | 数组顺序不保证，看错了下标会得出「setData 无效」的错误结论 | 打印**整个数组**（id + 状态），别只信第一个 |
| **IDE 存在多个实例时挂自动化** | `connect()` 能成功、`evaluate`/`reLaunch` 全部超时。`auto` 启动的 IDE 在一个端口，后续 `cli` 命令连的是另一个端口，自动化挂到了没有项目窗口的空实例上 | 先 `taskkill /F /IM wechatdevtools.exe` 清干净，再 `cli open --project ... --port 38038`，然后 `cli auto --project ... --port 38038 --auto-port 9420`，**所有 CLI 调用都显式带同一个 `--port`** |
| 把 `islogin=false` 当成自动化失败的原因 | 日志里 `errcode 41001 需要重新登录` 刷了几千条，看起来像根因 —— 但对照成功那次的日志，同样有 4585 条该报错却跑得好好的 | **判断根因要拿「成功样本」对照**，不要只看失败样本里最显眼的报错 |

## 七、运行时验证（已补齐）

之前只有静态分析。后来用 `miniprogram-automator` 连上开发者工具，
做了真实的页面跳转与点击验证，**13 项全部通过**：

```
=== 页面渲染 ===
  ✅ pages/index/index
  ✅ pages/community/community
  ✅ pages/food-list/food-list
  ✅ pages/profile/profile
  ✅ pages/ai-chat/ai-chat
  ✅ pages/market-list/market-list
  ✅ pages/discover/discover

=== 个人中心统计行（Bug 1）===
  ✅ 找到 3 个统计行
  ✅ 点「发布」跳个人主页  pages/user-home/user-home
  ✅ 点「关注」弹提示  「关注和粉丝名单还在开发中，暂时看不了」

=== AI 聊天弹窗（Bug 2）===
  ✅ selectComponent('#ui-popup') 能找到组件
  ✅ 设置 visible 后标题正确
  ✅ 弹窗 DOM 真的渲染出来（15 个节点）

结果：13 通过，0 失败
```

**这证明了两件事**：
1. 小程序**编译是成功的**，7 个主要页面都能正常渲染
2. 两个 bug 的修复**在运行时确实生效**，不是只改了代码

### 怎么复现这个验证

```bash
# 1. 开发者工具打开项目后，另开终端：
cd "D:\JavaStudy\微信web开发者工具"
cli.bat auto --project "E:\goodworlk\campus-life-miniapp" --port 38038 --auto-port 9420 --lang zh

# 2. 跑冒烟测试
cd E:\goodworlk\campus-life-miniapp\tools
node smoke-test.js
```

> automator 的 `checkVersion()` 在版本串为空时会崩，脚本里用直接 `connect` 绕过了。

---

## 八、仍然未验证的部分

| 项 | 状态 |
|---|---|
| 小程序编译 | ✅ 已通过 automator 验证（7 个页面正常渲染） |
| 两个 Bug 的修复效果 | ✅ 已验证（见第七节） |
| **真机表现** | ❌ 无真机环境，未测 |
| **视觉观感** | ❌ 视觉服务不可用（模型 404），截图读不出来 |
| **并发/压力** | ❌ 未做，见 docs/压测方案与性能基线.md |

真机需要你用自己的 AppID 预览后确认，重点看：
1. 个人中心三个数字的点击反馈
2. AI 聊天输入超长文字时的提示、清空对话的确认框
3. 悬浮宠物拖动是否跟手

