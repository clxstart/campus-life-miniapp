# 多学校架构 + Web 管理端 · 规划

日期：2026-10-16
状态：**已确认，开始实施**

---

## 一、阿星的决策记录

| 决策项 | 结论 |
|---|---|
| 多学校 | **要规划**，这是地基 |
| 身份认证 | **先不做**，后面再说 |
| 帖子可见性 | **混合模式** —— 默认本校，可切「全部学校」 |
| 其他内容 | 按校隔离（圈子/二手/兼职/活动/外卖/校历） |
| 优先级 | **先做 Web 管理端** |
| 课表节次时间 | 默认一套常见作息（8:00/10:00/…），**学生可自己改** |
| 课表周次 | 默认起止周；周六调休等**学生自己编辑** |
| Web 风格 | **严格遵循 StyleKit Natural Organic** |

---

## 二、Natural Organic 风格 Token（从 stylekit 提取）

```
Primary       #5c4033   石棕
Background    #faf6f1   暖米色
Accent1       #8b9d77   鼠尾草绿
Accent2       #d4a373   暖棕黄
Accent3       #e9e0d4   浅陶土
Accent4       #75a191   灰青绿（辅助）
```

**必须遵守**

- 大地色系 amber / stone / olive / sage
- 背景 `bg-[#faf6f1]`
- 不规则圆角 `rounded-[2rem]` 或 blob
- 纸张/织物纹理（CSS 或 SVG）
- 标题 `font-serif` + `tracking-tight`；正文 `font-sans` + `text-stone-600`
- 按钮柔和过渡 `transition-colors duration-300`，hover `bg-stone-200`
- 动效 `duration-500` + `ease-in-out`

**绝对禁止**

| 禁止 | 原因 |
|---|---|
| `bg-blue-500` / `bg-purple-500` / `bg-cyan-500` | 冷色调 |
| `text-black` / `#000000` | 纯黑 |
| `rounded-none` / `rounded-sm` | 尖锐几何 |
| `shadow-lg` / `shadow-xl` / `shadow-2xl` | 重阴影 |
| `bg-gradient-to-r` | 渐变（用纯色） |
| 霓虹/高饱和色 | 违背自然调性 |

**组件 Token**

```
按钮  px-6 py-3 rounded-full font-medium transition-colors duration-300 bg-stone-800 text-stone-50
卡片  bg-[#faf6f1] rounded-[2rem] border border-stone-200 p-6 md:p-8
输入  px-5 py-3 bg-white border border-stone-200 rounded-full text-stone-800
      placeholder:text-stone-400 focus:border-stone-400 focus:ring-2 focus:ring-stone-200
      transition-all duration-300
布局  Section py-16 md:py-24 · 容器 px-6 md:px-12 · 内容 max-w-6xl mx-auto
```

---

## 三、多学校数据模型

### 3.1 新增 school 表

```sql
CREATE TABLE school (
  id, name, city, logo, 
  period_config   -- 作息时间 JSON（课表节次默认值）
  status,         -- 0停用 / 1启用
  created_at
);
```

### 3.2 user 表

```
+ school_id  BIGINT   -- 归属学校（替代原 school 字符串）
  school     VARCHAR  -- 保留做兼容，以 school_id 为准
```

### 3.3 需要加 school_id 的表

| 表 | 原因 |
|---|---|
| circle | A校圈子 ≠ B校圈子 |
| goods | 异地无法面交，必须按校 |
| job | 校内兼职 |
| campus_activity | 校园活动 |
| shop | 各校商家不同 |
| academic_term | 学期按校 |
| banner | 可分学校投放 |
| post | **可空** —— NULL 表示跨校可见 |

### 3.4 帖子混合可见策略

```
post.school_id = NULL  → 所有学校可见（跨校广场）
post.school_id = X     → 仅 X 校可见
```

前端提供切换：**本校 / 全部学校**。默认本校。

---

## 四、Web 管理端

### 4.1 技术栈

