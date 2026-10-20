# 后端接口文档

统一响应包装：`{ "code": 0, "msg": "ok", "data": ... }`，`code !== 0` 或 HTTP 非 2xx 视为失败。
前端统一走 `utils/api.js`；读接口保留离线演示；新增搜索/消息/课表/校历读取仅在网络失败时兜底，服务端错误显示错误态。带离线兜底的写接口必须保留 `error.server`，不得把服务端拒绝当作成功；原投票、资料编辑仍需后端。全局横条提示演示状态，本机演示写入不会自动同步。

## 发现页

### 活动轮播位

`GET /api/banners?slot=discover`

运营在 `banner` 表加一行即可上线活动，无需发版。`link_type=page` 时 `link_value` 为小程序内部页面路径；`none` 时前端点击弹「即将开始」提示。

```json
[
  {
    "id": 1,
    "slot": "discover",
    "eyebrow": "CAMPUS IDOL",
    "title": "校草校花评选",
    "subtitle": "报名即将开始 · 上传照片赢校园人气王",
    "badge": "即将开始",
    "theme": "pink",
    "linkType": "none",
    "linkValue": null,
    "sort": 1,
    "status": 1
  }
]
```

字段：`theme` 控制卡片配色（blue/green/pink）；`badge` 是右侧圆徽文字；`sort` 越小越靠前；`status=0` 下线。

### 热门话题

`GET /api/topics/hot`

按帖子标签聚合：`count = SUM(like_count) + SUM(comment_count)`（热度），`posts` 为该标签帖子数。前端取前 3 个渲染话题卡，封面图按标签本地映射。

```json
[
  { "tag": "社团活动", "posts": 2, "count": 599 }
]
```

### 热度流（发现信息流）

`GET /api/posts?scope=hot&userId=1&page=1&size=50`

只收录被点赞或浏览过的帖子，按 `like_count` → `view_count` → `comment_count` 降序。帖子表 `view_count` 由浏览行为驱动。

### 浏览上报

`POST /api/posts/{id}/view`

详情页每次打开调用一次，`view_count + 1`。无请求体，无返回数据（`data: null`）。

## 论坛/社区页

### 帖子流

`GET /api/posts?tag=&scope=&userId=1&page=1&size=10`

- `scope=followed`：只看关注的人；`scope=hot`：热度流；空：按时间倒序
- `tag`：按标签精确/模糊匹配（社区页 tab 用）
- `userId`：用于计算 `liked`/`followed` 状态

返回 `{ list: [...], total: 10 }`，`list` 每项：

```json
{
  "id": 10,
  "authorId": 2,
  "authorNickname": "周予安",
  "authorAvatar": null,
  "title": "学习资料互助共享",
  "content": "整理了一份考试周资料清单…",
  "image": null,
  "tag": "学习",
  "location": "线上",
  "likeCount": 39,
  "commentCount": 3,
  "viewCount": 389,
  "liked": false,
  "followed": true,
  "createdAt": "2026-10-09 10:34"
}
```

### 单帖详情

`GET /api/posts/{id}?userId=1`

同上字段；帖子不存在或已删除时 `data: null`。前端失败时退化为在列表里查找。

### 发布帖子

`POST /api/posts`

```json
{ "userId": 1, "title": "标题", "content": "正文", "image": "url或空", "tag": "校园日常", "location": "线上" }
```

校验：`userId`/`title`/`content` 必填，title≤64 字，content 非空，image≤255，tag≤16，location≤64。返回创建后的 post 实体（前端只取 `id`）。

### 点赞（切换）

`POST /api/posts/{id}/like`，body `{ "userId": 1 }` → `{ "liked": true }`，并同步增减 `like_count`。

### 评论

- `GET /api/posts/{id}/comments` → 数组，按时间升序：

```json
{ "id": 18, "userId": 1, "content": "求清单！", "createdAt": "2026-10-09 14:45", "authorNickname": "林知夏", "authorAvatar": null }
```

- `POST /api/posts/{id}/comments`，body `{ "userId": 1, "content": "…" }`（content≤255），`comment_count` 自动 +1。

### 关注（切换）

`POST /api/users/{id}/follow`，body `{ "userId": 1 }`（userId=关注者）→ `{ "followed": true }`。

## 校草校花评选（pages/activity-idol + pages/idol-join）

发现页 banner `link_value=/pages/activity-idol/activity-idol` 直接进入。

### 活动概览

`GET /api/idol/activity` →

```json
{ "title": "校园人气王评选", "phase": "voting", "voteEndAt": "2026-10-31 23:59:59", "contestants": 8, "votes": 0 }
```

