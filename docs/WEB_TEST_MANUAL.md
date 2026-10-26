# Web 管理端 · 联调测试手册

> 适用版本：2026-10-17 构建
> 测试对象：`web/`（Vue 3 管理端）+ `backend/`（Spring Boot 管理接口）
> 本文所有「预期结果」均为**实际调用后端后记录的真实响应**，不是推测。

---

## 0. 测试前准备

### 0.1 启动三端

| 端 | 启动方式 | 地址 |
|---|---|---|
| 后端 | `mvn -o spring-boot:run -P !jdk-1.8`（JAVA_HOME 指向 JDK 25） | `http://127.0.0.1:8080` |
| Web 管理端 | `node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5180` | `http://127.0.0.1:5180` |
| 小程序 | 微信开发者工具打开项目根目录 | 开发者工具内 |

> **注意**：后端必须用 `-P !jdk-1.8`。Maven 全局 `settings.xml` 里 `jdk-1.8` profile 是 `activeByDefault=true`，不加这个参数会用 JDK 8 编译，Lombok 直接崩。

### 0.2 测试账号

| 角色 | 账号 | 密码 | 可见菜单数 |
|---|---|---|---|
| 超级管理员 | `admin` | `admin123` | 10 |
| 学校管理员 | `neimenggu` | `school123` | 8（无「学校管理」「管理员」） |

### 0.3 数据基线

开始测试前，数据库应处于以下状态（若不符，说明有历史测试数据残留）：

| 实体 | 数量 |
|---|---|
| 学校 | 2（内蒙古大学 id=1、北京大学 id=2） |
| 管理员 | 2 |
| 用户 | 5 |
| 帖子 | 11 |
| 圈子 | 11 |
| 二手商品 | 7 |
| 兼职 | 5 |
| 活动 | 3 |
| 店铺 | 5 |
| 学期 | 1 |
| 校历事件 | 7 |
| Banner | 2 |

### 0.4 测试数据清理

联调会产生临时数据，测完执行：

```sql
USE campus_life;
DELETE FROM circle WHERE name LIKE '联调%';
DELETE FROM calendar_event WHERE title LIKE '联调%';
DELETE FROM academic_term WHERE term_label LIKE '联调%';
DELETE FROM admin_user WHERE username LIKE 'testadmin%';
DELETE FROM banner WHERE title LIKE '联调%';
```

> 表名易错点：学期表是 **`academic_term`**（不是 `term`），字段是 **`term_label`**。

---

## 1. 登录与鉴权

### T1.1 正常登录

**操作**：访问 `http://127.0.0.1:5180` → 自动跳转登录页 → 输入 `admin / admin123`

**预期**：
- 登录成功，跳转 `/#/dashboard`
- localStorage 写入两个键：`campus_admin_token`、`campus_admin_profile`
- 左侧菜单 10 项全部可见

**接口**：`POST /api/admin/login` → `{"code":0,"data":{"token":"...","role":"super",...}}`

### T1.2 密码错误

**操作**：输入 `admin / 错误密码`

**预期**：登录页提示错误，**不跳转**，不写入 token

**接口**：`POST /api/admin/login` → `HTTP 400`，`msg` 为账号或密码错误

### T1.3 未登录直接访问内页

**操作**：清空 localStorage 后直接访问 `http://127.0.0.1:5180/#/circles`

**预期**：路由守卫拦截，跳回 `/#/login`，URL 带 `?redirect=/circles`

### T1.4 Token 失效（关键：不能静默降级）

**操作**：登录后手动把 localStorage 里的 `campus_admin_token` 改成一个乱码字符串，然后点任意菜单

**预期**：
- 立即跳回登录页
- 本地 token 被清除
- **不会**显示空白页，**不会**用假数据糊弄

**实测**：
| 场景 | HTTP | code | msg |
|---|---|---|---|
| 无 token 访问 `/api/admin/dashboard` | 401 | 401 | 登录已过期，请重新登录 |
| 伪造 token 访问 `/api/admin/dashboard` | 401 | 401 | 登录已过期，请重新登录 |

