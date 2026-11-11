# 校园生活社区后端

技术栈：Java 17、Spring Boot 3.2.5、MyBatis-Plus 3.5.6、MySQL 8.0、Lombok。此次没有新增第三方依赖。

## 本地启动

1. 启动 MySQL 8.0。全新数据库依次**手动**执行：
   - `backend/src/main/resources/db/schema.sql`（原有 20 张表与演示种子）
   - `backend/src/main/resources/db/module-messages-academics.sql`（消息/教学，+6 张）
   - `backend/src/main/resources/db/module-circles.sql`（圈子，+2 张并给 post 加 circle_id）
   - `backend/src/main/resources/db/module-circle-chat.sql`（圈子群聊，+2 张并给 circle 加 last_message 字段）
   - `backend/src/main/resources/db/module-auth.sql`（给 user 加 openid）
   共 30 张表。已有数据库只执行尚未跑过的脚本即可，上述脚本都必须按顺序执行。

   **校园帮功能（兼职 / 配送 / 圈子火花 / 认证）新增脚本**，全部幂等，按序执行：

   - `db/module-safety-gates.sql`（6 个安全闸门，**全部默认关闭**：校外岗位、有偿接单、替课、自由抢单、通用钱包、自助认证）
   - `db/module-verification.sql`（`user_verification`：学生与实名核验**结论**，不含证件原文）
   - `db/module-circle-streak.sql`（`circle_activity_day` + circle 活跃列）
   - `db/module-rider-dispatch.sql`（`building` 楼栋责任区、`order_dispatch_log`、user_order 派单列）
   - `db/module-earnings-ledger.sql`（`earnings_ledger`：履约账单，**不是钱包余额、不可提现**）
   - `db/module-jobs-marketplace.sql`（job 扩列 + `job_application` / `job_contract` / `job_dispute`）

   > 注意：`feature_flag` 有 30 秒本地缓存，改开关**必须**用管理端接口
   > `PUT /api/admin/feature-flags/{key}`（会立即失效缓存）；直接改数据库测出来的结论不可信。

2. 数据库账号密码不要写在 `application.yml` 里。配置按 profile 分开：
   - 本机开发直接用 `application-dev.yml` 的默认值（127.0.0.1 / root / 演示密码），也可以覆盖环境变量 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`。
   - 生产必须 `--spring.profiles.active=prod`，此时 `application-prod.yml` 的敏感项全部没有默认值，缺环境变量会**启动失败**（这是故意的）。
3. 进入 `backend` 目录执行 `mvn spring-boot:run`（默认 profile = dev），接口地址为 `http://127.0.0.1:8080/api`。
4. 用微信开发者工具打开项目根目录，AppID 保持 `touristappid`。本地开发配置已关闭合法域名校验。

> 接口鉴权已收紧：写操作与「我的数据」读接口必须带 token（否则 401），身份一律以 token 为准，客户端传的 `userId` 不再可信。
> 完整规则（哪些接口要 token、白名单、多校隔离）见 `docs/部署与上线指南.md`；
> 环境变量模板见根目录 `.env.example`。

### 编译注意（JDK 版本与 Lombok）

在 JDK 23 及以上编译需要两个条件，否则 Lombok 不生成 getter/setter，会报大量「找不到符号」：

1. `pom.xml` 已配置 `maven-compiler-plugin` 的 `-proc:full`（JDK 23 起 javac 默认不再自动启用注解处理）。
2. 若本机 Maven 的 `settings.xml` 中有 `activeByDefault` 的 `jdk-1.8` profile，它会覆盖项目的 `java.version`，
   需要禁用：`mvn clean compile -P '!jdk-1.8'`，或在 settings.xml 中移除该 profile。
3. Lombok 需 1.18.48 或更高（低版本在 JDK 25 上报 `TypeTag :: UNKNOWN`），`pom.xml` 已固定该版本。

项目不会自动执行 SQL。新增脚本使用 `CREATE TABLE IF NOT EXISTS` 和带唯一键的 `INSERT IGNORE`，重复执行不会覆盖聊天、已读、免打扰或课程数据。原 `schema.sql` 的部分旧种子 INSERT 不具备幂等性，不要把完整 schema 当作每次升级脚本重复执行。`module-food.sql` 和 `module-market-jobs-activities.sql` 是原有模块的独立脚本；完整 schema 已包含这些模块，新库无需再重复执行。

可从项目根目录手动运行以下命令（需安装对应运行环境）：

```bash
mysql -u root -p < backend/src/main/resources/db/schema.sql
mysql -u root -p < backend/src/main/resources/db/module-messages-academics.sql
mysql -u root -p < backend/src/main/resources/db/module-circles.sql
mysql -u root -p < backend/src/main/resources/db/module-circle-chat.sql
mysql -u root -p < backend/src/main/resources/db/module-auth.sql
mysql -u root -p < backend/src/main/resources/db/module-hotness.sql
cd backend
mvn clean package
mvn spring-boot:run -P "!jdk-1.8"
```

## 全站搜索

`GET /api/search/all?keyword=&type=&page=&size=&userId=`

