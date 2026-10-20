# 内容审核与敏感词 · GPT 提示词

> 工程目录：`E:\_verify_completed\campus-life-miniapp`
> 目标：给用户发布的内容加审核，防止违规内容和诈骗信息
> 技术选型：开源敏感词库 + 微信内容安全接口

---

=== 提示词开始 ===

## 一、你的任务

给一个已有的校园生活小程序加上**内容审核**能力。用户发布内容时，系统要能拦住违规文字和违规图片。

**要审的 6 个地方：**

1. **兼职岗位**（最高优先级，诈骗高发）
2. **发帖**（标题 + 正文 + 图片）
3. **评论**（文字）
4. **二手商品**（标题 + 描述 + 图片）
5. **私信**（文字）
6. **圈子名称和简介**（文字）

**明确不做：** 用户昵称、用户头像、活动内容。这三个暂时不审。

## 二、项目背景

- 小程序：微信原生（WXML/WXSS/JS/JSON），AppID `touristappid`，28 个页面
- 后端：Spring Boot 3.2.5 + MyBatis-Plus 3.5.6 + MySQL 8，包名 `com.campuslife`
- 管理端：Vue 3 + Vite + Tailwind
- 数据库：`campus_life`，账号 `root`
- 统一返回 `{code, msg, data}`，成功时 `code=0`
- 项目约定：**读接口兜底、写接口不兜底**（读失败回落本地数据，写失败必须把错误抛给用户）

## 三、动手前必须先读这些文件

1. `backend/.../controller/ForumController.java` —— 发帖、评论接口
2. `backend/.../service/ForumService.java` —— 发帖业务逻辑
3. `backend/.../controller/BizController.java` —— 二手、兼职接口
4. `backend/.../service/BizService.java`
5. `backend/.../service/UploadService.java` —— 上传服务（已有文件类型校验）
6. `backend/.../controller/UploadController.java`
7. `backend/.../controller/ChatController.java` —— 私信
8. `backend/.../config/GlobalExceptionHandler.java` —— 异常处理
9. `backend/.../config/AdminInterceptor.java` —— 管理端鉴权
10. `backend/src/main/resources/application.yml`
11. `backend/src/main/resources/db/` —— 数据库脚本风格（按模块分文件、可重复执行）
12. `web/src/views/Posts.vue` —— 管理端页面写法参考
13. `web/src/router/index.js` —— 菜单注册

## 四、现有结构

**已有但没审核的地方：**

- `post` 表：`id, user_id, title, content, image, tag, circle_id, location, like_count, comment_count, status, created_at, updated_at, view_count, school_id, learn_category`
- `comment` 表、`goods` 表、`job` 表、`chat_message` 表、`circle` 表
- `UploadService` 已有 `detectImageType()`（魔数校验，只认 PNG/JPEG/GIF/WEBP）和 `UploadRateLimiter`（60 秒 20 次），**但没有内容审核**
- 管理端有 `Posts.vue` 等内容管理页，**没有审核页**

**完全没有敏感词代码，也没有接微信内容安全接口。**

## 五、要做什么

### 5.1 引入开源敏感词库

在 `backend/pom.xml` 加依赖：

```xml
<dependency>
    <groupId>com.github.houbb</groupId>
    <artifactId>sensitive-word</artifactId>
    <version>0.2.0</version>
</dependency>
```

**这个库的特点：**
- 基于 DFA 算法，几万词也能毫秒级匹配
- 自带通用词库（涉政、涉黄、辱骂）
- 支持自定义增删词
- 支持忽略空格、全半角、繁简等变体

**基础用法：**

```java
SensitiveWordHelper.contains("文本")   // 是否包含敏感词
SensitiveWordHelper.findAll("文本")    // 找出所有敏感词
SensitiveWordHelper.replace("文本")    // 替换成 *
```

**带自定义词库的用法：**

```java
SensitiveWordBs bs = SensitiveWordBs.newInstance()
    .wordDeny("刷单", "押金", "代写", "返利", "日赚", "包过", "代考")
    .ignoreCase(true)      // 忽略大小写
    .ignoreWidth(true)     // 忽略全半角
    .ignoreNumStyle(true)  // 忽略数字变体
    .init();
```

### 5.2 补充校园诈骗词表

**开源库的通用词库不包含校园诈骗词**，这是必须自己加的。至少覆盖：

