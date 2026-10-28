# 全项目排查报告：接口缺口与 Bug

> 排查日期：2026-10-28
> 分支：`merge-all`
> 方式：静态分析 + 运行时接口实测

---

## 一、结论速览

| 排查项 | 结论 |
|---|---|
| **前端调了但后端没有的接口** | **0 个** |
| **确认的真 Bug** | **2 个**（已修复） |
| **死参数（传了但组件不认）** | 5 处（已清理） |
| 结构性风险 | 3 项（见第五节） |

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

仓库 `tools/` 下有 4 个可复用脚本，改动后建议全跑一遍：

```bash
cd tools

# 1. 静态完整性（页面/组件/JS语法/WXML平衡/样式/资源）
node check-static.js

# 2. WXML 绑定了但 JS 没定义的方法
node check-bindings.js

# 3. 前后端接口对账
node check-api.js

# 4. 传给组件的属性是否已声明
node check-props.js

# 5. AI 聊天弹窗传参校验
node verify-ai-popup.js
```

**当前全部通过状态**：

```
check-static    : 6/6 ✅（40 页面 / 80 JS / 54 WXML / 41 资源）
check-bindings  : 0 处缺失 ✅
check-api       : 0 个真缺失 ✅
verify-ai-popup : id 存在 ✅
```

> **脚本准确性已修正**（排查过程中发现并修掉了 4 处解析器缺陷）：
>
> | 缺陷 | 影响 | 修法 |
> |---|---|---|
> | 数组多路径 `{"/a","/b"}` 里的 `{id}` 被误当分隔符 | 误报 `/food/shops/{id}/menu` 缺失 | 改为按括号配平解析 |
> | 裸 `@GetMapping`（无括号）未识别 | 误报 `/pet`、`/circle-messages` 缺失 | 正则改为 `\b` 边界 + 配平 |
> | 单行 `properties: { local: {...} }` 未识别 | 误报 `offline-banner.local` | 改为花括号配平 + 顶层 key 提取 |
> | `data-id` 先转 camel 再判前缀 | 误报 `chat-message.dataId` | 先按原始名过滤再做转换 |
>
> 另修了前端解析的顺序问题：`/jobs${qs}` 先 `split('?')` 会残留 `${qs}`，
> 凭空造出一个 `/jobs/:p`。改为先替换模板变量再切查询串。
>
> 现在两个脚本输出可直接采信（当前 61/61 匹配、0 处属性不匹配）。

---

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

