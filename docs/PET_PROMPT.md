# 校园宠物（Lottie 动画）· GPT 提示词

> 工程目录：`E:\_verify_completed\campus-life-miniapp`
> 目标：给小程序加一个养成宠物页面，用 Lottie 做动画
> 技术选型：**Lottie（MIT 可商用）+ lottie-miniprogram**

---

=== 提示词开始 ===

## 一、你的任务

给校园小程序加一个**养成宠物**功能。学生打开小程序能看到自己的宠物，能喂它、摸它、签到涨经验。

**核心要求：**

1. 宠物用 **Lottie 矢量动画**渲染（不是 GIF，不是视频）
2. 宠物页面是**独立页面**，从首页或「我的」跳转进去
3. 宠物有**状态变化**：开心、饥饿、困倦、升级
4. 有简单的养成玩法：喂食、摸摸、每日签到

**明确不做：**

- ❌ 3D 形象
- ❌ 宠物之间对战/社交
- ❌ 复杂的换装系统
- ❌ 实时推送提醒（微信订阅消息限制，做不了）

## 二、项目背景（重要，先看懂再动手）

- 小程序：微信原生（WXML/WXSS/JS/JSON），AppID `touristappid`，当前 **28 个页面**
- 后端：Spring Boot 3.2.5 + MyBatis-Plus 3.5.6 + MySQL 8，包名 `com.campuslife`
- 数据库：`campus_life`，账号 `root`
- 统一返回 `{code, msg, data}`，成功 `code=0`

### 2.1 一个必须遵守的项目约定

**这个项目的小程序端是「零 npm 依赖」的。**

现状：

- 没有 `package.json`
- 没有 `node_modules`
- 没有 `miniprogram_npm`
- 只有 `components/` 目录放自定义组件

**所以你不能用 `npm install lottie-miniprogram` + 「构建 npm」的方式引入。**

必须用**手动放文件**的方式（见 5.1）。

### 2.2 另一个现状

**28 个页面里没有一个用过 canvas。** 宠物页是第一个，所以要确保 canvas 初始化的写法正确。

## 三、动手前必须先读这些文件

1. `pages/index/index.wxml` —— 首页，宠物入口要加在这里（参考 `.ai-entry` 的写法）
2. `pages/index/index.js` —— 首页逻辑，参考 `openAiChat()` 的跳转写法
3. `pages/profile/profile.wxml` —— 「我的」页面，也可能加入口
4. `utils/api.js` —— 接口封装（注意「读兜底、写不兜底」）
5. `utils/navigation.js` —— `getNavigationMetrics()`
6. `utils/ui-popup-helper.js` —— 弹提示必须用这个，不用 `wx.showToast`
7. `app.json` —— 新页面要注册
8. `backend/.../controller/` 任选一个 controller —— 参考接口写法
9. `backend/src/main/resources/db/` —— 迁移脚本风格

## 四、要做什么

### 4.1 引入 Lottie（保持零依赖）

**步骤 1：下载 lottie 的小程序版本**

从 `lottie-miniprogram` 的 npm 包里，把编译好的 JS 文件复制到项目里：

```
campus-life-miniapp/
└── libs/
    └── lottie.min.js       ← 手动放进来的，不是 npm 安装
```

**你需要在提示词执行时做的事：**

1. 创建一个 `libs/` 目录（项目里现在没有）
2. 把 `lottie-miniprogram` 的构建产物 `lottie.min.js` 放进去
3. 如果拿不到文件，告诉我，我来下载

**步骤 2：页面里用相对路径 require**

```js
const lottie = require('../../libs/lottie.min.js')
```

**不要写 `require('lottie-miniprogram')`**，那样会报找不到模块。

### 4.2 canvas 初始化的正确写法（重点，容易错）

**WXML：**

```html
<canvas
  type="2d"
  id="pet-canvas"
  class="pet-canvas"
></canvas>
```

**JS：**

```js
onReady() {
  this.initLottie()
},

initLottie() {
  const query = wx.createSelectorQuery().in(this)
  query.select('#pet-canvas')
    .fields({ node: true, size: true })
    .exec((res) => {
      if (!res || !res[0] || !res[0].node) {
        console.error('canvas 节点获取失败')
        return
      }
      const canvas = res[0].node
      const ctx = canvas.getContext('2d')

      // 关键：处理设备像素比，否则动画会糊
      const dpr = wx.getWindowInfo().pixelRatio || 2
      canvas.width = res[0].width * dpr
      canvas.height = res[0].height * dpr
      ctx.scale(dpr, dpr)

      // 初始化 lottie
      lottie.setup(canvas)

      this.ani = lottie.loadAnimation({
        loop: true,
        autoplay: true,
        animationData: require('./pet-idle.json'),
        rendererSettings: { context: ctx }
      })
    })
}
```

