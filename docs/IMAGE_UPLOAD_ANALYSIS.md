# Web 管理端图片上传分析

> 分析时间：2026-10-17
> 分析时结论：**管理端一个上传入口都没有**。后端上传接口可用但无鉴权；两个后端已支持的字段（学校 logo、圈子封面）前端没做输入框。
>
> **状态：全部 8 项已实现**（2026-10-17），详见文末「实现记录」。

---

## 一、现状总览

### 1.1 数据库里所有图片字段（11 个）

| 表 | 字段 | 类型 | 谁在用 |
|---|---|---|---|
| `school` | `logo` | varchar(255) | 小程序首页学校标识 |
| `circle` | `cover` | varchar(255) | 小程序圈子卡片 |
| `campus_activity` | `poster` | varchar(255) | 小程序活动卡片 |
| `post` | `image` | varchar(255) | 小程序帖子配图 |
| `goods` | `image` / `images` | varchar(255) / text | 小程序二手商品 |
| `user` | `avatar` | varchar(255) | 小程序头像 |
| `shop` | `cover` | varchar(255) | 小程序外卖店铺 |
| `shop_goods` | `image` | varchar(255) | 小程序外卖菜品 |
| `idol_contestant` | `photo` | varchar(255) | 小程序校园评选 |
| `chat_message` | `image` | varchar(255) | 小程序私信图片 |

> **注意**：`banner` 表**没有** image 列，它的展示靠 `theme` 主题色 + 文字，不依赖图片。

### 1.2 管理端页面 × 图片字段 对照

| 管理端页面 | 对应图片字段 | 后端支持 | 前端有输入框 | 小程序用不用 |
|---|---|---|---|---|
| 学校管理 | `school.logo` | ✅ 已支持 | ❌ 无 | ✅ 首页显示 |
| 圈子管理 | `circle.cover` | ✅ 已支持 | ❌ 无 | ✅ 圈子卡片 |
| 内容管理（帖子） | `post.image` | ❌ 只能下架/删除 | ❌ 无 | ✅ 帖子配图 |
| 业务-活动 | `campus_activity.poster` | ❌ 只能下架 | ❌ 无 | ✅ 活动卡片 |
| 业务-二手 | `goods.image` | ❌ 只能下架 | ❌ 无 | ✅ 商品图 |
| 用户管理 | `user.avatar` | ❌ 无编辑接口 | ❌ 无 | ✅ 头像 |
| 运营配置（已隐藏） | — | — | — | — |
| （无页面） | `shop.cover` | ❌ 无管理 | ❌ 无 | ✅ 外卖店铺 |
| （无页面） | `shop_goods.image` | ❌ 无管理 | ❌ 无 | ✅ 外卖菜品 |
| （无页面） | `idol_contestant.photo` | ❌ 无管理 | ❌ 无 | ✅ 校园评选 |

**一句话**：10 个有图的字段里，管理端能操作的只有 2 个（还缺输入框），其余 8 个完全没有管理入口。

---

## 二、上传基础设施现状（实测）

### 2.1 接口：`POST /api/upload`

```java
@PostMapping("/upload")
public ApiResponse<Map<String,Object>> upload(@RequestParam("file") MultipartFile file){
    return ApiResponse.ok(service.store(file));
}
```

**实测结果**：

| 检查项 | 结果 |
|---|---|
| 带 admin token 上传 | ✅ `code=0`，返回 `http://127.0.0.1:8080/uploads/xxx.png` |
| **不带任何 token 上传** | ⚠️ **`code=0` —— 无鉴权，任何人都能传** |
| 上传后的图片能否访问 | ✅ HTTP 200（`/uploads/**` 静态映射正常） |
| 传 txt 文件 | ✅ 被拒 `仅支持 jpg/png/gif/webp 格式` |
| 文件大小上限 | 5MB（`campus.upload.max-size`） |
| 允许格式 | jpg / jpeg / png / gif / webp |

### 2.2 为什么无鉴权

`AuthInterceptor` 的设计是「解析失败不阻断」，因为要保持「无后端也能演示」的小程序行为：

```java
// 轻量鉴权：解析 Authorization 头里的 JWT，把 userId 放进请求属性供接口读取。
// 解析失败不阻断请求 —— 保持「无后端/未登录也能演示」的既有行为，由接口自行决定降级。
if (userId != null) request.setAttribute(USER_ID, userId);
return true;   // ← 永远放行
```

这对**读接口**是合理的设计，但 `/api/upload` 是**写操作**，白嫖风险很实际：
- 任何人都能往服务器磁盘写文件
- 没有清理机制，可以刷满磁盘
- 上传的文件通过 `/uploads/**` 完全公开

