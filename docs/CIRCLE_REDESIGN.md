# 圈子化改造 + 功能补齐 · 设计方案

日期：2026-10-14
状态：**已确认，开始实施**

---

## 一、需求确认（来自阿星）

| # | 需求 | 决策 |
|---|---|---|
| 1 | 「发现」页改为「圈子」页 | 圈子**独立**——每个圈子有自己的帖子，不是标签筛选 |
| 2 | 圈子由谁创建 | **管理员统一建**，用户只能加入/退出 |
| 3 | 校草校花、校园歌手大赛 | 从发现页轮播**移走**，改为活动页里的**入口卡片** |
| 4 | 顶部轮播 | 活动 banner → **热门圈子轮询**，UI 风格保持 |
| 5 | 补齐 6 项未完成 | 见第五节 |
| 6 | Web 管理端 | 出方案分析（另见 WEB_ADMIN_PLAN.md） |

---

## 二、数据模型设计

### 2.1 新增表 `circle`

```sql
CREATE TABLE IF NOT EXISTS circle (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  name         VARCHAR(32)  NOT NULL,          -- 圈子名，如「王者荣耀」
  slug         VARCHAR(32)  NOT NULL,          -- 英文标识，路由用，如 'wzry'
  category     VARCHAR(16)  NOT NULL DEFAULT '兴趣',  -- 游戏/学习/运动/兴趣/生活
  description  VARCHAR(128) NOT NULL DEFAULT '',
  cover        VARCHAR(255) NOT NULL DEFAULT '',      -- 封面图
  theme        VARCHAR(16)  NOT NULL DEFAULT 'blue',  -- 轮播配色 blue/green/pink/amber/purple
  member_count INT          NOT NULL DEFAULT 0,       -- 冗余计数，避免每次 COUNT(*)
  post_count   INT          NOT NULL DEFAULT 0,       -- 冗余计数
  hot          TINYINT      NOT NULL DEFAULT 0,       -- 是否进热门轮播
  sort         INT          NOT NULL DEFAULT 0,       -- 越小越靠前
  status       TINYINT      NOT NULL DEFAULT 1,       -- 0=解散
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_circle_slug (slug),
  KEY idx_circle_hot (hot, sort),
  KEY idx_circle_category (category, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 2.2 新增表 `circle_member`（成员关系）

```sql
CREATE TABLE IF NOT EXISTS circle_member (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  circle_id  BIGINT NOT NULL,
  user_id    BIGINT NOT NULL,
  role       VARCHAR(16) NOT NULL DEFAULT 'member',  -- owner/member
  joined_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_circle_user (circle_id, user_id),
  KEY idx_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 2.3 改造 `post` 表

```sql
ALTER TABLE post ADD COLUMN circle_id BIGINT NULL AFTER tag;
ALTER TABLE post ADD INDEX idx_post_circle (circle_id, status, created_at);
```

**兼容策略**：`circle_id` 可为 NULL —— 老的帖子（无圈子）仍然能在「全校」流里看到，不破坏现有数据。

### 2.4 圈子与标签的关系

- **圈子**是发帖的**归属容器**（一个帖子属于一个圈子，或不属于任何圈子）
- **tag**保留为圈子内部的细分话题
- 现有 `tag` 数据（校园日常/学习/社团活动等）通过 SQL **迁移**到对应圈子

---

## 三、后端接口设计（新增 7 个）

```
GET    /api/circles                  圈子列表（?category=&hot=&page=&size=）
GET    /api/circles/{id}             圈子详情（含是否已加入）
GET    /api/circles/hot              热门圈子（轮播用，?limit=5）
POST   /api/circles/{id}/join        加入圈子
POST   /api/circles/{id}/leave       退出圈子
GET    /api/circles/{id}/posts       圈内帖子流（复用分页格式）
POST   /api/posts                    发帖（payload 新增可选 circle_id）
```

**计数维护**：加入/退出时在同一事务内更新 `circle.member_count`；发帖时 `post_count + 1`。

---

## 四、前端改造设计

### 4.1 发现页 → 圈子页

**保留**：整体视觉风格、导航条、底部导航（`active="discover"` 保持，避免动 `bottom-nav` 组件）

**变化**：
| 位置 | 改造前 | 改造后 |
|---|---|---|
| 页面标题 | 发现 | **圈子** |
| 搜索占位 | 搜索校园新鲜事 | 搜索圈子 |
| 顶部轮播 | 活动 banner（校草/歌手） | **热门圈子**（`/circles/hot`），点击进圈 |
| 热门话题 | 标签卡 | **圈子分类**（游戏/学习/运动/兴趣/生活） |
| 主内容 | 全校动态流 | **圈子卡片网格**（封面+名称+成员数+帖子数） |
| 分类 tab | 推荐/热门/社团/组队 | 全部/游戏/学习/运动/兴趣/生活 |

### 4.2 新增圈子详情页 `pages/circle-detail`

- 圈子头部（封面、名称、简介、成员数、加入按钮）
- 圈内帖子流（复用现有 feed 卡片样式）
- 圈子内发帖入口（自动带上 `circle_id`）

### 4.3 活动页加入两个入口卡片

`pages/activities` 顶部加两个固定卡片：
- 校草校花评选 → `/pages/activity-idol/activity-idol`
- 校园歌手大赛 → 详情页或报名

### 4.4 从发现页移除的部分

`discover.js` 里的默认 `heroBanners`（校草校花、校园歌手大赛）**删除**，改为圈子数据。这两个活动的入口**只在活动页**。

---

## 五、6 项功能补齐设计

### 5.1 登录鉴权

**方案**：微信登录 + JWT
```
前端：wx.login() → code
后端：POST /api/auth/login {code}
     → 调微信 code2session 换 openid
     → 查/建 user 记录
     → 签发 JWT（含 userId）
前端：存 token 到 storage，每次请求带 Authorization 头
```

**降级**：无微信 AppID 时（`touristappid`），后端提供 `POST /api/auth/dev-login` 直接发 token，保证可演示。

**改造点**：`utils/api.js` 的 `request()` 统一加 token 头；`CURRENT_USER_ID` 从写死 1 改为从 token 解析。

### 5.2 图片上传

```
后端：POST /api/upload  (multipart)
     → 存到 backend/uploads/ 目录
     → 返回 { url: "/uploads/xxx.jpg" }
前端：wx.uploadFile() 替换现有 wx.chooseMedia 的本地路径
```

**注意**：小程序真机访问需 HTTPS + 合法域名；开发者工具可用 `127.0.0.1`。

### 5.3 图片私信

- `chat_message.type` 已支持 `'image'`、`image` 字段已存在
- 前端 chat 页加「+」按钮 → `wx.chooseMedia` → 上传 → 发 `type='image'` 消息
- 消息气泡渲染图片

### 5.4 外部教务对接

**现实约束**：教务系统没有开放 API，需要爬虫/逆向，且各校不同，**无法通用实现**。

**方案**：分层
1. **不做真实对接**（没有目标学校的接口文档，做了也是猜）
2. 改为：**管理员在 Web 后台录入/导入课表**（CSV 批量导入）
3. 预留 `POST /api/admin/timetable/import` 接口，未来对接时替换数据来源

> 这一项必须诚实说明：没有目标系统的情况下「完成」只能是造假的。

### 5.5 推荐算法

**方案**：基于现有数据的评分排序（不需外部服务）
```
score = 点赞数×3 + 评论数×5 + 浏览数×1
      + 兴趣匹配加成（用户加入的圈子 → 该圈帖子加权）
      + 时效衰减（越新分越高）
```
实现：`GET /api/recommend?userId=`，SQL 排序。

### 5.6 粉丝数据

- 现在 `user-home` 粉丝数是写死初值
- 改为真实查询：`SELECT COUNT(*) FROM follow WHERE followee_id=?`
- 新增 `GET /api/users/{id}/stats` 返回 关注数/粉丝数/发帖数

### 5.7 个人主页筛选上限

- 现在 `user-home` 用 `getForumPosts({size:50})` 然后在本地筛作者，**最多只能拿到 50 条**
- 修复：新增 `GET /api/users/{id}/posts?page=&size=` 服务端按作者分页

---

## 六、实施顺序

```
1. SQL：circle / circle_member 建表 + post 加列 + 种子数据
2. 后端：CircleController + CircleService + CircleMapper
3. 后端：7 项功能接口（登录/上传/推荐/粉丝/用户帖子）
4. 前端：api.js 扩展
5. 前端：圈子页（原 discover 改造）
6. 前端：circle-detail 新页面
7. 前端：活动页加入口卡片
8. 前端：登录流程 + 上传接入
9. 联调验证
```

**每一步都实测**（起后端、打接口、看数据），不靠"看起来对"。

---

## 七、验证清单

- [ ] `GET /api/circles` 返回圈子列表
- [ ] `GET /api/circles/hot` 返回热门圈子（轮播数据源）
- [ ] 加入/退出圈子，`member_count` 正确增减
- [ ] 圈内发帖，帖子出现在该圈列表，`post_count +1`
- [ ] 发现页轮播显示的是圈子，不是活动
- [ ] 校草校花入口在活动页，不在发现页
- [ ] 登录接口返回 token，后续请求带 token
- [ ] 图片上传返回可访问 URL
- [ ] 粉丝数来自真实统计
- [ ] 个人主页帖子数突破 50 上限
