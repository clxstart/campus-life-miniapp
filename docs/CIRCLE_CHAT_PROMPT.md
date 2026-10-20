# 圈子群聊 + 实时通讯 · GPT 提示词

> 工程目录：`E:\_verify_completed\campus-life-miniapp`
> 目标：给圈子加群聊，用 WebSocket 做实时通讯
> 技术选型：Spring Boot WebSocket + 小程序 wx.connectSocket

---

=== 提示词开始 ===

## 一、你的任务

给校园小程序的圈子加**群聊**，用 WebSocket 做**真正的实时通讯**（不是轮询）。

要做的事：

1. 圈子里能聊天，所有圈成员看到同一条消息流
2. 消息实时推送，别人发的消息 1 秒内出现在屏幕上
3. 支持文字、图片
4. 显示未读数
5. 在线状态（谁在群里）

**明确不做：**

- ❌ 一对一私信改造（现有私信保持不动）
- ❌ 语音、视频
- ❌ 消息撤回
- ❌ 群成员管理（踢人/禁言）先不做

## 二、项目背景

- 小程序：微信原生（WXML/WXSS/JS/JSON），AppID `touristappid`，28 个页面
- 后端：Spring Boot 3.2.5 + MyBatis-Plus 3.5.6 + MySQL 8，包名 `com.campuslife`
- 管理端：Vue 3 + Vite + Tailwind
- 数据库：`campus_life`，账号 `root`
- 统一返回 `{code, msg, data}`
- **项目目前完全没有 WebSocket 依赖**，需要从零引入

## 三、动手前必须先读这些文件

1. `backend/pom.xml` —— 加依赖
2. `backend/.../config/AdminInterceptor.java` —— 鉴权模式参考（WebSocket 鉴权要类似）
3. `backend/.../service/AdminAuthService.java` —— JWT 签发与校验
4. `backend/.../controller/ChatController.java` —— 现有私信接口
5. `backend/.../service/ChatService.java` —— 现有私信逻辑
6. `pages/chat/chat.js` + `.wxml` —— 现有私信页面（样式参考）
7. `pages/circle-detail/circle-detail.js` —— 圈子详情页（群聊入口要加在这里）
8. `utils/api.js` —— 接口封装
9. `app.js` —— 全局状态，WebSocket 连接要在这里管理
10. `backend/src/main/resources/db/` —— 迁移脚本风格

## 四、现有结构

### 4.1 圈子表 `circle`

```sql
id, name, slug, category, description, cover, theme,
member_count, post_count, hot, sort, status, created_at, school_id
```

### 4.2 圈子成员表 `circle_member`

```sql
id, circle_id, user_id, role, joined_at
```

`role` 目前是 `member`，可以有 `owner` / `admin`。

### 4.3 私信表 `chat_message`（**注意：这是一对一的，不能直接复用**）

```sql
id, sender_id, receiver_id, type, content, image,
client_id, read_at, created_at
```

**它是 `sender_id → receiver_id` 的结构，没有圈子字段。群聊要新建表。**

## 五、要做什么

### 5.1 引入 WebSocket

在 `backend/pom.xml` 加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

**注意：不要用 STOMP。** STOMP 是给浏览器端用的协议，微信小程序原生不支持，要自己实现 STOMP 帧解析，很麻烦。

**用原生 WebSocket（`@ServerEndpoint` 或 Spring 的 `WebSocketHandler`）。**

### 5.2 数据库迁移

新建 `backend/src/main/resources/db/module-circle-chat.sql`，**可重复执行**。

**A. 群消息表**

```sql
CREATE TABLE circle_message (
    id         bigint PRIMARY KEY AUTO_INCREMENT,
    circle_id  bigint       NOT NULL,
    sender_id  bigint       NOT NULL,
    type       varchar(8)   DEFAULT 'text',   -- text / image
    content    varchar(1000) DEFAULT '',
    image      varchar(255),
    created_at datetime     DEFAULT CURRENT_TIMESTAMP,
    KEY idx_circle_time (circle_id, id DESC),
    KEY idx_sender (sender_id)
);
```

**为什么用 `id DESC` 排序而不是 `created_at`**：同一秒可能有多条消息，用自增 ID 保证顺序稳定。

**B. 群成员已读位置表**

```sql
CREATE TABLE circle_read (
    circle_id    bigint NOT NULL,
    user_id      bigint NOT NULL,
    last_read_id bigint DEFAULT 0,     -- 最后读到的消息 ID
    updated_at   datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (circle_id, user_id)
);
```

**未读数 = 该圈子 ID 大于 `last_read_id` 的消息条数。**

**C. 圈子表加字段**

```sql
last_message_at datetime NULL,     -- 最后一条消息时间（列表排序用）
last_message    varchar(100) NULL  -- 最后一条消息摘要（列表展示）
```