**三个必须注意的点：**

1. **`type="2d"` 不能少**，不加就是旧版 canvas，lottie 用不了
2. **必须处理 `dpr`**，不处理的话在高分屏上动画会模糊
3. **`lottie.setup(canvas)` 必须在 `loadAnimation` 之前调用**

### 4.3 动画的销毁与切换

**页面隐藏时必须暂停，页面卸载时必须销毁**，否则会内存泄漏、耗电。

```js
onHide() {
  if (this.ani) this.ani.pause()
},

onUnload() {
  if (this.ani) {
    this.ani.destroy()
    this.ani = null
  }
},

onShow() {
  if (this.ani) this.ani.play()
  // 顺便刷新宠物状态
  this.loadPet()
}
```

**切换动画（比如从 idle 切到 happy）：**

```js
switchAnimation(state) {
  if (this._currentAnim === state) return
  this._currentAnim = state

  const data = {
    idle: () => require('./pet-idle.json'),
    happy: () => require('./pet-happy.json'),
    hungry: () => require('./pet-hungry.json'),
    sleep: () => require('./pet-sleep.json'),
    levelup: () => require('./pet-levelup.json')
  }[state]

  if (!data) return

  if (this.ani) this.ani.destroy()
  const canvas = this._canvas
  const ctx = this._ctx
  lottie.setup(canvas)
  this.ani = lottie.loadAnimation({
    loop: state !== 'levelup',
    autoplay: true,
    animationData: data(),
    rendererSettings: { context: ctx }
  })
}
```

**注意：`levelup` 动画只播一次**，播完要切回 `idle`。

### 4.4 宠物状态设计

**五个状态，对应五个 Lottie 文件：**

| 状态 | 触发条件 | 动画文件 |
|---|---|---|
| `idle` | 默认 | `pet-idle.json` |
| `happy` | 刚喂食/摸摸后 3 秒 | `pet-happy.json` |
| `hungry` | 饥饿度 < 30 | `pet-hungry.json` |
| `sleep` | 23:00-07:00 或心情 < 20 | `pet-sleep.json` |
| `levelup` | 刚升级 | `pet-levelup.json` |

**判断顺序（重要，从上往下匹配）：**

```js
function computeState(pet, now) {
  if (pet.justLeveledUp) return 'levelup'
  if (pet.justInteracted && now - pet.lastInteractAt < 3000) return 'happy'
  const hour = new Date().getHours()
  if (hour >= 23 || hour < 7) return 'sleep'
  if (pet.hunger < 30) return 'hungry'
  if (pet.mood < 20) return 'sleep'
  return 'idle'
}
```

### 4.5 数据库迁移

新建 `backend/src/main/resources/db/module-pet.sql`，**可重复执行**（用 `information_schema` 判断，参考 `module-learn.sql`）。

**A. 宠物表**

```sql
CREATE TABLE user_pet (
    id          bigint PRIMARY KEY AUTO_INCREMENT,
    user_id     bigint NOT NULL,
    name        varchar(20) DEFAULT '小星',      -- 宠物名字，可改
    level       int DEFAULT 1,                   -- 等级 1-10
    exp         int DEFAULT 0,                   -- 当前经验
    hunger      int DEFAULT 80,                  -- 饥饿度 0-100，越高越饱
    mood        int DEFAULT 80,                  -- 心情 0-100
    last_feed_at      datetime,                  -- 上次喂食
    last_touch_at     datetime,                  -- 上次摸摸
    last_decay_at     datetime,                  -- 上次衰减计算时间
    created_at  datetime DEFAULT CURRENT_TIMESTAMP,
    updated_at  datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user (user_id)
);
```

**B. 互动记录表**

```sql
CREATE TABLE pet_interaction_log (
    id         bigint PRIMARY KEY AUTO_INCREMENT,
    user_id    bigint NOT NULL,
    action     varchar(16) NOT NULL,    -- feed / touch / checkin / levelup
    exp_gain   int DEFAULT 0,
    cost_points int DEFAULT 0,          -- 消耗的积分
    created_at datetime DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_time (user_id, created_at)
);
```