> 这是本项目最重要的一条红线：**管理端 token 失效必须 401 跳登录，绝不能像小程序那样降级到本地 mock**。小程序「读兜底」的策略在这里是禁止的。

### T1.5 越权访问（校管访问超管接口）

**操作**：用 `neimenggu / school123` 登录，手动在地址栏访问 `/#/schools`

**预期**：页面不可达 / 菜单里根本没有该项

**实测**：
| 请求 | HTTP | code | msg |
|---|---|---|---|
| 校管 `GET /api/admin/schools` | 403 | 403 | 仅超级管理员可操作 |
| 校管 `GET /api/admin/admins` | 403 | 403 | 仅超级管理员可操作 |

---

## 2. 数据看板

**路径**：`/#/dashboard`

### T2.1 概览卡

**预期**：4 张卡片，数值与数据库一致

| 卡片 | 超管实测值 |
|---|---|
| 用户 | 5 |
| 帖子 | 11 |
| 圈子 | 11 |
| 闲置 | 7 |

### T2.2 发帖趋势图

**预期**：近 7 天柱状图。鼠标悬停显示 `日期：N 篇`

**实测响应**：`postTrend: [{"day":"2026-10-14","total":1}]`

> **注意**：只有有数据的日期才会出现柱子。当前数据库只有 1 天有发帖记录，所以图上只有一根柱子——**这是正常的**，不是图表坏了。

### T2.3 完整统计口径

`GET /api/admin/dashboard` 实际返回 8 项统计：

```json
{"comments":20,"activities":3,"jobs":5,"circles":11,
 "goods":7,"orders":3,"posts":11,"users":5}
```

> 看板只展示了其中 4 项（用户/帖子/圈子/闲置）。`comments`、`activities`、`jobs`、`orders` 目前**没有在界面上展示**，但接口已经返回，后续可加卡片。

---

## 3. 学校管理（仅超管）

**路径**：`/#/schools`

### T3.1 列表

**预期**：2 行，列：ID / 学校名称 / 城市 / 状态 / 操作

### T3.2 新建学校

**操作**：点「新建学校」→ 填名称、城市 → 保存

**预期**：列表新增一行，刷新后仍在

**实测**：`code=0, msg=ok, id=3`

**接口**：`POST /api/admin/schools`，必填 `name`，选填 `city`

### T3.3 编辑学校

**操作**：点某行「编辑」→ 改城市 → 保存

**预期**：列表即时更新

**实测**：`code=0, msg=ok`

**接口**：`PUT /api/admin/schools/{id}`

### T3.4 停用学校

**操作**：点「停用」

**预期**：状态徽章变灰

**实测**：`code=0, msg=ok`

**接口**：`PUT /api/admin/schools/{id}`（带 `status` 字段）

> 学校停用后，该校用户/内容仍在库里，只是不再出现在选择列表。**当前实现不会级联隐藏内容**——若要彻底隔离，需要额外加过滤逻辑。

---

## 4. 管理员管理（仅超管）

**路径**：`/#/admins`

### T4.1 列表

**预期**：2 行（admin 超管、neimenggu 校管），显示角色与所属学校

**接口**：`GET /api/admin/admins`

### T4.2 新建校管

> ⚠️ **账号字符集限制**：只允许 ASCII 字母、数字、下划线 `_`、点 `.`、连字符 `-`，长度 3–32 位。
> 中文一律拒绝 —— 账号是登录凭证，混入中文会遇到输入法状态、全角/半角、大小写等问题。
> **中文名字请填「真实姓名」字段。**

| 账号 | 结果 |
|---|---|
| `zhangsan` | ✅ 通过 |
| `zhang_san01` | ✅ 通过 |
| `zhang.san` / `zhang-san` | ✅ 通过 |
| `cc成` / `张三` | ❌ 拒绝（含中文） |
| `ｚｈａｎｇ`（全角） | ❌ 拒绝 |
| `zhang san`（空格）/ `zhang@san` | ❌ 拒绝 |
| `ab`（少于3位） | ❌ 拒绝 |