---

## 三、哪些地方「需要」图片上传

按**业务必要性**分三档。

### 第一档：必须做（后端已就绪，只差前端）

| # | 位置 | 字段 | 为什么必须 |
|---|---|---|---|
| 1 | 学校管理 → 编辑学校 | `school.logo` | 后端已支持、小程序首页已显示，**只差一个输入框**。当前所有学校 logo 都是空 |
| 2 | 圈子管理 → 新建/编辑圈子 | `circle.cover` | 后端已支持、小程序圈子卡片在用。现在只能靠 SQL 改 |

这两个是**最划算的**：改动量最小（前端加个上传控件 + 传字段），立刻见效。

### 第二档：应该做（需要补后端接口）

| # | 位置 | 字段 | 缺什么 |
|---|---|---|---|
| 3 | 业务-活动 | `campus_activity.poster` | 后端缺「编辑活动」接口，现在只能下架 |
| 4 | 业务-二手 | `goods.image` | 后端缺「编辑商品」接口，现在只能下架 |
| 5 | 内容管理-帖子 | `post.image` | 后端缺「编辑帖子」接口，现在只能下架/删除 |

这三个的问题不是「缺上传控件」，而是**整个编辑能力都没有**。要不要做取决于：运营是否需要修改已发布内容的配图。

### 第三档：看业务需求

| # | 位置 | 字段 | 说明 |
|---|---|---|---|
| 6 | 用户管理 | `user.avatar` | 用户头像通常由用户自己传，管理员改头像不常见 |
| 7 | 外卖店铺 | `shop.cover` | **管理端完全没有外卖管理页面**，需要先建页面 |
| 8 | 外卖菜品 | `shop_goods.image` | 同上 |
| 9 | 校园评选 | `idol_contestant.photo` | **管理端没有评选管理页面** |

7/8/9 是「整个模块没管理端」，不是图片上传问题。

---

## 四、建议的实现方案

### 4.1 先补上传组件的安全底线

当前 `/api/upload` 裸奔，建议：

```java
// 新增管理端专用上传接口，走 AdminInterceptor 鉴权
@PostMapping("/admin/upload")
public ApiResponse<Map<String,Object>> adminUpload(@RequestParam("file") MultipartFile file){
    return ApiResponse.ok(service.store(file));
}
```

或更简单：在 `UploadController` 里判断请求路径，`/api/admin/upload` 必须带 admin token。

**理由**：小程序端上传需要保持宽松（游客也能发帖演示），但管理端必须收紧。

### 4.2 前端上传组件

管理端目前没有上传控件，需要新建 `web/src/components/NoImageUpload.vue`：

- 点击选图 → `POST /api/admin/upload` → 回填 URL
- 显示缩略图 + 「更换」「移除」
- 校验：格式、大小（前端先拦一道，避免传完才报错）
- 风格：Natural Organic（大圆角、米色底、`#8b9d77` 主色）

### 4.3 优先落地顺序

| 步骤 | 内容 | 工作量 |
|---|---|---|
| 1 | 建 `NoImageUpload.vue` 组件 | 中 |
| 2 | 学校管理加 logo 上传 | 小（后端已支持） |
| 3 | 圈子管理加封面上传 | 小（后端已支持） |
| 4 | 收紧 `/api/upload` 鉴权 | 小 |
| 5 | （按需）活动/二手/帖子编辑 + 图片 | 大（要先补编辑接口） |

---

## 五、安全提醒（上生产前必须处理）

| # | 风险 | 说明 |
|---|---|---|
| 1 | **上传无鉴权** | 任何人可写文件到服务器磁盘 |
| 2 | **无清理机制** | 上传的文件永久保留，删除内容后图片仍在 |
| 3 | **`/uploads/**` 完全公开** | 任何人拿到 URL 就能访问 |
| 4 | 无图片内容审核 | 违规图片可以直接传上来 |
| 5 | 无频率限制 | 可以脚本刷上传 |

第 3 条在**做学生证认证功能之前必须先解决** —— 否则证件照会直接泄露。

---

## 六、实测命令（可复现）

```powershell
# 1. 带 admin token 上传
$T = (Invoke-WebRequest "$BASE/admin/login" -Method POST `
  -Body (@{username='admin';password='admin123'}|ConvertTo-Json) `
  -ContentType "application/json" -UseBasicParsing).Content | ConvertFrom-Json
$form = @{ file = Get-Item "test.png" }
Invoke-WebRequest "$BASE/upload" -Method POST `
  -Headers @{Authorization="Bearer $($T.data.token)"} -Form $form -UseBasicParsing