```
刷单、押金、保证金、先交钱、垫付、返利、日赚、月入过万、
代写、代考、代课、包过、内部渠道、包录取、
兼职日结高薪、无需经验高薪、加微信私聊、加QQ、
刷信誉、点赞返现、任务佣金、垫付返现
```

**词表要能热更新**：不要把词写死在代码里。建议：

- 建一张 `sensitive_word` 表存自定义词
- 启动时加载，提供管理端接口可以增删
- 加词后不需要重启服务

```sql
CREATE TABLE sensitive_word (
    id bigint PRIMARY KEY AUTO_INCREMENT,
    word varchar(64) NOT NULL,
    category varchar(32) DEFAULT 'custom',   -- fraud/abuse/politics/custom
    enabled tinyint DEFAULT 1,
    created_at datetime DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_word (word)
);
```

### 5.3 接入微信内容安全接口

**文字审核：** `security.msgSecCheck`

```
POST https://api.weixin.qq.com/wxa/msg_sec_check?access_token=ACCESS_TOKEN
Content-Type: application/json

{
  "content": "要检测的文本",
  "version": 2,
  "scene": 2,
  "openid": "用户的openid"
}
```

返回：

```json
{
  "errcode": 0,
  "result": {
    "suggest": "pass",     // pass 通过 / review 需人工 / risky 违规
    "label": 100
  }
}
```

**图片审核：** `security.imgSecCheck`

```
POST https://api.weixin.qq.com/wxa/img_sec_check?access_token=ACCESS_TOKEN
Content-Type: multipart/form-data

media=@图片文件
```

返回 `errcode=0` 表示通过，`errcode=87014` 表示图片违规。

**重要限制：**

- 需要 `access_token`，要拿小程序的 AppID + AppSecret 换取
- 当前项目用的是测试号 `touristappid`，**可能调不通**。必须做降级：调不通就跳过微信审核，只靠本地词库，并在日志里记一笔
- 有调用频率限制，图片审核尤其慢，必须异步

### 5.4 数据库迁移

新建 `backend/src/main/resources/db/module-audit.sql`，**必须可重复执行**（用 `information_schema` 判断字段/表是否存在，参考 `module-learn.sql` 的写法）：

**A. 给要审的表加字段**

给 `post`、`comment`、`goods`、`job`、`chat_message`、`circle` 各加：

```sql
audit_status varchar(16) DEFAULT 'pending'   -- pending/passed/rejected/reviewing
audit_reason varchar(200) NULL               -- 拒绝原因（命中什么词）
audit_at     datetime NULL                   -- 审核时间
```

索引：`audit_status`

**B. 新建敏感词表**

见上面 5.2 的 `sensitive_word`。

**C. 新建审核日志表**

```sql
CREATE TABLE audit_log (
    id bigint PRIMARY KEY AUTO_INCREMENT,
    target_type varchar(32) NOT NULL,   -- post/comment/goods/job/chat/circle
    target_id bigint NOT NULL,
    user_id bigint,
    content_snippet varchar(200),        -- 内容摘要（排障用）
    source varchar(16),                  -- local/wechat/manual
    result varchar(16),                  -- pass/reject/review
    matched_words varchar(200),          -- 命中的敏感词
    created_at datetime DEFAULT CURRENT_TIMESTAMP,
    KEY idx_target (target_type, target_id),
    KEY idx_created (created_at)
);
```

### 5.5 审核服务

新建 `AuditService`，对外提供三个方法：

```java
// 文字审核（同步）
AuditResult checkText(String text, String targetType);

// 图片审核（异步）
void checkImageAsync(String imageUrl, String targetType, Long targetId);

// 批量图片
void checkImagesAsync(List<String> urls, String targetType, Long targetId);
```

**`AuditResult` 结构：**

```java
class AuditResult {
    boolean pass;
    String reason;        // 拒绝原因
    List<String> words;   // 命中的词
}
```

**审核流程（文字）：**

```
1. 本地词库匹配
   命中 → 直接返回不通过，reason="包含违规词：xxx"

2. 调微信 msgSecCheck（如果 enabled）
   suggest=risky → 不通过
   suggest=review → 标记为待人工审核（reviewing）
   suggest=pass → 通过

3. 微信调不通 → 记录日志，按通过处理（降级）
```

**审核流程（图片）：**

```
1. 内容发布时，图片先标 audit_status='pending'
2. 异步线程池送微信 imgSecCheck
3. 通过 → 改 passed
4. 违规 → 改 rejected + 自动下架（status=0）+ 通知管理员
```