- `type` 取值：`all`（混排，默认）/ `post` 帖子 / `circle` 圈子 / `goods` 闲置 / `activity` 活动 / `job` 兼职 / `food` 外卖（店铺 + 菜品）
- 返回 `counts`（各类命中数）、`list`、`total`。混排按 帖子→圈子→商品→活动→兼职→外卖 顺序各取若干条穿插，一次给全不翻页；单类模式按 `total` 翻页。
- 关键词语义与前端 `keywordMatcher` 一致：`%` 匹配任意多字符、`_` 匹配单字符、`\` 转义。
- **所有关键词都走 `#{}` 参数绑定**，不做字符串拼接。
- 旧的 `GET /api/search` 只搜帖子，保留给既有调用方。

## 首页今日推荐（真实热度）

`GET /api/recommend/home?userId=&batch=&perType=`

- 「外卖最火 + 二手最火」，`batch` 递增实现「换一批」轮换（按总数取模循环）。
- **热度由真实用户行为累加，不是手填的**：
  - 闲置：`浏览×3 + 收藏×5 + 咨询×8`
  - 外卖：`真实订单×5 + 店铺浏览×1`
- 埋点接口：`POST /goods/{id}/view`、`/goods/{id}/favorite`、`/goods/{id}/contact`、`/shops/{id}/view`；
  下单成功后由 `BizService.order()` 在同一事务内累加店铺订单数。
- `shop.monthly_sales` 是平台侧大盘数据，不是本小程序产生的，**所以不用于热度排序**；排序用的是 `order_count`。
- 冷启动时 `module-hotness.sql` 会给闲置商品回填一个很小的基础浏览数，之后完全由真实行为驱动。
- 点击埋点分散在页面里（商品详情打开时上报浏览、点收藏/私聊时上报），失败不影响页面。

## 消息与教学数据

- 消息记录存于 `chat_message`，会话列表由双方消息聚合。路由里的 `id` / 接口里的 `peerId` 都是**对方用户 ID**。
- `chat_preference` 保存每个用户的清空游标和免打扰设置，清空只影响操作用户，未物理删除对方记录。
- `notification` 保存通知与已读状态，点赞、评论、关注、收藏会生成相关通知；GET 返回最近 100 条。
- `academic_term` 按学校维护学期，`course_schedule` 按用户、学期和周次维护课程，`calendar_event` 保存学期事件。默认学期按用户资料的 school 匹配最新 active=1 的配置。
- SQL 中的数据是演示种子。没有接入外部教务平台；管理员需维护这些表。若换学期，新增学期和对应课表/事件，确认同校 active 配置正确。
- 聊天只新增文本发送；种子图片可展示，没有扩展为图片/语音发送。
- 前端聊天在可见时每 5 秒轮询；隐藏/卸载时停止。

## 登录鉴权与上传

- `POST /api/auth/login` 用 `wx.login()` 的 code 换 JWT；未配置微信 AppID 时改用 `POST /api/auth/dev-login`（`campus.wx.appid` 为空即启用）。
- token 通过 `Authorization: Bearer <token>` 传递。`AuthInterceptor` 解析后放入请求属性，**解析失败不阻断请求**——这是刻意的「轻量鉴权」，保证无后端/未登录时演示流程照常。
- 生产环境请把 `campus.jwt.secret` 换成环境变量注入的随机值，并按接口分级收紧授权（当前任意伪造 token 只会降级为匿名，不会报错）。
- `POST /api/upload` 单图上传（`name=file`，≤5MB，仅 jpg/png/gif/webp），返回 `/uploads/xxx.ext`；文件名由服务端生成，不使用客户端提供的名字。`/uploads/**` 已映射为静态资源。
- 上传的图片目前没有清理机制，会持续累积，生产需要补充定期清理。

## 圈子

- `circle` 存圈子，`circle_member` 存成员关系，`post.circle_id` 为归属（可为 NULL，表示不属于任何圈子，仍进全校流）。
- `member_count` / `post_count` 是冗余计数，在加入/退出/发帖时于同一事务内重算（`syncMemberCount` / `syncPostCount`）。
- 圈子由管理员通过 SQL 或后台创建，小程序端只能加入/退出，没有创建入口。
- `GET /api/recommend` 的排序在 SQL 内完成：`(赞×3 + 评论×5 + 浏览×1) × 兴趣加权 × 时效衰减`。
  兴趣加权取自用户已加入的圈子。该表达式无法走索引，数据量大时需要改为离线预计算。

## 圈子群聊（WebSocket）

- 群聊与一对一私信完全分离：新增 `circle_message`（消息）和 `circle_read`（每人每圈的已读游标），不改动 `chat_message`。
- 写消息只走 HTTP，WebSocket 只负责推送，因此审核失败、断线重试都有明确的成功/失败返回：
  - `GET /api/circle-messages?circleId=&limit=&beforeId=&afterId=` 历史/增量；`beforeId` 向前翻页，`afterId` 用于重连补消息，返回始终按 id 升序。
  - `POST /api/circle-messages` 发送（`circleId/type/content/image/clientId`），`clientId` 由客户端生成，同一发送者重复提交不会重复入库或重复推送。
  - `POST /api/circle-messages/read` 标记已读（`circleId/lastReadId`）。
  - `GET /api/circle-messages/unread` 返回当前用户在所有已加入圈子的未读数。
  - `GET /api/circles` 的每个圈子额外返回 `unreadCount`、`lastMessage`、`lastMessageAt`。