**操作**：点「新建管理员」→ 用户名 `testadmin001`、密码 `test123456`、角色「学校管理员」、学校选「内蒙古大学」→ 保存

**预期**：
- 列表新增一行，角色显示「学校管理员」，学校显示「内蒙古大学」
- 用该账号能登录，且只能看到 8 个菜单

**实测**：`code=0, msg=ok, id=3`

**接口**：`POST /api/admin/admins`

> **密码存储**：BCrypt 哈希，数据库里看不到明文。

### T4.3 停用管理员

**操作**：点「停用」

**预期**：状态变更；**该账号已登录的会话在 token 过期前仍有效**（当前实现如此，见 §11 已知限制）

**实测**：`code=0`

**接口**：`POST /api/admin/admins/{id}/status`

---

## 5. 用户管理

**路径**：`/#/users`

### T5.1 列表与分页

**预期**：超管 5 条，校管 4 条（看不到外校用户）

**实测**：
| 角色 | `GET /api/admin/users?page=1&size=100` → total |
|---|---|
| 超管 | 5 |
| 校管 | 4 |

### T5.2 关键词搜索

**操作**：搜索框输入某个昵称片段 → 搜索

**预期**：列表过滤为匹配项

**实测**：
| 关键词 | 结果 |
|---|---|
| `北大同学` | 1 条 |
| `zzz不存在zzz` | 0 条 |

**接口**：`GET /api/admin/users?keyword=xxx&page=1&size=20`

### T5.3 学校隔离验证

**操作**：分别用超管和校管登录，对比用户总数

**预期**：校管数字 = 超管数字 − 外校用户数

---

## 6. 内容管理（帖子）

**路径**：`/#/posts`

### T6.1 列表

**预期**：超管 11 条，校管 10 条

**实测**：
| 角色 | total |
|---|---|
| 超管 | 11 |
| 校管 | 10 |

> 差的 1 条是 id=1 的**跨校帖**（`school_id = NULL`），只有超管能看到并操作。

### T6.2 下架帖子

**操作**：对某条正常帖子点「下架」

**预期**：状态变「已下架」；小程序端该帖子消失

**实测**：`POST /api/admin/posts/13/status {"status":0}` → `code=0`

### T6.3 恢复帖子

**操作**：对已下架帖子点「恢复」

**预期**：状态变回「正常」

**实测**：`code=0`

### T6.4 删除帖子

**操作**：点「删除」→ 二次确认

**预期**：行消失；`GET /api/admin/posts` 的 total 减 1

**接口**：`DELETE /api/admin/posts/{id}`

> **易错点**：帖子 id 不连续！基线是 1-10 和 13（11、12 已被历史测试删除）。**用不存在的 id 测试会返回 `400 帖子不存在`，这是正确行为，不是 bug。**

### T6.5 跨校帖的权限边界（重点）

**操作**：
1. 超管对 id=1（跨校帖）执行下架 → 应成功
2. 校管对 id=1 执行下架 → 应 403

**实测**：
| 操作者 | HTTP | code | msg |
|---|---|---|---|
| 超管 | 200 | 0 | ok |
| 校管 | **403** | 403 | 无权操作其他学校的数据 |

> 规则：`school_id = NULL` 的跨校内容，**只有超管能动**。校管一律 403。

### T6.6 「只看已下架」筛选

**操作**：勾选筛选开关

**预期**：只显示已下架帖子

**实测**：
| 筛选 | total |
|---|---|
| `offlineOnly=true` | 0（当前无下架帖子） |
| `offlineOnly=false` | 11 |

**接口**：`GET /api/admin/posts?offlineOnly=true`

> 测试方法：先下架一条帖子，再勾选筛选，应该只看到那一条。

---

## 7. 圈子管理

**路径**：`/#/circles`

### T7.1 列表

**预期**：11 个圈子，列：ID / 圈子（带配色圆点）/ 分类 / 成员 / 帖子 / 热门 / 状态 / 操作