### 选手列表（按票数排序）

`GET /api/idol/contestants?gender=2&userId=1`

`gender`：1=校草组 2=校花组；`voted` 为当前用户是否已投过。每项：

```json
{ "id": 1, "name": "苏晚", "gender": 2, "college": "新闻与传播学院", "photo": "url", "slogan": "把春天穿在身上", "voteCount": 862, "voted": false, "createdAt": "10-09 15:20" }
```

### 投票

`POST /api/idol/contestants/{id}/vote`，body `{ "userId": 1 }` → `{ "voted": true, "voteCount": 532 }`

规则：每人为同一位选手限 1 票（`idol_vote` 唯一键兜底），重复投返回 `code=500, msg=每人为同一位选手只能投一票`。`idol_contestant.vote_count` 事务内 +1。

### 报名参选

`POST /api/idol/contestants`

```json
{ "userId": 1, "name": "展示昵称", "gender": 2, "college": "新闻与传播学院", "photo": "照片URL", "slogan": "拉票宣言" }
```

创建后直接进入展示队列（`status=1`）。表：`idol_contestant`（选手，含 `vote_count`/`status`）、`idol_vote`（投票记录，唯一键 `contestant_id+user_id` 防重复）。

## 「我的」页用户域（pages/profile + 帖子详情收藏）

### 个人资料

- `GET /api/users/{id}/profile` → `{ id, nickname, school, avatar, posts, following, followers }`（统计为真实计数：发帖数、关注/粉丝来自 `follow` 表）
- `POST /api/users/{id}/profile`，body `{ "nickname": "新昵称" }` → 保存昵称

### 收藏（帖子详情「☆ 收藏」按钮）

- `POST /api/users/{userId}/favorites`，body `{ "postId": 9 }` → toggle，返回 `{ "favored": true|false }`
- `GET /api/users/{id}/favorites` → 收藏的帖子列表 `[{ id, title, createdAt }]`，按收藏时间倒序
- 帖子详情 `GET /api/posts/{id}` 返回 `favored` 字段用于按钮初始态

### 浏览记录

- 详情页打开时 `POST /api/posts/{id}/view?userId=1`：`view_count +1` 并写一条 `post_view` 历史
- `GET /api/users/{id}/views` → 最近浏览（同帖去重）`[{ id, title, createdAt }]`

### 地址管理

- `GET /api/users/{id}/addresses` → `[{ id, contact, phone, detail }]`
- `POST /api/users/{id}/addresses`，body `{ "detail": "学生公寓 6 号楼 312", "contact": "林知夏" }`

### 优惠与权益

- `GET /api/users/{id}/coupons` → `[{ id, title, amount, expireAt, status }]`

### 帮助与反馈

- `POST /api/feedback`，body `{ "userId": 1, "content": "…" }` → `{ "received": true }`

## 帖子搜索（首页与发现页共用）

`GET /api/search?keyword=台灯&page=1&size=10&userId=1`

- `keyword` 必填，先 trim；缺失、空串/纯空白、超过 64 个 UTF-16 单元返回 HTTP 400、`code=400`，不截断关键词。前端也按 JS length 校验 64，搜索输入框上限 64。
- `page` 默认 1，下限 1；`size` 默认 10，夹取为 1–50；非法数字返回 HTTP 400。
- `userId` 默认 1，按原帖子流风格夹取为至少 1，用于 liked/followed。
- 只查询 `status=1` 帖子，标题或正文 `LIKE %keyword%`，OR 放在括号内，按 created_at、id 降序稳定分页。MyBatis-Plus `.like()` 绑定参数，不把关键词拼入 SQL。
- `%` 和 `_` 沿用 SQL LIKE 通配符，反斜线转义；离线过滤分别匹配标题/正文，不匹配标签，包含本机演示发布的帖子。数据库 utf8mb4_unicode_ci 与 JS 的大小写匹配在部分语言/重音字符上可能不同。
- 返回 **与 `/api/posts` 相同的 DTO 和 `{ list, total }`**；在线不额外混入本机演示帖子。网络失败返回对应本地分页并增加 `offline: true`；服务端错误不伪装为空结果。

请求示例：

```bash
curl --get 'http://127.0.0.1:8080/api/search' --data-urlencode 'keyword=台灯' --data 'page=1&size=10&userId=1'
```