### 5.3 WebSocket 服务端

#### 5.3.1 连接地址与鉴权

**端点：** `ws://127.0.0.1:8080/ws/circle?token=xxx&circleId=123`

**鉴权方式：** token 放在 URL 参数里（小程序 `wx.connectSocket` 不能自定义 header）。

**连接时必须校验：**

1. token 有效（复用现有 JWT 校验逻辑）
2. 用户是这个圈子的成员（查 `circle_member`）
3. 圈子存在且 `status=1`

**任何一条不满足，直接关闭连接并返回错误码。**

#### 5.3.2 连接管理器

```java
@Component
public class CircleWebSocketManager {

    // circleId -> (userId -> session)
    private final Map<Long, Map<Long, WebSocketSession>> rooms = new ConcurrentHashMap<>();

    public void join(Long circleId, Long userId, WebSocketSession session) {
        rooms.computeIfAbsent(circleId, k -> new ConcurrentHashMap<>())
             .put(userId, session);
    }

    public void leave(Long circleId, Long userId) {
        Map<Long, WebSocketSession> room = rooms.get(circleId);
        if (room != null) {
            room.remove(userId);
            if (room.isEmpty()) rooms.remove(circleId);
        }
    }

    // 广播给房间内所有人
    public void broadcast(Long circleId, String message) {
        Map<Long, WebSocketSession> room = rooms.get(circleId);
        if (room == null) return;
        room.values().forEach(session -> {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.warn("推送失败", e);
                }
            }
        });
    }

    // 在线人数
    public int onlineCount(Long circleId) {
        Map<Long, WebSocketSession> room = rooms.get(circleId);
        return room == null ? 0 : room.size();
    }
}
```

**注意：一个用户可能在多个圈子里，`userId` 在同一房间内是唯一的，但跨房间会重复。上面的结构是对的。**

#### 5.3.3 消息推送格式

**服务端推给客户端的 JSON：**

```json
{
  "type": "message",
  "data": {
    "id": 1001,
    "circleId": 3,
    "senderId": 5,
    "senderName": "张三",
    "senderAvatar": "/assets/social/avatar1.webp",
    "messageType": "text",
    "content": "晚上打球吗",
    "image": null,
    "createdAt": "2026-10-19 22:15:30"
  }
}
```

**其他消息类型：**

```json
{"type": "online", "data": {"count": 5}}
{"type": "join", "data": {"userId": 5, "userName": "张三"}}
{"type": "leave", "data": {"userId": 5, "userName": "张三"}}
{"type": "error", "data": {"msg": "你不是这个圈子的成员"}}
```

#### 5.3.4 心跳

**必须做心跳，否则连接会被中间设备断开。**

- 服务端每 30 秒发一次 `{"type":"ping"}`
- 客户端收到回 `{"type":"pong"}`
- 服务端 90 秒没收到 pong 就关闭连接

**小程序端也要主动发心跳**（微信小程序后台 5 分钟没数据会断连）。

### 5.4 发送消息的流程

**不能直接走 WebSocket 发消息**，要走 HTTP 接口：

```
1. 小程序调 POST /api/circle-messages 发消息
2. 后端校验（成员身份 + 内容审核 + 敏感词）
3. 存数据库
4. 通过 WebSocket 广播给房间内所有人（包括发送者自己）
5. 返回成功给发送方
```

**为什么不直接走 WebSocket 发：**

- HTTP 有明确的成功/失败返回
- 内容审核是同步的，WebSocket 里做审核会阻塞
- 断线时消息不会丢（可以重发）

**消息去重：** 客户端发送时带 `clientId`（UUID），后端存下来，重复的 `clientId` 直接返回成功不重复入库。

```sql
-- circle_message 加字段
client_id varchar(64) DEFAULT ''
-- 加唯一索引
UNIQUE KEY uk_client (sender_id, client_id)
```

### 5.5 后端接口

#### 5.5.1 群聊接口（`/api/circle-messages`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/circle-messages` | 拉历史消息（`circleId`, `beforeId`, `limit`） |
| POST | `/api/circle-messages` | 发消息（`circleId`, `type`, `content`, `image`, `clientId`） |
| POST | `/api/circle-messages/read` | 标记已读（`circleId`, `lastReadId`） |
| GET | `/api/circle-messages/unread` | 未读数（返回所有圈子的未读数） |

**历史消息分页用 `beforeId`（游标），不要用 offset。**

原因：群聊消息一直在增加，用 offset 翻页会出现重复或漏消息。

```sql
SELECT * FROM circle_message
WHERE circle_id = #{circleId}
  AND (#{beforeId} IS NULL OR id < #{beforeId})
ORDER BY id DESC
LIMIT #{limit}
```

#### 5.5.2 圈子列表加未读

`GET /api/circles` 返回时，每个圈子带上 `unreadCount` 和 `lastMessage`。