| 层 | 选型 | 理由 |
|---|---|---|
| 框架 | Vue 3 + Vite | 轻、快 |
| 样式 | Tailwind CSS | stylekit 的 token 就是 Tailwind class |
| 组件 | **自研**（不用 Element Plus） | Element Plus 默认蓝色 + 小圆角，与风格冲突 |
| 路由 | vue-router | |
| 状态 | Pinia | |
| 请求 | axios（带 token 拦截器） | |

> 关键决定：**不用现成组件库**。Natural Organic 明确禁止蓝色和尖锐圆角，
> Element Plus 的默认主题要覆盖的地方太多，不如自研一套严格符合 token 的组件。

### 4.2 模块清单

```
登录           账号密码 + 验证码
布局           侧边栏 + 顶栏 + 面包屑
数据看板       用户/内容/活跃度趋势
学校管理       开通/停用学校、配置作息
用户管理       搜索、封禁、详情
内容管理       帖子/评论下架、举报处理
圈子管理       建圈/编辑/解散、热门位
教务管理       学期、校历事件、课表模板
业务管理       兼职/活动/二手 审核与下架
运营配置       Banner、热门话题
操作日志       谁改了什么
```

### 4.3 两级管理员

| 角色 | 范围 | 职责 |
|---|---|---|
| 超管 | 全部学校 | 开通学校、任命校管、全局配置 |
| 校管 | 本校 | 内容审核、圈子、教务、业务、商家 |

### 4.4 后端要新增的接口

```
POST   /api/admin/login                管理员登录
GET    /api/admin/profile              当前管理员信息
GET    /api/admin/dashboard            看板数据
GET    /api/admin/schools              学校列表
POST   /api/admin/schools              新增学校
PUT    /api/admin/schools/{id}         编辑学校
GET    /api/admin/users                用户列表
POST   /api/admin/users/{id}/ban       封禁/解封
GET    /api/admin/posts                帖子管理列表
POST   /api/admin/posts/{id}/offline   下架
DELETE /api/admin/posts/{id}           删除
GET    /api/admin/reports              举报列表
POST   /api/admin/reports/{id}/handle  处理举报
GET    /api/admin/circles              圈子列表
POST   /api/admin/circles              新建
PUT    /api/admin/circles/{id}         编辑
DELETE /api/admin/circles/{id}         解散
GET    /api/admin/terms                学期列表
POST   /api/admin/terms                新增学期
GET    /api/admin/calendar-events      校历事件
POST   /api/admin/calendar-events      新增
PUT    /api/admin/calendar-events/{id} 编辑
DELETE /api/admin/calendar-events/{id} 删除
GET    /api/admin/jobs                 兼职管理
GET    /api/admin/activities           活动管理
GET    /api/admin/goods                二手管理
GET    /api/admin/banners              Banner 管理
POST   /api/admin/banners              新增
PUT    /api/admin/banners/{id}         编辑
DELETE /api/admin/banners/{id}         删除
GET    /api/admin/logs                 操作日志
```

### 4.5 安全红线

| 要求 | 说明 |
|---|---|
| 独立 admin 表 | 不复用 user，避免提权 |
| 强制鉴权 | **不能**照搬小程序的「伪造 token 只降级」，必须 401 |
| RBAC | 超管 / 校管 数据权限隔离 |
| 操作日志 | 所有写操作记录 admin_id + IP + 时间 + 变更 |
| 登录风控 | 失败次数限制 |

---

## 五、实施顺序

```
1. 数据库：school 表 + 各表 school_id + admin 表 + admin_log 表
2. 后端：AdminAuth（登录/JWT/RBAC 拦截器）+ 各模块 admin 接口
3. 前端脚手架：Vite + Vue3 + Tailwind + 风格 token 落地
4. 前端：登录页 + 布局
5. 前端：逐模块页面（看板 → 学校 → 用户 → 内容 → 圈子 → 教务 → 业务 → 运营 → 日志）
6. 联调验证
```

每一步都实测（起服务、打接口、看页面），不靠「看起来对」。