**C. 积分表（如果还没建）**

宠物喂食要消耗积分，所以需要积分系统。如果项目里还没有：

```sql
CREATE TABLE user_points (
    user_id      bigint PRIMARY KEY,
    balance      int DEFAULT 0,
    total_earned int DEFAULT 0,
    updated_at   datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE point_log (
    id         bigint PRIMARY KEY AUTO_INCREMENT,
    user_id    bigint NOT NULL,
    amount     int NOT NULL,            -- 正数赚，负数花
    type       varchar(32) NOT NULL,    -- checkin/feed/touch/post
    ref_id     bigint,
    remark     varchar(100),
    created_at datetime DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_time (user_id, created_at)
);
```

**D. 插入测试数据**

给 user_id=1 插入一只宠物（level=3, exp=50, hunger=60, mood=70），方便验证页面。

### 4.6 养成规则

**经验与升级：**

| 等级 | 升级所需经验 |
|---|---|
| 1→2 | 100 |
| 2→3 | 200 |
| 3→4 | 350 |
| 4→5 | 550 |
| 5→6 | 800 |
| 6→7 | 1100 |
| 7→8 | 1500 |
| 8→9 | 2000 |
| 9→10 | 2600 |
| 10 | 满级 |

**公式：`needExp(level) = 50 * level * (level + 1)`**，满级 10。

**互动规则：**

| 操作 | 效果 | 消耗 | 限制 |
|---|---|---|---|
| 喂食 | 饥饿 +30，经验 +15 | 10 积分 | 每天 5 次 |
| 摸摸 | 心情 +10，经验 +5 | 免费 | 每小时 10 次（防刷） |
| 每日签到 | 经验 +20，积分 +5 | 免费 | 每天 1 次 |

**状态衰减（重要，别用定时任务）：**

不要写定时任务每天扫全表。用**惰性计算**：

```java
// 每次查询宠物时，按时间差计算衰减
long hours = Duration.between(pet.getLastDecayAt(), now).toHours();
if (hours > 0) {
    int newHunger = Math.max(0, pet.getHunger() - (int)(hours * 3));   // 每小时 -3
    int newMood = Math.max(0, pet.getMood() - (int)(hours * 2));       // 每小时 -2
    pet.setHunger(newHunger);
    pet.setMood(newMood);
    pet.setLastDecayAt(now);
    // 更新数据库
}
```

**为什么惰性计算**：定时任务要扫所有用户的宠物，几万条扫不动；惰性计算只在用户打开时算一次，成本几乎为零。

### 4.7 小程序页面 `pages/pet/pet`

**注册到 `app.json`：** `pages/pet/pet`

**页面布局：**

```
┌────────────────────────────────┐
│ ← 我的宠物              积分 320 │  ← 自定义导航栏
├────────────────────────────────┤
│                                │
│        [Lottie 动画区]          │
│          （宠物）               │
│                                │
│         小星  Lv.3             │
│                                │
├────────────────────────────────┤
│ 饱食度  ████████░░  60          │
│ 心情    ███████░░░  70          │
│ 经验    ███░░░░░░░  50/350      │
├────────────────────────────────┤
│  [喂食 10积分]  [摸摸]  [签到]   │
└────────────────────────────────┘
```

**关键实现：**

1. **动画区固定高度**，比如 `560rpx`，宽度撑满
2. **状态条**用 CSS 实现，不用 canvas
3. **三个按钮**：喂食（消耗积分）、摸摸（免费）、签到（每天一次）
4. **点按钮后**：调接口 → 刷新状态 → 播 `happy` 动画 3 秒 → 切回 `idle`
5. **积分不够时**：提示「积分不足，去发帖赚积分」
6. **升级时**：播 `levelup` 动画 + 弹提示「升到 Lv.4 了！」

**页面样式：** 参考项目现有风格（`#fcfcfc` 背景、白卡片、圆角 `24rpx`、主色 `#3476f6`）。

**导航栏必须写 `box-sizing: content-box`**（项目全局是 `border-box`，不写会被压扁，这是踩过的坑）。

### 4.8 入口跳转

**在首页加入口**（`pages/index/index.wxml`）：