### 5.6 小程序端

#### 5.6.1 WebSocket 连接管理（全局）

在 `app.js` 里统一管理，不要每个页面各连一个：

```js
App({
  globalData: {
    socket: null,
    socketCircleId: null,
    socketListeners: [],   // 订阅回调
  },

  // 连接群聊
  connectCircleChat(circleId) {
    const token = wx.getStorageSync('token')
    // 已经连了同一个圈子，直接复用
    if (this.globalData.socket && this.globalData.socketCircleId === circleId) {
      return Promise.resolve()
    }
    this.closeCircleChat()

    return new Promise((resolve, reject) => {
      const socket = wx.connectSocket({
        url: `ws://127.0.0.1:8080/ws/circle?token=${token}&circleId=${circleId}`
      })
      this.globalData.socket = socket
      this.globalData.socketCircleId = circleId

      socket.onOpen(() => {
        this.startHeartbeat()
        resolve()
      })
      socket.onMessage((res) => {
        const msg = JSON.parse(res.data)
        this.globalData.socketListeners.forEach(fn => fn(msg))
      })
      socket.onError((err) => {
        this.stopHeartbeat()
        reject(err)
      })
      socket.onClose(() => {
        this.stopHeartbeat()
        this.globalData.socket = null
        this.globalData.socketCircleId = null
      })
    })
  },

  closeCircleChat() {
    this.stopHeartbeat()
    if (this.globalData.socket) {
      try { this.globalData.socket.close() } catch (e) {}
    }
    this.globalData.socket = null
    this.globalData.socketCircleId = null
  },

  startHeartbeat() {
    this.stopHeartbeat()
    this._heartbeat = setInterval(() => {
      const s = this.globalData.socket
      if (s) { try { s.send({ data: JSON.stringify({ type: 'pong' }) }) } catch (e) {} }
    }, 25000)
  },

  stopHeartbeat() {
    if (this._heartbeat) { clearInterval(this._heartbeat); this._heartbeat = null }
  },

  // 订阅消息
  onCircleMessage(fn) {
    this.globalData.socketListeners.push(fn)
    return () => {
      const i = this.globalData.socketListeners.indexOf(fn)
      if (i >= 0) this.globalData.socketListeners.splice(i, 1)
    }
  }
})
```

**注意：`wx.connectSocket` 在真机上必须用 `wss://`，本地开发用 `ws://`。** 上线前要换成 `wss` 并配好证书。

#### 5.6.2 群聊页面 `pages/circle-chat/circle-chat`

**新页面**，从圈子详情页进。

**路由注册到 `app.json`：** `pages/circle-chat/circle-chat`

**页面结构：**

```
┌─────────────────────────────┐
│ ← 王者荣耀        5 人在线   │  ← 自定义导航栏
├─────────────────────────────┤
│                             │
│  张三  22:15                │
│  晚上打球吗                  │
│                             │
│              我  22:16      │
│              来            │
│                             │
│  李四  22:17                │
│  [图片]                     │
│                             │
├─────────────────────────────┤
│ [＋] 说点什么...      [发送] │
└─────────────────────────────┘
```

**关键实现点：**

1. **进页面时连 WebSocket**，`onLoad` 里连，`onUnload` 里断
2. **先拉历史消息**（最近 30 条），再连 WebSocket
3. **收到消息追加到列表底部**，自动滚到底
4. **自己发的消息**：先本地插入（乐观更新），收到服务端广播后用真实 ID 替换
5. **消息按 id 去重**（WebSocket 可能重复推送）
6. **上拉加载更多**：用 `beforeId` 拉更早的消息
7. **离开页面时调 `/read` 标记已读**

**滚动到底部的写法：**

```js
scrollToBottom() {
  this.setData({ scrollIntoView: 'msg-bottom' })
}
```
WXML 里最后放一个 `<view id="msg-bottom"></view>`。

#### 5.6.3 圈子详情页加入口

`pages/circle-detail/circle-detail` 顶部或底部加一个按钮：

```html
<view class="chat-entry" bindtap="openChat">
  <text>群聊</text>
  <view wx:if="{{unreadCount > 0}}" class="badge">{{unreadCount}}</view>
</view>
```

#### 5.6.4 圈子列表显示未读

`pages/discover/discover` 里每个圈子卡片右上角显示未读红点。

### 5.7 内容审核接入

**群聊消息必须过审核**，否则会变成法外之地。

发消息时走和发帖一样的审核流程（本地词库 + 微信 msgSecCheck），审核不通过直接拒绝。

**私信如果已经有审核，群聊也要一致。**

## 六、并发与可靠性

### 6.1 消息顺序

同一秒内的多条消息，用自增 `id` 保证顺序。客户端按 `id` 排序，不要按 `created_at`。

### 6.2 断线重连

小程序端要自动重连：