返回示例（具体内容与总数取决于数据库）：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "list": [{
      "id": 7, "authorId": 3, "authorNickname": "陈一川", "authorAvatar": null,
      "title": "出一台九成新台灯", "content": "亮度可调，宿舍自用半年，期末价出。",
      "image": "/assets/home/recommend-lamp.jpg", "tag": "二手闲置", "location": "南区宿舍",
      "likeCount": 63, "commentCount": 2, "viewCount": 0, "liked": false,
      "followed": false, "favored": null, "createdAt": "2026-10-14 10:00"
    }],
    "total": 1
  }
}
```

空白关键词返回：

```json
{"code":400,"msg":"请输入搜索关键词","data":null}
```

超长关键词返回：

```json
{"code":400,"msg":"关键词不能超过64字","data":null}
```

## 消息、通知和聊天

所有请求/响应仍使用 `{ code, msg, data }`。接口 `peerId` 是**对方用户 ID**，不再混用旧 mock 的会话序号。

| 接口 | 参数 | data / 行为 |
|---|---|---|
| `GET /api/conversations` | `userId=1` | `[{ id:2, name, avatar, avatarText, color, preview, createdAt, unread, muted, online:false }]`，按最新消息 ID 倒序；不伪造在线状态 |
| `GET /api/conversations/{peerId}/messages` | `userId=1&size=30&beforeId=` | `{ list:[{id,senderId,receiverId,mine,type,content,image,createdAt}], hasMore, muted }`；size 1–50，beforeId 正整数，返回升序列表 |
| `POST /api/conversations/{peerId}/messages` | body `{ userId:1, content:"你好", clientId:"唯一请求标识" }` | 新消息对象；content trim 后非空、≤1000，clientId 必填≤64，用户必须存在且不是自己；同一发送者相同 clientId 幂等 |
| `POST /api/conversations/{peerId}/read` | body `{ userId:1, upToId:5 }` | `null`，只标记收到的、ID≤游标的消息，避免把并发新消息误标已读 |
| `POST /api/conversations/{peerId}/clear` | body `{ userId:1 }` | `null`，写清空游标，只影响当前用户视图 |
| `POST /api/conversations/{peerId}/mute` | query `userId=1&muted=true` | `null`，持久化免打扰，未读消息可见，但免打扰会话不计入总角标 |
| `GET /api/notifications` | `userId=1` | 最近100条 `[{ id,title,content,category,tone,postId,createdAt,readAt,unread }]` |
| `POST /api/messages/read-all` | body `{ userId:1 }` | `null`，事务内标记聊天与通知已读 |

会话/消息/通知读取仅在网络失败时本地演示；服务端参数/业务错误保留。聊天页每 5 秒轮询，可加载更早记录。离线发消息写入 `chat_messages_<userId>_<peerId>`，会话、已读、清空和免打扰也持久化；离线发送明确标记“仅本机”，不会自动重发。清空/免打扰在演示视图下只修改本机数据，避免连接恢复后误操作真实记录。

交互通知来自论坛点赞、评论、关注以及收藏的实际事务，自己的行为不通知自己；系统通知可由后台写入 notification。消息页三个快捷入口按 category 过滤，有 postId 时可进入帖子。

## 课表与校历

- `GET /api/timetable?userId=1&week=7` → `{ termInfo, week, courses:[{id,name,room,day,period,theme,weekStart,weekEnd}] }`。
- week 不传时按服务器 Asia/Shanghai 日期计算教学周；传入时夹取 1–60。仅返回周次范围内课程，`day=0..6`、`period=0..4`。
- `GET /api/calendar?userId=1` → `{ termInfo, events:[{id,title,start,end,note}] }`。
- `termInfo` 为 `{id,termLabel,termStart,termEnd,midtermStart,midtermEnd,finalStart}`，日期为 `YYYY-MM-DD`。
- 学期按用户资料的 school 匹配最新 active=1 记录。学校未配置返回 HTTP 400，不冒用其他学校学期。
- 无后端时用现有 mock 演示；在线课程/事件来自数据库管理，不代表已经接入学校教务系统。

## 数据库

原 `schema.sql` **20 张表**；新增 `module-messages-academics.sql` **6 张表**：chat_message、chat_preference、notification、academic_term、course_schedule、calendar_event。新库共 **26 张表**。脚本均由用户手动执行，详见 README_BACKEND.md；新增脚本可重复执行，原 schema 部分旧种子不可重复执行。

## 待补齐的体系

- **登录鉴权**：目前 `userId` 写死为 1，无真实账号。需 `wx.login` → 后端换 token → 请求头携带。
- **图片上传**：发布时 `media` 仍是本地临时路径，需要文件上传接口换 URL。
- **点赞/关注防刷**：切换接口无鉴权，做校草校花投票前必须先解决登录。