参考现有 `.ai-entry` 的写法，在它旁边加一个：

```html
<view class="pet-entry" bindtap="openPet">
  <text class="pet-entry-icon">🐾</text>
  <text class="pet-entry-text">我的宠物</text>
  <view wx:if="{{petHungry}}" class="pet-entry-dot"></view>
</view>
```

```js
openPet() {
  wx.navigateTo({ url: '/pages/pet/pet' })
}
```

**同时在「我的」页面加入口**（`pages/profile/profile.wxml`），放在常用功能区。

**首页入口要显示小红点**：如果宠物饿了（hunger < 30），显示红点提示用户去喂。

### 4.9 后端接口

新建 `PetController`（`@RequestMapping("/api/pet")`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/pet` | 查我的宠物（含衰减计算后的最新状态） |
| POST | `/pet/feed` | 喂食（扣积分，加经验） |
| POST | `/pet/touch` | 摸摸（免费，加心情和经验） |
| POST | `/pet/checkin` | 每日签到（加经验积分） |
| PUT | `/pet/name` | 改宠物名字 |

**返回结构：**

```json
{
  "code": 0,
  "data": {
    "id": 1,
    "name": "小星",
    "level": 3,
    "exp": 50,
    "expNeed": 350,
    "hunger": 60,
    "mood": 70,
    "state": "idle",
    "canFeed": true,
    "canTouch": true,
    "canCheckin": false,
    "points": 320
  }
}
```

`state` 由后端算好返回（前端不用自己判断时间），前端只负责按 `state` 切动画。

**并发安全（重要）：**

喂食要扣积分，必须用条件更新防重复扣：

```java
// 扣积分
int rows = pointsMapper.deduct(userId, 10);
if (rows == 0) throw new IllegalStateException("积分不足");