### 5.6 各接口接入审核

**必须在这 6 个写入点加审核钩子：**

| 接口 | 审什么 | 方式 |
|---|---|---|
| `POST /api/posts` | title + content | 同步 |
| `POST /api/posts/{id}/comments` | content | 同步 |
| `POST /api/goods` | title + description | 同步 |
| `POST /api/jobs`（或管理端创建） | title + description | 同步 |
| `POST /api/chat/messages` | content | 同步 |
| `POST /api/circles` | name + description | 同步 |

**图片：** `POST /api/posts`、`POST /api/goods` 里的 `image`/`images` 字段，走异步。

**审核不通过时返回：**

```json
{
  "code": 4001,
  "msg": "内容包含违规词：刷单，请修改后重试",
  "data": null
}
```

前端收到后要明确提示用户，不要让用户以为发成功了。

### 5.7 管理端审核页（重点，要完整做）

管理端是这个功能的**操作台**，不能只做展示。运营要靠它处理违规内容、维护词库。

#### 5.7.1 页面位置与注册

- 新建页面：`web/src/views/Audit.vue`
- 注册路由：在 `web/src/router/index.js` 的菜单数组里加一项

```js
{
  path: '/audit',
  name: 'Audit',
  component: () => import('../views/Audit.vue'),
  meta: { title: '内容审核', icon: '✓', superOnly: false }
}
```

**`superOnly: false`** —— 学校管理员也要能用（只管自己学校的），不要设成超管专属。

#### 5.7.2 页面整体布局

从上到下三段：

```
┌─────────────────────────────────────────┐
│ 标题：内容审核                           │
│ 副标题：处理违规内容 · 维护敏感词        │
├─────────────────────────────────────────┤
│ 统计卡片（4 个横排）                     │
│ 今日审核 | 待人工审核 | 已拒绝 | 敏感词数 │
├─────────────────────────────────────────┤
│ Tab: 待人工审核 | 已拒绝 | 敏感词管理    │
├─────────────────────────────────────────┤
│ 内容区（随 tab 切换）                    │
└─────────────────────────────────────────┘
```

#### 5.7.3 统计卡片

四个卡片，每个显示数字 + 标签：

| 卡片 | 数据来源 | 点击行为 |
|---|---|---|
| 今日审核 | 今天 `audit_at` 不为空的总数 | 无 |
| 待人工审核 | `audit_status='reviewing'` 总数 | 切到「待人工审核」tab |
| 已拒绝 | `audit_status='rejected'` 总数 | 切到「已拒绝」tab |
| 敏感词数 | `sensitive_word` 启用中的总数 | 切到「敏感词管理」tab |

**配色**：待人工审核用橙色（需要关注），已拒绝用红色，其余用中性色。

#### 5.7.4 Tab 一：待人工审核

**筛选栏**（表格上方）：

- 内容类型下拉：全部 / 发帖 / 评论 / 二手 / 兼职 / 私信 / 圈子
- 关键词搜索框（搜内容摘要）
- 日期范围选择（提交时间）
- 重置按钮

**表格列：**

| 列 | 内容 | 说明 |
|---|---|---|
| 类型 | 彩色标签 | 发帖/评论/二手/兼职/私信/圈子 |
| 内容摘要 | 最多 60 字，超出省略号 | 点击展开完整内容 |
| 命中词 | 命中的敏感词，红色标签 | 没有则显示「微信判定」 |
| 提交人 | 用户昵称 + ID | 点击可跳用户详情 |
| 学校 | 所属学校 | 校管只能看本校 |
| 提交时间 | `yyyy-MM-dd HH:mm` | 按时间倒序 |
| 操作 | 三个按钮 | 通过 / 拒绝 / 查看 |

**操作按钮：**

1. **通过** —— 直接把 `audit_status` 改成 `passed`，二次确认弹窗「确认通过这条内容？」
2. **拒绝** —— 弹窗填写拒绝原因（必填，最多 200 字），确认后改 `rejected` + 下架内容 + 通知用户
3. **查看** —— 抽屉或弹窗展示完整内容（含图片）

**批量操作**：表格左侧加复选框，顶部出现「批量通过 / 批量拒绝」按钮。

**分页**：每页 20 条，底部显示「共 N 条」。

**空状态**：「暂时没有待审核内容」+ 一个绿色对勾图标。

#### 5.7.5 Tab 二：已拒绝

**表格列：**