**实测**：超管 11 条，校管 10 条

### T7.2 新建圈子（易错点）

**操作**：点「新建圈子」→ 填名称、分类、简介、主题色 → 保存

**预期**：列表新增

**实测**：`code=0, id=12`

> ⚠️ **超管建圈必须指定学校**。后端逻辑：超管 `scope(req)` 为 null 时会读 `body.schoolId`，取不到就抛 `400 请指定所属学校`。
> 界面上有学校下拉框；**直接调接口不传 schoolId 会 400**，这是防止产生「跨校无主圈子」的保护。

**接口**：`POST /api/admin/circles`，必填 `name`，超管必填 `schoolId`

### T7.3 编辑圈子

**操作**：点「编辑」→ 改名称/分类/主题色/热门标记 → 保存

**预期**：列表即时更新；「热门」徽章跟着变

**实测**：`code=0`

**接口**：`PUT /api/admin/circles/{id}`

### T7.4 停用圈子（注意：没有独立接口）

**操作**：点「解散」

**预期**：状态变灰，小程序端圈子列表不再显示

**实测**：通过 `PUT /api/admin/circles/{id}` 带 `status=0` → `code=0`

> ⚠️ **没有 `POST /admin/circles/{id}/status` 这个接口**，也没有 `DELETE /admin/circles/{id}`。
> 圈子**只能停用不能删除**——因为帖子通过 `circle_id` 引用它，物理删除会产生孤儿数据。
> 直接调 `/admin/circles/{id}/status` 会返回 `500 No static resource`。

### T7.5 按钮文字不折行（回归项）

**检查点**：操作列的「编辑」「解散」两个按钮，文字必须是**横排**，不能出现「编/辑」竖排折行。

> 这是修过的 bug：列宽不够 + 按钮没禁换行。修复方式：全局 `.no-btn` 加 `whitespace-nowrap`。
> 若回归，检查 `web/src/style.css` 的 `.no-btn` 是否还有这个类。

---

## 8. 教务管理

**路径**：`/#/academics`

页面分两块：**学期** 和 **校历事件**。

### T8.1 学期列表

**预期**：1 条（内蒙古大学当前学期）

**接口**：`GET /api/admin/terms`

### T8.2 新建学期（字段名易错）

**操作**：点「新建学期」→ 填学期名称 + 5 个日期 → 保存

**必填字段**：

| 字段 | 说明 | 示例 |
|---|---|---|
| `termLabel` | 学期名称（**不是 `name`**） | 2026-2027学年第一学期 |
| `startDate` | 开学日期 | 2026-09-01 |
| `endDate` | 结束日期 | 2027-01-15 |
| `midtermStart` | 期中开始 | 2026-10-25 |
| `midtermEnd` | 期中结束 | 2026-11-02 |
| `finalStart` | 期末开始 | 2026-12-28 |
| `active` | 是否当前学期 | 0 / 1 |

**实测**：`code=0, id=2`

> ⚠️ **字段名是 `termLabel` 不是 `name`**。传 `name` 会返回 `400`（缺学期名称）。
> 日期格式必须是 `YYYY-MM-DD`，其他格式会被 `requireDate` 拒绝。

**接口**：`POST /api/admin/terms`

### T8.3 编辑学期

**实测**：`code=0`

**接口**：`PUT /api/admin/terms/{id}`

### T8.4 校历事件 CRUD

**列表**：7 条（含调休、放假、考试等）

**新建**：
- 必填：`termId`（关联学期）、`title`、`startDate`、`endDate`
- 选填：`note`

**实测**：`code=0, id=8`

**编辑**：`code=0`
**删除**：`code=0`

**接口**：`POST/PUT/DELETE /api/admin/calendar-events`

> ⚠️ 新建事件时 **`termId` 必填**。若学期列表为空（比如刚删光了），事件建不了。

---

## 9. 业务管理

**路径**：`/#/business`

三个 tab：**兼职** / **活动** / **二手**。

### T9.1 各 tab 数据量