# 2. 不带 token（当前会成功 —— 这是问题）
Invoke-WebRequest "$BASE/upload" -Method POST -Form $form -UseBasicParsing

# 3. 传非图片（应被拒）
Invoke-WebRequest "$BASE/upload" -Method POST -Headers @{Authorization="Bearer $($T.data.token)"} `
  -Form @{ file = Get-Item "bad.txt" } -UseBasicParsing
```

---

## 七、实现记录（2026-10-17）

第一档全部落地，168 个自动化测试通过。

### 7.1 新增管理端鉴权上传接口

```java
// UploadController
@PostMapping("/upload")        // 小程序用：保持无鉴权（未登录也能演示）
@PostMapping("/admin/upload")  // 管理端用：落在 /api/admin/**，AdminInterceptor 强制校验 token
```

**为什么保留两个**：小程序端刻意宽松（「无后端也能演示」是既有产品行为），
直接收紧会破坏它。管理端改用鉴权接口，风险面收敛到「只有游客上传这一条」。

实测：

| 场景 | 结果 |
|---|---|
| `POST /api/admin/upload` 无 token | ✅ 401 |
| `POST /api/admin/upload` 伪造 token | ✅ 401 |
| `POST /api/admin/upload` 有效 token | ✅ 200，返回 url/path |
| `POST /api/upload` 无 token | ✅ 仍可用（小程序演示不受影响） |
| 传 txt | ✅ 拒绝「仅支持 jpg/png/gif/webp」 |
| 传 6MB 图片 | ✅ 拒绝「不能超过 5MB」 |

### 7.2 新增上传组件 `NoImageUpload.vue`

| 能力 | 说明 |
|---|---|
| 未选图 | 虚线框「点击上传图片」+ 格式/大小提示 |
| 已选图 | 96×96 缩略图 + 「更换图片」「移除」 |
| 前端校验 | 类型（jpeg/png/gif/webp）+ 大小（5MB），先拦一道 |
| 加载失败 | 显示「图片地址已失效，建议重新上传」 |
| 上传中 | 按钮禁用 + 「上传中…」 |
| 接口 | 固定走 `/admin/upload`（测试断言禁止调用公开接口） |

### 7.3 接入位置

| 页面 | 字段 | 说明 |
|---|---|---|
| 学校管理 → 编辑弹窗 | `school.logo` | 上传后学校卡片显示 44×44 缩略图 |
| 圈子管理 → 编辑弹窗 | `circle.cover` | 上传后列表用缩略图替换配色圆点 |

### 7.4 端到端实测

```
1. 上传 logo        → code=0, url=http://127.0.0.1:8080/uploads/d720...png
2. 写入内蒙古大学    → code=0
3. GET /api/schools → id=1 logo=http://.../uploads/d720...png
4. 访问该 url       → HTTP 200, Content-Type: image/png
5. 圈子封面上传      → 小程序 /circles 接口返回 cover=http://.../uploads/398d...png
6. 管理端页面渲染    → 学校页 1 个 /uploads 引用，圈子页 1 个
```

### 7.5 仍未处理（第二、三档 + 安全）

| # | 项 | 状态 |
|---|---|---|
| 1 | 活动 / 二手 / 帖子的图片编辑 | 未做（要先补三个编辑接口） |
| 2 | 用户头像管理 | 未做 |
| 3 | 外卖店铺 / 菜品管理页 | 未做（整个模块没管理端） |
| 4 | 校园评选管理页 | 未做 |
| 5 | **上传频率限制** | 未做 |
| 6 | **图片内容审核** | 未做 |
| 7 | **上传文件清理机制** | 未做 |
| 8 | **`/uploads/**` 完全公开** | 未做 —— 做学生证认证前必须先解决 |

---

## 八、剩余全部完成（2026-10-17 第二轮）

第一档之外的第二、三档与全部安全加固已落地，215 个自动化测试通过。

### 8.1 内容编辑（帖子 / 二手 / 活动）

原来只能下架/删除，改不了内容也换不了图。新增：

| 端点 | 说明 |
|---|---|
| `PUT /api/admin/posts/{id}` | 标题 / 正文 / 标签 / 配图 |
| `PUT /api/admin/goods/{id}` | 标题 / 价格 / 分类 / 成色 / 图片 / 描述 |
| `PUT /api/admin/activities/{id}` | 标题 / 海报 / 时间 / 地点 / 描述 |
| `PUT /api/admin/users/{id}/avatar` | 用户头像（传空串清空） |