// 加饥饿度
petMapper.feed(userId, 30, 15);
```

```sql
UPDATE user_points SET balance = balance - #{cost}
WHERE user_id = #{userId} AND balance >= #{cost}
```

`rows == 0` 就说明积分不够。

## 五、Lottie 素材从哪来

**这是本功能唯一的难点：动画文件需要美术资源。**

三个来源：

### 来源 1：LottieFiles 免费区（推荐先试）

`lottiefiles.com` 有大量免费动画，筛选 `Free` + `License: Lottie Simple License`。

搜关键词：`cat`、`pet`、`cute character`、`idle`、`happy`。

**注意**：部分动画标注 `Premium`，不能商用。下载时看清授权。

### 来源 2：自己用 Figma 做（需要设计能力）

Figma + `LottieFiles` 插件，画简单形状做动画。

适合做极简风格（圆形的宠物、几何图形），成本低。

### 来源 3：先跑通链路，用占位动画

**这是我建议的起步方式：**

先自己写 5 个极简的 Lottie JSON（就是一个圆在呼吸/跳动），把整条链路跑通：

```json
{
  "v": "5.7.4",
  "fr": 30,
  "ip": 0,
  "op": 60,
  "w": 300,
  "h": 300,
  "nm": "placeholder",
  "layers": [{
    "ddd": 0,
    "ind": 1,
    "ty": 4,
    "nm": "circle",
    "sr": 1,
    "ks": {
      "o": {"a": 0, "k": 100},
      "r": {"a": 0, "k": 0},
      "p": {"a": 0, "k": [150, 150, 0]},
      "a": {"a": 0, "k": [0, 0, 0]},
      "s": {"a": 1, "k": [
        {"t": 0, "s": [100, 100, 100]},
        {"t": 30, "s": [110, 110, 100]},
        {"t": 60, "s": [100, 100, 100]}
      ]}
    },
    "shapes": [{
      "ty": "el",
      "p": {"a": 0, "k": [0, 0]},
      "s": {"a": 0, "k": [120, 120]}
    }]
  }]
}
```

**先把代码跑通，美术后面再换。** 不要因为找不到素材就卡住整个功能。

## 六、验收标准

| # | 检查项 | 通过标准 |
|---|---|---|
| 1 | 后端能编译 | Maven BUILD SUCCESS |
| 2 | 迁移脚本可重复执行 | 连跑两次不报错 |
| 3 | **保持零依赖** | 项目里没有 `package.json` / `node_modules` / `miniprogram_npm` |
| 4 | 页面能打开 | 从首页点「我的宠物」能进 |
| 5 | **Lottie 动画能播** | canvas 里能看到动画在动，不模糊 |
| 6 | 状态切换 | 喂食后播 happy 动画，3 秒后回 idle |
| 7 | 状态条正确 | 饱食度/心情/经验数值和进度条一致 |
| 8 | 喂食生效 | 饥饿 +30，经验 +15，积分 -10 |
| 9 | 积分不足提示 | 积分 <10 时喂食，提示「积分不足」 |
| 10 | 摸摸生效 | 心情 +10，经验 +5 |
| 11 | 签到生效 | 每天只能一次，第二次提示「今天已签到」 |
| 12 | 升级动画 | 经验满了播 levelup 动画并提示 |
| 13 | **页面隐藏不耗电** | 切到别的页面，动画暂停 |
| 14 | **内存不泄漏** | 反复进出宠物页 10 次，不卡顿 |
| 15 | 入口红点 | 宠物饿了首页显示红点 |

## 七、不要做的事

- **不要用 npm 安装 lottie**（必须手动放 `libs/`，保持零依赖）
- **不要忘记 `type="2d"`**（旧版 canvas 不支持 lottie）
- **不要忘记处理 dpr**（否则高分屏动画模糊）
- **不要在页面隐藏时不暂停动画**（耗电、卡顿）
- **不要在页面卸载时不 destroy**（内存泄漏）
- **不要用定时任务算状态衰减**（用惰性计算）
- **不要在前端判断宠物状态**（后端算好返回 `state`）
- **不要用 `wx.showToast`**（项目统一用 `ui-popup-helper`）
- **不要忘记给导航栏写 `box-sizing: content-box`**
- 不要做宠物社交/对战（明确不做）

## 八、交付顺序

1. 建表 + 后端接口（先用固定数据返回，不接逻辑）
2. 下载 `lottie.min.js` 放进 `libs/`，写一个占位 Lottie JSON
3. 做宠物页面，**先把动画渲染出来**（这一步最关键，卡住了后面都别做）
4. 接后端数据，做状态条
5. 做喂食/摸摸/签到三个按钮
6. 做状态切换（idle/happy/hungry/sleep/levelup）
7. 加入口跳转 + 红点
8. 最后换正式美术素材

**第 3 步验证通过再往下做。** 动画渲染不出来，其它都是空谈。

=== 提示词结束 ===

---

## 附：给你的说明

### 为什么强调「手动放文件」

你的项目是零 npm 依赖的，这是好事——小程序端引入 npm 会带来构建复杂度和包体积问题。

但 `lottie-miniprogram` 官方文档全是 `npm install` 的写法，GPT 大概率会照抄，然后你就卡在「构建 npm」这一步。

所以提示词里我写死了：**下载 JS 文件手动放进 `libs/`**。

如果 GPT 拿不到文件，你告诉我，我帮你下载。

### 为什么先做占位动画

找 5 个风格统一、可商用、还能表达不同情绪的 Lottie 动画，比写代码难得多。

**建议路径：**

1. 先用一个「圆形呼吸」的占位动画把链路跑通（半天）
2. 功能全部做完（2-3 天）
3. 再花时间找美术（时间不定）

这样功能不会因为美术卡住。

### 美术素材的现实

LottieFiles 免费区能搜到宠物动画，但：

- 风格很难统一（5 个状态要看起来是同一只宠物）
- 授权要一个个看（有的标 Free 但不能商用）
- 中文关键词搜不到，要用英文

**如果预算允许，建议花 200-500 元在猪八戒/淘宝找设计师做一套 5 个状态的 Lottie，比自己在免费库里凑快得多。**

### 工作量

| 部分 | 时间 |
|---|---|
| 建表 + 后端接口 | 1.5 天 |
| Lottie 接入 + 占位动画 | 0.5 天 |
| 宠物页面 + 状态条 | 1 天 |
| 三个互动按钮 | 1 天 |
| 状态切换 | 0.5 天 |
| 入口 + 红点 | 0.5 天 |
| **代码合计** | **约 5 天** |
| 美术素材 | 视情况，1 天到 2 周 |

### 一个提醒

宠物要消耗积分喂食，所以**积分系统必须先有**。

如果你还没做积分，这份提示词里包含了积分表（4.5 的 C 部分），可以一起做。

如果积分已经做了，让 GPT 复用现有的 `user_points` 和 `point_log` 表，不要重复建。