**实测**：

| tab | 超管 | 校管 |
|---|---|---|
| 兼职 | 5 | 4 |
| 活动 | 3 | 3 |
| 二手 | 7 | 6 |

### T9.2 兼职 tab

**操作**：点「删除」

**预期**：行消失

**接口**：`DELETE /api/admin/jobs/{id}`

> 兼职是三个 tab 里**唯一支持物理删除**的。

### T9.3 活动 tab

**操作**：点「下架」/「恢复」

**预期**：状态切换；小程序活动页跟着变

**实测**：`POST /api/admin/activities/3/status {"status":0}` → `code=0`，恢复 → `code=0`

**接口**：`POST /api/admin/activities/{id}/status`

> 活动**只能下架不能删除**（有报名记录 `activity_enroll` 引用）。

### T9.4 二手 tab

**操作**：点「下架」/「恢复」

**实测**：`code=0`

**接口**：`POST /api/admin/goods/{id}/status`

> 二手**只能下架不能删除**（同活动）。

### T9.5 tab 间差异（重要）

| tab | 按钮 | 原因 |
|---|---|---|
| 兼职 | 只有「删除」 | 无状态字段依赖 |
| 活动 | 只有「下架/恢复」 | 有报名记录引用 |
| 二手 | 只有「下架/恢复」 | 有订单记录引用 |

**这是设计差异，不是缺失功能。**

---

## 10. 运营配置 & 操作日志

### T10.1 Banner 管理

**路径**：`/#/ops`

**列表**：2 条

**新建字段**：

| 字段 | 说明 |
|---|---|
| `title` | 主标题 |
| `subtitle` | 副标题 |
| `eyebrow` | 眉标（如 `CAMPUS LIVE`） |
| `badge` | 角标（如 `12.05`） |
| `theme` | 主题色（下拉选择） |
| `sort` | 排序权重 |
| `linkType` | 跳转类型（`page` / 其他） |
| `linkValue` | 页面路径（如 `/pages/activities/activities`） |
| `status` | 是否启用 |

**实测全流程**：新建（完整 9 字段）`id=4` → 删除 `code=0`

> Banner 的 9 个字段全部会被接受：`title` / `subtitle` / `eyebrow` / `badge` / `theme` / `sort` / `linkType` / `linkValue` / `status`。只有 `title` 是必填。

**接口**：`GET/POST/PUT/DELETE /api/admin/banners`

### T10.2 操作日志

**路径**：`/#/logs`

**预期**：分页列表，每行显示操作时间 / 操作人 / 动作 / 目标类型 / 目标 ID / 描述

**实测**：`total=26`（含历史测试记录），最近 5 条示例：

```
delete banner#4  删除 Banner
create banner#4  新增 Banner 联调Banner...
update school#3  编辑学校 联调测试大学...改
create school#3  新增学校 联调测试大学...
```

**接口**：`GET /api/admin/logs?page=1&size=20`

**验证方法**：先在任意页面做一次写操作（如新建圈子），再来看日志，应该多一条记录。

**日志动作类型**：`create` / `update` / `delete` / `offline` / `online`

> **验证点**：管理端的每一次写操作都应该留痕。若某操作做完日志里没有，说明该端点漏了 `log(...)` 调用。

---

## 11. 分页行为（新增）

### 11.1 哪些页面有分页

| 页面 | 后端分页 | 前端分页控件 | 说明 |
|---|---|---|---|
| 用户管理 | ✅ | ✅ | 数据会增长 |
| 内容管理 | ✅ | ✅ | 数据会增长 |
| 业务管理 | ✅ | ✅ | 数据会增长 |
| 操作日志 | ✅ | ✅ | 数据会持续增长 |
| **圈子管理** | ✅ | ✅ | 2026-10-17 补齐 |
| **运营配置 Banner** | ✅ | ✅ | 2026-10-17 补齐 |
| 学校管理 | ❌ | ❌ | 顶多几十所，不值得分页 |
| 管理员 | ❌ | ❌ | 几个人，不值得分页 |
| 教务管理 | ❌ | ❌ | 学期 1-2 条/年，事件约 20 条/年 |