```js
socket.onClose(() => {
  // 3 秒后重连，最多重试 5 次
  this.retryCount = (this.retryCount || 0) + 1
  if (this.retryCount <= 5) {
    setTimeout(() => this.connectCircleChat(circleId), 3000)
  }
})
```

**重连后要拉一次增量消息**（`afterId = 本地最后一条消息的 id`），把断线期间的消息补回来。

### 6.3 服务端资源

- 每个连接占一个线程（如果用 `@ServerEndpoint`），人多会爆
- 建议用 Spring 的 `WebSocketHandler` + 异步发送
- 单机支持几百个连接没问题，超过再考虑集群

## 七、验收标准

| # | 检查项 | 通过标准 |
|---|---|---|
| 1 | 后端能编译 | Maven BUILD SUCCESS |
| 2 | 迁移脚本可重复执行 | 连跑两次不报错 |
| 3 | 能连上 WebSocket | 小程序进群聊页，连接成功 |
| 4 | **非成员连不上** | 非圈成员带 token 连接，被拒绝 |
| 5 | **实时性** | A 发消息，B 的屏幕 1 秒内出现 |
| 6 | 历史消息 | 进页面能看到之前的 30 条 |
| 7 | 上拉加载 | 能翻到更早的消息，不重复不丢 |
| 8 | 未读数 | 不在群里时收到消息，圈子列表显示红点 |
| 9 | 已读 | 进群聊后未读清零 |
| 10 | 图片消息 | 能发图片并显示 |
| 11 | 内容审核 | 发违规词被拒绝 |
| 12 | 断线重连 | 断网 10 秒后自动重连，补回期间消息 |
| 13 | 心跳 | 连接 5 分钟不断 |
| 14 | 在线人数 | 显示正确 |

## 八、不要做的事

- **不要用 STOMP**（小程序不支持，要手写帧解析）
- 不要直接通过 WebSocket 发消息（走 HTTP，WebSocket 只做推送）
- 不要用 offset 分页（用 `beforeId` 游标）
- 不要在 WebSocket 里做内容审核（会阻塞，走 HTTP 时做）
- 不要每个页面各建一个连接（全局一个，`app.js` 管理）
- 不要用 `created_at` 排序（用 `id`）
- 不要忘记心跳（不做的话 5 分钟必断）
- 不要忘记 `wss`（真机上 `ws://` 会被拒绝）
- 不要改现有私信逻辑（群聊是新增的）

## 九、交付顺序

1. 加 WebSocket 依赖 + 建表 + 连接管理器（地基）
2. 做连接鉴权（非成员拒绝）
3. 做 HTTP 发消息接口 + 广播
4. 做小程序群聊页（先能收发）
5. 做历史消息分页 + 未读数
6. 做断线重连 + 心跳
7. 接内容审核
8. 圈子列表加未读红点

**第 4 步做完就能两个人对着屏幕测实时性了，先验证这个再往下做。**

=== 提示词结束 ===

---

## 附：给你的说明

### 为什么强调不用 STOMP

Spring 官方文档推荐 STOMP，但那套是给浏览器用的（有 `sockjs` 和 `stomp.js`）。

微信小程序的原生 `wx.connectSocket` **只支持裸 WebSocket**，不支持 STOMP 帧。要硬上就得自己实现 STOMP 协议解析，纯属给自己找麻烦。

### 为什么消息要走 HTTP 而不是 WebSocket

很多人第一反应是「WebSocket 连上了就直接用它发消息」。

但这样有个致命问题：**没有明确的成功/失败返回**。

发出去之后，你不知道是成功了、还是审核没过、还是服务端挂了。走 HTTP 就清楚：200 就是成了，4001 就是违规，500 就是服务端问题。

WebSocket 只负责「推给别人」这一件事。

### 上线前必须换 wss

本地开发用 `ws://127.0.0.1:8080`，能连。

但**真机和正式环境必须用 `wss://你的域名/ws/circle`**，而且要配好 HTTPS 证书。微信对 `ws://` 在真机上是直接拒绝的。

这条不做，本地测得好好的，一上真机全挂。

### 工作量

| 部分 | 时间 |
|---|---|
| WebSocket 依赖 + 建表 + 连接管理 | 1.5 天 |
| 鉴权 + 发消息接口 + 广播 | 1.5 天 |
| 小程序群聊页 | 2 天 |
| 历史分页 + 未读数 | 1 天 |
| 断线重连 + 心跳 | 1 天 |
| 内容审核接入 | 0.5 天 |
| **合计** | **约 7.5 天** |

### 一个规模提醒

单机 WebSocket 撑几百个连接没问题。

但如果以后有几千人同时在线，需要做集群，那时候要解决「A 连在服务器 1、B 连在服务器 2，消息怎么互推」——需要引入 Redis 发布订阅。现在不用考虑。
