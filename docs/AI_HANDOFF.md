# 给 AI 的项目说明（请先读这里）

> 这份文件是专门为把本项目交给 AI 助手分析而准备的。项目原有文档见 `README.md`、`README_BACKEND.md`、`docs/`。

## 一、这是什么

一个**原生微信小程序**（校园生活 App）+ 一个 **Spring Boot 后端**，前后端分离。

- 前端：原生小程序（WXML/WXSS/JS/JSON），**不是** uni-app / Taro / 任何框架
- 后端：Java 17 · Spring Boot 3.2 · MyBatis-Plus · MySQL 8 · Lombok
- 前端 AppID 为测试号 `touristappid`，可直接在微信开发者工具中编译运行

## 二、关键约定（读代码前务必知道）

### 1. 接口适配层是唯一的出入口

**所有页面只通过 `utils/api.js` 取数据**，绝不直接调用 `wx.request`，也不直接读 `utils/mock-data.js`（`timetable` / `calendar` / `profile` 例外，见下文）。

```
页面 JS  →  utils/api.js  →  ① wx.request 打后端
                          →  ② 失败时退回 utils/mock-data.js（离线兜底）
```

**推论**：要接真实后端，只需改 `utils/api.js` 内部实现，21 个页面一行都不用动。

### 2. 后端统一响应格式

```json
{ "code": 0, "msg": "...", "data": { ... } }
```

前端判断：`res.statusCode` 在 200–299 **且** `body.code === 0` 才算成功（见 `utils/api.js` 的 `request()`）。

### 3. 「读兜底、写不兜底」原则 —— 重要

`utils/api.js` 里区分了两类接口：

```js
// 读接口：网络失败 → 静默退回 mock，保证无后端也能演示
getForumPosts().catch(() => wait(mockForum(...)))

// 写接口：只有【网络失败】才兜底；【服务端业务错误】必须抛给用户看
createFoodOrder().catch(error => {
  if (error.server) throw error      // ← 例如"未达起送价""重复投票"，不能吞掉
  return wait(mock.makeFoodOrder(payload))
})
```

后端错误被标记为 `error.server = true`（见 `request()`）。**改代码时不要破坏这个区分**，否则用户会以为下单成功了，其实服务端拒单了。

### 4. 各模块离线数据在 `utils/mock-data.js`

该文件 214 行，导出：`discoveryPosts, conversations, notifications, profile, termInfo, timetableCourses, calendarEvents, idolContestants`，另在文件后半段挂了 `foodShops / foodMenus / goods / jobs / activities` 等外卖、二手、兼职、活动数据。

## 三、目录结构

```
campus-life-miniapp/
├── app.js / app.json / app.wxss     小程序入口与全局配置
├── project.config.json              开发者工具配置（appid: touristappid）
├── sitemap.json
├── api.js                           ⚠️ 根目录的旧版残留，见第四节 P3-2
├── index.js                         ⚠️ 同上，死文件
├── pages/                           21 个页面，每个含 .js/.wxml/.wxss/.json
├── components/
│   ├── ui-popup/                    通用弹层（sheet / modal / toast）
│   └── bottom-nav/                  底部导航（5 个页面复用，带未读角标）
├── utils/
│   ├── api.js                       ★ 接口适配层（核心）
│   ├── mock-data.js                 ★ 离线假数据（核心）
│   ├── food.js                      外卖费用/满减计算
│   ├── navigation.js                胶囊按钮/状态栏高度计算
│   ├── term.js                      学期周次计算
│   ├── format-time.js               时间格式化
│   ├── ui-popup-helper.js           弹层调用封装
│   └── food.wxss
├── assets/                          37 个本地图片（webp/svg），不依赖网络
├── docs/                            项目文档（不打包进小程序）
└── backend/                         Spring Boot 后端
    ├── pom.xml
    ├── test-goods2.json             接口测试用的请求样本
    ├── test-goods3.json
    └── src/main/
        ├── java/com/campuslife/
        │   ├── CampusLifeApplication.java
        │   ├── config/              GlobalExceptionHandler / MybatisPlusConfig / WebConfig(CORS)
        │   ├── controller/          ★ Forum / User / Idol / Biz 共 4 个，32 个端点
        │   ├── service/             Forum / User / Idol / Biz 共 4 个
        │   ├── mapper/              17 个 MyBatis-Plus Mapper
        │   ├── entity/              16 个实体
        │   └── dto/                 11 个 DTO（含 ApiResponse 统一响应）
        └── resources/
            ├── application.yml      ★ 数据库连接配置需自行修改
            └── db/
                ├── schema.sql                         21 张表 + 测试数据
                ├── module-food.sql                    外卖模块
                └── module-market-jobs-activities.sql  二手/兼职/活动模块
```