> **设计取舍**：不是所有列表都该分页。学校、管理员、教务这三类数据天生量小，加分页属于过度设计。只给「会真正增长」的列表做分页。

### 11.2 分页控件什么时候出现

分页栏**只在 `total > size` 时显示**（见 `NoTable.vue` 第 54 行 `v-if="total > size"`）。

所以当前数据（圈子 11 条、Banner 2 条，每页 20 条）**看不到分页栏，这是正确的**，不是坏了。

想看到分页栏，用这两个办法之一：
1. 把每页条数调小（临时改 `size` ref）
2. 造足够多的数据

### 11.3 验证分页真的生效

**方法 A：直接调接口**

```bash
# 第 1 页
curl -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8080/api/admin/circles?page=1&size=5"
# → {"code":0,"data":{"list":[...5条...],"total":11,"page":1,"size":5}}

# 第 2 页
curl -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8080/api/admin/circles?page=2&size=5"
# → {"code":0,"data":{"list":[...],"total":11,"page":2,"size":5}}
```

**判断标准**：
- `total` 两页一致（都是 11）
- 两页的 `id` 不重复
- `page` 回显正确

**方法 B：临时把 size 改成 5**

改 `Circles.vue` 的 `const size = ref(20)` 为 `ref(5)`，刷新页面应看到：

```
共 11 条 · 第 1 / 3 页        [上一页]  [下一页]
```

表格正好 5 行。点「下一页」→ 变成 `第 2 / 3 页`，数据换成后 5 条。

> 实测确认：`size=5` 时 DOM 渲染出 `共 11 条 | 第 1 / 3 页 | 上一页 | 下一页`，表格 5 行。验证后已改回 20。

### 11.4 分页的安全边界

| 场景 | 预期 | 实测 |
|---|---|---|
| `size=99999` | 收敛到 100 | ✅ `size` 返回 100 |
| `page=999` | 空列表，`total` 不变 | ✅ `list=[]`，`total=11` |
| `page=0` | 当作第 1 页 | ✅ |
| 关键词 + 分页 | 两者叠加 | ✅ 搜索结果都含关键词 |
| 校管分页 | `total` 只算本校 | ✅ 校管 10 ≤ 超管 11 |

**为什么要收敛 size**：`paging()` 里 `Math.min(Math.max(size,1),100)`。不设上限的话，前端传 `size=99999` 就等于一次拉全库，分页形同虚设。

### 11.5 编辑/删除后停在当前页

**已处理**：在圈子、Banner 页面做编辑或启停后，**停留在当前页**（`load(page.value)`），不会跳回第 1 页。

**Banner 删除的特殊处理**：如果删掉的是当前页最后一条，会自动回退一页（`rows.length === 1 && page > 1 ? page - 1 : page`），否则会停在空白页。

**验证方法**：把 size 改成 5，翻到第 2 页，编辑一条 → 应该还在第 2 页。

### 11.6 接口响应结构变更（重要）

`/admin/circles` 和 `/admin/banners` 的响应**从裸数组改成了分页对象**：

```jsonc
// 改之前
{"code":0,"data":[{...},{...}]}

// 改之后
{"code":0,"data":{"list":[{...}],"total":11,"page":1,"size":20}}
```

**影响面已排查**：全项目只有 `Circles.vue` 和 `Ops.vue` 调用这两个端点，均已同步修改。`Dashboard.vue` 只用 `/admin/dashboard` 的统计数字，不受影响。

> 若以后新增调用方，**必须按 `data.list` 取数组**，不能直接把 `data` 当数组用。

---

## 12. 已知限制（不是 bug，但要知道）