| 列 | 内容 |
|---|---|
| 类型 | 同上面 |
| 内容摘要 | 同上面 |
| 拒绝原因 | 运营填的原因 |
| 拒绝时间 | `audit_at` |
| 操作 | 恢复 / 查看 |

**恢复**：二次确认后把 `audit_status` 改回 `passed`，并恢复内容上架（`status=1`）。

**筛选**：类型、日期范围、关键词。

#### 5.7.6 Tab 三：敏感词管理

**顶部**：搜索框 + 「新增词」按钮 + 「批量导入」按钮 + 分类筛选

**表格列：**

| 列 | 内容 |
|---|---|
| 敏感词 | 词本身 |
| 分类 | 标签：诈骗(fraud) / 辱骂(abuse) / 涉政(politics) / 自定义(custom) |
| 状态 | 开关（启用/停用） |
| 创建时间 | |
| 操作 | 编辑 / 删除 |

**新增词弹窗：**

- 输入框：敏感词（必填，最多 64 字）
- 下拉：分类（fraud/abuse/politics/custom）
- 说明文字：「添加后立即生效，不需要重启服务」

**批量导入：**

- 一个多行文本框，每行一个词
- 或上传 txt 文件
- 导入后显示「成功 N 个，跳过重复 M 个」

**删除**：二次确认。

**重要**：增删改后必须**立即刷新后端内存中的词库**，不能等重启。

#### 5.7.7 后端管理接口（配套实现）

在 `AdminController` 或新建 `AdminAuditController`（`@RequestMapping("/api/admin")`，走 `AdminInterceptor` 鉴权）：

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| GET | `/audit/list` | `type, status, keyword, startDate, endDate, page, size` | 审核列表（分页） |
| POST | `/audit/{type}/{id}/pass` | — | 通过 |
| POST | `/audit/{type}/{id}/reject` | `{reason}` | 拒绝 |
| POST | `/audit/{type}/{id}/restore` | — | 恢复已拒绝的 |
| GET | `/audit/stats` | — | 统计卡片数据 |
| GET | `/audit/detail/{type}/{id}` | — | 查看完整内容 |
| GET | `/sensitive-words` | `category, keyword, page, size` | 词库列表 |
| POST | `/sensitive-words` | `{word, category}` | 新增 |
| PUT | `/sensitive-words/{id}` | `{word, category, enabled}` | 编辑 |
| DELETE | `/sensitive-words/{id}` | — | 删除 |
| POST | `/sensitive-words/import` | `{words: []}` | 批量导入 |

**返回格式**：统一 `{code, msg, data}`。

**列表返回结构：**

```json
{
  "code": 0,
  "data": {
    "list": [
      {
        "id": 123,
        "type": "post",
        "typeName": "发帖",
        "snippet": "想找人代课，一次50...",
        "matchedWords": ["代课"],
        "userId": 1,
        "userName": "张三",
        "schoolId": 1,
        "schoolName": "内蒙古大学",
        "auditStatus": "reviewing",
        "createdAt": "2026-10-19 21:30"
      }
    ],
    "total": 45,
    "page": 1,
    "size": 20
  }
}
```

**学校管理员的数据隔离**：校管只能看到自己学校的（`school_id = 当前管理员的学校`），超管看全部。

#### 5.7.8 样式要求

**不要引入 Element Plus**（项目刻意没用）。

复用项目已有的风格：

- 卡片：`bg-white rounded-[2rem] shadow-sm p-6`
- 主色：`#3476f6`
- 文字：主 `#18212b`，次 `#737e88`
- 表格：参考 `web/src/views/Posts.vue` 的写法
- 弹窗：用项目已有的 `NoModal` 组件，不要用原生 `alert`/`confirm`
- 标签：`px-2 py-1 rounded text-xs`

**状态标签配色：**

| 状态 | 颜色 |
|---|---|
| pending（待审） | 灰 `bg-gray-100 text-gray-600` |
| reviewing（待人工） | 橙 `bg-orange-100 text-orange-700` |
| passed（通过） | 绿 `bg-green-100 text-green-700` |
| rejected（拒绝） | 红 `bg-red-100 text-red-700` |

#### 5.7.9 交互细节

- 所有写操作（通过/拒绝/删除/恢复）都要**二次确认**
- 操作成功后**局部刷新**，不要整页 reload
- 操作失败要显示后端返回的 `msg`，不要只说「操作失败」
- 表格加载时显示骨架屏或 loading 遮罩
- 拒绝原因弹窗要限制字数并显示剩余字数