图片字段统一走 `imageOrEmpty()`：空串允许（表示清空），非空必须是服务端地址。
校管只能改本校内容（`assertSameSchool`）。

前端：`Posts.vue`、`Business.vue`（活动/二手两个弹窗）、`Users.vue`（改头像）均接入 `NoImageUpload`。

### 8.2 外卖管理（店铺 + 菜品）

管理端原来**完全没有外卖页面**，店铺和菜品只能靠 SQL 维护。新增 9 个端点：

| 端点 | 说明 |
|---|---|
| `GET/POST /api/admin/shops` | 列表（分页+搜索）/ 新建 |
| `PUT /api/admin/shops/{id}` | 编辑 |
| `POST /api/admin/shops/{id}/status` | 营业/停用 |
| `DELETE /api/admin/shops/{id}` | 删除 |
| `GET /api/admin/shops/{id}/goods` | 菜品列表 |
| `POST /api/admin/shop-goods` | 新建菜品 |
| `PUT/DELETE /api/admin/shop-goods/{id}` | 编辑/删除菜品 |

前端新增 `Shops.vue`：店铺表格 + 菜品弹窗管理（增删改），超管可切学校。

### 8.3 校园评选管理

`idol_contestant` 没有 `school_id`，评选是全平台活动，**仅超管可操作**。

| 端点 | 说明 |
|---|---|
| `GET /api/admin/idol-contestants?gender=` | 列表（可按组别筛选） |
| `POST /api/admin/idol-contestants` | 新建选手 |
| `PUT /api/admin/idol-contestants/{id}` | 编辑 |
| `POST /api/admin/idol-contestants/{id}/status` | 停用/启用 |
| `DELETE /api/admin/idol-contestants/{id}` | 删除 |

前端新增 `Idol.vue`：卡片式布局，全部/女生组/男生组切换，照片上传。

### 8.4 上传安全加固（4 项）

| 项 | 实现 |
|---|---|
| **文件头魔数校验** | 读前 12 字节比对 PNG/JPEG/GIF/WEBP 真实签名。把 `.php` 改名成 `.jpg` 也会被拒 |
| **频率限制** | `UploadRateLimiter`，60 秒窗口最多 20 次，超限抛 `TooManyRequestsException` → HTTP 429 |
| **孤儿文件清理** | `GET /api/admin/uploads/orphans?dryRun=true&olderThanDays=7`，默认只预览 |
| **私有目录隔离** | `POST /api/admin/upload/private` → 存 `uploads/private/`，返回 `/private/xxx`，**不走 `/uploads/**` 静态映射**，无法直接访问 |

**为什么私有目录重要**：做学生证认证时，证件照不能像普通图片那样公开可访问。

### 8.5 频率限制的设计取舍

- 管理端按 `adminId` 计数（同账号共享配额）
- 游客按 IP 计数
- 支持 `X-Upload-Client` 头区分来源，便于多端调试
- 单机内存计数（`ConcurrentHashMap`），多实例部署需换 Redis
- 超过 2000 个桶时自动清理过期项，避免内存增长

### 8.6 回归结果

| 套件 | 结果 |
|---|---|
| 小程序行为 / 选学校 / 地点 | 45 + 12 + 8 |
| 同类问题修复 / 作息 / 账号 | 15 + 15 + 4 |
| 后端隔离（帖子/二手/学校） | 4 + 5 + 11 |
| 后端分页 / 选学校 / 地点 | 9 + 15 + 15 |
| 后端上传 / 内容编辑 | 10 + 14 |
| 后端外卖 / 评选 | 13 + 8 |
| 后端上传安全（新增） | 12 |
| 静态校验 | PASS |
| **合计** | **215 个测试全绿** |

### 8.7 管理端菜单（11 项）

数据看板 / 学校管理 / 管理员 / 用户管理 / 内容管理 / 圈子管理 / 地点管理 / **外卖管理** / **校园评选** / 教务管理 / 业务管理 / 操作日志

### 8.8 仍未处理

| # | 项 | 说明 |
|---|---|---|
| 1 | 上传文件引用级联清理 | 现在按时间清理，不能精确判断「某图是否还被引用」 |
| 2 | 图片内容审核（违规识别） | 需接第三方审核服务 |
| 3 | 多实例频率限制 | 单机内存计数，需换 Redis |
| 4 | 私有图片的读取接口 | 上传已隔离，但还没有带鉴权的读取端点（做认证功能时一起做） |