| # | 限制 | 影响 | 说明 |
|---|---|---|---|
| 1 | 圈子/活动/二手**不能删除**，只能停用 | 数据只增不减 | 有外键引用（帖子、报名、订单），物理删除会产生孤儿数据 |
| 2 | 停用管理员后，其已登录会话在 token 过期前仍有效 | 最长 8 小时 | JWT 无状态，无法即时踢人。需要即时失效得引入黑名单 |
| 3 | 看板只展示 8 项统计中的 4 项 | 信息不全 | `comments`/`activities`/`jobs`/`orders` 接口已返回，界面未展示 |
| 4 | 发帖趋势图只有 1 根柱子 | 看着像坏了 | 数据库只有 1 天有发帖记录 |
| 5 | 帖子 id 不连续（1-10、13） | 用 11/12 测试会 400 | 历史测试数据已删 |
| 6 | `/uploads/**` 完全公开 | 任何人可访问上传的图片 | 做学生证认证前必须先加鉴权，否则证件照会泄露 |
| 7 | 没有内容审核/举报/敏感词 | 违规内容只能事后手工下架 | 上生产前必须补 |
| 8 | 没有请求日志和慢查询日志 | 线上排障困难 | 需补 access log |

---

## 13. 快速回归清单

每次改完管理端，按这个顺序跑一遍（约 10 分钟）：

| # | 检查项 | 通过标准 |
|---|---|---|
| 1 | 超管登录 | 进得去，10 个菜单 |
| 2 | 校管登录 | 进得去，只有 8 个菜单 |
| 3 | 改乱 token 点菜单 | 跳回登录页，不是白屏 |
| 4 | 看板 4 张卡 | 数字非 0、非 `NaN` |
| 5 | 帖子下架 → 恢复 | 状态来回切换 |
| 6 | 新建圈子 | 成功，列表 +1 |
| 7 | 圈子操作列按钮 | 文字横排，不折行 |
| 8 | 新建学期 | 成功（用 `termLabel`） |
| 9 | 新建 Banner → 删除 | 成功，列表回到原数量 |
| 10 | 操作日志 | 能看到第 5/6/8 步的记录 |
| 11 | 校管访问 `/#/schools` | 403 / 菜单不可见 |
| 12 | 圈子分页接口 | `total` 稳定，两页 id 不重复 |
| 13 | size 超上限 | 传 99999 返回 size=100，不是全库 |
| 14 | 小程序选学校 | 列表来自接口，不是硬编码 |
| 15 | 小程序切学校 | 切到北大后圈子从 10 个变 1 个 |
| 16 | 新建管理员填中文账号 | 被拒绝并提示「中文请填真实姓名」 |

---

## 14. 接口速查

### 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/admin/login` | 登录 |
| GET | `/api/admin/profile` | 当前管理员信息 |
| GET | `/api/admin/admins` | 管理员列表（超管） |
| POST | `/api/admin/admins` | 新建管理员（超管） |
| POST | `/api/admin/admins/{id}/status` | 启用/停用（超管） |
| GET | `/api/admin/logs` | 操作日志 |