### 5.8 小程序端提示

审核不通过时，用现有的 `utils/ui-popup-helper.js` 弹提示，不要用 `wx.showToast`：

```js
try {
  await api.createPost({ title, content })
  popup.toast(this, '发布成功', { tone: 'success' })
} catch (error) {
  // 审核不通过的错误要原样显示给用户
  popup.toast(this, error.message || '发布失败', { tone: 'error' })
}
```

## 六、三条原则

### 原则一：文字同步、图片异步

文字审核快（本地词库毫秒级，微信接口几百毫秒），用户能等。

图片审核慢（微信审图可能几秒），不能让用户等，必须异步。发布时先标记 `pending`，审完再改状态。

### 原则二：宁可误拦，不要漏放

审核不通过时，**默认拒绝**，让用户改。不要为了体验放过去。

特别是兼职板块——诈骗信息一旦发出去，学生真的会交钱。

### 原则三：必须能降级

微信接口可能挂、可能限流、测试号可能调不通。任何一种情况都不能让用户发不了内容。

```java
try {
    // 调微信
} catch (Exception e) {
    log.warn("微信审核不可用，降级为仅本地词库", e);
    // 按通过处理，但要记日志
}
```

## 七、验收标准

| # | 检查项 | 通过标准 |
|---|---|---|
| 1 | 后端能编译 | Maven BUILD SUCCESS |
| 2 | 迁移脚本可重复执行 | 连跑两次不报错 |
| 3 | 本地词库生效 | 发帖含「刷单」，直接拒绝并提示 |
| 4 | 自定义词可加 | 管理端加词后，立刻能拦住（不用重启） |
| 5 | 微信审核生效 | 配了 AppID 后，语义违规内容被拦 |
| 6 | 微信挂了能降级 | 断网时仍能发帖（只靠本地词库） |
| 7 | 图片异步审核 | 发带图帖，图片状态从 pending 变 passed |
| 8 | 图片违规自动下架 | 上传违规图，帖子自动 status=0 |
| 9 | 6 个地方全覆盖 | 发帖/评论/二手/兼职/私信/圈子都能拦 |
| 10 | 管理端能审核 | 待审列表能看到、能通过、能拒绝 |
| 11 | 前端提示正确 | 被拦时用户看到明确原因，不是「发布失败」 |
| 12 | 昵称头像不审 | 改昵称、换头像不受影响（明确不审） |

## 八、不要做的事

- 不要审用户昵称和头像（明确不做）
- 不要审活动内容（明确不做）
- 不要把敏感词写死在代码里（要能热更新）
- 不要在图片审核时阻塞用户请求
- 不要因为微信接口挂了就让用户发不了内容
- 不要用 `wx.showToast`（项目统一用 `ui-popup-helper`）
- 不要改现有接口的返回格式（`{code, msg, data}`）
- 不要动 `UploadService` 已有的魔数校验和限流逻辑

## 九、交付顺序

1. 先建表（`module-audit.sql`）+ 敏感词表
2. 再做 `AuditService`，先只接本地词库（不依赖微信）
3. 给 6 个接口挂钩子，跑通「命中就拒绝」
4. 再接微信 `msgSecCheck`，带降级
5. 再做图片异步审核
6. 最后做管理端审核页

**这样即使微信接口调不通，前 3 步也能完整交付。**

=== 提示词结束 ===

---

## 附：给你的说明

### 为什么必须先做本地词库

微信接口有三个问题：可能调不通（测试号）、有限流、有延迟。

本地词库是**唯一 100% 可控**的防线。先把这条做扎实，微信接口只是补充。

### 为什么兼职要单独强调

校园兼职是诈骗重灾区。常见套路：

- 「日结 200，只需手机操作」→ 刷单诈骗
- 「先交 99 押金，做满 10 单退」→ 押金诈骗
- 「帮老师代课，一次 50」→ 违反校规

这些词的组合，通用词库一个都拦不住，必须自己加。

### 一个建议

管理端「敏感词管理」页做好后，可以让学生也参与举报。学生看到可疑兼职点举报，你审核后一键把相关词加进词库，形成正循环。

### 上线前必查

1. 微信小程序的 AppSecret 配了吗（调 msgSecCheck 要用）
2. 测试号可能没有内容安全权限，正式号才有
3. 微信审核接口有免费额度，超了要付费