- WebSocket 端点为 `ws://127.0.0.1:8080/ws/circle?token=<JWT>&circleId=<圈子ID>`。连接时校验 token、圈子 `status=1` 和 `circle_member` 成员关系，任一不满足会收到 `{"type":"error"}` 后按 1008 关闭。
- 服务端每 30 秒推送 `{"type":"ping"}`，90 秒未收到客户端 `pong` 会关闭连接；小程序端在 `app.js` 统一维护连接、25 秒心跳、3 秒退避重连（最多 5 次），页面只订阅事件。
- 消息字段：`{type:"message",data:{id,circleId,senderId,senderName,senderAvatar,messageType,content,image,createdAt}}`；另有 `online`（在线人数与名单）、`join`、`leave`、`error`。
- 内容审核先走本地词库，配置了 `campus.wx.appid/secret` 时再调用微信 `msg_sec_check`；`touristappid` 开发环境只走本地词库。
- 小程序真机必须使用 `wss://`。`app.js` 会根据 `utils/config.js` 的 `BASE_URL` 自动切换 ws/wss，上线时把 `utils/config.js` 的 `ENV` 改成 `'prod'` 并填真实 HTTPS 域名，同时在微信公众平台配置合法域名。

## 教务数据

各高校教务系统没有公开接口，字段也各不相同，因此**没有做「假装对接」**。
改为管理员把教务导出的课表整理成 CSV 后调 `POST /api/admin/timetable/import` 批量导入：

```
课程名,教室,星期,节次,起始周,结束周[,配色]
数据结构与算法,博学楼 A301,周一,0,1,16
```

星期支持「周一」或数字 1-7，节次 0-9（0 表示第一大节）。
**任一行有错则整批拒绝**，返回带行号的错误清单，不写入半截数据；`replace=true` 时先清空该用户本学期课表。
将来拿到真实教务接口，只需替换该方法的数据来源，接口形状不变。

## 管理端兼职与履约（校园帮 §9）

管理端页面：`#/jobs`（`web/src/views/Jobs.vue`）。

- `GET /api/admin/jobs?keyword=&pending=&page=&size=`
  岗位列表（含待审核）；`school` 角色强制只看本校，`super` 可看全部。
- `POST /api/admin/jobs/{id}/review` `{approve, reason}`
  审核通过 / 下架。**在途履约单（taken/working/pending_confirm/disputed）只停新报名，不冻结结算**。
- `POST /api/admin/jobs/{id}/fee` `{feeMode, feeValue}`
  逐岗位服务费；只在接单/录用时快照，改价不影响旧单。
- `GET /api/admin/jobs/contracts?status=&jobId=&page=&size=`
  履约单处理台列表：带双方昵称、争议、工时/计价数量、**报酬与费率快照**（取 `job_contract.*_snapshot`，不是岗位当前值）。
- `GET /api/admin/jobs/contracts/{id}`
  单张履约单详情：账单流水、争议、结算估算。
- `POST /api/admin/jobs/contracts/{id}/arbitrate` `{decision: settled|refunded, hours, reason}`
  争议裁定。**settled 必须显式传 hours**：双方申报不一致时系统不自动采信任一方；裁定写 `admin_log`。

权限与边界：

- 以上接口都在 `/api/admin/**`，`AdminInterceptor` 只放行 `super|school`；`school` 的学校范围从登录态取，不接受前端传入。
- 功能开关 `PUT /api/admin/feature-flags/{key}` **仅超级管理员**可改：`feature_flag` 是全局开关，校管能打开校外招聘/有偿接单会造成跨校合规风险。
- 履约账单（`earnings_ledger`）不是钱包余额、不是已付款、不可提现；页面文案已同步。

## 认证审核的学校范围

- `GET /api/admin/verifications`、`POST /api/admin/verifications/{userId}/review` 对 `school` 角色按 `user.school_id` 限定本校；跨校审核返回 403。
- 核验结论只存 `user_verification`（不存证件原文），`provider=manual_admin`。

## 验证

项目根目录运行：

```bash
node --test tests/regression.test.js      # 22 项前端行为回归
node --test tests/circle-chat.test.js     # 圈子群聊接口与全局 socket 行为
python tests/validate_project.py          # 静态工程检查
python tests/backend_smoke.py             # 需先起后端；只发 GET，不写业务数据
```

本轮已在本机完成：后端编译（JDK 25 + Lombok 1.18.48 + `-P '!jdk-1.8'`）、真实 MySQL 联调、
58 个端点的实调（20 个核心接口全通过）、上传与鉴权的安全边界验证。22 项行为测试与静态检查通过。

界面视觉效果、真机预览仍需在微信开发者工具中人工确认。
生产仍需：管理端鉴权与 RBAC、内容审核、HTTPS 与微信 request 合法域名配置。