### 业务

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/dashboard` | 看板统计 |
| GET | `/api/schools` | **公开**学校列表（小程序选学校用，无需鉴权） |
| GET/POST | `/api/admin/schools` | 学校列表/新建（超管） |
| PUT | `/api/admin/schools/{id}` | 编辑学校（超管） |
| GET | `/api/admin/users` | 用户列表 |
| GET | `/api/admin/posts` | 帖子列表 |
| POST | `/api/admin/posts/{id}/status` | 下架/恢复 |
| DELETE | `/api/admin/posts/{id}` | 删除帖子 |
| GET/POST | `/api/admin/circles` | 圈子列表（**分页** page/size）/新建 |
| PUT | `/api/admin/circles/{id}` | 编辑圈子（含停用） |
| GET/POST | `/api/admin/terms` | 学期列表/新建 |
| PUT | `/api/admin/terms/{id}` | 编辑学期 |
| GET/POST | `/api/admin/calendar-events` | 校历事件列表/新建 |
| PUT/DELETE | `/api/admin/calendar-events/{id}` | 编辑/删除事件 |
| GET | `/api/admin/jobs` | 兼职列表 |
| DELETE | `/api/admin/jobs/{id}` | 删除兼职 |
| GET | `/api/admin/activities` | 活动列表 |
| POST | `/api/admin/activities/{id}/status` | 活动下架/恢复 |
| GET | `/api/admin/goods` | 二手列表 |
| POST | `/api/admin/goods/{id}/status` | 二手下架/恢复 |
| GET/POST | `/api/admin/banners` | Banner 列表（**分页** page/size）/新建 |
| PUT/DELETE | `/api/admin/banners/{id}` | 编辑/删除 Banner |

---

## 15. 小程序选学校（跨端联调）

### 15.1 这条链路原来是断的

| 位置 | 原来 | 现在 |
|---|---|---|
| 小程序学校列表 | 硬编码 `['内蒙古大学','北京大学','中国人民大学']` | `GET /api/schools` 动态拉取 |
| 选中态 | 只写 `globalData.schoolName` | `storage` 持久化 `{id, name}` |
| 传给接口 | **没传** | 所有列表接口带 `schoolId` |
| 结果 | 切了学校数据不变 | 切了真的换数据 |

> 「中国人民大学」是前端硬编码的，数据库里从来没有这所学校。

### 15.2 公开学校接口

```bash
curl http://127.0.0.1:8080/api/schools
# → {"code":0,"data":[{"id":1,"name":"内蒙古大学","city":"呼和浩特","periodConfig":null},
#                     {"id":2,"name":"北京大学","city":"北京","periodConfig":null}]}
```

**要点**：
- **无需鉴权** —— 小程序未登录也要能选学校
- **只返回 `status=1`** 的学校（停用的不出现）
- **不带运营数据** —— 没有 `userCount`/`circleCount`，那些只给管理端看

### 15.3 切换学校的实际效果（实测）

用户 `userId=1` 属于内蒙古大学（id=1）：

| schoolId | 圈子 | 兼职 | 外卖 |
|---|---|---|---|
| 1（内蒙古大学） | 10 个 | 9 个 | 4 家 |
| 2（北京大学） | 1 个（北大未名湖跑团） | 1 个 | 1 家 |
| 不传（走账号学校） | 10 个 | 9 个 | 4 家 |

### 15.4 优先级规则（三档）

```
显式 schoolId  >  schoolScope=all  >  用户账号所属学校
```

**为什么要显式 schoolId**：「用户所属学校」是账号属性，「当前选中学校」是使用状态。两者不是一回事 —— 内蒙古大学的学生完全可能想看看北京大学有什么。

### 15.5 首次进入强制选学校

- 没选过 → 弹出选择器，**必须选**
- 只有一所学校 → 自动选中，不打扰用户
- 选完写入 storage，之后进小程序直接进首页

### 15.6 兼容性

| 场景 | 行为 |
|---|---|
| 不传 `schoolId` | 完全保持原行为（走用户账号学校） |
| `schoolScope=all` | 仍能看全部，不受 schoolId 影响 |
| `schoolId` 不存在（如 99999） | 返回空列表，**不报 500** |
| 后端挂了 | 回落到本地兜底 2 所学校，仍可进小程序 |

### 15.7 相关测试

```bash
node --test tests/backend-school-picker.test.js   # 后端 15 个
node --test tests/miniapp-school-picker.test.js   # 小程序 12 个
```

---

## 16. 自动化测试

```bash
# 小程序行为测试（45 个）
node --test tests/regression.test.js

# 后端集成测试（20 个，需后端运行）
node --test tests/backend-posts-scope.test.js
node --test tests/backend-goods-scope.test.js
node --test tests/backend-school-scope.test.js
node --test tests/backend-admin-paging.test.js   # 管理端分页（9 个）

# 静态校验（页面四件套 / 路由 / 资源 / SQL 参数）
python tests/validate_project.py
```

**当前基线**：45 + 20 全通过，静态校验 PASS。

> 集成测试会往数据库写 `E2E-` 前缀的临时数据。重跑 `tests/fixtures/school-isolation.sql` 会自动清理。