## 四、已知问题（分析时请一并考虑）

这些是**已知的、经代码核对确认的**问题，不是猜测：

| 编号 | 位置 | 问题 |
|---|---|---|
| P0-1 | `pages/index/index.js:78-111` | `openFeature()` 是 if 白名单，末尾无兜底分支，未登记的入口会静默降级为一句 toast，且不报错 |
| P0-2 | `pages/index/index.js:68-72`、`pages/discover/discover.js` | **搜索功能整体缺失**：后端无 `/api/search` 端点，前端无搜索结果页，只弹 toast |
| P1 | `pages/index/index.wxml` 多处 | 9 个入口点击只弹「功能开发中」：开学优惠、学习好物、奶茶优惠、闲置台灯、联想广告、通知铃铛、换一批等 |
| P2-1 | `pages/chat/chat.js:13-19` | 聊天消息**硬编码在 data 中**（5 条），发送只进内存，退出即失 |
| P2-2/3 | `utils/api.js:111-112` | `getConversations()` / `getNotifications()` **连 `request()` 都没调**，直接 `wait(mock.xxx)` |
| P2-5/6 | `pages/timetable/timetable.js:39`、`pages/calendar/calendar.js:30` | 课表与日历直接 require `mock-data`，**绕过 api.js**，无后端支持 |
| P3-1 | `utils/api.js:81` vs `backend/.../ForumController.java:11` | `followUser` 前端发 body，后端收 `@RequestParam`；后端写了兼容逻辑但**未实测** |
| P3-2 | 根目录 `index.js`（122 行） | **死文件**：`app.json` 未注册，永不加载；内容比 `pages/index/index.js` 更旧 |
| P3-4 | `utils/api.js` 全局 | 后端未启动时代码静默降级到 mock，**UI 未提示用户当前是演示数据** |
| P3-5 | `README_BACKEND.md` | 文档写"五张表"，实际 `schema.sql` 已有 21 张表，**文档未同步** |

### ⚠️ 关于 P2-2/3 的补充

`getConversations()` 和 `getNotifications()` 的实现是 `return wait(mock.conversations)` —— 意味着**即使后端跑起来，聊天和通知也依然是假数据**，因为请求根本没发出去。这是比其他 P2 项更彻底的"假"。

## 五、如何运行

### 前端
1. 微信开发者工具 → 导入项目 → 选择本目录
2. AppID 用测试号即可（`project.config.json` 里已是 `touristappid`）
3. 点「编译」

### 后端
1. 启动 MySQL 8.0，依次执行 `backend/src/main/resources/db/schema.sql`、`module-food.sql`、`module-market-jobs-activities.sql`
2. 修改 `backend/src/main/resources/application.yml` 的数据库账号密码
3. `cd backend && mvn spring-boot:run`，服务跑在 `http://127.0.0.1:8080/api`
4. 前端 `utils/api.js` 顶部的 `BASE_URL` 已指向该地址

> 后端未启动时，前端**所有功能依然可用**，只是数据来自本地 mock。

## 六、请你（AI）注意

1. **以代码实际行为为准**，不要只信 README —— 本项目文档与实际代码已有若干不同步（见 P3-5）。
2. 分析「哪里没实现」时，请区分三类：①纯占位 toast ②后端无接口、页面用假数据 ③后端有接口但前端没接。三者的修复成本完全不同。
3. 本项目**不存在「整页未开发」**的功能，21 个页面全部有真实实现。
4. 修改 `utils/api.js` 时请保持第四节 3 的「读兜底、写不兜底」原则。
