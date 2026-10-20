# campus-life-miniapp 补齐交付报告

交付日期：2026-10-14。代码已完成，前端行为和工程静态检查通过；后端构建、真实数据库/HTTP 和微信视觉验收尚未在本环境完成。

## 1. 开工前的确认

项目是原生微信小程序与 Spring Boot 分离架构，原有21页与32个后端端点。页面主要通过 utils/api.js 取数，后端用 ApiResponse 统一包装。小程序使用 custom 导航，新增页必须处理状态栏与胶囊避让。无后端时的读演示属于既有特性，写接口的服务端拒绝必须显式保留。此次以补缺为目标，没有替换框架或增加第三方依赖，图片继续复用原37个本地资产。

推进顺序：先完成路由、搜索和实际入口，再补齐消息/教学数据与离线标识，最后处理错误透传、死代码、文档和验证，因为先接通完整用户链路能尽早暴露真实的数据/参数缺口。

### 文档与代码不一致

| 原说法 | 实際代码核对 |
|---|---|
| 发现页搜索只弹 toast | 旧版实际对当前已加载列表做本地过滤；缺完整接口、结果页和分页 |
| 消息页已有真实通知 | getNotifications/getConversations 直接 wait(mock)，没有请求；本次才新增真实数据链路 |
| followUser 仅收 query | 已有可选 @RequestBody，body.userId 优先于 query.userId，无参数位置冲突 |
| 首页6条路由 | 实际7条，任务正文列举正确 |
| 路由“无兜底分支” | 有末尾 toast，缺少开发者日志 |
| 9个真实占位入口 | 清单将无绑定 switchTab 和运营 banner 待配置也计入，实际不能作为9个可点击按钮看待 |
| schema.sql 有五表/13表/21表 | 原文件实数20表；本次新增6表，共26表 |
| 所有写接口透传服务端拒绝 | 多个旧写函数直接 .catch(()=>wait(...)) 吞掉业务错误；本次补上 guard 与调用页反馈 |
| 后端不启动“所有功能依然可用” | 原投票、资料修改等本来需要后端；现在仍如实保留该边界 |

## 2. 逐任务实现

下面路径是交付工作区的完整路径；解压后相应文件位于校园小程序根目录内。新增文件全文及修改文件 diff 全部在本报告末尾，补丁另存 docs/CHANGESET.patch。

### 任务1：首页路由表（P0-1）

文件：
- `/workspace/scratch/4abc3effa72e/project/pages/index/index.js`
- `/workspace/scratch/4abc3effa72e/project/pages/index/index.wxml`

FEATURE_ROUTES 以 data-label 原文为 key，静态路由只需一行配置。动态台灯用 action 查到真实在售 id 后跳转。未知 key 使用 hasOwnProperty 避免原型属性误命中，打印“该入口未配置路由”，并用 ui-popup 给出合理提示。原七条 URL 保持逐字符不变，回归测试验证。

原 index.wxml 的全部 data-label 值依出现顺序为：

`开学优惠`, `外卖`, `二手市场`, `校园论坛`, `兼职`, `季节活动`, `作业答疑`, `论文指导`, `学习好物`, `奶茶优惠`, `闲置台灯`, `联想小新校园优惠`

改后 index.wxml 的全部字面值为：

`开学优惠`, `外卖`, `二手市场`, `校园论坛`, `兼职`, `季节活动`, `作业答疑`, `论文指导`, `学习好物`, `{{item.label}}`, `联想小新校园优惠`

`{{item.label}}` 从 recommendations 获取，只会取“奶茶优惠”和“闲置台灯”，均有表项；因此实际覆盖标签仍为以下12个，没有遗留未配置首页标签。

| 标签 | 路由/处理 |
|---|---|
| 校园论坛 | /pages/community/community |
| 外卖 | /pages/food-list/food-list |
| 二手市场 | /pages/market-list/market-list |
| 兼职 | /pages/jobs/jobs |
| 季节活动 | /pages/activities/activities |
| 作业答疑 | /pages/community/community?tab=2 |
| 论文指导 | /pages/community/community?tab=4 |
| 开学优惠 | /pages/activities/activities |
| 学习好物 | /pages/market-list/market-list?category=学习好物；目标映射为已有图书分类 |
| 奶茶优惠 | /pages/food-shop/food-shop?id=1；原 SQL 与 mock 均存在茶言观色奶茶店1 |
| 闲置台灯 | 查询出售列表的真实台灯 id；原种子为1，缺货则数码市场 |
| 联想小新校园优惠 | /pages/campus-offer/campus-offer |

通知铃铛与换一批没有 data-label，分别绑定 openNotifications 和 changeRecommendations。未知/空标签明确落入 warn+提示。

### 任务2：搜索（P0-2）

文件：
- `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/controller/ForumController.java`
- `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/service/ForumService.java`
- `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/config/GlobalExceptionHandler.java`
- `/workspace/scratch/4abc3effa72e/project/utils/api.js`
- `/workspace/scratch/4abc3effa72e/project/pages/search/search.js`
- `/workspace/scratch/4abc3effa72e/project/pages/search/search.wxml`
- `/workspace/scratch/4abc3effa72e/project/pages/search/search.wxss`
- `/workspace/scratch/4abc3effa72e/project/pages/search/search.json`
- `/workspace/scratch/4abc3effa72e/project/pages/index/index.js`
- `/workspace/scratch/4abc3effa72e/project/pages/index/index.wxml`
- `/workspace/scratch/4abc3effa72e/project/pages/discover/discover.js`
- `/workspace/scratch/4abc3effa72e/project/app.json`
- `/workspace/scratch/4abc3effa72e/project/docs/API_INTEGRATION.md`

- GET /api/search 必填 keyword；trim 后为空或超过64返回 HTTP400/code400，非法数字/缺参数也使用统一400包装。page至少1，size为1–50，userId默认1并按原帖子端点夹取至少1。
- ForumService.search 使用 status=1 AND (title LIKE ? OR content LIKE ?)；两个 .like 都由 MyBatis-Plus 参数绑定，没有拼接关键词 SQL。按创建时间+ID倒序，复用原帖子流 DTO/作者/点赞/关注拼装函数，data 只有 list/total。
- 新搜索页沿用社区样式、getNavigationMetrics 与 ui-popup，包含输入、首屏加载、空态、失败重试、分页按钮/触底、下拉刷新和详情跳转。请求序号与卸载标志防止旧结果覆盖新词。网络与真实数据源切换时重新读第一页，避免混合分页。
- 首页 submitSearch 和发现页 openSearch 都 encodeURIComponent 后跳转。微信页面参数已解码，搜索页不再次解码，保留用户输入的 %xx。
- searchPosts 用 request + catch：网络失败才在 mock.discoveryPosts 和本机演示发布帖子上按标题/正文分别匹配。分页、大小写、%/_、反斜线转义均有行为测试；业务错误保留失败态。数据库 collation 对部分重音字符可能与 JS 匹配有差异，未声称完全复刻全部排序规则。

### 任务3：占位入口（P1）

文件：
- `/workspace/scratch/4abc3effa72e/project/pages/index/index.js`
- `/workspace/scratch/4abc3effa72e/project/pages/index/index.wxml`
- `/workspace/scratch/4abc3effa72e/project/pages/market-list/market-list.js`
- `/workspace/scratch/4abc3effa72e/project/pages/messages/messages.js`
- `/workspace/scratch/4abc3effa72e/project/pages/messages/messages.wxml`
- `/workspace/scratch/4abc3effa72e/project/pages/campus-offer/campus-offer.js`
- `/workspace/scratch/4abc3effa72e/project/pages/campus-offer/campus-offer.wxml`
- `/workspace/scratch/4abc3effa72e/project/pages/campus-offer/campus-offer.wxss`
- `/workspace/scratch/4abc3effa72e/project/pages/campus-offer/campus-offer.json`
- `/workspace/scratch/4abc3effa72e/project/pages/discover/discover.js`
- `/workspace/scratch/4abc3effa72e/project/pages/discover/discover.wxml`
- `/workspace/scratch/4abc3effa72e/project/app.json`

已逐一核验 activities、market-list、food-shop、market-detail、messages 页面存在且注册。food-shop/market-detail 读取 id，messages 新增读取 tab，market-list 新增读取 category；原页面忽略分类参数的问题已解决。学习好物是首页文案，不虚构数据库分类，实际选图书。台灯不硬编码未知 id：先查询在售列表，找到后直达详情；没找到进数码列表。店铺1来自两份真实种子，若用户后来删除/下架，店铺页保留已有错误态。

联想广告接校园数码专题，复用现有笔记本图片，说明官方活动链接未配置，不虚构折扣、资格或外链。专题提供校园数码闲置入口，并说明与品牌官方活动无关联。换一批真正轮换两条本地推荐数组，WXML 由数组渲染，顺序实际变化。

确认 app.json 注册的是 pages/index/index；首页 WXML 未绑定 switchTab，全部源码没有调用它，底部组件自行 navigate，因此删除 switchTab。根目录 index.js/api.js 也没有 require、import、页面注册或事件绑定引用，已删除。发现页本地评选 banner 接真实评选页；运营显式配置 linkType=none 的提示仍是合法降级。

### P2：消息、通知、课表和校历

文件：
- `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/controller/CampusController.java`
- `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/service/CampusService.java`
- `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/mapper/CampusMapper.java`
- `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/dto/SendMessageRequest.java`
- `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/service/ForumService.java`
- `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/service/UserService.java`
- `/workspace/scratch/4abc3effa72e/project/backend/src/main/resources/db/module-messages-academics.sql`
- `/workspace/scratch/4abc3effa72e/project/utils/api.js`
- `/workspace/scratch/4abc3effa72e/project/utils/mock-data.js`
- `/workspace/scratch/4abc3effa72e/project/utils/term.js`
- `/workspace/scratch/4abc3effa72e/project/pages/chat/chat.js`
- `/workspace/scratch/4abc3effa72e/project/pages/chat/chat.wxml`
- `/workspace/scratch/4abc3effa72e/project/pages/chat/chat.wxss`
- `/workspace/scratch/4abc3effa72e/project/pages/messages/messages.js`
- `/workspace/scratch/4abc3effa72e/project/pages/messages/messages.wxml`
- `/workspace/scratch/4abc3effa72e/project/pages/messages/messages.wxss`
- `/workspace/scratch/4abc3effa72e/project/pages/messages/messages.json`
- `/workspace/scratch/4abc3effa72e/project/pages/timetable/timetable.js`
- `/workspace/scratch/4abc3effa72e/project/pages/timetable/timetable.wxml`
- `/workspace/scratch/4abc3effa72e/project/pages/timetable/timetable.json`
- `/workspace/scratch/4abc3effa72e/project/pages/calendar/calendar.js`
- `/workspace/scratch/4abc3effa72e/project/pages/calendar/calendar.wxml`
- `/workspace/scratch/4abc3effa72e/project/pages/calendar/calendar.wxss`
- `/workspace/scratch/4abc3effa72e/project/pages/calendar/calendar.json`
- `/workspace/scratch/4abc3effa72e/project/pages/profile/profile.js`
- `/workspace/scratch/4abc3effa72e/project/components/bottom-nav/index.js`

- 消息不再在 Page.data 中硬编码。GET/POST 持久化文本，按对方用户 ID 隔离；beforeId 游标分页，size上限50，首屏升序展示，页面可见时每5秒轮询，隐藏/卸载停止。发送端 userId/文本/clientId 验证，clientId唯一键用于幂等，重复标识与不同内容/对象冲突时拒绝。
- 会话列表从双方消息聚合，最新预览和未读数来自数据库，不伪造“在线”。通知从 notification 表读取，点赞/评论/关注/收藏事务会生成相关通知。全部已读写数据库，两类消息一并处理；免打扰保留未读展示但不计入总角标。
- 清空写每个用户的消息ID游标，物理记录不删除，不影响对方。已读 upToId 防止把尚未展示的并发消息标为已读。
- 网络失败时发出的演示消息、会话预览、已读、清空与免打扰保存在 wx.storage，跨重启保留，页面明确“仅本机”。不会自动重发。演示页的清空/免打扰固定操作本机，避免恢复网络后误清真实记录。
- 课表按 user/term/week 范围查询，校历按学期读取，均通过 API。个人中心课表摘要也读取同一接口；教学周工具接收真实 termStart。日期输出规范为 YYYY-MM-DD。管理员维护学校学期、课程与事件，尚未对接外部学校教务平台。
- getProfile 从真实资料接口读取，网络失败才回演示；个人中心不再直接读课程 mock。

### P3 与直接阻碍任务的问题

文件：
- `/workspace/scratch/4abc3effa72e/project/utils/data-mode.js`
- `/workspace/scratch/4abc3effa72e/project/components/offline-banner/index.js`
- `/workspace/scratch/4abc3effa72e/project/components/offline-banner/index.wxml`
- `/workspace/scratch/4abc3effa72e/project/components/offline-banner/index.wxss`
- `/workspace/scratch/4abc3effa72e/project/components/offline-banner/index.json`
- `/workspace/scratch/4abc3effa72e/project/app.json`
- `/workspace/scratch/4abc3effa72e/project/utils/api.js`
- `/workspace/scratch/4abc3effa72e/project/utils/ui-popup-helper.js`
- `/workspace/scratch/4abc3effa72e/project/pages/community/community.js`
- `/workspace/scratch/4abc3effa72e/project/pages/community/community.wxml`
- `/workspace/scratch/4abc3effa72e/project/pages/post-detail/post-detail.js`
- `/workspace/scratch/4abc3effa72e/project/pages/post-detail/post-detail.wxml`
- `/workspace/scratch/4abc3effa72e/project/pages/user-home/user-home.js`
- `/workspace/scratch/4abc3effa72e/project/pages/user-home/user-home.wxml`
- `/workspace/scratch/4abc3effa72e/project/pages/discover/discover.js`
- `/workspace/scratch/4abc3effa72e/project/pages/discover/discover.wxml`
- `/workspace/scratch/4abc3effa72e/project/pages/publish/publish.js`
- `/workspace/scratch/4abc3effa72e/project/pages/market-detail/market-detail.js`
- `/workspace/scratch/4abc3effa72e/project/pages/market-publish/market-publish.js`
- `/workspace/scratch/4abc3effa72e/project/project.config.json`
- `/workspace/scratch/4abc3effa72e/project/README.md`
- `/workspace/scratch/4abc3effa72e/project/README_BACKEND.md`
- `/workspace/scratch/4abc3effa72e/project/AI_HANDOFF.md`
- `/workspace/scratch/4abc3effa72e/project/docs/FEATURE_STATUS.md`

全部23页 WXML 加全局演示组件，按接口记录连接状态，避免并行成功请求掩盖其他离线接口；有数据源状态的页面额外传 local，避免缓存演示结果被误标为真实。所有已有 ui-popup 挂载补齐 actions/showCancel/cancelText 绑定，helper 同时兼容 event 与原始 detail，并捕捉异步回调错误。新增错误提示均走该组件。

followUser 后端的 body.userId 优先逻辑本来成立。本次没有假称它不兼容，也没有无故改成新协议；前端请求体保留 userId，测试已确认。真实 HTTP 验证尚缺本地后端环境，见下方人工步骤。

补齐旧写接口的 error.server guard，论坛/详情/发现/个人主页的点赞关注和详情评论在服务端失败时不再显示成功；发布捕获异常并收尾提交状态。在线发布不再错误写入 mock_posts，发现页也不重复合并。修复社区分享 const id 的暂时性死区、详情 loading 不能收尾、三处不存在的本地图片路径。二手离线发布支持 local-* 详情 ID，并将本机物品持久化，避免重启丢失。演示校历寒假起始改到学期结束次日，与 termEnd 一致，不当作真实校历宣传。小程序 packOptions 忽略 backend/tests，减少包体。

全量实际修改文件清单见附录A；涉及所有页的横条与已有弹层绑定在 diff 中逐文件列出。

## 3. 需要你手动执行的 SQL

本次**未执行任何数据库 SQL**，没有改数据库自动初始化配置。

- 已有完整原数据库：只运行下面新增脚本。
- 新数据库：先手动运行原 schema.sql 一次，再运行下方脚本。原 schema 已包含 food/market/jobs/activities，不必重复运行两个独立模块种子；原部分旧 INSERT 不幂等。
- 新脚本6表使用 CREATE TABLE IF NOT EXISTS，种子使用唯一键+INSERT IGNORE，不覆盖已有已读/清空/消息/课程；可重复执行。

```bash
mysql -u root -p < backend/src/main/resources/db/schema.sql
mysql -u root -p < backend/src/main/resources/db/module-messages-academics.sql
```

第二条为本次新增必需脚本，完整 SQL 可复制：

```sql
-- 手动执行；后端不会自动建表。可重复执行，种子唯一键不会覆盖已有消息/已读状态。
USE campus_life;
CREATE TABLE IF NOT EXISTS chat_message (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 sender_id BIGINT NOT NULL, receiver_id BIGINT NOT NULL,
 type VARCHAR(8) NOT NULL DEFAULT 'text', content VARCHAR(1000) NOT NULL DEFAULT '', image VARCHAR(255),
 client_id VARCHAR(64) NOT NULL, read_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_chat_client(sender_id,client_id),
 KEY idx_chat_pair(sender_id,receiver_id,id), KEY idx_chat_receiver(receiver_id,read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS chat_preference (
 user_id BIGINT NOT NULL, peer_id BIGINT NOT NULL,
 cleared_before BIGINT NOT NULL DEFAULT 0, muted TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,peer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS notification (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL,
 title VARCHAR(64) NOT NULL, content VARCHAR(1000) NOT NULL,
 category VARCHAR(16) NOT NULL DEFAULT 'system', tone VARCHAR(16) NOT NULL DEFAULT 'blue',
 post_id BIGINT NULL, source_key VARCHAR(64) NULL, read_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_notification_seed(user_id,source_key), KEY idx_notification_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS academic_term (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, school VARCHAR(64) NOT NULL, term_label VARCHAR(64) NOT NULL,
 start_date DATE NOT NULL, end_date DATE NOT NULL, midterm_start DATE NOT NULL, midterm_end DATE NOT NULL, final_start DATE NOT NULL,
 active TINYINT NOT NULL DEFAULT 1, UNIQUE KEY uk_term(school,term_label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS course_schedule (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, term_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 name VARCHAR(64) NOT NULL, room VARCHAR(64) NOT NULL, day INT NOT NULL, period INT NOT NULL,
 theme VARCHAR(16) NOT NULL DEFAULT 'blue', week_start INT NOT NULL DEFAULT 1, week_end INT NOT NULL DEFAULT 21,
 UNIQUE KEY uk_course(term_id,user_id,day,period,name), KEY idx_course_week(user_id,term_id,week_start,week_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS calendar_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, term_id BIGINT NOT NULL, title VARCHAR(64) NOT NULL,
 start_date DATE NOT NULL, end_date DATE NOT NULL, note VARCHAR(255) NOT NULL DEFAULT '',
 UNIQUE KEY uk_calendar(term_id,title,start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 以下均为演示种子；正式课表/校历由管理员按学校、用户、学期维护，未对接外部教务平台。
INSERT IGNORE INTO chat_message(sender_id,receiver_id,type,content,image,client_id) VALUES
 (2,1,'text','你好，看到你收藏了我的台灯～',NULL,'seed-lamp-1'),
 (1,2,'text','你好，请问台灯还在吗？晚上方便自提吗？',NULL,'seed-lamp-2'),
 (2,1,'text','还在的，今晚可以宿舍楼下自提。',NULL,'seed-lamp-3'),
 (2,1,'image','','/assets/home/recommend-lamp.jpg','seed-lamp-4'),
 (1,2,'text','好的，那晚上七点见！',NULL,'seed-lamp-5'),
 (4,1,'text','欢迎加入！本周四晚上第一次排练',NULL,'seed-music-1'),
 (3,1,'text','好的，东区二号场见',NULL,'seed-basketball-1');
INSERT IGNORE INTO notification(user_id,title,content,category,tone,source_key) VALUES
 (1,'校园市集提醒','你收藏的校园市集将在明天下午开始','system','green','seed-market'),
 (1,'内容审核通过','你发布的“几乎全新护眼台灯”已经展示','system','blue','seed-review'),
 (1,'系统维护通知','本周日凌晨将进行短时服务升级','system','yellow','seed-maintenance');
INSERT IGNORE INTO academic_term(school,term_label,start_date,end_date,midterm_start,midterm_end,final_start)
 VALUES('内蒙古大学','2026-2027学年 第一学期','2026-08-31','2027-01-22','2026-11-02','2026-11-06','2027-01-04');
SET @campus_term_id=(SELECT id FROM academic_term WHERE school='内蒙古大学' AND term_label='2026-2027学年 第一学期');
INSERT IGNORE INTO course_schedule(term_id,user_id,name,room,day,period,theme) VALUES
 (@campus_term_id,1,'新闻学概论','博学楼 B203',0,0,'blue'),
 (@campus_term_id,1,'大学英语','外国语楼 305',0,1,'green'),
 (@campus_term_id,1,'传播学原理','博学楼 A110',1,1,'amber'),
 (@campus_term_id,1,'新闻摄影','实验楼 402',2,0,'pink'),
 (@campus_term_id,1,'体育·羽毛球','体育馆',2,3,'purple'),
 (@campus_term_id,1,'新媒体运营','博学楼 B305',3,1,'blue'),
 (@campus_term_id,1,'形势与政策','图书馆报告厅',4,2,'amber');
INSERT IGNORE INTO calendar_event(term_id,title,start_date,end_date,note) VALUES
 (@campus_term_id,'秋季学期开学','2026-08-31','2026-08-31','第一教学周开始，记得注册报到'),
 (@campus_term_id,'国庆假期','2026-10-01','2026-10-07','10月8日（周四）正常上课'),
 (@campus_term_id,'秋季运动会','2026-10-24','2026-10-25','东区田径场，全校停课两天'),
 (@campus_term_id,'期中考试周','2026-11-02','2026-11-06','具体安排以教务系统通知为准'),
 (@campus_term_id,'校园歌手大赛决赛','2026-12-05','2026-12-05','大礼堂，晚上 19:00'),
 (@campus_term_id,'期末考试周','2027-01-04','2027-01-15','考试周图书馆延长开放至 23:00'),
 (@campus_term_id,'寒假开始','2027-01-23','2027-01-23','演示日期，与学期结束日期保持一致，实际以教务通知为准');

```

## 4. 最终报告

### ✅ 完成

P0-1/P0-2、全部清单所列 P1 实际入口、P2 消息/通知/课表/校历 API 与页面接线、本机聊天持久化、P3 参数核对/死代码/离线提示/文档同步，以及影响这些链路的额外修复。未新增第三方依赖，未执行SQL，原七条首页路由不变。现23页、5个Controller、43端点、26张表（手动执行新增脚本后）。

### 🔍 已验证

18项 Node 行为回归通过：搜索匹配/分页/通配符/编码/空态/错误/竞态/卸载；所有带兜底写接口的业务拒绝透传；新增读接口错误；首页路由和真实重排；本机聊天重启、用户隔离、已读/清空/免打扰与分页；本机物品详情；弹层回调与异步错误；全局演示状态；真实学期周次；关注请求体和分享；发现页搜索/详情及聊天页行为。

工程静态检查通过：23页四件套与注册、所有 JS语法/JSON/WXML标签、组件文件、路由目标、本地资源、require、样式导入、Mapper XML与参数绑定、新增SQL的幂等结构。这里只静态读SQL，没有执行。

复现命令：

```bash
node --test tests/regression.test.js
python tests/validate_project.py
```

### ⚠️ 需要人工验证

环境没有可用Maven/JDK、MySQL和微信开发者工具。未声称后端编译、SQL实跑、真实HTTP或真机视觉通过。你需手动初始化数据库、填写 application.yml 的账号密码，然后：

```bash
cd backend
mvn clean package
mvn spring-boot:run
# 另开终端，在小程序根目录运行；仅GET，无SQL/业务写入。
python tests/backend_smoke.py
```

微信工具/真机验收：

1. 分别从首页、发现搜索台灯/学习；核对第二页、无结果、业务失败重试及结果详情。
2. 点全部首页标签、通知铃铛、换一批、广告专题；学习好物应选中图书。数据库无台灯时应进数码列表。
3. 从会话/卖家/帖子作者进入同一用户聊天；发送文本，重进与重启检查留存；用另一演示用户调用接口后检查收信/未读/已读/免打扰/清空隔离。自己账号不发起私聊。
4. 关闭后端后验证横条、本机消息标签和本机记录留存；恢复后端确认演示消息没有自动补发。离线缓存与真实数据不强行合并。
5. 修改数据库学期起始/课程周次和事件，刷新课表/校历与个人中心摘要，检查日期和导航胶囊。
6. SQL新增脚本在测试库手动执行两次，确认种子不重复、已读状态不回滚。数据库执行必须由你自己完成。
7. 关注参数：代码已确认 body优先，但要HTTP验证，可在测试账号上发 POST /api/users/2/follow，body {"userId":3}，核对记录 follower_id=3；重复一次应切回。此操作会写测试数据并产生通知，本次没有代执行。

### ⏭️ 未扩展及原因

这些不属于给定P0/P1/P2/P3清单的补缺，仍保留原项目边界：真实wx.login/token鉴权、远程图片上传、语音/图片私信、外部教务连接、官方广告活动链接、服务端推荐算法。它们需要账号/服务/运营配置或新的产品与接口范围；此次不编造外部接口/活动。保留本地轮换作为允许的推荐实现，复用已有资产，无需生成新图片。

### 🔍 发现的新问题

已修复：弹层actions/事件形状错误；市场分类忽略参数；发现帖详情/互动未接线；在线发布混入mock与重复；分享id临时性死区；详情loading；local物品详情/持久化；不存在的图片路径；演示学期/假期日期矛盾；后端目录打包。

仍存在的旧范围限制：个人主页依旧只从最多50个帖子筛选作者，粉丝初值328为原演示数字。没有把它扩成新的作者分页接口或重写个人主页。登录、媒体上传、无鉴权 userId 与旧读接口广义失败mock也是已明确披露的原项目边界，不能将此交付视为已通过生产安全或全量真机验收。

技术参考：搜索使用 [MyBatis-Plus 官方 Wrapper 文档](https://baomidou.com/en/guides/wrapper/) 的参数绑定构造方式；消息幂等遵循 [MySQL官方 ON DUPLICATE KEY UPDATE 文档](https://dev.mysql.com/doc/refman/8.0/en/insert-on-duplicate.html)。代码行为结论以本包源码和测试为准。

## 附录A：全量文件清单

| 状态 | 完整路径 |
|---|---|
| 修改 | `/workspace/scratch/4abc3effa72e/project/AI_HANDOFF.md` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/README.md` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/README_BACKEND.md` |
| 删除 | `/workspace/scratch/4abc3effa72e/project/api.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/app.json` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/config/GlobalExceptionHandler.java` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/controller/CampusController.java` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/controller/ForumController.java` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/dto/SendMessageRequest.java` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/mapper/CampusMapper.java` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/service/CampusService.java` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/service/ForumService.java` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/service/UserService.java` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/backend/src/main/resources/db/module-messages-academics.sql` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/components/bottom-nav/index.js` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/components/offline-banner/index.js` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/components/offline-banner/index.json` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/components/offline-banner/index.wxml` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/components/offline-banner/index.wxss` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/docs/API_INTEGRATION.md` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/docs/FEATURE_STATUS.md` |
| 删除 | `/workspace/scratch/4abc3effa72e/project/index.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/activities/activities.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/activity-idol/activity-idol.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/calendar/calendar.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/calendar/calendar.json` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/calendar/calendar.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/calendar/calendar.wxss` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/pages/campus-offer/campus-offer.js` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/pages/campus-offer/campus-offer.json` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/pages/campus-offer/campus-offer.wxml` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/pages/campus-offer/campus-offer.wxss` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/chat/chat.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/chat/chat.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/chat/chat.wxss` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/community/community.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/community/community.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/discover/discover.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/discover/discover.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/food-list/food-list.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/food-order/food-order.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/food-shop/food-shop.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/idol-join/idol-join.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/index/index.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/index/index.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/jobs/jobs.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/market-detail/market-detail.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/market-detail/market-detail.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/market-list/market-list.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/market-list/market-list.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/market-publish/market-publish.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/market-publish/market-publish.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/messages/messages.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/messages/messages.json` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/messages/messages.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/messages/messages.wxss` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/post-detail/post-detail.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/post-detail/post-detail.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/profile/profile.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/profile/profile.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/publish/publish.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/publish/publish.wxml` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/pages/search/search.js` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/pages/search/search.json` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/pages/search/search.wxml` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/pages/search/search.wxss` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/timetable/timetable.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/timetable/timetable.json` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/timetable/timetable.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/user-home/user-home.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/pages/user-home/user-home.wxml` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/project.config.json` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/tests/backend_smoke.py` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/tests/regression.test.js` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/tests/validate_project.py` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/utils/api.js` |
| 新增 | `/workspace/scratch/4abc3effa72e/project/utils/data-mode.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/utils/mock-data.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/utils/term.js` |
| 修改 | `/workspace/scratch/4abc3effa72e/project/utils/ui-popup-helper.js` |

## 附录B：新增文件全文

以下包含全部新增源文件与SQL/测试文件。报告自身、补丁和测试日志是交付说明文件，不递归展开。

### /workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/controller/CampusController.java

```java
package com.campuslife.controller;
import com.campuslife.dto.*; import com.campuslife.service.CampusService; import jakarta.validation.Valid; import lombok.RequiredArgsConstructor; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequestMapping("/api") @RequiredArgsConstructor public class CampusController { private final CampusService service;
 @GetMapping("/conversations") public ApiResponse<List<Map<String,Object>>> conversations(@RequestParam(defaultValue="1") Long userId){return ApiResponse.ok(service.conversations(userId));}
 @GetMapping("/conversations/{peerId}/messages") public ApiResponse<Map<String,Object>> messages(@PathVariable Long peerId,@RequestParam(defaultValue="1") Long userId,@RequestParam(required=false) Long beforeId,@RequestParam(defaultValue="30") int size){return ApiResponse.ok(service.messages(userId,peerId,beforeId,Math.min(Math.max(size,1),50)));}
 @PostMapping("/conversations/{peerId}/messages") public ApiResponse<Map<String,Object>> send(@PathVariable Long peerId,@Valid @RequestBody SendMessageRequest r){return ApiResponse.ok(service.send(peerId,r));}
 @PostMapping("/conversations/{peerId}/read") public ApiResponse<Object> read(@PathVariable Long peerId,@RequestBody Map<String,Long> r){service.readChat(r.get("userId"),peerId,r.get("upToId"));return ApiResponse.ok(null);}
 @PostMapping("/conversations/{peerId}/clear") public ApiResponse<Object> clear(@PathVariable Long peerId,@RequestBody Map<String,Long> r){service.clear(r.get("userId"),peerId);return ApiResponse.ok(null);}
 @PostMapping("/conversations/{peerId}/mute") public ApiResponse<Object> mute(@PathVariable Long peerId,@RequestParam(defaultValue="1") Long userId,@RequestParam boolean muted){service.mute(userId,peerId,muted);return ApiResponse.ok(null);}
 @GetMapping("/notifications") public ApiResponse<List<Map<String,Object>>> notifications(@RequestParam(defaultValue="1") Long userId){return ApiResponse.ok(service.notifications(userId));}
 @PostMapping("/messages/read-all") public ApiResponse<Object> readAll(@RequestBody Map<String,Long> r){service.readAll(r.get("userId"));return ApiResponse.ok(null);}
 @GetMapping("/timetable") public ApiResponse<Map<String,Object>> timetable(@RequestParam(defaultValue="1") Long userId,@RequestParam(required=false) Integer week){return ApiResponse.ok(service.timetable(userId,week));}
 @GetMapping("/calendar") public ApiResponse<Map<String,Object>> calendar(@RequestParam(defaultValue="1") Long userId){return ApiResponse.ok(service.calendar(userId));}
}

```

### /workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/dto/SendMessageRequest.java

```java
package com.campuslife.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data public class SendMessageRequest {
 @NotNull @Min(1) private Long userId;
 @NotBlank @Size(max=1000,message="消息不能超过1000字") private String content;
 @NotBlank @Size(max=64) private String clientId;
}

```

### /workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/mapper/CampusMapper.java

```java
package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

// 消息和教学数据的参数全部绑定，不拼接来自请求的 SQL。
@Mapper
public interface CampusMapper {
    @Select("""
        SELECT u.id, u.nickname AS name, u.avatar,
               m.type, m.content AS preview, m.created_at AS createdAt,
               COALESCE(p.muted,0) AS muted,
               (SELECT COUNT(*) FROM chat_message z WHERE z.sender_id=u.id AND z.receiver_id=#{userId}
                AND z.read_at IS NULL AND z.id>COALESCE(p.cleared_before,0)) AS unread
        FROM `user` u
        LEFT JOIN chat_preference p ON p.user_id=#{userId} AND p.peer_id=u.id
        JOIN chat_message m ON m.id=(SELECT MAX(x.id) FROM chat_message x
          WHERE ((x.sender_id=#{userId} AND x.receiver_id=u.id) OR (x.sender_id=u.id AND x.receiver_id=#{userId}))
          AND x.id>COALESCE(p.cleared_before,0))
        WHERE u.id<>#{userId} ORDER BY m.id DESC
        """)
    List<Map<String,Object>> conversations(@Param("userId") Long userId);

    @Select("""
        SELECT cleared_before AS clearedBefore, muted FROM chat_preference WHERE user_id=#{userId} AND peer_id=#{peerId}
        """)
    Map<String,Object> preference(@Param("userId") Long userId, @Param("peerId") Long peerId);

    @Select("""
        <script>
        SELECT id, sender_id AS senderId, receiver_id AS receiverId, type, content, image, created_at AS createdAt
        FROM chat_message
        WHERE ((sender_id=#{userId} AND receiver_id=#{peerId}) OR (sender_id=#{peerId} AND receiver_id=#{userId}))
        AND id>#{clearedBefore}
        <if test="beforeId != null">AND id &lt; #{beforeId}</if>
        ORDER BY id DESC LIMIT #{limit}
        </script>
        """)
    List<Map<String,Object>> messages(@Param("userId") Long userId, @Param("peerId") Long peerId,
        @Param("clearedBefore") long clearedBefore, @Param("beforeId") Long beforeId, @Param("limit") int limit);

    @Insert("""
        INSERT INTO chat_message(sender_id,receiver_id,type,content,client_id)
        VALUES(#{senderId},#{receiverId},'text',#{content},#{clientId})
        ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)
        """)
    @Options(useGeneratedKeys=true,keyProperty="id",keyColumn="id")
    int send(Map<String,Object> params);

    @Select("SELECT id,sender_id AS senderId,receiver_id AS receiverId,type,content,image,created_at AS createdAt FROM chat_message WHERE id=#{id}")
    Map<String,Object> message(@Param("id") Long id);

    @Update("UPDATE chat_message SET read_at=CURRENT_TIMESTAMP WHERE receiver_id=#{userId} AND sender_id=#{peerId} AND id<=#{upToId} AND read_at IS NULL")
    int readChat(@Param("userId") Long userId,@Param("peerId") Long peerId,@Param("upToId") Long upToId);

    @Update("UPDATE chat_message SET read_at=CURRENT_TIMESTAMP WHERE receiver_id=#{userId} AND read_at IS NULL")
    int readAllChats(@Param("userId") Long userId);

    @Select("SELECT COALESCE(MAX(id),0) FROM chat_message WHERE (sender_id=#{userId} AND receiver_id=#{peerId}) OR (sender_id=#{peerId} AND receiver_id=#{userId})")
    long latestId(@Param("userId") Long userId,@Param("peerId") Long peerId);

    @Insert("""
        INSERT INTO chat_preference(user_id,peer_id,cleared_before) VALUES(#{userId},#{peerId},#{id})
        ON DUPLICATE KEY UPDATE cleared_before=GREATEST(cleared_before,VALUES(cleared_before))
        """)
    int clear(@Param("userId") Long userId,@Param("peerId") Long peerId,@Param("id") long id);

    @Insert("""
        INSERT INTO chat_preference(user_id,peer_id,muted) VALUES(#{userId},#{peerId},#{muted})
        ON DUPLICATE KEY UPDATE muted=VALUES(muted)
        """)
    int mute(@Param("userId") Long userId,@Param("peerId") Long peerId,@Param("muted") boolean muted);

    @Select("SELECT id,title,content,tone,category,post_id AS postId,created_at AS createdAt,read_at AS readAt FROM notification WHERE user_id=#{userId} ORDER BY id DESC LIMIT 100")
    List<Map<String,Object>> notifications(@Param("userId") Long userId);

    @Update("UPDATE notification SET read_at=CURRENT_TIMESTAMP WHERE user_id=#{userId} AND read_at IS NULL")
    int readNotifications(@Param("userId") Long userId);

    @Insert("INSERT INTO notification(user_id,title,content,category,tone,post_id) VALUES(#{userId},#{title},#{content},#{category},'blue',#{postId})")
    int notifyUser(@Param("userId") Long userId,@Param("title") String title,@Param("content") String content,@Param("category") String category,@Param("postId") Long postId);

    @Select("SELECT id,term_label AS termLabel,start_date AS termStart,end_date AS termEnd,midterm_start AS midtermStart,midterm_end AS midtermEnd,final_start AS finalStart FROM academic_term WHERE school=#{school} AND active=1 ORDER BY start_date DESC LIMIT 1")
    Map<String,Object> term(@Param("school") String school);

    @Select("SELECT id,name,room,day,period,theme,week_start AS weekStart,week_end AS weekEnd FROM course_schedule WHERE user_id=#{userId} AND term_id=#{termId} AND week_start<=#{week} AND week_end>=#{week} ORDER BY day,period,id")
    List<Map<String,Object>> courses(@Param("userId") Long userId,@Param("termId") Long termId,@Param("week") int week);

    @Select("SELECT id,title,start_date AS start,end_date AS end,note FROM calendar_event WHERE term_id=#{termId} ORDER BY start_date,id")
    List<Map<String,Object>> events(@Param("termId") Long termId);
}

```

### /workspace/scratch/4abc3effa72e/project/backend/src/main/java/com/campuslife/service/CampusService.java

```java
package com.campuslife.service;

import com.campuslife.dto.SendMessageRequest;
import com.campuslife.entity.User;
import com.campuslife.mapper.CampusMapper;
import com.campuslife.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service @RequiredArgsConstructor
public class CampusService {
 private final CampusMapper mapper; private final UserMapper users;
 private static long number(Object x){return x instanceof Boolean b?(b?1:0):x instanceof Number n?n.longValue():0;}
 private User user(Long id){if(id==null||id<1)throw new IllegalArgumentException("用户参数无效");User u=users.selectById(id);if(u==null)throw new IllegalArgumentException("用户不存在");return u;}
 private void pair(Long userId,Long peerId){user(userId);user(peerId);if(userId.equals(peerId))throw new IllegalArgumentException("不能与自己聊天");}
 public List<Map<String,Object>> conversations(Long userId){user(userId);List<Map<String,Object>> rows=mapper.conversations(userId);for(Map<String,Object> row:rows){String name=String.valueOf(row.get("name"));row.put("avatarText",name.isEmpty()?"同":name.substring(0,1));row.put("color","#7EB6F6");row.put("online",false);row.put("muted",number(row.get("muted"))>0);if("image".equals(row.get("type")))row.put("preview","[图片]");}return rows;}
 public Map<String,Object> messages(Long userId,Long peerId,Long beforeId,int size){
   pair(userId,peerId);if(beforeId!=null&&beforeId<1)throw new IllegalArgumentException("分页游标无效");
   Map<String,Object> pref=mapper.preference(userId,peerId);long cleared=pref==null?0:number(pref.get("clearedBefore"));
   List<Map<String,Object>> rows=mapper.messages(userId,peerId,cleared,beforeId,size+1);
   boolean hasMore=rows.size()>size;if(hasMore)rows=new ArrayList<>(rows.subList(0,size));
   Collections.reverse(rows);for(Map<String,Object> row:rows)row.put("mine",number(row.get("senderId"))==userId);
   return Map.of("list",rows,"hasMore",hasMore,"muted",pref!=null&&number(pref.get("muted"))>0);
 }
 @Transactional public Map<String,Object> send(Long peerId,SendMessageRequest r){pair(r.getUserId(),peerId);Map<String,Object> row=new LinkedHashMap<>();row.put("senderId",r.getUserId());row.put("receiverId",peerId);row.put("content",r.getContent().trim());row.put("clientId",r.getClientId());mapper.send(row);Map<String,Object> out=mapper.message(number(row.get("id")));if(number(out.get("receiverId"))!=peerId||!Objects.equals(out.get("content"),r.getContent().trim()))throw new IllegalArgumentException("消息请求标识已被其他内容使用");out.put("mine",true);return out;}
 public void readChat(Long userId,Long peerId,Long upToId){pair(userId,peerId);if(upToId==null||upToId<1)throw new IllegalArgumentException("消息游标无效");mapper.readChat(userId,peerId,upToId);}
 @Transactional public void readAll(Long userId){user(userId);mapper.readAllChats(userId);mapper.readNotifications(userId);}
 @Transactional public void clear(Long userId,Long peerId){pair(userId,peerId);long last=mapper.latestId(userId,peerId);mapper.clear(userId,peerId,last);if(last>0)mapper.readChat(userId,peerId,last);}
 public void mute(Long userId,Long peerId,boolean muted){pair(userId,peerId);mapper.mute(userId,peerId,muted);}
 public List<Map<String,Object>> notifications(Long userId){user(userId);List<Map<String,Object>> rows=mapper.notifications(userId);for(Map<String,Object> row:rows)row.put("unread",row.get("readAt")==null);return rows;}
 private Map<String,Object> termFor(Long userId){User u=user(userId);Map<String,Object> term=mapper.term(u.getSchool());if(term==null)throw new IllegalArgumentException("该学校尚未配置学期");for(String key:List.of("termStart","termEnd","midtermStart","midtermEnd","finalStart"))term.put(key,date(term.get(key)).toString());return term;}
 private static LocalDate date(Object value){return LocalDate.parse(value.toString());}
 public Map<String,Object> timetable(Long userId,Integer week){Map<String,Object> term=termFor(userId);int current=Math.max(1,(int)ChronoUnit.WEEKS.between(date(term.get("termStart")).with(java.time.DayOfWeek.MONDAY),LocalDate.now(ZoneId.of("Asia/Shanghai")).with(java.time.DayOfWeek.MONDAY))+1);int target=week==null?current:Math.max(1,Math.min(week,60));return Map.of("termInfo",term,"week",target,"courses",mapper.courses(userId,number(term.get("id")),target));}
 public Map<String,Object> calendar(Long userId){Map<String,Object> term=termFor(userId);List<Map<String,Object>> events=mapper.events(number(term.get("id")));for(Map<String,Object> event:events){event.put("start",date(event.get("start")).toString());event.put("end",date(event.get("end")).toString());}return Map.of("termInfo",term,"events",events);}
}

```

### /workspace/scratch/4abc3effa72e/project/backend/src/main/resources/db/module-messages-academics.sql

```sql
-- 手动执行；后端不会自动建表。可重复执行，种子唯一键不会覆盖已有消息/已读状态。
USE campus_life;
CREATE TABLE IF NOT EXISTS chat_message (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 sender_id BIGINT NOT NULL, receiver_id BIGINT NOT NULL,
 type VARCHAR(8) NOT NULL DEFAULT 'text', content VARCHAR(1000) NOT NULL DEFAULT '', image VARCHAR(255),
 client_id VARCHAR(64) NOT NULL, read_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_chat_client(sender_id,client_id),
 KEY idx_chat_pair(sender_id,receiver_id,id), KEY idx_chat_receiver(receiver_id,read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS chat_preference (
 user_id BIGINT NOT NULL, peer_id BIGINT NOT NULL,
 cleared_before BIGINT NOT NULL DEFAULT 0, muted TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,peer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS notification (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL,
 title VARCHAR(64) NOT NULL, content VARCHAR(1000) NOT NULL,
 category VARCHAR(16) NOT NULL DEFAULT 'system', tone VARCHAR(16) NOT NULL DEFAULT 'blue',
 post_id BIGINT NULL, source_key VARCHAR(64) NULL, read_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_notification_seed(user_id,source_key), KEY idx_notification_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS academic_term (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, school VARCHAR(64) NOT NULL, term_label VARCHAR(64) NOT NULL,
 start_date DATE NOT NULL, end_date DATE NOT NULL, midterm_start DATE NOT NULL, midterm_end DATE NOT NULL, final_start DATE NOT NULL,
 active TINYINT NOT NULL DEFAULT 1, UNIQUE KEY uk_term(school,term_label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS course_schedule (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, term_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 name VARCHAR(64) NOT NULL, room VARCHAR(64) NOT NULL, day INT NOT NULL, period INT NOT NULL,
 theme VARCHAR(16) NOT NULL DEFAULT 'blue', week_start INT NOT NULL DEFAULT 1, week_end INT NOT NULL DEFAULT 21,
 UNIQUE KEY uk_course(term_id,user_id,day,period,name), KEY idx_course_week(user_id,term_id,week_start,week_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS calendar_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, term_id BIGINT NOT NULL, title VARCHAR(64) NOT NULL,
 start_date DATE NOT NULL, end_date DATE NOT NULL, note VARCHAR(255) NOT NULL DEFAULT '',
 UNIQUE KEY uk_calendar(term_id,title,start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 以下均为演示种子；正式课表/校历由管理员按学校、用户、学期维护，未对接外部教务平台。
INSERT IGNORE INTO chat_message(sender_id,receiver_id,type,content,image,client_id) VALUES
 (2,1,'text','你好，看到你收藏了我的台灯～',NULL,'seed-lamp-1'),
 (1,2,'text','你好，请问台灯还在吗？晚上方便自提吗？',NULL,'seed-lamp-2'),
 (2,1,'text','还在的，今晚可以宿舍楼下自提。',NULL,'seed-lamp-3'),
 (2,1,'image','','/assets/home/recommend-lamp.jpg','seed-lamp-4'),
 (1,2,'text','好的，那晚上七点见！',NULL,'seed-lamp-5'),
 (4,1,'text','欢迎加入！本周四晚上第一次排练',NULL,'seed-music-1'),
 (3,1,'text','好的，东区二号场见',NULL,'seed-basketball-1');
INSERT IGNORE INTO notification(user_id,title,content,category,tone,source_key) VALUES
 (1,'校园市集提醒','你收藏的校园市集将在明天下午开始','system','green','seed-market'),
 (1,'内容审核通过','你发布的“几乎全新护眼台灯”已经展示','system','blue','seed-review'),
 (1,'系统维护通知','本周日凌晨将进行短时服务升级','system','yellow','seed-maintenance');
INSERT IGNORE INTO academic_term(school,term_label,start_date,end_date,midterm_start,midterm_end,final_start)
 VALUES('内蒙古大学','2026-2027学年 第一学期','2026-08-31','2027-01-22','2026-11-02','2026-11-06','2027-01-04');
SET @campus_term_id=(SELECT id FROM academic_term WHERE school='内蒙古大学' AND term_label='2026-2027学年 第一学期');
INSERT IGNORE INTO course_schedule(term_id,user_id,name,room,day,period,theme) VALUES
 (@campus_term_id,1,'新闻学概论','博学楼 B203',0,0,'blue'),
 (@campus_term_id,1,'大学英语','外国语楼 305',0,1,'green'),
 (@campus_term_id,1,'传播学原理','博学楼 A110',1,1,'amber'),
 (@campus_term_id,1,'新闻摄影','实验楼 402',2,0,'pink'),
 (@campus_term_id,1,'体育·羽毛球','体育馆',2,3,'purple'),
 (@campus_term_id,1,'新媒体运营','博学楼 B305',3,1,'blue'),
 (@campus_term_id,1,'形势与政策','图书馆报告厅',4,2,'amber');
INSERT IGNORE INTO calendar_event(term_id,title,start_date,end_date,note) VALUES
 (@campus_term_id,'秋季学期开学','2026-08-31','2026-08-31','第一教学周开始，记得注册报到'),
 (@campus_term_id,'国庆假期','2026-10-01','2026-10-07','10月8日（周四）正常上课'),
 (@campus_term_id,'秋季运动会','2026-10-24','2026-10-25','东区田径场，全校停课两天'),
 (@campus_term_id,'期中考试周','2026-11-02','2026-11-06','具体安排以教务系统通知为准'),
 (@campus_term_id,'校园歌手大赛决赛','2026-12-05','2026-12-05','大礼堂，晚上 19:00'),
 (@campus_term_id,'期末考试周','2027-01-04','2027-01-15','考试周图书馆延长开放至 23:00'),
 (@campus_term_id,'寒假开始','2027-01-23','2027-01-23','演示日期，与学期结束日期保持一致，实际以教务通知为准');

```

### /workspace/scratch/4abc3effa72e/project/components/offline-banner/index.js

```javascript
const mode = require('../../utils/data-mode')
Component({
  properties: { local: { type: Boolean, value: false } },
  data: { demo: false },
  lifetimes: {
    attached() { this._unsubscribe = mode.subscribe(demo => this.setData({ demo })) },
    detached() { if (this._unsubscribe) this._unsubscribe() }
  }
})

```

### /workspace/scratch/4abc3effa72e/project/components/offline-banner/index.json

```json
{"component":true}

```

### /workspace/scratch/4abc3effa72e/project/components/offline-banner/index.wxml

```xml
<view wx:if="{{demo || local}}" class="offline-banner">部分数据为本地演示 · 离线操作不会自动同步到后端</view>

```

### /workspace/scratch/4abc3effa72e/project/components/offline-banner/index.wxss

```css
.offline-banner{margin:12rpx 24rpx;padding:12rpx 18rpx;border-radius:12rpx;background:#fff5db;color:#a56a10;font-size:22rpx;line-height:32rpx;text-align:center}

```

### /workspace/scratch/4abc3effa72e/project/pages/campus-offer/campus-offer.js

```javascript
const { getNavigationMetrics } = require('../../utils/navigation')
Page({
  data: { ...getNavigationMetrics() },
  openMarket() { wx.navigateTo({ url: '/pages/market-list/market-list?category=' + encodeURIComponent('数码') }) },
  goBack() { if (getCurrentPages().length > 1) wx.navigateBack(); else wx.reLaunch({ url: '/pages/index/index' }) }
})

```

### /workspace/scratch/4abc3effa72e/project/pages/campus-offer/campus-offer.json

```json
{"navigationStyle":"custom"}

```

### /workspace/scratch/4abc3effa72e/project/pages/campus-offer/campus-offer.wxml

```xml
<view class="offer-page">
  <view class="offer-nav" style="padding-top:{{statusBarHeight}}px;"><view class="offer-nav-row" style="height:{{navBarHeight}}px;padding-right:{{capsuleReservedRight}}px;"><text bindtap="goBack" class="back">‹</text><text>校园数码专题</text></view></view>
  <offline-banner />
  <view class="offer-card"><text class="badge">广告展示位</text><text class="title">联想小新 · 校园学习装备</text><image src="/assets/home/laptop-silver.webp" mode="aspectFit" /><text class="copy">这是一处校园推广展示位。当前尚未配置品牌官方活动链接，具体优惠、资格和价格以品牌官方公布为准。</text><view class="offer-button" bindtap="openMarket">逛逛校园数码闲置 ›</view><text class="note">二手物品由校园用户发布，与品牌官方活动无关联。</text></view>
</view>

```

### /workspace/scratch/4abc3effa72e/project/pages/campus-offer/campus-offer.wxss

```css
.offer-page{min-height:100vh;background:#fcfcfc;color:#18212b}.offer-nav{background:#fff}.offer-nav-row{display:flex;align-items:center;font-size:30rpx;font-weight:600;padding-left:28rpx}.back{font-size:44rpx;margin-right:26rpx;font-weight:400}.offer-card{margin:28rpx;padding:36rpx;border-radius:24rpx;background:#eef5ff}.badge{display:block;color:#8795a5;font-size:22rpx}.title{display:block;margin-top:24rpx;font-size:36rpx;font-weight:700}.offer-card image{display:block;width:100%;height:340rpx}.copy{display:block;font-size:27rpx;line-height:44rpx;color:#607087}.offer-button{margin-top:30rpx;padding:22rpx;border-radius:40rpx;background:#378be7;color:#fff;text-align:center;font-size:27rpx}.note{display:block;margin-top:20rpx;font-size:22rpx;line-height:34rpx;color:#8795a5}

```

### /workspace/scratch/4abc3effa72e/project/pages/search/search.js

```javascript
const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { formatTime } = require('../../utils/format-time')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: { ...getNavigationMetrics(), keyword: '', draft: '', posts: [], page: 0, size: 10, total: 0, loading: false, searched: false, offline: false, error: '', uiPopup: null },
  onLoad(options) {
    this._alive = true
    // 微信已解码页面参数；不要再次解码，避免把用户输入的 %xx 改掉。
    const keyword = String(options.keyword || '').trim()
    this.setData({ keyword, draft: keyword })
    if (keyword) this.loadResults(true)
  },
  onUnload() { this._alive = false; this._seq = (this._seq || 0) + 1 },
  inputKeyword(event) { this.setData({ draft: event.detail.value }) },
  submitSearch() {
    const keyword = this.data.draft.trim()
    if (!keyword || keyword.length > 64) { popup.toast(this, !keyword ? '请输入搜索关键词' : '关键词不能超过64字'); return }
    this.setData({ keyword })
    this.loadResults(true)
  },
  async loadResults(reset = false) {
    if (!reset && (this.data.loading || this.data.posts.length >= this.data.total)) return
    const seq = this._seq = (this._seq || 0) + 1
    const page = reset ? 1 : this.data.page + 1
    const keyword = this.data.keyword
    this.setData({ loading: true, error: '', searched: true, ...(reset ? { posts: [], page: 0, total: 0, offline: false } : {}) })
    try {
      const result = await api.searchPosts({ keyword, page, size: this.data.size })
      if (!this._alive || seq !== this._seq) return
      const list = (result.list || []).map(post => ({ ...post, avatarText: (post.authorNickname || '同')[0], createdAt: formatTime(post.createdAt) }))
      // 后端上线/掉线时重新读第一页，避免把真实帖子与演示帖子混为一次分页。
      if (!reset && this.data.offline !== !!result.offline) { this.loadResults(true); return }
      const posts = reset ? list : this.data.posts.concat(list.filter(post => !this.data.posts.some(old => String(old.id) === String(post.id))))
      this.setData({ posts, page, total: Number(result.total) || 0, offline: !!result.offline })
    } catch (error) {
      if (this._alive && seq === this._seq) this.setData({ error: error.message || '搜索失败，请重试' })
    } finally {
      if (this._alive && seq === this._seq) this.setData({ loading: false })
    }
  },
  loadMore() { return this.loadResults(false) },
  retry() { return this.loadResults(this.data.page === 0) },
  onReachBottom() { if (!this.data.error) this.loadMore() },
  onPullDownRefresh() { this.loadResults(true).finally(() => wx.stopPullDownRefresh()) },
  openPost(event) { wx.navigateTo({ url: `/pages/post-detail/post-detail?id=${encodeURIComponent(event.currentTarget.dataset.id)}` }) },
  goBack() { if (getCurrentPages().length > 1) wx.navigateBack(); else wx.reLaunch({ url: '/pages/index/index' }) },
  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event) },
  onPopupInput(event) { this._popupInput && this._popupInput(event) },
  onPopupClose() { popup.hide(this) }
})

```

### /workspace/scratch/4abc3effa72e/project/pages/search/search.json

```json
{"navigationStyle":"custom","enablePullDownRefresh":true,"backgroundColor":"#FCFCFC","usingComponents":{"ui-popup":"/components/ui-popup/index"}}

```

### /workspace/scratch/4abc3effa72e/project/pages/search/search.wxml

```xml
<page-meta page-style="background:#FCFCFC;" />
<view class="search-page">
  <view class="search-navbar" style="padding-top:{{statusBarHeight}}px;">
    <view class="search-navbar-row" style="height:{{navBarHeight}}px;padding-right:{{capsuleReservedRight}}px;">
      <view class="back" bindtap="goBack">‹</view>
      <view class="search-box">
        <image src="/assets/icons/search.svg" mode="aspectFit" />
        <input value="{{draft}}" placeholder="搜索校园帖子" maxlength="64" confirm-type="search" bindinput="inputKeyword" bindconfirm="submitSearch" />
      </view>
      <text class="search-submit" bindtap="submitSearch">搜索</text>
    </view>
  </view>
  <offline-banner local="{{offline}}" />
  <view class="result-caption" wx:if="{{searched}}">“{{keyword}}” · 共 {{total}} 条结果</view>
  <view class="search-state" wx:if="{{!searched}}">输入关键词，发现校园里的新鲜事</view>
  <view class="search-state" wx:elif="{{!loading && !error && !posts.length}}">没有找到相关帖子，试试其他关键词吧</view>
  <view wx:for="{{posts}}" wx:key="id" class="result-card" data-id="{{item.id}}" bindtap="openPost">
    <view class="author-left">
      <image wx:if="{{item.authorAvatar}}" class="avatar" src="{{item.authorAvatar}}" mode="aspectFill" />
      <view wx:else class="avatar fallback">{{item.avatarText}}</view>
      <view class="author-meta"><text class="nickname">{{item.authorNickname}}</text><text class="time">{{item.createdAt}}</text></view>
    </view>
    <view class="title">{{item.title}}</view>
    <view class="summary">{{item.content}}</view>
    <image wx:if="{{item.image}}" class="post-image" src="{{item.image}}" mode="aspectFill" />
    <view class="result-footer"><text class="topic"># {{item.tag}}</text><text>{{item.likeCount || 0}} 赞 · {{item.commentCount || 0}} 评论</text></view>
  </view>
  <view wx:if="{{loading}}" class="search-state">正在搜索…</view>
  <view wx:elif="{{error}}" class="search-state"><text class="error-text">{{error}}</text><view class="retry" bindtap="retry">重新加载</view></view>
  <view wx:elif="{{posts.length < total}}" class="load-more" bindtap="loadMore">加载更多</view>
  <view wx:elif="{{posts.length}}" class="search-state">已经到底啦</view>
  <ui-popup id="ui-popup" visible="{{uiPopup.visible}}" mode="{{uiPopup.mode}}" tone="{{uiPopup.tone}}" title="{{uiPopup.title}}" message="{{uiPopup.message}}" editable="{{uiPopup.editable}}" placeholder="{{uiPopup.placeholder}}" inputValue="{{uiPopup.inputValue}}" actions="{{uiPopup.actions}}" show-cancel="{{uiPopup.showCancel}}" confirm-text="{{uiPopup.confirmText}}" bindconfirm="onPopupConfirm" bindcancel="onPopupCancel" bindaction="onPopupAction" bindinput="onPopupInput" bindclose="onPopupClose" />
</view>

```

### /workspace/scratch/4abc3effa72e/project/pages/search/search.wxss

```css
@import "../community/community.wxss";
.search-page{min-height:100vh;background:#fcfcfc;color:#18212b;padding-bottom:calc(30rpx + env(safe-area-inset-bottom))}
.search-navbar{background:#fff}
.search-navbar-row{display:flex;align-items:center;padding-left:24rpx;gap:12rpx;box-sizing:border-box}
.search-box{flex:1;min-width:0;display:flex;align-items:center;height:58rpx;border-radius:30rpx;background:#f3f6fa;padding:0 16rpx}
.search-box image{width:28rpx;height:28rpx;flex:none;margin-right:12rpx}
.search-box input{flex:1;min-width:0;font-size:25rpx;color:#26313a}
.search-submit{font-size:24rpx;color:#378be7;flex:none}
.result-caption{padding:24rpx 32rpx;color:#8b959f;font-size:24rpx;overflow-wrap:break-word}
.result-card{padding:28rpx 32rpx;background:#fff;border-bottom:1rpx solid #f0f1f3}
.result-footer{display:flex;align-items:baseline;justify-content:space-between;margin-top:12rpx;color:#99a3ad;font-size:22rpx}
.search-state{text-align:center;padding:50rpx 32rpx;font-size:24rpx;color:#99a3ad}
.error-text{display:block;color:#c76156}
.retry,.load-more{margin:22rpx auto;text-align:center;font-size:25rpx;color:#378be7;padding:18rpx 28rpx}
.retry{display:inline-block;background:#eaf3ff;border-radius:28rpx}

```

### /workspace/scratch/4abc3effa72e/project/tests/backend_smoke.py

```python
"""启动后端后运行：python tests/backend_smoke.py [http://127.0.0.1:8080/api]
只发 GET 请求；不会执行 SQL，也不会改变关注/消息等业务数据。
"""
import json,sys
from urllib.parse import urlencode
from urllib.request import urlopen
from urllib.error import HTTPError
base=(sys.argv[1] if len(sys.argv)>1 else 'http://127.0.0.1:8080/api').rstrip('/')
def get(path,params=None,expected=200):
 url=base+path+('?' + urlencode(params) if params else '')
 try:
  with urlopen(url,timeout=10) as response:status=response.status;body=json.load(response)
 except HTTPError as error:status=error.code;body=json.load(error)
 assert status==expected,(url,status,body)
 assert body['code']==(0 if expected==200 else 400),(url,body)
 return body.get('data')
for params in [None,{'keyword':''},{'keyword':'   '},{'keyword':'字'*65},{'keyword':'台灯','page':'x'}]:
 get('/search',params,400)
for params,limit in [({'keyword':'%','size':999,'page':1},50),({'keyword':'%','size':0,'page':0},1),({'keyword':'台灯'},10),({'keyword':"' OR 1=1 --"},10)]:
 result=get('/search',params)
 assert set(result)=={'list','total'}
 assert isinstance(result['total'],int) and len(result['list'])<=limit
 for post in result['list']:assert {'id','title','content','authorId','authorNickname','liked','followed','createdAt'}<=set(post)
assert isinstance(get('/conversations'),list)
assert isinstance(get('/notifications'),list)
messages=get('/conversations/2/messages',{'size':2})
assert len(messages['list'])<=2 and isinstance(messages['hasMore'],bool)
timetable=get('/timetable')
assert {'termInfo','week','courses'}<=set(timetable)
calendar=get('/calendar')
assert {'termInfo','events'}<=set(calendar)
print('PASS: 后端搜索边界与消息/教学读取冒烟检查；未执行 SQL，未发送写请求。')

```

### /workspace/scratch/4abc3effa72e/project/tests/regression.test.js

```javascript
// 无第三方依赖的行为回归：node --test tests/regression.test.js
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const root = path.resolve(__dirname, '..')

function environment(storage = new Map()) {
  let handler = options => options.fail({ errMsg: 'request:fail connect refused' })
  let requests = []
  let pageConfig
  const cache = new Map()
  const context = vm.createContext({ console, Promise, Map, Set, Date, Math, JSON, Number, String, Array, Object, RegExp, Error, encodeURIComponent, decodeURIComponent,
    setTimeout: fn => { Promise.resolve().then(fn); return 1 }, clearTimeout() {}, setInterval: () => 1, clearInterval() {},
    getApp: () => ({ globalData: { schoolName: '内蒙古大学' } }), getCurrentPages: () => [1, 2], Page: config => { pageConfig = config },
    wx: {
      request(options) { requests.push(options); handler(options) },
      getStorageSync(key) { return storage.has(key) ? JSON.parse(JSON.stringify(storage.get(key))) : '' },
      setStorageSync(key, value) { storage.set(key, JSON.parse(JSON.stringify(value))) },
      getWindowInfo: () => ({ statusBarHeight: 20, windowWidth: 375 }), getMenuButtonBoundingClientRect: () => ({ top: 24, left: 280, height: 32 }),
      navigateTo({ url }) { context.lastUrl = url }, reLaunch({ url }) { context.lastUrl = url }, showToast() {}, stopPullDownRefresh() {}
    }
  })
  function load(filename) {
    const full = path.resolve(root, filename)
    if (cache.has(full)) return cache.get(full).exports
    const module = { exports: {} }; cache.set(full, module)
    const source = fs.readFileSync(full, 'utf8')
    const run = vm.runInContext(`(function(require,module,exports){${source}\n})`, context, { filename: full })
    run(specifier => load(path.resolve(path.dirname(full), specifier + (path.extname(specifier) ? '' : '.js'))), module, module.exports)
    return module.exports
  }
  function page(filename) {
    load(filename)
    const page = { ...pageConfig, data: JSON.parse(JSON.stringify(pageConfig.data)), selectComponent: () => ({}) }
    page.setData = data => {
      for (const [key, value] of Object.entries(data)) {
        const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
        let current = page.data
        parts.slice(0, -1).forEach(part => { current = current[part] ||= {} })
        current[parts.at(-1)] = value
      }
    }
    return page
  }
  return { load, page, context, storage, requests, respond(fn) { handler = fn }, online(data) { handler = options => options.success({ statusCode: 200, data: { code: 0, msg: 'ok', data } }) }, businessError() { handler = options => options.success({ statusCode: 400, data: { code: 400, msg: '业务拒绝', data: null } }) } }
}
const plain = value => JSON.parse(JSON.stringify(value))
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve() }

test('搜索：标题/正文匹配、分页、通配符、转义及空结果', async () => {
  const env = environment(), api = env.load('utils/api.js')
  const mock = env.load('utils/mock-data.js')
  mock.discoveryPosts.push({ id: 91, title: '100% off', content: 'AbC\n校园', author: '测试' }, { id: 92, title: '符号 [.*+?]', content: '其他', author: '测试' }, { id: 93, title: 'A😀B', content: '特殊字符结尾%', author: '测试' })
  let result = await api.searchPosts({ keyword: '台灯' })
  assert.equal(result.offline, true); assert.ok(result.list.some(post => post.title.includes('台灯') || post.content.includes('台灯')))
  assert.equal((await api.searchPosts({ keyword: 'AbC' })).total, 1)
  assert.equal((await api.searchPosts({ keyword: '100\\%' })).total, 1)
  assert.equal((await api.searchPosts({ keyword: '[.*+?]' })).total, 1)
  assert.equal((await api.searchPosts({ keyword: 'A_B' })).total, 1)
  assert.equal((await api.searchPosts({ keyword: '\\' })).total, 1)
  assert.equal((await api.searchPosts({ keyword: '不存在的关键词' })).total, 0)
  result = await api.searchPosts({ keyword: '%', page: 2, size: 2 })
  assert.equal(result.list.length, 2); assert.equal(result.total, mock.discoveryPosts.length)
  assert.equal((await api.searchPosts({ keyword: 'Ab_\n校园' })).total, 1)
  await assert.rejects(api.searchPosts({ keyword: ' ' }), /请输入/)
  await assert.rejects(api.searchPosts({ keyword: '字'.repeat(65) }), /64/)
})

test('搜索：绑定请求参数编码、分页边界、服务端错误不伪装为结果', async () => {
  const env = environment(), api = env.load('utils/api.js')
  env.online({ list: [], total: 0 })
  await api.searchPosts({ keyword: '台灯 & #?%20', page: -1, size: 100 })
  const url = new URL(env.requests.at(-1).url)
  assert.equal(url.searchParams.get('keyword'), '台灯 & #?%20'); assert.equal(url.searchParams.get('size'), '50'); assert.equal(url.searchParams.get('page'), '1')
  env.businessError(); await assert.rejects(api.searchPosts({ keyword: '台灯' }), error => error.server === true && error.message === '业务拒绝')
})

test('所有带离线兜底的写接口：服务端拒绝必须原样抛出', async () => {
  const env = environment(), api = env.load('utils/api.js'); env.businessError()
  const writes = [() => api.likePost(1), () => api.addComment(1, '评论'), () => api.followUser(2, 3), () => api.toggleFavorite(1), () => api.submitFeedback(1, '反馈'), () => api.joinIdol({}), () => api.publishPost({}), () => api.viewPost(1), () => api.createFoodOrder({}), () => api.publishGoods({}), () => api.enrollActivity(1), () => api.addAddress(1, '宿舍'), () => api.sendChatMessage(2, '你好'), () => api.markChatRead(2, 5), () => api.markAllMessagesRead(), () => api.clearChatMessages(2), () => api.muteChat(2, true)]
  for (const write of writes) await assert.rejects(write(), error => error.server && error.message === '业务拒绝')
  assert.equal(env.storage.size, 0)
})

test('新增读接口：业务错误显示错误态，网络失败才演示', async () => {
  const env = environment(), api = env.load('utils/api.js'); env.businessError()
  for (const read of [() => api.getConversations(), () => api.getNotifications(), () => api.getChatMessages(2), () => api.getTimetable(), () => api.getCalendar(), () => api.getProfile()]) await assert.rejects(read(), error => error.server)
})

test('离线聊天：持久化、按对方隔离、已读/免打扰/清空跨重启保留', async () => {
  const storage = new Map(), env = environment(storage), api = env.load('utils/api.js')
  const before = await api.getChatMessages(2); assert.equal(before.list.length, 5)
  const sent = await api.sendChatMessage(2, '离线新消息', { name: '周予安', clientId: 'test-1' })
  assert.equal(sent.offline, true)
  await api.sendChatMessage(2, '离线新消息', { name: '周予安', clientId: 'test-1' })
  const next = environment(storage).load('utils/api.js')
  assert.equal((await next.getChatMessages(2)).list.length, 6)
  assert.equal((await next.getChatMessages(3)).list.length, 1)
  await next.markChatRead(2, sent.id, 1, true)
  assert.equal((await next.getConversations()).find(row => row.id === 2).unread, 0)
  await next.muteChat(2, true, 1, true)
  assert.equal((await next.getConversations()).find(row => row.id === 2).muted, true)
  await next.clearChatMessages(2, 1, true)
  const third = environment(storage).load('utils/api.js')
  assert.equal((await third.getChatMessages(2)).list.length, 0)
  assert.equal((await third.getConversations()).some(row => row.id === 2), false)
  await third.markAllMessagesRead()
  assert.ok((await third.getNotifications()).every(row => !row.unread))
})

test('离线历史分页及本机用户隔离', async () => {
  const env = environment(), api = env.load('utils/api.js')
  const latest = await api.getChatMessages(2, { size: 2 })
  assert.deepEqual(plain(latest.list.map(row => row.id)), [4, 5]); assert.equal(latest.hasMore, true)
  const older = await api.getChatMessages(2, { size: 2, beforeId: 4 })
  assert.deepEqual(plain(older.list.map(row => row.id)), [2, 3])
  assert.equal((await api.getChatMessages(2, {}, 3)).list.length, 0)
})

test('二手离线发布：local id 持久化后仍可打开详情', async () => {
  const storage = new Map(), env = environment(storage), api = env.load('utils/api.js')
  const result = await api.publishGoods({ title: '本机台灯', price: '12', category: '数码', images: ['/assets/home/recommend-lamp.jpg'], image: '/assets/home/recommend-lamp.jpg' })
  const next = environment(storage).load('utils/api.js')
  assert.equal((await next.getGoodsDetail(result.id)).title, '本机台灯')
  assert.ok((await next.getGoodsList()).some(row => row.id === result.id))
})

test('首页：原七条路由保持一致、新入口有效、未知入口告警、换一批真正变序', async () => {
  const env = environment(), page = env.page('pages/index/index.js')
  const routes = { 校园论坛: '/pages/community/community', 外卖: '/pages/food-list/food-list', 二手市场: '/pages/market-list/market-list', 兼职: '/pages/jobs/jobs', 季节活动: '/pages/activities/activities', 作业答疑: '/pages/community/community?tab=2', 论文指导: '/pages/community/community?tab=4' }
  for (const [label, url] of Object.entries(routes)) { page.openFeature({ currentTarget: { dataset: { label } } }); assert.equal(env.context.lastUrl, url) }
  page.openNotifications(); assert.equal(env.context.lastUrl, '/pages/messages/messages?tab=notice')
  page.submitSearch({ detail: { value: '台灯 & %20' } }); assert.equal(decodeURIComponent(env.context.lastUrl.split('keyword=')[1]), '台灯 & %20')
  let warned = false; env.context.console = { ...console, warn() { warned = true } }
  page.openFeature({ currentTarget: { dataset: { label: '不存在' } } }); assert.equal(warned, true); assert.equal(page.data.uiPopup.tone, 'warn')
  const old = plain(page.data.recommendations.map(row => row.label)); page.changeRecommendations(); assert.notDeepEqual(plain(page.data.recommendations.map(row => row.label)), old)
  await page.openRecommendedLamp(); assert.equal(env.context.lastUrl, '/pages/market-detail/market-detail?id=1')
  env.online([]); await page.openRecommendedLamp(); assert.equal(decodeURIComponent(env.context.lastUrl), '/pages/market-list/market-list?category=数码')
})

test('二手分类参数：学习好物实际映射图书', () => {
  const env = environment(), page = env.page('pages/market-list/market-list.js')
  page.onLoad({ category: '学习好物' }); assert.equal(page.data.category, '图书')
})

test('搜索页：新关键词不会被旧响应覆盖、加载更多保留已加载结果', async () => {
  const env = environment(); env.respond(() => {})
  const page = env.page('pages/search/search.js'); page.onLoad({ keyword: '旧词' })
  const first = env.requests.at(-1)
  page.setData({ draft: '新词' }); page.submitSearch(); const second = env.requests.at(-1)
  second.success({ statusCode: 200, data: { code: 0, data: { list: [{ id: 2, title: '新词' }], total: 2 } } }); await flush()
  first.success({ statusCode: 200, data: { code: 0, data: { list: [{ id: 1, title: '旧词' }], total: 1 } } }); await flush()
  assert.equal(page.data.posts[0].id, 2); assert.equal(page.data.page, 1)
  const more = page.loadMore(); env.requests.at(-1).success({ statusCode: 200, data: { code: 0, data: { list: [{ id: 3, title: '新词' }], total: 2 } } }); await more
  assert.deepEqual(plain(page.data.posts.map(row => row.id)), [2, 3])
})

test('搜索页：空结果、失败重试、详情跳转、卸载后不更新', async () => {
  const env = environment(); env.online({ list: [], total: 0 }); const page = env.page('pages/search/search.js'); page.onLoad({ keyword: 'x' }); await flush()
  assert.equal(page.data.loading, false); assert.equal(page.data.posts.length, 0); assert.equal(page.data.searched, true)
  env.businessError(); await page.loadResults(true); assert.equal(page.data.error, '业务拒绝')
  env.online({ list: [{ id: 5 }], total: 1 }); await page.retry(); assert.equal(page.data.error, '')
  page.openPost({ currentTarget: { dataset: { id: 5 } } }); assert.equal(env.context.lastUrl, '/pages/post-detail/post-detail?id=5')
  env.respond(() => {}); const pending = page.loadResults(true); const request = env.requests.at(-1); page.onUnload()
  request.success({ statusCode: 200, data: { code: 0, data: { list: [{ id: 6 }], total: 1 } } }); await pending
  assert.equal(page.data.posts.length, 0)
})

test('弹层：原始 detail 与完整事件均能选中操作/获取输入；异步失败显示错误', async () => {
  const env = environment(), popup = env.load('utils/ui-popup-helper.js'), page = env.page('pages/index/index.js')
  let selected
  popup.sheet(page, { actions: [{ label: '北京大学' }], action: payload => { selected = payload.item.label } })
  page._popupAction({ index: 0 }); await flush(); assert.equal(selected, '北京大学')
  let value
  popup.modal(page, { editable: true, confirm: payload => { value = payload.inputValue } }); page._popupConfirm({ detail: { value: '%20' } }); await flush(); assert.equal(value, '%20')
  popup.modal(page, { confirm: async () => { throw new Error('保存失败') } }); page._popupConfirm({}); await flush(); assert.equal(page.data.uiPopup.message, '保存失败')
})

test('全局演示状态：回退可观察、成功后端读取恢复，解除订阅', async () => {
  const env = environment(), api = env.load('utils/api.js'), mode = env.load('utils/data-mode.js'); const values = []
  const unsubscribe = mode.subscribe(value => values.push(value))
  await api.searchPosts({ keyword: '台灯' }); env.online({ list: [], total: 0 }); await api.searchPosts({ keyword: '台灯' })
  assert.deepEqual(values, [false, true, false]); unsubscribe(); await api.sendChatMessage(2, 'ok'); assert.equal(values.length, 3)
})

test('教学数据从 API 返回；周次跟随后端学期而非固定日期', async () => {
  const env = environment(), api = env.load('utils/api.js'), term = env.load('utils/term.js')
  assert.equal((await api.getTimetable({ week: 1 })).courses.length, 7)
  assert.equal((await api.getCalendar()).events.length, 7)
  assert.equal(term.getTermWeek(new Date(2026, 9, 14), '2026-10-05'), 2)
})

test('关注请求体保留指定 follower；社区分享无暂时性死区错误', async () => {
  const env = environment(), api = env.load('utils/api.js'); env.online({ followed: true })
  await api.followUser(2, 3); assert.equal(env.requests.at(-1).data.userId, 3)
  const page = env.page('pages/community/community.js'); page.setData({ posts: [{ id: 7, title: '分享测试' }] })
  assert.equal(page.onShareAppMessage({ target: { dataset: { id: 7 } } }).path, '/pages/post-detail/post-detail?id=7')
})

test('全局演示提示：一个成功接口不能掩盖其他离线接口', async () => {
  const env = environment(), api = env.load('utils/api.js'), mode = env.load('utils/data-mode.js'); let demo
  mode.subscribe(value => { demo = value })
  await api.getChatMessages(2)
  env.online([]); await api.getNotifications(); assert.equal(demo, true)
  env.online({ list: [], hasMore: false, muted: false }); await api.getChatMessages(2); assert.equal(demo, false)
})

test('聊天页：发送失败保留输入，离线发送/重载/清空可操作', async () => {
  const env = environment(), page = env.page('pages/chat/chat.js')
  page.onLoad({ id: '2', name: '周%20' }); assert.equal(page.data.name, '周%20')
  await page.loadMessages(); assert.equal(page.data.messages.length, 5)
  page.setData({ inputValue: '新消息' }); env.businessError(); await page.sendMessage()
  assert.equal(page.data.inputValue, '新消息'); assert.equal(page.data.messages.length, 5)
  env.respond(options => options.fail({ errMsg: '断网' })); await page.sendMessage(); await flush()
  assert.ok(page.data.messages.some(row => row.content === '新消息')); assert.equal(page.data.inputValue, '')
  await page.clearMessages(); assert.equal(page.data.messages.length, 0)
  await page.loadMessages(); assert.equal(page.data.messages.length, 0); page.onUnload()
})

test('发现页：搜索进入结果页、点击帖子打开详情', async () => {
  const env = environment(), page = env.page('pages/discover/discover.js')
  page.openSearch(); page._popupConfirm({ value: '台灯&%20' }); await flush()
  assert.equal(decodeURIComponent(env.context.lastUrl.split('keyword=')[1]), '台灯&%20')
  page.openPost({ currentTarget: { dataset: { id: 3 } } }); assert.equal(env.context.lastUrl, '/pages/post-detail/post-detail?id=3')
})

```

### /workspace/scratch/4abc3effa72e/project/tests/validate_project.py

```python
"""静态工程检查；不会运行任何数据库脚本。python tests/validate_project.py"""
from pathlib import Path
import json,re,subprocess
from html.parser import HTMLParser
root=Path(__file__).resolve().parents[1]
errors=[]
class WxmlParser(HTMLParser):
 def __init__(self,path): super().__init__(convert_charrefs=False);self.stack=[];self.path=path
 def handle_starttag(self,tag,attrs): self.stack.append(tag)
 def handle_startendtag(self,tag,attrs): pass
 def handle_endtag(self,tag):
  if not self.stack or self.stack[-1]!=tag:errors.append(f'{self.path}: 闭合标签不匹配 {tag}')
  else:self.stack.pop()
for p in root.rglob('*.json'):
 try:json.loads(p.read_text())
 except Exception as e:errors.append(f'{p}: {e}')
for p in root.rglob('*.js'):
 result=subprocess.run(['node','--check',str(p)],capture_output=True,text=True)
 if result.returncode:errors.append(result.stderr)
for p in root.rglob('*.wxml'):
 parser=WxmlParser(p.relative_to(root));parser.feed(p.read_text())
 if parser.stack:errors.append(f'{p}: 未闭合标签 {parser.stack}')
app=json.loads((root/'app.json').read_text());pages=set(app['pages'])
for page in pages:
 for ext in ['.js','.json','.wxml','.wxss']:
  if not (root/(page+ext)).is_file():errors.append(f'缺少已注册页面文件 {page+ext}')
 if 'offline-banner' not in (root/(page+'.wxml')).read_text():errors.append(f'页面未挂全局演示提示 {page}')
# 所有本地静态资源路径和模块 require 均必须存在。
for p in list(root.rglob('*.js'))+list(root.rglob('*.wxml'))+list(root.rglob('*.wxss')):
 s=p.read_text()
 for asset in re.findall(r'[\'"(](/assets/[^\'"()\s]+)',s):
  if '{{' not in asset and not (root/asset.lstrip('/')).exists():errors.append(f'{p}: 资源不存在 {asset}')
 if p.suffix=='.js':
  for spec in re.findall(r"require\(['\"](\.[^'\"]+)['\"]\)",s):
   target=p.parent/spec
   if not target.exists() and not target.with_suffix('.js').exists():errors.append(f'{p}: require 不存在 {spec}')
 for page in re.findall(r'[\'"`](/pages/[\w-]+/[\w-]+)',s):
  if page.lstrip('/') not in pages:errors.append(f'{p}: 路由未注册 {page}')
 for css in re.findall(r'@import\s+[\'\"]([^\'\"]+)',s):
  if not (p.parent/css).exists():errors.append(f'{p}: 样式导入不存在 {css}')
for p in root.glob('pages/*/*.json'):
 settings=json.loads(p.read_text())
 for component,target in settings.get('usingComponents',{}).items():
  base=root/target.lstrip('/') if target.startswith('/') else p.parent/target
  for ext in ['.js','.json','.wxml','.wxss']:
   if not Path(str(base)+ext).exists():errors.append(f'{p}: 组件缺失 {target+ext}')
# 注解 SQL XML 必须闭合，禁止动态 ${} 插值和执行 SQL 的配置。
mapper=(root/'backend/src/main/java/com/campuslife/mapper/CampusMapper.java').read_text()
assert '${' not in mapper
assert 'q.like(Post::getTitle,keyword).or().like(Post::getContent,keyword)' in (root/'backend/src/main/java/com/campuslife/service/ForumService.java').read_text()
assert 'body.get("userId")' in (root/'backend/src/main/java/com/campuslife/controller/ForumController.java').read_text()
for statement in re.findall(r'"""(.*?)"""',mapper,re.S):
 if '<script>' in statement:
  import xml.etree.ElementTree as ET
  try: ET.fromstring(statement.strip())
  except Exception as e:errors.append(f'CampusMapper XML: {e}')
sql=(root/'backend/src/main/resources/db/module-messages-academics.sql').read_text()
assert len(re.findall('CREATE TABLE IF NOT EXISTS',sql))==6
assert all('INSERT IGNORE' in insert for insert in re.findall(r'INSERT.*?;',sql,re.S))
assert 'spring.sql.init' not in (root/'backend/src/main/resources/application.yml').read_text()
if errors:
 print('\n'.join(errors));raise SystemExit(1)
print(f'PASS: {len(pages)} 个页面四件套、所有 JS/JSON/WXML、静态资源/路由/组件与 SQL 参数静态检查；未执行数据库脚本。')

```

### /workspace/scratch/4abc3effa72e/project/utils/data-mode.js

```javascript
// 按接口记录连接失败，避免一个成功请求掩盖其他接口的离线回退。
const failed = new Set()
const listeners = []
let localDemo = false
let demo = false
function publish() {
  const next = localDemo || failed.size > 0
  if (demo === next) return
  demo = next
  listeners.slice().forEach(listener => listener(demo))
}
function keyFor(url) { return url.split('?')[0].replace(/\/conversations\/([^/]+)\/(read|clear|mute)$/, '/conversations/$1/messages').replace('/messages/read-all', '/conversations') }
function connection(url, online) {
  const key = keyFor(url)
  if (online) failed.delete(key)
  else failed.add(key)
  if (online) localDemo = false
  publish()
}
function setDemo(value) { localDemo = value; publish() }
function subscribe(listener) {
  listeners.push(listener)
  listener(demo)
  return () => { const index = listeners.indexOf(listener); if (index >= 0) listeners.splice(index, 1) }
}
module.exports = { setDemo, subscribe, connection }

```

## 附录C：修改/删除文件清晰 diff

补丁以原上传代码为基准。

```diff
--- a/AI_HANDOFF.md
+++ b/AI_HANDOFF.md
@@ -1,141 +1,40 @@
-# 给 AI 的项目说明（请先读这里）
+# 项目交接说明（补齐后）
 
-> 这份文件是专门为把本项目交给 AI 助手分析而准备的。项目原有文档见 `README.md`、`README_BACKEND.md`、`docs/`。
+本项目是原生微信小程序 + Java 17 / Spring Boot 3.2.5 / MyBatis-Plus 3.5.6 / MySQL 8，测试 AppID 为 touristappid。前端现有 23 页，后端 5 个 Controller、43 个端点。不要换成 uni-app/Taro，不要重新实现已接通的外卖、二手、论坛等模块。
 
-## 一、这是什么
+## 数据与交互约定
 
-一个**原生微信小程序**（校园生活 App）+ 一个 **Spring Boot 后端**，前后端分离。
+- 页面通过 `utils/api.js` 取数；弹层通过 `utils/ui-popup-helper.js` 和 `components/ui-popup`。
+- 统一响应 `{code:0,msg:"ok",data:...}`，HTTP 非 2xx 或 code 非 0 标记 `error.server=true`。
+- 新增读接口在网络失败时使用本地演示数据，服务端错误显示失败状态。已有部分读接口保留原来的广义失败兜底。
+- 带兜底的写接口仅网络失败时演示，`error.server` 必须继续抛出；投票和资料编辑仍需后端。离线写入不自动同步。
+- `components/offline-banner` 在全部页面提供演示提示，`utils/data-mode.js` 按接口记录连接异常，避免其他成功请求掩盖失败接口。
+- `app.json` 为 custom 导航。新增页面使用 `utils/navigation.js`。
 
-- 前端：原生小程序（WXML/WXSS/JS/JSON），**不是** uni-app / Taro / 任何框架
-- 后端：Java 17 · Spring Boot 3.2 · MyBatis-Plus · MySQL 8 · Lombok
-- 前端 AppID 为测试号 `touristappid`，可直接在微信开发者工具中编译运行
+## 已补齐
 
-## 二、关键约定（读代码前务必知道）
+1. 首页 `FEATURE_ROUTES` 表驱动，12 个标签均覆盖，未配置入口 warn + 用户提示；原七条路由保持不变。
+2. `GET /api/search` + 搜索结果四件套，首页/发现页接线，真实分页、加载/空/错误态、本地 LIKE 兜底。
+3. 首页活动、图书分类、奶茶店、动态台灯详情、广告专题、通知 tab 与推荐重排；二手页现在读取 category，学习好物映射图书。
+4. 消息/会话/通知后端，聊天文本持久化、游标分页、轮询、已读、免打扰和个人清空游标；离线本机持久化。
+5. 课表/校历数据库与接口，页面和个人中心课程摘要接线，按真实学期起始日计算周次。
+6. 修复若干旧写接口吞业务错误、弹层 actions 缺少绑定与 detail 传法、发现页帖子只弹 toast、在线发布混入本机 mock、社区分享临时性死区错误、三处错误资源路径及本机二手 local-id 详情问题。
+7. 无引用的根目录 index.js/api.js 与首页 switchTab 已移除。后端/测试目录不进入小程序打包。
 
-### 1. 接口适配层是唯一的出入口
+## SQL 与运行
 
-**所有页面只通过 `utils/api.js` 取数据**，绝不直接调用 `wx.request`，也不直接读 `utils/mock-data.js`（`timetable` / `calendar` / `profile` 例外，见下文）。
+全新库：手动执行 `backend/src/main/resources/db/schema.sql`（原有 20 表），然后 `module-messages-academics.sql`（新增 6 表）。已有库只执行新增脚本。原 schema 部分旧种子不幂等，新脚本可重复执行。工程不会自动执行 SQL。
 
-```
-页面 JS  →  utils/api.js  →  ① wx.request 打后端
-                          →  ② 失败时退回 utils/mock-data.js（离线兜底）
-```
+配置 application.yml 数据库账号，`cd backend && mvn spring-boot:run`；微信开发者工具导入根目录即可。BASE_URL 仍为 `http://127.0.0.1:8080/api`。
 
-**推论**：要接真实后端，只需改 `utils/api.js` 内部实现，21 个页面一行都不用动。
+## 验证与边界
 
-### 2. 后端统一响应格式
+`node --test tests/regression.test.js` 和 `python tests/validate_project.py` 已通过。`python tests/backend_smoke.py` 为启动后端后的只读检查，交付环境没有可用 Maven/JDK、MySQL 和微信开发者工具，不能声称后端编译/数据库联调/真机验收已经通过。
 
-```json
-{ "code": 0, "msg": "...", "data": { ... } }
-```
+- 消息路由 id 和接口 peerId 均指对方用户 ID。避免恢复旧 mock 的会话序号。
+- 教学数据由校园后台表维护，未接入外部教务系统。初始 SQL 是演示种子，不是已核验的真实校历。
+- 广告专题未配置官方活动链接，不提供虚构的活动价格、资格或外链。
+- 登录鉴权、持久图片上传仍为原项目边界；userId=1 是演示约定。
+- 个人主页仍只筛选最多 50 条帖子、粉丝初值为旧演示数字；个人中心统计来自真实接口。这一旧主页的数据完整性问题没有扩大改造。
 
-前端判断：`res.statusCode` 在 200–299 **且** `body.code === 0` 才算成功（见 `utils/api.js` 的 `request()`）。
-
-### 3. 「读兜底、写不兜底」原则 —— 重要
-
-`utils/api.js` 里区分了两类接口：
-
-```js
-// 读接口：网络失败 → 静默退回 mock，保证无后端也能演示
-getForumPosts().catch(() => wait(mockForum(...)))
-
-// 写接口：只有【网络失败】才兜底；【服务端业务错误】必须抛给用户看
-createFoodOrder().catch(error => {
-  if (error.server) throw error      // ← 例如"未达起送价""重复投票"，不能吞掉
-  return wait(mock.makeFoodOrder(payload))
-})
-```
-
-后端错误被标记为 `error.server = true`（见 `request()`）。**改代码时不要破坏这个区分**，否则用户会以为下单成功了，其实服务端拒单了。
-
-### 4. 各模块离线数据在 `utils/mock-data.js`
-
-该文件 214 行，导出：`discoveryPosts, conversations, notifications, profile, termInfo, timetableCourses, calendarEvents, idolContestants`，另在文件后半段挂了 `foodShops / foodMenus / goods / jobs / activities` 等外卖、二手、兼职、活动数据。
-
-## 三、目录结构
-
-```
-campus-life-miniapp/
-├── app.js / app.json / app.wxss     小程序入口与全局配置
-├── project.config.json              开发者工具配置（appid: touristappid）
-├── sitemap.json
-├── api.js                           ⚠️ 根目录的旧版残留，见第四节 P3-2
-├── index.js                         ⚠️ 同上，死文件
-├── pages/                           21 个页面，每个含 .js/.wxml/.wxss/.json
-├── components/
-│   ├── ui-popup/                    通用弹层（sheet / modal / toast）
-│   └── bottom-nav/                  底部导航（5 个页面复用，带未读角标）
-├── utils/
-│   ├── api.js                       ★ 接口适配层（核心）
-│   ├── mock-data.js                 ★ 离线假数据（核心）
-│   ├── food.js                      外卖费用/满减计算
-│   ├── navigation.js                胶囊按钮/状态栏高度计算
-│   ├── term.js                      学期周次计算
-│   ├── format-time.js               时间格式化
-│   ├── ui-popup-helper.js           弹层调用封装
-│   └── food.wxss
-├── assets/                          37 个本地图片（webp/svg），不依赖网络
-├── docs/                            项目文档（不打包进小程序）
-└── backend/                         Spring Boot 后端
-    ├── pom.xml
-    ├── test-goods2.json             接口测试用的请求样本
-    ├── test-goods3.json
-    └── src/main/
-        ├── java/com/campuslife/
-        │   ├── CampusLifeApplication.java
-        │   ├── config/              GlobalExceptionHandler / MybatisPlusConfig / WebConfig(CORS)
-        │   ├── controller/          ★ Forum / User / Idol / Biz 共 4 个，32 个端点
-        │   ├── service/             Forum / User / Idol / Biz 共 4 个
-        │   ├── mapper/              17 个 MyBatis-Plus Mapper
-        │   ├── entity/              16 个实体
-        │   └── dto/                 11 个 DTO（含 ApiResponse 统一响应）
-        └── resources/
-            ├── application.yml      ★ 数据库连接配置需自行修改
-            └── db/
-                ├── schema.sql                         21 张表 + 测试数据
-                ├── module-food.sql                    外卖模块
-                └── module-market-jobs-activities.sql  二手/兼职/活动模块
-```
-
-## 四、已知问题（分析时请一并考虑）
-
-这些是**已知的、经代码核对确认的**问题，不是猜测：
-
-| 编号 | 位置 | 问题 |
-|---|---|---|
-| P0-1 | `pages/index/index.js:78-111` | `openFeature()` 是 if 白名单，末尾无兜底分支，未登记的入口会静默降级为一句 toast，且不报错 |
-| P0-2 | `pages/index/index.js:68-72`、`pages/discover/discover.js` | **搜索功能整体缺失**：后端无 `/api/search` 端点，前端无搜索结果页，只弹 toast |
-| P1 | `pages/index/index.wxml` 多处 | 9 个入口点击只弹「功能开发中」：开学优惠、学习好物、奶茶优惠、闲置台灯、联想广告、通知铃铛、换一批等 |
-| P2-1 | `pages/chat/chat.js:13-19` | 聊天消息**硬编码在 data 中**（5 条），发送只进内存，退出即失 |
-| P2-2/3 | `utils/api.js:111-112` | `getConversations()` / `getNotifications()` **连 `request()` 都没调**，直接 `wait(mock.xxx)` |
-| P2-5/6 | `pages/timetable/timetable.js:39`、`pages/calendar/calendar.js:30` | 课表与日历直接 require `mock-data`，**绕过 api.js**，无后端支持 |
-| P3-1 | `utils/api.js:81` vs `backend/.../ForumController.java:11` | `followUser` 前端发 body，后端收 `@RequestParam`；后端写了兼容逻辑但**未实测** |
-| P3-2 | 根目录 `index.js`（122 行） | **死文件**：`app.json` 未注册，永不加载；内容比 `pages/index/index.js` 更旧 |
-| P3-4 | `utils/api.js` 全局 | 后端未启动时代码静默降级到 mock，**UI 未提示用户当前是演示数据** |
-| P3-5 | `README_BACKEND.md` | 文档写"五张表"，实际 `schema.sql` 已有 21 张表，**文档未同步** |
-
-### ⚠️ 关于 P2-2/3 的补充
-
-`getConversations()` 和 `getNotifications()` 的实现是 `return wait(mock.conversations)` —— 意味着**即使后端跑起来，聊天和通知也依然是假数据**，因为请求根本没发出去。这是比其他 P2 项更彻底的"假"。
-
-## 五、如何运行
-
-### 前端
-1. 微信开发者工具 → 导入项目 → 选择本目录
-2. AppID 用测试号即可（`project.config.json` 里已是 `touristappid`）
-3. 点「编译」
-
-### 后端
-1. 启动 MySQL 8.0，依次执行 `backend/src/main/resources/db/schema.sql`、`module-food.sql`、`module-market-jobs-activities.sql`
-2. 修改 `backend/src/main/resources/application.yml` 的数据库账号密码
-3. `cd backend && mvn spring-boot:run`，服务跑在 `http://127.0.0.1:8080/api`
-4. 前端 `utils/api.js` 顶部的 `BASE_URL` 已指向该地址
-
-> 后端未启动时，前端**所有功能依然可用**，只是数据来自本地 mock。
-
-## 六、请你（AI）注意
-
-1. **以代码实际行为为准**，不要只信 README —— 本项目文档与实际代码已有若干不同步（见 P3-5）。
-2. 分析「哪里没实现」时，请区分三类：①纯占位 toast ②后端无接口、页面用假数据 ③后端有接口但前端没接。三者的修复成本完全不同。
-3. 本项目**不存在「整页未开发」**的功能，21 个页面全部有真实实现。
-4. 修改 `utils/api.js` 时请保持第四节 3 的「读兜底、写不兜底」原则。
+详见 `docs/IMPLEMENTATION_REPORT.md`：逐任务说明、完整新增源码、修改 diff、手动 SQL 与人工验收清单。
--- a/README.md
+++ b/README.md
@@ -34,4 +34,13 @@
 
 ## 说明
 
-项目目前使用测试 AppID，可直接预览页面。所有列表数据位于 `utils/mock-data.js`，页面通过 `utils/api.js` 获取数据。接入真实后端时，将 `utils/api.js` 内部替换成 `wx.request` 即可，页面数据结构无需重写。详细字段约定见 `docs/API_INTEGRATION.md`。
+本项目同时提供真实 Spring Boot 接口和离线演示。页面通过 `utils/api.js` 获取数据；新增搜索、消息、课表、校历已经前后端接线，不需要替换 API 层。后端配置、手动 SQL 和启动步骤见 `README_BACKEND.md`，详细字段见 `docs/API_INTEGRATION.md`。
+
+本次补齐代码、完整 diff、人工验收项见 `docs/IMPLEMENTATION_REPORT.md`。前端现有23页；原 schema 为20表，新增消息与教学模块6表。现有37个本地图片/图标已复用，无新增图像依赖。
+
+```bash
+node --test tests/regression.test.js
+python tests/validate_project.py
+```
+
+这些测试在交付环境已通过；后端实际构建、MySQL 联调和微信开发者工具/真机视觉仍需本地验收。投票、资料编辑等原功能需要后端；离线写入属于本机演示，不会自动同步。登录鉴权和持久图片上传沿用原项目的待接入边界。
--- a/README_BACKEND.md
+++ b/README_BACKEND.md
@@ -1,12 +1,47 @@
 # 校园生活社区后端
 
-技术栈：Java 17、Spring Boot 3.2、Spring Web、MyBatis-Plus、MySQL 8.0、Lombok。
+技术栈：Java 17、Spring Boot 3.2.5、MyBatis-Plus 3.5.6、MySQL 8.0、Lombok。此次没有新增第三方依赖。
 
 ## 本地启动
 
-1. 启动 MySQL 8.0，并执行 `backend/src/main/resources/db/schema.sql`。脚本会创建 `campus_life` 库、五张表和 10 条帖子测试数据。
+1. 启动 MySQL 8.0。全新数据库先**手动**执行 `backend/src/main/resources/db/schema.sql`（原有 20 张表与演示种子，已包含外卖/二手/兼职/活动模块），再执行 `backend/src/main/resources/db/module-messages-academics.sql`（新增 6 张表，共 26 张表）。已有数据库只执行新增脚本即可。
 2. 修改 `backend/src/main/resources/application.yml` 中的数据库账号密码。
 3. 进入 `backend` 目录执行 `mvn spring-boot:run`，接口地址为 `http://127.0.0.1:8080/api`。
-4. 用微信开发者工具打开项目根目录。后端未启动时，社区页显示离线演示模式并使用本地 mock；启动后端并完成数据库初始化后会自动请求真实数据。
+4. 用微信开发者工具打开项目根目录，AppID 保持 `touristappid`。本地开发配置已关闭合法域名校验。
 
-生产环境请使用 HTTPS，并在微信公众平台配置 request 合法域名；当前 CORS 仅为开发者工具本地调试开放。
+项目不会自动执行 SQL。新增脚本使用 `CREATE TABLE IF NOT EXISTS` 和带唯一键的 `INSERT IGNORE`，重复执行不会覆盖聊天、已读、免打扰或课程数据。原 `schema.sql` 的部分旧种子 INSERT 不具备幂等性，不要把完整 schema 当作每次升级脚本重复执行。`module-food.sql` 和 `module-market-jobs-activities.sql` 是原有模块的独立脚本；完整 schema 已包含这些模块，新库无需再重复执行。
+
+可从项目根目录手动运行以下命令（需安装对应运行环境）：
+
+```bash
+mysql -u root -p < backend/src/main/resources/db/schema.sql
+mysql -u root -p < backend/src/main/resources/db/module-messages-academics.sql
+cd backend
+mvn clean package
+mvn spring-boot:run
+```
+
+## 消息与教学数据
+
+- 消息记录存于 `chat_message`，会话列表由双方消息聚合。路由里的 `id` / 接口里的 `peerId` 都是**对方用户 ID**。
+- `chat_preference` 保存每个用户的清空游标和免打扰设置，清空只影响操作用户，未物理删除对方记录。
+- `notification` 保存通知与已读状态，点赞、评论、关注、收藏会生成相关通知；GET 返回最近 100 条。
+- `academic_term` 按学校维护学期，`course_schedule` 按用户、学期和周次维护课程，`calendar_event` 保存学期事件。默认学期按用户资料的 school 匹配最新 active=1 的配置。
+- SQL 中的数据是演示种子。没有接入外部教务平台；管理员需维护这些表。若换学期，新增学期和对应课表/事件，确认同校 active 配置正确。
+- 聊天只新增文本发送；种子图片可展示，没有扩展为图片/语音发送。
+- 前端聊天在可见时每 5 秒轮询；隐藏/卸载时停止。
+
+## 验证
+
+项目根目录运行：
+
+```bash
+node --test tests/regression.test.js
+python tests/validate_project.py
+# 先完成 SQL 初始化并启动后端，再运行；只发 GET，不执行 SQL、不写业务数据。
+python tests/backend_smoke.py
+```
+
+本次交付环境的前端行为测试和静态检查已通过。该环境没有可用 Maven/JDK、MySQL 和微信开发者工具，后端构建、真实 HTTP/MySQL 联调与真机视觉验收仍需在本地完成，不能把静态检查视为后端编译通过。
+
+生产仍需真实登录鉴权、持久图片上传、HTTPS 与微信 request 合法域名；当前 userId=1 为演示约定，CORS 仅面向本地调试。
--- a/api.js
+++ /dev/null
@@ -1,48 +0,0 @@
-const mock = require('./mock-data')
-
-const BASE_URL = 'http://127.0.0.1:8080/api'
-const CURRENT_USER_ID = 1
-
-function clone(data) { return JSON.parse(JSON.stringify(data)) }
-function wait(data, delay = 160) { return new Promise(resolve => setTimeout(() => resolve(clone(data)), delay)) }
-function request(url, method = 'GET', data = {}) {
-  return new Promise((resolve, reject) => {
-    wx.request({
-      url: `${BASE_URL}${url}`,
-      method,
-      data,
-      header: { 'content-type': 'application/json' },
-      success(res) {
-        const body = res.data || {}
-        if (res.statusCode >= 200 && res.statusCode < 300 && body.code === 0) resolve(body.data)
-        else reject(new Error(body.msg || `请求失败（${res.statusCode}）`))
-      },
-      fail: reject
-    })
-  })
-}
-
-function mockForum(tag, page, size) {
-  const all = (mock.discoveryPosts || []).map(p => ({
-    id: p.id, authorNickname: p.author || '校园同学', authorAvatar: p.avatar || '', title: p.title,
-    content: p.content, image: p.image || '', tag: p.tag || '校园日常', location: p.location || '',
-    likeCount: p.likes || 0, commentCount: p.comments || 0, liked: !!p.liked, followed: !!p.followed,
-    createdAt: p.time || '刚刚'
-  })).filter(p => !tag || tag === '推荐' || p.tag.includes(tag) || (tag === '生活' && p.tag === '校园日常') || (tag === '社团' && p.tag === '社团活动') || (tag === '二手' && p.tag === '校园市集'))
-  const start=(page-1)*size
-  return { list: all.slice(start,start+size), total: all.length }
-}
-function getForumPosts(params={}) {
-  const {tag='', page=1, size=10} = params
-  return request(`/posts?tag=${encodeURIComponent(tag)}&page=${page}&size=${size}`, 'GET').catch(() => wait({...mockForum(tag,page,size), offline:true}))
-}
-function likePost(id, userId=CURRENT_USER_ID) { return request(`/posts/${id}/like`, 'POST', {userId}).catch(() => wait({liked:true})) }
-function addComment(id, content, userId=CURRENT_USER_ID) { return request(`/posts/${id}/comments`, 'POST', {content,userId}).catch(() => wait({id:Date.now(),content})) }
-function followUser(id, userId=CURRENT_USER_ID) { return request(`/users/${id}/follow`, 'POST', {userId}).catch(() => wait({followed:true})) }
-function getHotTopics() { return request('/topics/hot').catch(() => wait([{tag:'校园日常',count:18},{tag:'学习',count:12},{tag:'社团活动',count:9}])) }
-function getDiscoveryPosts() { return wait(mock.discoveryPosts) }
-function getConversations() { return wait(mock.conversations) }
-function getNotifications() { return wait(mock.notifications) }
-function getProfile() { return wait(mock.profile) }
-function publishPost(payload) { return wait({ id: Date.now(), ...payload }, 280) }
-module.exports = { BASE_URL, request, getDiscoveryPosts, getConversations, getNotifications, getProfile, publishPost, getForumPosts, likePost, addComment, followUser, getHotTopics }
--- a/app.json
+++ b/app.json
@@ -20,7 +20,9 @@
     "pages/market-detail/market-detail",
     "pages/market-publish/market-publish",
     "pages/jobs/jobs",
-    "pages/activities/activities"
+    "pages/activities/activities",
+    "pages/search/search",
+    "pages/campus-offer/campus-offer"
   ],
   "window": {
     "navigationStyle": "custom",
@@ -29,7 +31,8 @@
   },
   "style": "v2",
   "usingComponents": {
-    "ui-popup": "/components/ui-popup/index"
+    "ui-popup": "/components/ui-popup/index",
+    "offline-banner": "/components/offline-banner/index"
   },
   "sitemapLocation": "sitemap.json"
 }
--- a/backend/src/main/java/com/campuslife/config/GlobalExceptionHandler.java
+++ b/backend/src/main/java/com/campuslife/config/GlobalExceptionHandler.java
@@ -1,3 +1,3 @@
 package com.campuslife.config;
 import com.campuslife.dto.ApiResponse; import org.springframework.web.bind.annotation.*; import org.springframework.http.ResponseEntity; import org.springframework.web.bind.MethodArgumentNotValidException;
-@RestControllerAdvice public class GlobalExceptionHandler { @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<ApiResponse<Object>> valid(MethodArgumentNotValidException e){return ResponseEntity.badRequest().body(new ApiResponse<>(400,e.getBindingResult().getFieldError()!=null?e.getBindingResult().getFieldError().getDefaultMessage():"参数错误",null));} @ExceptionHandler(Exception.class) public ResponseEntity<ApiResponse<Object>> error(Exception e){return ResponseEntity.internalServerError().body(new ApiResponse<>(500,e.getMessage()==null?"服务器错误":e.getMessage(),null));} }
+@RestControllerAdvice public class GlobalExceptionHandler { @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<ApiResponse<Object>> valid(MethodArgumentNotValidException e){return ResponseEntity.badRequest().body(new ApiResponse<>(400,e.getBindingResult().getFieldError()!=null?e.getBindingResult().getFieldError().getDefaultMessage():"参数错误",null));} @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) public ResponseEntity<ApiResponse<Object>> badRequest(Exception e){return ResponseEntity.badRequest().body(new ApiResponse<>(400,e.getMessage()==null?"参数错误":e.getMessage(),null));} @ExceptionHandler(Exception.class) public ResponseEntity<ApiResponse<Object>> error(Exception e){return ResponseEntity.internalServerError().body(new ApiResponse<>(500,e.getMessage()==null?"服务器错误":e.getMessage(),null));} }
--- a/backend/src/main/java/com/campuslife/controller/ForumController.java
+++ b/backend/src/main/java/com/campuslife/controller/ForumController.java
@@ -2,6 +2,7 @@
 import com.campuslife.dto.*; import com.campuslife.service.ForumService; import jakarta.validation.Valid; import lombok.RequiredArgsConstructor; import org.springframework.web.bind.annotation.*; import java.util.*;
 @RestController @RequestMapping("/api") @RequiredArgsConstructor public class ForumController { private final ForumService service;
  @GetMapping("/posts") public ApiResponse<Map<String,Object>> posts(@RequestParam(defaultValue="") String tag,@RequestParam(defaultValue="") String scope,@RequestParam(defaultValue="1") Long userId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size){return ApiResponse.ok(service.page(tag,scope,Math.max(userId,1),Math.max(page,1),Math.min(Math.max(size,1),50)));}
+ @GetMapping("/search") public ApiResponse<Map<String,Object>> search(@RequestParam String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,@RequestParam(defaultValue="1") Long userId){String text=keyword.trim();if(text.isEmpty())throw new IllegalArgumentException("请输入搜索关键词");if(text.length()>64)throw new IllegalArgumentException("关键词不能超过64字");return ApiResponse.ok(service.search(text,Math.max(userId,1),Math.max(page,1),Math.min(Math.max(size,1),50)));}
  @PostMapping("/posts") public ApiResponse<Object> create(@Valid @RequestBody CreatePostRequest r){return ApiResponse.ok(service.create(r));}
  @GetMapping("/posts/{id}") public ApiResponse<ForumPostDTO> detail(@PathVariable Long id,@RequestParam(defaultValue="1") Long userId){return ApiResponse.ok(service.detail(id,Math.max(userId,1)));}
  @PostMapping("/posts/{id}/like") public ApiResponse<Map<String,Object>> like(@PathVariable Long id,@Valid @RequestBody LikeRequest r){return ApiResponse.ok(service.toggleLike(id,r.getUserId()));}
--- a/backend/src/main/java/com/campuslife/service/ForumService.java
+++ b/backend/src/main/java/com/campuslife/service/ForumService.java
@@ -1,7 +1,7 @@
 package com.campuslife.service;
 import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper; import com.baomidou.mybatisplus.extension.plugins.pagination.Page; import com.campuslife.dto.*; import com.campuslife.entity.*; import com.campuslife.mapper.*; import lombok.RequiredArgsConstructor; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import java.time.LocalDateTime; import java.util.*;
 @Service @RequiredArgsConstructor public class ForumService {
- private final PostMapper postMapper; private final UserMapper userMapper; private final PostLikeMapper likeMapper; private final CommentMapper commentMapper; private final FollowMapper followMapper; private final PostViewMapper viewMapper; private final FavoriteMapper favoriteMapper; private final com.campuslife.mapper.BannerMapper bannerMapper;
+ private final CampusMapper campusMapper; private final PostMapper postMapper; private final UserMapper userMapper; private final PostLikeMapper likeMapper; private final CommentMapper commentMapper; private final FollowMapper followMapper; private final PostViewMapper viewMapper; private final FavoriteMapper favoriteMapper; private final com.campuslife.mapper.BannerMapper bannerMapper;
  public List<com.campuslife.entity.Banner> banners(String slot){return bannerMapper.selectList(new LambdaQueryWrapper<com.campuslife.entity.Banner>().eq(com.campuslife.entity.Banner::getSlot,slot).eq(com.campuslife.entity.Banner::getStatus,1).orderByAsc(com.campuslife.entity.Banner::getSort));}
  public Map<String,Object> page(String tag,String scope,Long userId,int page,int size){
    LambdaQueryWrapper<Post> wrapper=new LambdaQueryWrapper<Post>().eq(Post::getStatus,1);
@@ -19,6 +19,16 @@
    }else{
      wrapper.orderByDesc(Post::getCreatedAt);
    }
+   return pageResult(wrapper,userId,page,size);
+ }
+ public Map<String,Object> search(String keyword,Long userId,int page,int size){
+   // LIKE 参数由 MyBatis-Plus 绑定；OR 必须括起来，避免绕过 status=1。
+   LambdaQueryWrapper<Post> wrapper=new LambdaQueryWrapper<Post>().eq(Post::getStatus,1)
+     .and(q->q.like(Post::getTitle,keyword).or().like(Post::getContent,keyword))
+     .orderByDesc(Post::getCreatedAt).orderByDesc(Post::getId);
+   return pageResult(wrapper,userId,page,size);
+ }
+ private Map<String,Object> pageResult(LambdaQueryWrapper<Post> wrapper,Long userId,int page,int size){
    Page<Post> p=postMapper.selectPage(new Page<>(page,size),wrapper);
    List<Long> uids=new ArrayList<>();
    for(Post x:p.getRecords()){uids.add(x.getUserId());}
@@ -48,8 +58,8 @@
    d.setFavored(favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>().eq(Favorite::getPostId,id).eq(Favorite::getUserId,userId))>0);
    return d;}
  @Transactional public Post create(CreatePostRequest r){Post p=new Post(); p.setUserId(r.getUserId());p.setTitle(r.getTitle());p.setContent(r.getContent());p.setImage(r.getImage());p.setTag(r.getTag());p.setLocation(r.getLocation());p.setLikeCount(0);p.setCommentCount(0);p.setStatus(1);p.setCreatedAt(LocalDateTime.now());p.setUpdatedAt(LocalDateTime.now());postMapper.insert(p);return p;}
- @Transactional public Map<String,Object> toggleLike(Long id,Long uid){LambdaQueryWrapper<PostLike> q=new LambdaQueryWrapper<PostLike>().eq(PostLike::getPostId,id).eq(PostLike::getUserId,uid);PostLike old=likeMapper.selectOne(q); boolean liked;if(old==null){PostLike x=new PostLike();x.setPostId(id);x.setUserId(uid);x.setCreatedAt(LocalDateTime.now());likeMapper.insert(x);postMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>().eq(Post::getId,id).setSql("like_count = like_count + 1"));liked=true;}else{likeMapper.delete(q);postMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>().eq(Post::getId,id).setSql("like_count = GREATEST(like_count - 1, 0)"));liked=false;}return Map.of("liked",liked);}
- @Transactional public Comment addComment(Long id,CommentRequest r){Comment c=new Comment();c.setPostId(id);c.setUserId(r.getUserId());c.setContent(r.getContent());c.setCreatedAt(LocalDateTime.now());commentMapper.insert(c);postMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>().eq(Post::getId,id).setSql("comment_count = comment_count + 1"));return c;}
+ @Transactional public Map<String,Object> toggleLike(Long id,Long uid){LambdaQueryWrapper<PostLike> q=new LambdaQueryWrapper<PostLike>().eq(PostLike::getPostId,id).eq(PostLike::getUserId,uid);PostLike old=likeMapper.selectOne(q); boolean liked;if(old==null){PostLike x=new PostLike();x.setPostId(id);x.setUserId(uid);x.setCreatedAt(LocalDateTime.now());likeMapper.insert(x);postMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>().eq(Post::getId,id).setSql("like_count = like_count + 1"));liked=true;}else{likeMapper.delete(q);postMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>().eq(Post::getId,id).setSql("like_count = GREATEST(like_count - 1, 0)"));liked=false;}if(liked){Post post=postMapper.selectById(id);if(post!=null&&!post.getUserId().equals(uid))campusMapper.notifyUser(post.getUserId(),"收到新的赞","有同学赞了你的帖子："+post.getTitle(),"like",id);}return Map.of("liked",liked);}
+ @Transactional public Comment addComment(Long id,CommentRequest r){Comment c=new Comment();c.setPostId(id);c.setUserId(r.getUserId());c.setContent(r.getContent());c.setCreatedAt(LocalDateTime.now());commentMapper.insert(c);postMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>().eq(Post::getId,id).setSql("comment_count = comment_count + 1"));Post post=postMapper.selectById(id);if(post!=null&&!post.getUserId().equals(r.getUserId()))campusMapper.notifyUser(post.getUserId(),"收到新的评论",r.getContent(),"comment",id);return c;}
  public List<Map<String,Object>> comments(Long id){
    List<Comment> cs=commentMapper.selectList(new LambdaQueryWrapper<Comment>().eq(Comment::getPostId,id).orderByAsc(Comment::getCreatedAt));
    List<Long> uids=new ArrayList<>(); for(Comment c:cs){uids.add(c.getUserId());}
@@ -59,7 +69,7 @@
    for(Comment c:cs){User u=users.get(c.getUserId()); Map<String,Object> m=new LinkedHashMap<>(); m.put("id",c.getId()); m.put("userId",c.getUserId()); m.put("content",c.getContent()); m.put("createdAt",c.getCreatedAt()==null?null:c.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))); m.put("authorNickname",u==null?"同学":u.getNickname()); m.put("authorAvatar",u==null?null:u.getAvatar()); out.add(m);}
    return out;}
  @Transactional public void view(Long id,Long userId){postMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>().eq(Post::getId,id).setSql("view_count = view_count + 1"));PostView record=new PostView();record.setPostId(id);record.setUserId(userId);record.setCreatedAt(LocalDateTime.now());viewMapper.insert(record);}
- @Transactional public Map<String,Object> toggleFollow(Long followee,Long follower){LambdaQueryWrapper<Follow> q=new LambdaQueryWrapper<Follow>().eq(Follow::getFollowerId,follower).eq(Follow::getFolloweeId,followee);Follow old=followMapper.selectOne(q);if(old==null){Follow f=new Follow();f.setFollowerId(follower);f.setFolloweeId(followee);f.setCreatedAt(LocalDateTime.now());followMapper.insert(f);return Map.of("followed",true);}followMapper.delete(q);return Map.of("followed",false);}
+ @Transactional public Map<String,Object> toggleFollow(Long followee,Long follower){LambdaQueryWrapper<Follow> q=new LambdaQueryWrapper<Follow>().eq(Follow::getFollowerId,follower).eq(Follow::getFolloweeId,followee);Follow old=followMapper.selectOne(q);if(old==null){Follow f=new Follow();f.setFollowerId(follower);f.setFolloweeId(followee);f.setCreatedAt(LocalDateTime.now());followMapper.insert(f);if(!followee.equals(follower))campusMapper.notifyUser(followee,"新的关注","有同学关注了你","follow",null);return Map.of("followed",true);}followMapper.delete(q);return Map.of("followed",false);}
  public List<Map<String,Object>> hot(){return postMapper.selectMaps(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Post>().select("tag","COUNT(*) AS posts","IFNULL(SUM(like_count),0)+IFNULL(SUM(comment_count),0) AS count").eq("status",1).groupBy("tag").orderByDesc("count").last("LIMIT 10"));}
  static class Strings{static boolean isBlank(String s){return s==null||s.isBlank();}}
 }
--- a/backend/src/main/java/com/campuslife/service/UserService.java
+++ b/backend/src/main/java/com/campuslife/service/UserService.java
@@ -6,7 +6,7 @@
 import lombok.RequiredArgsConstructor; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
 import java.time.LocalDateTime; import java.util.*;
 @Service @RequiredArgsConstructor public class UserService {
- private final UserMapper userMapper; private final PostMapper postMapper; private final FollowMapper followMapper; private final FavoriteMapper favoriteMapper; private final PostViewMapper viewMapper; private final UserAddressMapper addressMapper; private final CouponMapper couponMapper; private final FeedbackMapper feedbackMapper;
+ private final CampusMapper campusMapper; private final UserMapper userMapper; private final PostMapper postMapper; private final FollowMapper followMapper; private final FavoriteMapper favoriteMapper; private final PostViewMapper viewMapper; private final UserAddressMapper addressMapper; private final CouponMapper couponMapper; private final FeedbackMapper feedbackMapper;
  // 个人资料：昵称/学校/头像 + 真实统计（发帖数、关注数、粉丝数）
  public Map<String,Object> profile(Long id){
    User u=userMapper.selectById(id);
@@ -38,7 +38,7 @@
    Favorite old=favoriteMapper.selectOne(q);
    if(old!=null){favoriteMapper.delete(q);return Map.of("favored",false);}
    Favorite f=new Favorite(); f.setPostId(postId); f.setUserId(userId); f.setCreatedAt(LocalDateTime.now()); favoriteMapper.insert(f);
-   return Map.of("favored",true);}
+   Post post=postMapper.selectById(postId);if(post!=null&&!post.getUserId().equals(userId))campusMapper.notifyUser(post.getUserId(),"收到新的收藏","有同学收藏了你的帖子："+post.getTitle(),"favorite",postId);return Map.of("favored",true);}
  public List<Map<String,Object>> coupons(Long id){
    List<Coupon> list=couponMapper.selectList(new LambdaQueryWrapper<Coupon>().eq(Coupon::getUserId,id).orderByDesc(Coupon::getId));
    List<Map<String,Object>> out=new ArrayList<>();
--- a/components/bottom-nav/index.js
+++ b/components/bottom-nav/index.js
@@ -1,3 +1,5 @@
+const api = require('../../utils/api')
+
 Component({
   properties: {
     active: {
@@ -6,11 +8,20 @@
     },
     unread: {
       type: Number,
-      value: 3
+      value: 0
     }
   },
 
+  lifetimes: { attached() { this.refreshUnread() } },
+  pageLifetimes: { show() { this.refreshUnread() } },
   methods: {
+    async refreshUnread() {
+      try {
+        const [conversations, notifications] = await Promise.all([api.getConversations(), api.getNotifications()])
+        const unread = conversations.reduce((sum, row) => sum + (row.muted ? 0 : Number(row.unread) || 0), 0) + notifications.filter(row => row.unread).length
+        this.setData({ unread })
+      } catch (error) { console.warn('[未读消息]', error.message || '读取失败') }
+    },
     navigate(event) {
       const key = event.currentTarget.dataset.key
       if (!key || key === this.data.active) return
--- a/docs/API_INTEGRATION.md
+++ b/docs/API_INTEGRATION.md
@@ -1,7 +1,7 @@
 # 后端接口文档
 
 统一响应包装：`{ "code": 0, "msg": "ok", "data": ... }`，`code !== 0` 或 HTTP 非 2xx 视为失败。
-前端统一走 `utils/api.js`；**任一接口失败时自动降级到本地 mock**（发现页会有离线横条，banner/话题用页面内置默认值），所以后端未启动也能完整演示。
+前端统一走 `utils/api.js`；读接口保留离线演示；新增搜索/消息/课表/校历读取仅在网络失败时兜底，服务端错误显示错误态。带离线兜底的写接口必须保留 `error.server`，不得把服务端拒绝当作成功；原投票、资料编辑仍需后端。全局横条提示演示状态，本机演示写入不会自动同步。
 
 ## 发现页
 
@@ -190,17 +190,85 @@
 
 - `POST /api/feedback`，body `{ "userId": 1, "content": "…" }` → `{ "received": true }`
 
-## 其余页面（暂为本地 mock，后端待建）
-
-| 函数 | 页面 | 待建接口建议 |
+## 帖子搜索（首页与发现页共用）
+
+`GET /api/search?keyword=台灯&page=1&size=10&userId=1`
+
+- `keyword` 必填，先 trim；缺失、空串/纯空白、超过 64 个 UTF-16 单元返回 HTTP 400、`code=400`，不截断关键词。前端也按 JS length 校验 64，搜索输入框上限 64。
+- `page` 默认 1，下限 1；`size` 默认 10，夹取为 1–50；非法数字返回 HTTP 400。
+- `userId` 默认 1，按原帖子流风格夹取为至少 1，用于 liked/followed。
+- 只查询 `status=1` 帖子，标题或正文 `LIKE %keyword%`，OR 放在括号内，按 created_at、id 降序稳定分页。MyBatis-Plus `.like()` 绑定参数，不把关键词拼入 SQL。
+- `%` 和 `_` 沿用 SQL LIKE 通配符，反斜线转义；离线过滤分别匹配标题/正文，不匹配标签，包含本机演示发布的帖子。数据库 utf8mb4_unicode_ci 与 JS 的大小写匹配在部分语言/重音字符上可能不同。
+- 返回 **与 `/api/posts` 相同的 DTO 和 `{ list, total }`**；在线不额外混入本机演示帖子。网络失败返回对应本地分页并增加 `offline: true`；服务端错误不伪装为空结果。
+
+请求示例：
+
+```bash
+curl --get 'http://127.0.0.1:8080/api/search' --data-urlencode 'keyword=台灯' --data 'page=1&size=10&userId=1'
+```
+
+返回示例（具体内容与总数取决于数据库）：
+
+```json
+{
+  "code": 0,
+  "msg": "ok",
+  "data": {
+    "list": [{
+      "id": 7, "authorId": 3, "authorNickname": "陈一川", "authorAvatar": null,
+      "title": "出一台九成新台灯", "content": "亮度可调，宿舍自用半年，期末价出。",
+      "image": "/assets/home/recommend-lamp.jpg", "tag": "二手闲置", "location": "南区宿舍",
+      "likeCount": 63, "commentCount": 2, "viewCount": 0, "liked": false,
+      "followed": false, "favored": null, "createdAt": "2026-10-14 10:00"
+    }],
+    "total": 1
+  }
+}
+```
+
+空白关键词返回：
+
+```json
+{"code":400,"msg":"请输入搜索关键词","data":null}
+```
+
+超长关键词返回：
+
+```json
+{"code":400,"msg":"关键词不能超过64字","data":null}
+```
+
+## 消息、通知和聊天
+
+所有请求/响应仍使用 `{ code, msg, data }`。接口 `peerId` 是**对方用户 ID**，不再混用旧 mock 的会话序号。
+
+| 接口 | 参数 | data / 行为 |
 |---|---|---|
-| `getConversations` | 消息列表 | `GET /api/conversations` |
-| `getNotifications` | 通知列表 | `GET /api/notifications` |
-| 聊天消息 | 聊天页 | `GET/POST /api/conversations/{id}/messages` |
+| `GET /api/conversations` | `userId=1` | `[{ id:2, name, avatar, avatarText, color, preview, createdAt, unread, muted, online:false }]`，按最新消息 ID 倒序；不伪造在线状态 |
+| `GET /api/conversations/{peerId}/messages` | `userId=1&size=30&beforeId=` | `{ list:[{id,senderId,receiverId,mine,type,content,image,createdAt}], hasMore, muted }`；size 1–50，beforeId 正整数，返回升序列表 |
+| `POST /api/conversations/{peerId}/messages` | body `{ userId:1, content:"你好", clientId:"唯一请求标识" }` | 新消息对象；content trim 后非空、≤1000，clientId 必填≤64，用户必须存在且不是自己；同一发送者相同 clientId 幂等 |
+| `POST /api/conversations/{peerId}/read` | body `{ userId:1, upToId:5 }` | `null`，只标记收到的、ID≤游标的消息，避免把并发新消息误标已读 |
+| `POST /api/conversations/{peerId}/clear` | body `{ userId:1 }` | `null`，写清空游标，只影响当前用户视图 |
+| `POST /api/conversations/{peerId}/mute` | query `userId=1&muted=true` | `null`，持久化免打扰，未读消息可见，但免打扰会话不计入总角标 |
+| `GET /api/notifications` | `userId=1` | 最近100条 `[{ id,title,content,category,tone,postId,createdAt,readAt,unread }]` |
+| `POST /api/messages/read-all` | body `{ userId:1 }` | `null`，事务内标记聊天与通知已读 |
+
+会话/消息/通知读取仅在网络失败时本地演示；服务端参数/业务错误保留。聊天页每 5 秒轮询，可加载更早记录。离线发消息写入 `chat_messages_<userId>_<peerId>`，会话、已读、清空和免打扰也持久化；离线发送明确标记“仅本机”，不会自动重发。清空/免打扰在演示视图下只修改本机数据，避免连接恢复后误操作真实记录。
+
+交互通知来自论坛点赞、评论、关注以及收藏的实际事务，自己的行为不通知自己；系统通知可由后台写入 notification。消息页三个快捷入口按 category 过滤，有 postId 时可进入帖子。
+
+## 课表与校历
+
+- `GET /api/timetable?userId=1&week=7` → `{ termInfo, week, courses:[{id,name,room,day,period,theme,weekStart,weekEnd}] }`。
+- week 不传时按服务器 Asia/Shanghai 日期计算教学周；传入时夹取 1–60。仅返回周次范围内课程，`day=0..6`、`period=0..4`。
+- `GET /api/calendar?userId=1` → `{ termInfo, events:[{id,title,start,end,note}] }`。
+- `termInfo` 为 `{id,termLabel,termStart,termEnd,midtermStart,midtermEnd,finalStart}`，日期为 `YYYY-MM-DD`。
+- 学期按用户资料的 school 匹配最新 active=1 记录。学校未配置返回 HTTP 400，不冒用其他学校学期。
+- 无后端时用现有 mock 演示；在线课程/事件来自数据库管理，不代表已经接入学校教务系统。
 
 ## 数据库
 
-`backend/src/main/resources/db/schema.sql`：`user`、`post`（含 `view_count`）、`post_like`、`comment`、`follow`、`banner`、`idol_contestant`、`idol_vote`、`favorite`、`post_view`、`user_address`、`coupon`、`feedback` 共 13 张表 + 种子数据（10 帖、20 评论、关注/点赞、收藏、地址、优惠券、8 名评选选手、2 条 discover banner）。
+原 `schema.sql` **20 张表**；新增 `module-messages-academics.sql` **6 张表**：chat_message、chat_preference、notification、academic_term、course_schedule、calendar_event。新库共 **26 张表**。脚本均由用户手动执行，详见 README_BACKEND.md；新增脚本可重复执行，原 schema 部分旧种子不可重复执行。
 
 ## 待补齐的体系
 
--- a/docs/FEATURE_STATUS.md
+++ b/docs/FEATURE_STATUS.md
@@ -1,279 +1,47 @@
-# 功能状态清单 · campus-life-miniapp
+# 功能状态清单（补齐后）
 
-> 生成时间：2026-10-14
-> 方法：静态扫描全部页面 JS/WXML/JSON + 后端 Controller/SQL，逐条比对「前端调用 ↔ 后端端点」。
-> 结论基准：**以代码实际行为为准**，不以 README 声明为准。
+日期：2026-10-14。以本次源码为准。代码完成与运行验收分开记录；静态检查不代表 MySQL/微信真机联调完成。
 
----
+| 项目 | 代码状态 | 实现位置 |
+|---|---|---|
+| P0-1 首页路由表 | 已完成，原7条不变，12个标签覆盖，未知入口 warn | pages/index/index.js |
+| P0-2 搜索 | 已完成，GET /api/search、分页页与两处入口、LIKE 离线过滤 | ForumController/ForumService、utils/api.js、pages/search |
+| P1-1 开学优惠 | 已接活动页 | FEATURE_ROUTES |
+| P1-2 学习好物 | 已接二手列表，category 参数映射已有图书分类 | index、market-list |
+| P1-3 奶茶优惠 | 已接真实种子店铺1（可在店铺页显示不存在态） | index、food-shop |
+| P1-4 闲置台灯 | 查询在售台灯真实 id；找不到时去数码市场 | openRecommendedLamp |
+| P1-5 联想广告 | 已接专题页；官方链接尚未提供，明确提示 | pages/campus-offer |
+| P1-6 通知铃铛 | 打开消息页通知 tab，通知现由数据库读取 | messages?tab=notice |
+| P1-7 换一批 | 真正轮换 recommendations 数组顺序 | index |
+| P1-8 switchTab | 确认无绑定/调用后已删除 | index；底部导航在组件内 |
+| P1-9 发现页 banner | 本地评选 banner 已接真实页；运营 linkType=none 仍是合理待配置状态 | discover |
+| P2-1 聊天 | 文本持久化、历史分页、5秒轮询、已读/免打扰/个人清空；离线本机存储 | CampusController/Service/Mapper、chat、api |
+| P2-2 会话 | 已请求后端；对方用户 ID 与私聊入口统一 | api.getConversations |
+| P2-3 通知/全部已读 | 真实表与接口，论坛交互生成通知，已读可持久化 | api.getNotifications、messages/read-all |
+| P2-4 用户资料兜底 | getProfile 先请求后端；个人中心只走 API | api、profile |
+| P2-5 课表 | 按用户/学期/周次从数据库读取；无外部教务同步 | timetable、academic_term/course_schedule |
+| P2-6 校历 | 真实表与接口；默认种子仍为演示数据 | calendar、calendar_event |
+| P3-1 followUser | 源码兼容 body 优先于 query；前端请求体已测，真实 HTTP 待本地验收 | ForumController.follow |
+| P3-2 根目录旧文件 | 确认 app/require/WXML 无引用后删除 index.js、api.js | 根目录 |
+| P3-4 全局演示提示 | 23页均挂组件，离线写入明确提示，不自动补发 | offline-banner/data-mode |
+| P3-5 表数量说明 | 原 schema 是20表，本次新增6表，共26表 | README_BACKEND |
 
-## 一、总览
+## 原清单需要纠正的内容
 
-| 状态 | 数量 | 含义 |
-|---|---|---|
-| ✅ 完整实现 | 14 个模块 | 前端页面 + 交互 + 后端接口全通，有离线兜底 |
-| ⚠️ 假数据（后端缺失） | 6 处 | 页面能跑，但数据写死在本地，接不上真实后端 |
-| ❌ 纯占位（toast） | 9 个入口 | 点击只弹提示，无实际跳转或行为 |
-| 🧹 冗余残留 | 1 个文件 | 永不被加载的死文件 |
+- 发现页旧搜索有当前列表的本地过滤，并非只弹 toast；缺的是完整搜索接口、结果页及分页。
+- 原消息通知直接来自 mock，“那里已经有真实通知”的说法不成立；本次新增真实通知存储与读取。
+- 首页原有七条真实路由，原清单有一处写成六条。
+- “无兜底分支”并不准确，实际已有 toast 兜底，缺少开发者告警。
+- “9 个入口”混入了无绑定的 switchTab 和运营 banner 待配置；不能等同于 9 个真实按钮。
+- 原 schema 实际20表，而不是五表、13表或21表。原32端点计数正确，本次新增11个，共43个。
+- “所有写接口均透传业务错误”“后端未启动所有功能仍可用”都过于乐观；旧论坛/反馈/报名等存在吞错误，投票、资料编辑本来没有兜底。
 
-**最重要的结论**：不存在「整页未开发」的功能。21 个页面全部有真实实现。缺口集中在**入口层**和**少数无后端的模块**。
+## 本次额外修复
 
----
+弹层 action 属性和回调形状；发现帖点击和真实点赞/关注；在线帖子重复/错误混入 mock；分享 id 暂时性死区；帖子详情 loading 不收尾；本机二手详情 ID/持久化；错误图片路径；演示放假日期与学期结束日期矛盾；小程序打包包含后端。
 
-## 二、✅ 已完成功能清单
+## 验证与保留边界
 
-### 2.1 首页（`pages/index/index`）—— 入口 + 导航
+18项 Node 行为回归通过；23页四件套、JS/JSON/WXML、组件、路由、资源、SQL参数与幂等结构静态检查通过，SQL 未执行。后端 Maven 编译、实际 MySQL/HTTP 和微信视觉/真机验收待本地完成，附只读 backend_smoke.py。
 
-| 功能 | 实现方式 | 状态 |
-|---|---|---|
-| 自定义顶部导航 | `initNavigation()` 读胶囊位置动态算 `navBarHeight` | ✅ |
-| 状态栏/胶囊避让 | `wx.getMenuButtonBoundingClientRect()` + `wx.getWindowInfo()` | ✅ |
-| 学校选择 | 弹层三选一，写入 `app.globalData.schoolName` | ✅ |
-| 功能卡跳转 | `openFeature()` 白名单路由到 6 个真实页面 | ✅ 部分 |
-| 底部导航 | `components/bottom-nav` 组件，5 个 tab | ✅ |
-| 推荐位/活动位 | 静态卡片 + 跳转 | ✅ 部分 |
-
-> 首页 `index.wxml` 共 211 行，视觉完整度高；`project.config.json` 中 `navigationStyle: custom`，所以每个页面都自带导航条。
-
-### 2.2 校园论坛（`pages/community` + `pages/post-detail`）
-
-| 功能 | 前端 | 后端端点 | 状态 |
-|---|---|---|---|
-| 帖子分页列表 | `api.getForumPosts({tag,scope,page,size})` | `GET /api/posts` | ✅ |
-| 标签筛选 | `tag` 参数 | 同上 | ✅ |
-| 关注流 `scope=followed` | 同上 | 同上 | ✅ |
-| 帖子详情 | `api.getPost(id)` | `GET /api/posts/{id}` | ✅ |
-| 发帖 | `api.publishPost(payload)` | `POST /api/posts` | ✅ |
-| 点赞/取消 | `api.likePost(id)` | `POST /api/posts/{id}/like` | ✅ |
-| 评论列表 | `api.getPostComments(id)` | `GET /api/posts/{id}/comments` | ✅ |
-| 发评论 | `api.addComment(id, content)` | `POST /api/posts/{id}/comments` | ✅ |
-| 关注用户 | `api.followUser(id)` | `POST /api/users/{id}/follow` | ⚠️ 参数位置待实测 |
-| 浏览上报 | `api.viewPost(id)` | `POST /api/posts/{id}/view` | ✅ |
-| 收藏/取消 | `api.toggleFavorite(postId)` | `POST /api/users/{id}/favorites` | ✅ |
-| 热门话题 | `api.getHotTopics()` | `GET /api/topics/hot` | ✅ |
-| 发现页轮播 | `api.getBanners('discover')` | `GET /api/banners?slot=` | ✅ |
-| 本地发布合并进流 | `localForumPosts()` 读 `wx.storage` | — | ✅ 本地 |
-
-### 2.3 外卖（`pages/food-list` → `food-shop` → `food-order`）
-
-| 功能 | 前端 | 后端端点 | 状态 |
-|---|---|---|---|
-| 店铺列表 + 分类 | `api.getFoodShops(category)` | `GET /api/shops` | ✅ |
-| 店铺搜索（页内） | 前端 `keyword` 过滤 | — | ✅ |
-| 菜单（分类+商品） | `api.getFoodMenu(id)` | `GET /api/shops/{id}/menu` | ✅ |
-| 购物车/加减件 | 页面 `counts` 状态 | — | ✅ |
-| 起送价校验 | `missing` 差额计算，disable 按钮 | 后端亦校验 | ✅ |
-| 地址列表 | `api.getAddresses(userId)` | `GET /api/users/{id}/addresses` | ✅ |
-| 新增地址 | `api.addAddress(userId, detail)` | `POST /api/users/{id}/addresses` | ✅ |
-| 下单 | `api.createFoodOrder(payload)` | `POST /api/orders` | ✅ |
-| 服务端拒单透传 | `.catch` 中 `error.server` 直接抛出，不兜底 | — | ✅ 设计到位 |
-
-### 2.4 二手市场（`pages/market-list` / `market-detail` / `market-publish`）
-
-| 功能 | 前端 | 后端端点 | 状态 |
-|---|---|---|---|
-| 求购/出售双模式 | `api.getGoodsList(mode, category)` | `GET /api/goods?mode=&category=` | ✅ |
-| 物品详情 | `api.getGoodsDetail(id)` | `GET /api/goods/{id}` | ✅ |
-| 发布闲置 | `api.publishGoods(payload)` | `POST /api/goods` | ✅ |
-| 卖家主页跳转 | `openSeller()` → `user-home` | — | ✅ |
-
-### 2.5 兼职 / 季节活动
-
-| 功能 | 前端 | 后端端点 | 状态 |
-|---|---|---|---|
-| 兼职列表 + 结算方式筛选 | `api.getJobs(settle)` | `GET /api/jobs?settle=` | ✅ |
-| 活动列表 | `api.getActivities(userId)` | `GET /api/activities?userId=` | ✅ |
-| 活动报名 | `api.enrollActivity(id)` | `POST /api/activities/{id}/enroll` | ✅ |
-| 已报名状态回显 | `enrolledByMe` disable 按钮 | 后端返回 | ✅ |
-
-### 2.6 校草校花评选（`pages/activity-idol` + `idol-join`）
-
-| 功能 | 前端 | 后端端点 | 状态 |
-|---|---|---|---|
-| 活动配置 | `api.getIdolActivity()` | `GET /api/idol/activity` | ✅ |
-| 选手列表（分性别） | `api.getIdolContestants(gender)` | `GET /api/idol/contestants?gender=` | ✅ |
-| 投票 | `api.voteIdol(id)` | `POST /api/idol/contestants/{id}/vote` | ✅ |
-| 重复投票限制 | 后端 `uk_idol_vote` 唯一键 | DB 约束 | ✅ |
-| 我要报名 | `api.joinIdol(payload)` | `POST /api/idol/contestants` | ✅ |
-
-### 2.7 个人中心（`pages/profile`）
-
-| 功能 | 前端 | 后端端点 | 状态 |
-|---|---|---|---|
-| 资料展示 | `api.getUserProfile(id)` | `GET /api/users/{id}/profile` | ✅ |
-| 编辑昵称 | `api.updateUserProfile(id, name)` | `POST /api/users/{id}/profile` | ✅ |
-| 我的收藏 | `api.getFavorites(id)` | `GET /api/users/{id}/favorites` | ✅ |
-| 浏览记录 | `api.getMyViews(id)` | `GET /api/users/{id}/views` | ✅ |
-| 地址管理 | `api.getAddresses(id)` / `addAddress` | `/api/users/{id}/addresses` | ✅ |
-| 优惠券 | `api.getCoupons(id)` | `GET /api/users/{id}/coupons` | ✅ |
-| 意见反馈 | `api.submitFeedback(id, content)` | `POST /api/feedback` | ✅ |
-| 统计角标 | `loadCounts()` 并发拉取 4 类数据 | — | ✅ |
-
-### 2.8 后端工程（`backend/`）
-
-| 项目 | 内容 | 状态 |
-|---|---|---|
-| 技术栈 | Java 17 · Spring Boot 3.2 · MyBatis-Plus · MySQL 8 · Lombok | ✅ |
-| Controller | `Forum` / `User` / `Idol` / `Biz` 共 4 个，**32 个端点** | ✅ |
-| Service | `ForumService` / `UserService` / `IdolService` / `BizService`（4 个） | ✅ |
-| Mapper | 17 个 | ✅ |
-| Entity | 16 个 | ✅ |
-| DTO | 11 个（含 `ApiResponse` 统一响应） | ✅ |
-| 全局异常 | `GlobalExceptionHandler` | ✅ |
-| CORS | `WebConfig`（仅本地调试） | ✅ |
-| 数据库 | `schema.sql` 21 张表 + `module-food.sql` + `module-market-jobs-activities.sql` | ✅ |
-| 构建 | `pom.xml`（Maven） | ✅ |
-| 统一响应 | `ApiResponse.ok(data)`，前端判 `body.code === 0` | ✅ |
-
-### 2.9 工程化 / 架构亮点
-
-| 项目 | 说明 | 状态 |
-|---|---|---|
-| 接口适配层 | 页面只 import `utils/api.js`，不碰数据源 → 换真后端零改页面 | ✅ |
-| 离线兜底 | 每个 `request()` 后跟 `.catch(() => wait(mock))`，无后端也能演示 | ✅ |
-| **写入接口不兜底** | `createFoodOrder` / `getGoodsList` 等：`if (error.server) throw error` —— 服务端业务错误（如拒单）必须让用户看到，只有**网络失败**才退回 mock | ✅ 设计正确 |
-| 公共弹层组件 | `components/ui-popup` + `utils/ui-popup-helper`，统一 sheet/modal/toast | ✅ |
-| 底部导航组件 | `components/bottom-nav`，5 个页面复用，支持 `unread` 角标 | ✅ |
-| 导航计算复用 | `utils/navigation.js` 抽出胶囊/状态栏计算 | ✅ |
-| 学期周次 | `utils/term.js` 计算教学周 | ✅ |
-| 时间格式化 | `utils/format-time.js` | ✅ |
-| 外卖金额计算 | `utils/food.js` 抽出费用/满减规则 | ✅ |
-| 本地发布打通 | 发布 → `wx.storage` → 论坛流 + 发现流都能看到 | ✅ |
-| 静态资源本地化 | 17 个 webp + 18 个 svg，README 声明不依赖网络 | ✅ |
-| 文档 | `docs/API_INTEGRATION.md`(7.4KB) / `IMAGE_ASSETS.md` / `README_BACKEND.md` | ✅ |
-
----
-
-## 三、❌ 待办缺口清单
-
-### P0 · 结构性缺陷（建议优先）
-
-#### P0-1 首页 `openFeature()` 白名单无兜底分支
-
-**位置**：`pages/index/index.js:78-111`
-
-```js
-openFeature(event) {
-  const label = event.currentTarget.dataset.label || '功能'
-  if (label === '校园论坛') { wx.navigateTo(...); return }
-  if (label === '外卖') { ... return }
-  if (label === '二手市场') { ... return }
-  if (label === '兼职') { ... return }
-  if (label === '季节活动') { ... return }
-  if (label === '作业答疑') { ... return }
-  if (label === '论文指导') { ... return }
-  popup.toast(this, `${label}功能开发中`)   // ← 所有未登记标签都掉这里
-}
-```
-
-**问题**：这是一个「查表失败即静默降级」的模式。任何新增入口只要忘了在上面加 `if`，就会**静默变成一句 toast**，不报错、不报警。
-**影响面**：当前 9 个入口落在这条兜底里。
-
-**建议**：改成**路由表 + 显式声明**：
-
-```js
-// 用表驱动，未登记项在开发期就暴露
-const FEATURE_ROUTES = {
-  '校园论坛': '/pages/community/community',
-  '外卖':    '/pages/food-list/food-list',
-  '二手市场': '/pages/market-list/market-list',
-  '兼职':    '/pages/jobs/jobs',
-  '季节活动': '/pages/activities/activities',
-  '作业答疑': '/pages/community/community?tab=2',
-  '论文指导': '/pages/community/community?tab=4'
-}
-```
-好处：新增入口只需加一行；未命中的 key 可集中打日志而不是散落各处的 toast。
-
----
-
-#### P0-2 搜索功能缺失（前端只有 toast，后端无接口）
-
-**位置**：`pages/index/index.js:68-72`、`pages/discover/discover.js` 的 `openSearch`
-
-```js
-submitSearch(event) {
-  const keyword = (event.detail.value || '').trim()
-  if (!keyword) return
-  popup.toast(this, `搜索：${keyword}`)   // ← 只弹提示
-}
-```
-
-**缺口链条**：
-1. 后端 `ForumController` / `BizController` **没有 `/api/search` 端点**；
-2. 前端没有搜索结果页（`app.json` 里没有 search 页面）；
-3. 首页搜索框、发现页搜索入口都只弹 toast。
-
-**这是用户最直观能感知的缺失** —— 有搜索框、能输入、点了没反应。
-
-**建议实现路径**（三处都要改）：
-- 后端：`ForumController` 加 `GET /api/search?keyword=&page=&size=`，SQL 对 `title`/`content` 做 `LIKE`；
-- 前端：新建 `pages/search/search`，注册进 `app.json`；
-- 接线：`submitSearch` 改为 `wx.navigateTo({url: '/pages/search/search?keyword=...'})`。
-
----
-
-### P1 · 功能占位（点了没反应）
-
-| # | 入口 | 位置 | 现状 | 建议 |
-|---|---|---|---|---|
-| P1-1 | 开学优惠 | `index.wxml:32` | toast「功能开发中」 | 跳活动页或做专题页 |
-| P1-2 | 学习好物 | `index.wxml:116` | toast「功能开发中」 | 跳二手市场（`?category=学习好物`） |
-| P1-3 | 奶茶优惠 | `index.wxml:138` | toast「功能开发中」 | 跳外卖（`/pages/food-shop/food-shop?id=1`） |
-| P1-4 | 闲置台灯 | `index.wxml:159` | toast「功能开发中」 | 跳二手详情（`?id=N`） |
-| P1-5 | 联想小新校园优惠 | `index.wxml:180` | toast「功能开发中」 | 广告位，跳外链或专题页 |
-| P1-6 | 通知铃铛 | `index.wxml:25` | 写死「暂无新通知」 | 跳 `pages/messages`（那里有真通知） |
-| P1-7 | 换一批 | `index.wxml:131` | toast「已换一批」，数据未变 | 接入推荐接口或至少重排本地数组 |
-| P1-8 | 首页 `switchTab` | `index.js:132-144` | 非 home/publish 的 tab 弹「XX页面开发中」 | **疑似死代码** —— 底部导航实际走 `components/bottom-nav`，此方法可能从未被调用，建议确认后删除 |
-| P1-9 | 发现页 banner | `discover.js:109` | `linkType !== 'page'` 时弹「敬请期待」 | 正常降级，非缺陷（运营未配链接时） |
-
----
-
-### P2 · 假数据模块（后端无接口）
-
-| # | 模块 | 位置 | 现状 | 接真实数据的成本 |
-|---|---|---|---|---|
-| P2-1 | **聊天详情** | `pages/chat/chat.js:13-19` | **5 条消息硬编码在 `data` 里**；发消息只进内存，退出即失 | 高 —— 需 `message` 表 + 会话接口 + WebSocket 或轮询 |
-| P2-2 | 会话列表 | `api.js:111` `getConversations()` | 直接 `wait(mock.conversations)`，**连 `request()` 都没调** | 中 —— 先做列表接口 |
-| P2-3 | 通知列表 | `api.js:112` `getNotifications()` | 同上，`wait(mock.notifications)` | 中 —— 需 `notification` 表 |
-| P2-4 | 用户资料兜底 | `api.js:113` `getProfile()` | `wait(mock.profile)`；`profile.js` 再用 `mock.profile` 兜底 | 低 —— 已被 `getUserProfile` 部分取代 |
-| P2-5 | 课表 | `pages/timetable/timetable.js:39` | `mock.timetableCourses` 静态 5×7 网格 | 高 —— 需 `course` + `course_schedule` 表 + 学期维度 |
-| P2-6 | 日历 | `pages/calendar/calendar.js:30` | `mock.calendarEvents` 静态事件 | 中 —— 可与活动/课表共用数据源 |
-
-> 注：`messages.js` 的「全部已读」只改内存（`markAllRead`），刷新即复原 —— 属 P2-3 的连带问题。
-
----
-
-### P3 · 数据一致性 / 待实测
-
-| # | 问题 | 位置 | 说明 |
-|---|---|---|---|
-| P3-1 | 关注接口参数位置不一致 | 前端 `api.js:81` 发 body `{userId}`；后端 `ForumController:11` 是 `@RequestParam userId` + 可选 `@RequestBody` | 后端写了兼容逻辑（`body.get("userId")` 优先），**理论上能通，但必须实测** |
-| P3-2 | 根目录 `index.js` 死文件 | `E:\goodworlk\campus-life-miniapp\index.js` (122 行) | `app.json` 只注册 `pages/index/index`，此文件**永不加载**。且内容比正式版更旧（用 `wx.showActionSheet` 而非 `popup` 组件）。**建议删除**，避免改错文件 |
-| P3-3 | `pages/calendar` / `timetable` 无后端 | 见 P2-5/6 | 已在 P2 记录 |
-| P3-4 | 后端未启动时的静默降级 | `utils/api.js` 全局 | 所有 `request()` 失败都退回 mock，**用户无法区分「真实数据」与「演示数据」**。部分函数返回 `offline: true` 标记，但 UI 未必展示。建议加一个全局"离线演示"角标 |
-| P3-5 | `README_BACKEND.md` 说"五张表" | 文档 vs `schema.sql` | 实际 `schema.sql` 已扩到 21 张表（含 food/market/jobs/activities 三个模块），**文档未同步** |
-
----
-
-## 四、优先级建议
-
-```
-P0-1  首页路由表重构    ← 结构性，改起来 10 分钟，但消除一整类隐患
-P0-2  搜索功能补完      ← 后端 + 前端页面 + 接线，是需要真动手的一块
-P1-6  通知铃铛接线      ← 一行改动，立竿见影（数据已存在）
-P3-2  删死文件          ← 一行删除，消除混淆
-P2-*  假数据模块        ← 工作量大，按需排期
-P3-1  follow 参数实测   ← 跑一次后端验证即可
-P3-5  文档同步          ← 顺手修
-```
-
----
-
-## 五、验证方式
-
-本清单全部来自静态代码扫描。以下项**需要实际运行才能确认**：
-
-1. **P3-1** 关注接口在真实后端下的行为 —— 需 `mvn spring-boot:run` + MySQL 初始化后点一次关注。
-2. **P1-8** `index.js` 的 `switchTab` 是否真的从未被调用 —— 需在方法内打断点或加日志。
-3. **P2-1** 聊天消息持久化行为 —— 需实际发消息后退出重进确认。
-4. **接口连通性** —— 后端 36 个端点是否全部可用，需逐个实测。
+未扩展原项目的登录鉴权、远程图片上传、语音/图片私信、外部教务连接、广告官方活动链接、推荐算法服务。个人主页仍有最多50帖筛选和粉丝演示初值的旧限制。它们需要独立产品/接口或账号配置，不属于本次列出的补缺范围。
--- a/index.js
+++ /dev/null
@@ -1,122 +0,0 @@
-const app = getApp()
-
-Page({
-  data: {
-    schoolName: '内蒙古大学',
-    statusBarHeight: 20,
-    navBarHeight: 44,
-    capsuleReservedRight: 16,
-    activeTab: 'home'
-  },
-
-  onLoad() {
-    this.initNavigation()
-    this.setData({
-      schoolName: app.globalData.schoolName || '内蒙古大学'
-    })
-  },
-
-  initNavigation() {
-    try {
-      const windowInfo = wx.getWindowInfo
-        ? wx.getWindowInfo()
-        : wx.getSystemInfoSync()
-      const menuRect = wx.getMenuButtonBoundingClientRect
-        ? wx.getMenuButtonBoundingClientRect()
-        : null
-      const statusBarHeight = windowInfo.statusBarHeight || 20
-      let navBarHeight = 44
-      let capsuleReservedRight = 16
-
-      if (menuRect && menuRect.height && menuRect.top) {
-        navBarHeight = (menuRect.top - statusBarHeight) * 2 + menuRect.height
-        capsuleReservedRight = Math.max(
-          16,
-          windowInfo.windowWidth - menuRect.left + 8
-        )
-      }
-
-      this.setData({
-        statusBarHeight,
-        navBarHeight,
-        capsuleReservedRight
-      })
-    } catch (error) {
-      this.setData({
-        statusBarHeight: 20,
-        navBarHeight: 44,
-        capsuleReservedRight: 16
-      })
-    }
-  },
-
-  chooseSchool() {
-    wx.showActionSheet({
-      itemList: ['内蒙古大学', '北京大学', '中国人民大学'],
-      success: ({ tapIndex }) => {
-        const schools = ['内蒙古大学', '北京大学', '中国人民大学']
-        const schoolName = schools[tapIndex] || '内蒙古大学'
-        app.globalData.schoolName = schoolName
-        this.setData({ schoolName })
-      }
-    })
-  },
-
-  submitSearch(event) {
-    const keyword = (event.detail.value || '').trim()
-    if (!keyword) return
-    wx.showToast({
-      title: `搜索：${keyword}`,
-      icon: 'none'
-    })
-  },
-
-  openNotifications() {
-    wx.showToast({
-      title: '暂无新通知',
-      icon: 'none'
-    })
-  },
-
-  openFeature(event) {
-    const label = event.currentTarget.dataset.label || '功能'
-    if (label === '校园论坛') {
-      wx.navigateTo({ url: '/pages/community/community' })
-      return
-    }
-    wx.showToast({
-      title: `${label}功能开发中`,
-      icon: 'none'
-    })
-  },
-
-  changeRecommendations() {
-    wx.showToast({
-      title: '已换一批',
-      icon: 'none'
-    })
-  },
-
-  openPublish() {
-    wx.showActionSheet({
-      itemList: ['发布闲置', '发布兼职', '发布话题', '发布活动']
-    })
-  },
-
-  switchTab(event) {
-    const tab = event.currentTarget.dataset.tab
-    if (tab === 'publish') {
-      this.openPublish()
-      return
-    }
-    if (tab === 'home') {
-      this.setData({ activeTab: 'home' })
-      return
-    }
-    this.setData({ activeTab: tab })
-    wx.showToast({
-      title: `${event.currentTarget.dataset.label}页面开发中`,
-      icon: 'none'
-    })
-  }
-})
--- a/pages/activities/activities.wxml
+++ b/pages/activities/activities.wxml
@@ -1,5 +1,6 @@
 <view class="activities-page">
   <view class="nav" style="padding-top:{{statusBarHeight}}px;height:{{navBarHeight}}px;padding-right:{{capsuleReservedRight}}px"><view class="back" catchtap="goBack">‹</view><view class="nav-title">季节活动</view></view>
+  <offline-banner />
   <view wx:if="{{loading}}" class="state">正在整理校园活动</view>
   <view wx:elif="{{!activities.length}}" class="state"><text class="state-title">暂时没有活动</text>新的精彩很快就来</view>
   <block wx:else>
--- a/pages/activity-idol/activity-idol.wxml
+++ b/pages/activity-idol/activity-idol.wxml
@@ -4,6 +4,7 @@
     <view class="back" catchtap="goBack">‹</view>
     <view class="nav-title">校园人气王</view>
   </view>
+  <offline-banner />
 
   <!-- 活动头图 -->
   <view class="idol-hero">
--- a/pages/calendar/calendar.js
+++ b/pages/calendar/calendar.js
@@ -1,6 +1,6 @@
 const { getNavigationMetrics } = require('../../utils/navigation')
-const { parseDate, startOfWeek, getTermWeek, daysUntil } = require('../../utils/term')
-const mock = require('../../utils/mock-data')
+const { parseDate, getTermWeek, daysUntil } = require('../../utils/term')
+const api = require('../../utils/api')
 
 function shortDate(date) {
   return `${date.getMonth() + 1}.${date.getDate()}`
@@ -9,7 +9,8 @@
 Page({
   data: {
     ...getNavigationMetrics(),
-    termLabel: mock.termInfo.termLabel,
+    termLabel: '',
+    loading: true, error: '', offline: false,
     termStartLabel: '',
     termEndLabel: '',
     weekNo: 1,
@@ -18,45 +19,54 @@
     events: []
   },
 
-  onLoad() {
-    const today = new Date()
-    today.setHours(0, 0, 0, 0)
-    const { termInfo } = mock
-    const termStart = parseDate(termInfo.termStart)
-    const termEnd = parseDate(termInfo.termEnd)
-    const weekNo = getTermWeek(today)
+  onLoad() { this._alive = true; this.loadCalendar() },
+  onUnload() { this._alive = false },
+  onPullDownRefresh() { this.loadCalendar().finally(() => wx.stopPullDownRefresh()) },
+  async loadCalendar() {
+    this.setData({ loading: true, error: '' })
+    try {
+      const result = await api.getCalendar()
+      if (!this._alive) return
+      const { termInfo } = result
+      const today = new Date()
+      today.setHours(0, 0, 0, 0)
+      const termStart = parseDate(termInfo.termStart)
+      const termEnd = parseDate(termInfo.termEnd)
+      const weekNo = getTermWeek(today, termInfo.termStart)
 
-    // 事件状态：按当天是否落在起止区间内判断
-    const events = mock.calendarEvents.map((event) => {
-      const start = parseDate(event.start)
-      const end = parseDate(event.end || event.start)
-      const status = today > end ? 'past' : today >= start ? 'ongoing' : 'upcoming'
-      return {
-        ...event,
-        dateLabel: event.start === event.end ? shortDate(start) : `${shortDate(start)} - ${shortDate(end)}`,
-        status,
-        statusLabel: status === 'past' ? '已结束' : status === 'ongoing' ? '进行中' : '未开始'
-      }
-    })
+      // 事件状态：按当天是否落在起止区间内判断
+      const events = result.events.map((event) => {
+        const start = parseDate(event.start)
+        const end = parseDate(event.end || event.start)
+        const status = today > end ? 'past' : today >= start ? 'ongoing' : 'upcoming'
+        return {
+          ...event,
+          dateLabel: event.start === event.end ? shortDate(start) : `${shortDate(start)} - ${shortDate(end)}`,
+          status,
+          statusLabel: status === 'past' ? '已结束' : status === 'ongoing' ? '进行中' : '未开始'
+        }
+      })
 
-    const totalDays = Math.max(1, (termEnd - termStart) / 86400000)
-    const passed = Math.min(totalDays, Math.max(0, (today - termStart) / 86400000))
-    const daysToMidterm = daysUntil(termInfo.midtermStart, today)
-    const daysToFinal = daysUntil(termInfo.finalStart, today)
-    const headline = today > termEnd
-      ? '本学期已结束，好好享受假期'
-      : daysToMidterm > 0
-        ? `距期中考试还有 ${daysToMidterm} 天`
-        : `距期末考试还有 ${daysToFinal} 天`
+      const totalDays = Math.max(1, (termEnd - termStart) / 86400000)
+      const passed = Math.min(totalDays, Math.max(0, (today - termStart) / 86400000))
+      const daysToMidterm = daysUntil(termInfo.midtermStart, today)
+      const daysToFinal = daysUntil(termInfo.finalStart, today)
+      const headline = today > termEnd
+        ? '本学期已结束，好好享受假期'
+        : daysToMidterm > 0
+          ? `距期中考试还有 ${daysToMidterm} 天`
+          : daysToFinal > 0 ? `距期末考试还有 ${daysToFinal} 天` : '考试期间，具体安排以教务通知为准'
 
-    this.setData({
-      termStartLabel: `${termStart.getMonth() + 1}月${termStart.getDate()}日`,
-      termEndLabel: `${termEnd.getMonth() + 1}月${termEnd.getDate()}日`,
-      weekNo,
-      headline,
-      progress: Math.round((passed / totalDays) * 100),
-      events
-    })
+      this.setData({
+        termLabel: termInfo.termLabel, loading: false, offline: !!result.offline,
+        termStartLabel: `${termStart.getMonth() + 1}月${termStart.getDate()}日`,
+        termEndLabel: `${termEnd.getMonth() + 1}月${termEnd.getDate()}日`,
+        weekNo,
+        headline,
+        progress: Math.round((passed / totalDays) * 100),
+        events
+      })
+    } catch (error) { if (this._alive) this.setData({ loading: false, error: error.message || '校历加载失败' }) }
   },
 
   goBack() {
--- a/pages/calendar/calendar.json
+++ b/pages/calendar/calendar.json
@@ -1,3 +1,4 @@
 {
-  "backgroundColor": "#F6F8FB"
+  "backgroundColor": "#F6F8FB",
+  "enablePullDownRefresh": true
 }
--- a/pages/calendar/calendar.wxml
+++ b/pages/calendar/calendar.wxml
@@ -4,15 +4,18 @@
     <view class="back" catchtap="goBack">‹</view>
     <view class="nav-title">校历</view>
   </view>
+  <offline-banner local="{{offline}}" />
 
-  <view class="cal-hero">
+  <view wx:if="{{loading}}" class="cal-state">正在加载校历…</view>
+  <view wx:if="{{error}}" class="cal-state" bindtap="loadCalendar">{{error}} · 点击重试</view>
+  <view class="cal-hero" wx:if="{{!loading && !error}}">
     <text class="cal-term">{{termLabel}}</text>
     <text class="cal-week">第{{weekNo}}教学周 · {{headline}}</text>
     <view class="cal-bar"><view class="cal-bar-inner" style="width:{{progress}}%"></view></view>
     <view class="cal-bar-meta"><text>{{termStartLabel}} 开学</text><text>已过 {{progress}}%</text><text>{{termEndLabel}} 放假</text></view>
   </view>
 
-  <view class="cal-list">
+  <view class="cal-list" wx:if="{{!loading && !error}}">
     <view wx:for="{{events}}" wx:key="title" class="cal-item">
       <view class="cal-rail">
         <view class="cal-dot {{item.status}}"></view>
--- a/pages/calendar/calendar.wxss
+++ b/pages/calendar/calendar.wxss
@@ -29,3 +29,5 @@
 .cal-chip.upcoming{background:#e2f7ea;color:#2f9e68}
 .cal-date{display:block;margin-top:10rpx;font-size:19rpx;color:#688092}
 .cal-note{display:block;margin-top:6rpx;font-size:18rpx;line-height:26rpx;color:#98a2ac}
+
+.cal-state{padding:50rpx 24rpx;text-align:center;font-size:26rpx;color:#99a3ad}
--- a/pages/chat/chat.js
+++ b/pages/chat/chat.js
@@ -1,79 +1,101 @@
+const api = require('../../utils/api')
 const { getNavigationMetrics } = require('../../utils/navigation')
+const { formatTime } = require('../../utils/format-time')
 const popup = require('../../utils/ui-popup-helper')
 
 Page({
-  data: {
-    ...getNavigationMetrics(),
-    name: '同学',
-    avatar: '同',
-    color: '#7EB6F6',
-    inputValue: '',
-    scrollTo: 'message-end',
-    uiPopup: null,
-    messages: [
-      { id: 1, mine: false, type: 'text', content: '你好，看到你收藏了我的台灯～' },
-      { id: 2, mine: true, type: 'text', content: '你好，请问台灯还在吗？晚上方便自提吗？' },
-      { id: 3, mine: false, type: 'text', content: '还在的，今晚可以宿舍楼下自提。' },
-      { id: 4, mine: false, type: 'image', image: '/assets/home/recommend-lamp.jpg' },
-      { id: 5, mine: true, type: 'text', content: '好的，那晚上七点见！' }
-    ]
+  data: { ...getNavigationMetrics(), name: '同学', avatar: '同', color: '#7EB6F6', inputValue: '', scrollTo: 'message-end', messages: [], loading: false, sending: false, hasMore: false, muted: false, offline: false, error: '', uiPopup: null },
+  onLoad(options) {
+    this._alive = true
+    this.peerId = Number(options.id)
+    this.setData({ name: options.name || '同学', avatar: options.avatar || '同', color: options.color || '#7EB6F6' })
+    if (!Number.isSafeInteger(this.peerId) || this.peerId < 1 || this.peerId === api.CURRENT_USER_ID) this.setData({ error: this.peerId === api.CURRENT_USER_ID ? '这是你自己的账号，无需发起私聊' : '聊天对象无效' })
   },
-
-  onLoad(options) {
-    this.setData({
-      name: decodeURIComponent(options.name || '同学'),
-      avatar: decodeURIComponent(options.avatar || '同'),
-      color: decodeURIComponent(options.color || '#7EB6F6')
-    })
+  onShow() {
+    if (!Number.isSafeInteger(this.peerId) || this.peerId < 1 || this.peerId === api.CURRENT_USER_ID) return
+    clearInterval(this._poll)
+    this.loadMessages()
+    this._poll = setInterval(() => this.loadMessages(false, true), 5000)
   },
-
-  goBack() {
-    wx.navigateBack()
+  onHide() { clearInterval(this._poll); this._poll = null },
+  onUnload() { this._alive = false; this.onHide(); this._seq = (this._seq || 0) + 1 },
+  async loadMessages(older = false, quiet = false) {
+    if (this._busy || this._mutating || this.data.sending || (older && !this.data.hasMore)) return
+    const beforeId = older && this.data.messages.length ? this.data.messages[0].id : undefined
+    const seq = this._seq = (this._seq || 0) + 1
+    this._busy = true
+    if (!quiet) this.setData({ loading: true, error: '' })
+    try {
+      const result = await api.getChatMessages(this.peerId, { beforeId })
+      if (!this._alive || seq !== this._seq) return
+      const sourceChanged = this.data.messages.length && this.data.offline !== !!result.offline
+      if (sourceChanged && older) { this._busy = false; this.loadMessages(); return }
+      const previous = sourceChanged ? [] : this.data.messages
+      const map = new Map(previous.map(message => [String(message.id), message]))
+      ;(result.list || []).forEach(message => map.set(String(message.id), { ...message, time: formatTime(message.createdAt) }))
+      const messages = Array.from(map.values()).sort((a, b) => Number(a.id) - Number(b.id))
+      const changed = messages.length !== previous.length
+      this.setData({ messages, offline: !!result.offline, muted: !!result.muted, error: '', hasMore: older || !previous.length ? !!result.hasMore : this.data.hasMore, ...(changed && !older ? { scrollTo: 'message-' + messages[messages.length - 1].id } : {}) })
+      const last = messages[messages.length - 1]
+      if (last) await api.markChatRead(this.peerId, last.id, api.CURRENT_USER_ID, !!result.offline)
+    } catch (error) {
+      if (this._alive && seq === this._seq) this.setData({ error: error.message || '消息加载失败，请重试' })
+    } finally {
+      if (seq === this._seq) { this._busy = false; if (this._alive) this.setData({ loading: false }) }
+    }
   },
-
-  inputMessage(event) {
-    this.setData({ inputValue: event.detail.value })
+  loadOlder() { return this.loadMessages(true) },
+  retry() { return this.loadMessages() },
+  inputMessage(event) { this.setData({ inputValue: event.detail.value }) },
+  async sendMessage() {
+    const content = this.data.inputValue.trim()
+    if (!content || this.data.sending || this._mutating) return
+    if (!Number.isSafeInteger(this.peerId) || this.peerId < 1 || this.peerId === api.CURRENT_USER_ID) { popup.toast(this, '聊天对象无效'); return }
+    this._seq = (this._seq || 0) + 1
+    this._busy = false
+    this.setData({ sending: true, loading: false })
+    try {
+      const message = await api.sendChatMessage(this.peerId, content, { name: this.data.name })
+      if (!this._alive) return
+      const sourceChanged = this.data.offline !== !!message.offline
+      const messages = (sourceChanged ? [] : this.data.messages).filter(old => String(old.id) !== String(message.id)).concat({ ...message, time: formatTime(message.createdAt) })
+      this.setData({ messages, inputValue: '', offline: !!message.offline, error: '', scrollTo: 'message-' + message.id })
+      if (message.offline) popup.toast(this, '已保存到本机演示记录，未发送给对方', { tone: 'warn' })
+    } catch (error) {
+      if (this._alive) popup.toast(this, error.message || '发送失败，请重试', { tone: 'error' })
+    } finally {
+      if (this._alive) { this.setData({ sending: false }); this.loadMessages(false, true) }
+    }
   },
-
-  sendMessage() {
-    const content = this.data.inputValue.trim()
-    if (!content) return
-    const messages = this.data.messages.concat({
-      id: Date.now(),
-      mine: true,
-      type: 'text',
-      content
-    })
-    this.setData({ messages, inputValue: '', scrollTo: 'message-end' })
-  },
-
   more() {
     popup.sheet(this, {
       title: this.data.name,
-      actions: [
-        { label: '查看资料' },
-        { label: '消息免打扰' },
-        { label: '清空聊天记录' }
-      ],
-      action: ({ item }) => {
-        if (item.label === '清空聊天记录') {
-          popup.modal(this, {
-            title: '清空聊天记录',
-            message: '清空后无法恢复，确定要清空吗？',
-            tone: 'error',
-            confirmText: '清空',
-            confirm: () => this.setData({ messages: [] })
-          })
-          return
-        }
-        popup.toast(this, item.label)
+      actions: [{ label: '查看资料' }, { label: this.data.muted ? '关闭免打扰' : '消息免打扰' }, { label: '清空聊天记录' }],
+      action: ({ index }) => {
+        if (index === 0) { wx.navigateTo({ url: `/pages/user-home/user-home?id=${this.peerId}` }); return }
+        if (index === 1) return this.toggleMute()
+        popup.modal(this, { title: '清空聊天记录', message: '只清空你看到的记录，不影响对方，确定清空吗？', tone: 'error', confirmText: '清空', confirm: () => this.clearMessages() })
       }
     })
   },
-
-  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
+  async toggleMute() {
+    const muted = !this.data.muted
+    await api.muteChat(this.peerId, muted, api.CURRENT_USER_ID, this.data.offline)
+    if (this._alive) this.setData({ muted })
+  },
+  async clearMessages() {
+    if (this._mutating || this.data.sending) return
+    this._mutating = true
+    this._seq = (this._seq || 0) + 1
+    try {
+      await api.clearChatMessages(this.peerId, api.CURRENT_USER_ID, this.data.offline)
+      if (this._alive) this.setData({ messages: [], hasMore: false, error: '', loading: false })
+    } finally { this._mutating = false; this._busy = false }
+  },
+  goBack() { if (getCurrentPages().length > 1) wx.navigateBack(); else wx.reLaunch({ url: '/pages/messages/messages' }) },
+  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event) },
   onPopupCancel() { this._popupCancel && this._popupCancel() },
-  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
-  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
+  onPopupAction(event) { this._popupAction && this._popupAction(event) },
+  onPopupInput(event) { this._popupInput && this._popupInput(event) },
   onPopupClose() { popup.hide(this) }
 })
--- a/pages/chat/chat.wxml
+++ b/pages/chat/chat.wxml
@@ -5,29 +5,34 @@
       <view class="back-button" bindtap="goBack">‹</view>
       <view class="chat-title-wrap">
         <text class="chat-name">{{name}}</text>
-        <text class="chat-status">在线</text>
+        <text class="chat-status">{{offline ? '本机演示' : (muted ? '已开启免打扰' : '校园私信')}}</text>
       </view>
       <view class="more-button" bindtap="more">•••</view>
     </view>
   </view>
+  <offline-banner local="{{offline}}" />
 
-  <scroll-view class="message-scroll" scroll-y scroll-into-view="{{scrollTo}}" enhanced>
-    <text class="chat-date">今天 18:42</text>
-    <view wx:for="{{messages}}" wx:key="id" class="message-row {{item.mine ? 'mine' : 'theirs'}}">
+  <scroll-view class="message-scroll" style="height:calc(100vh - {{statusBarHeight + navBarHeight}}px - 150rpx - env(safe-area-inset-bottom));" scroll-y scroll-into-view="{{scrollTo}}" enhanced>
+    <view wx:if="{{hasMore}}" class="chat-date" bindtap="loadOlder">加载更早消息</view>
+    <view wx:if="{{loading}}" class="chat-date">正在加载…</view>
+    <view wx:if="{{error}}" class="chat-date" bindtap="retry">{{error}} · 点击重试</view>
+    <view wx:if="{{!loading && !error && !messages.length}}" class="chat-date">还没有消息，打个招呼吧</view>
+    <view wx:for="{{messages}}" wx:key="id" id="message-{{item.id}}" class="message-row {{item.mine ? 'mine' : 'theirs'}}">
       <view wx:if="{{!item.mine}}" class="chat-avatar" style="background:{{color}}">{{avatar}}</view>
       <view class="message-bubble {{item.type === 'image' ? 'image-bubble' : ''}}">
         <text wx:if="{{item.type === 'text'}}">{{item.content}}</text>
         <image wx:else src="{{item.image}}" mode="aspectFill" />
       </view>
+      <text wx:if="{{item.offline && item.mine}}" class="local-label">仅本机</text>
     </view>
     <view id="message-end" class="message-end"></view>
   </scroll-view>
 
   <view class="chat-composer">
     <view class="voice-button">⌁</view>
-    <input class="message-input" value="{{inputValue}}" placeholder="发消息…" confirm-type="send" bindinput="inputMessage" bindconfirm="sendMessage" />
+    <input class="message-input" value="{{inputValue}}" placeholder="发消息…" maxlength="1000" disabled="{{sending}}" confirm-type="send" bindinput="inputMessage" bindconfirm="sendMessage" />
     <view class="emoji-button">☺</view>
-    <view class="send-button {{inputValue ? 'ready' : ''}}" bindtap="sendMessage">发送</view>
+    <view class="send-button {{inputValue ? 'ready' : ''}}" bindtap="sendMessage">{{sending ? '保存中' : '发送'}}</view>
     <view class="composer-safe"></view>
   </view>
 
@@ -41,6 +46,9 @@
     editable="{{uiPopup.editable}}"
     placeholder="{{uiPopup.placeholder}}"
     inputValue="{{uiPopup.inputValue}}"
+    actions="{{uiPopup.actions}}"
+    show-cancel="{{uiPopup.showCancel}}"
+    cancel-text="{{uiPopup.cancelText}}"
     confirm-text="{{uiPopup.confirmText}}"
     bindconfirm="onPopupConfirm"
     bindcancel="onPopupCancel"
--- a/pages/chat/chat.wxss
+++ b/pages/chat/chat.wxss
@@ -2,6 +2,8 @@
   height: 100vh;
   background: #f3f6fa;
   overflow: hidden;
+  display: flex;
+  flex-direction: column;
 }
 
 .chat-navbar {
@@ -55,6 +57,8 @@
 }
 
 .message-scroll {
+  flex: 1;
+  min-height: 0;
   height: calc(100vh - 180rpx - env(safe-area-inset-bottom));
   padding: 20rpx 26rpx;
   box-sizing: border-box;
@@ -179,3 +183,5 @@
 .send-button.ready {
   background: #3476f6;
 }
+
+.local-label{align-self:center;margin:0 12rpx;font-size:18rpx;color:#a56a10}
--- a/pages/community/community.js
+++ b/pages/community/community.js
@@ -1,9 +1,15 @@
+const popup = require('../../utils/ui-popup-helper')
 const api = require('../../utils/api')
 const { formatTime } = require('../../utils/format-time')
 const tabs = ['关注','推荐','学习','生活','求助','二手','社团']
 const tabMap = { 关注:{scope:'followed'}, 推荐:{tag:''}, 学习:{tag:'学习'}, 生活:{tag:'校园日常'}, 求助:{tag:'求助'}, 二手:{tag:'二手闲置'}, 社团:{tag:'社团活动'} }
 Page({
-  data:{statusBarHeight:20,navBarHeight:44,tabs,activeTab:1,posts:[],page:1,size:6,total:0,loading:false,offline:false},
+  data:{statusBarHeight:20,navBarHeight:44,tabs,activeTab:1,posts:[],page:1,size:6,total:0,loading:false,offline:false,uiPopup:null},
+  onPopupConfirm(e){this._popupConfirm&&this._popupConfirm(e)},
+  onPopupCancel(){this._popupCancel&&this._popupCancel()},
+  onPopupAction(e){this._popupAction&&this._popupAction(e)},
+  onPopupInput(e){this._popupInput&&this._popupInput(e)},
+  onPopupClose(){popup.hide(this)},
   onLoad(q){this.initNavigation();if(q&&q.tab!==undefined&&tabs[Number(q.tab)]){this.setData({activeTab:Number(q.tab)})}this.loadPosts(true)},
   onShow(){ if(this._needReload){ this._needReload=false; this.loadPosts(true) } },
   initNavigation(){try{const w=wx.getWindowInfo?wx.getWindowInfo():wx.getSystemInfoSync();this.setData({statusBarHeight:w.statusBarHeight||20})}catch(e){}},
@@ -23,14 +29,15 @@
   onReachBottom(){if(this.data.posts.length<this.data.total)this.loadPosts(false)},
   openPublish(){wx.navigateTo({url:'/pages/publish/publish'})},
   goBack(){const pages=getCurrentPages();if(pages.length>1){wx.navigateBack()}else{wx.reLaunch({url:'/pages/index/index'})}},
-  onLike(e){
-    const id=e.currentTarget.dataset.id
-    const posts=this.data.posts.map(p=>p.id===id?{...p,liked:!p.liked,likeCount:Math.max(0,p.likeCount+(p.liked?-1:1))}:p)
-    this.setData({posts});api.likePost(id).catch(()=>{})
+  async onLike(e){
+    const id=e.currentTarget.dataset.id;if(this._liking)return;this._liking=true
+    try{await api.likePost(id);this.setData({posts:this.data.posts.map(p=>String(p.id)===String(id)?{...p,liked:!p.liked,likeCount:Math.max(0,(p.likeCount||0)+(p.liked?-1:1))}:p)})}
+    catch(error){popup.toast(this,error.message||'点赞失败',{tone:'error'})}finally{this._liking=false}
   },
-  onFollow(e){
-    const i=e.currentTarget.dataset.index;const p=this.data.posts[i];if(!p)return
-    this.setData({[`posts[${i}].followed`]:!p.followed});api.followUser(p.authorId||p.id).catch(()=>{})
+  async onFollow(e){
+    const p=this.data.posts[Number(e.currentTarget.dataset.index)];if(!p||this._following)return;this._following=true
+    try{await api.followUser(p.authorId||p.id);this.setData({posts:this.data.posts.map(row=>String(row.authorId)===String(p.authorId)?{...row,followed:!p.followed}:row)})}
+    catch(error){popup.toast(this,error.message||'关注失败',{tone:'error'})}finally{this._following=false}
   },
   // 狐友节奏：点正文/图 → 整页详情；点头像/昵称 → 整页个人主页
   openDetail(e){
@@ -44,7 +51,7 @@
     wx.navigateTo({url:`/pages/user-home/user-home?id=${id}`})
   },
   onShareAppMessage(res){
-    const id=res.target&&res.target.dataset?id=res.target.dataset.id:null
+    const id=res&&res.target&&res.target.dataset?res.target.dataset.id:null
     const p=this.data.posts.find(x=>x.id==id)||this.data.posts[0]
     return { title:p?(p.title||p.content||'校园论坛帖子').slice(0,30):'校园论坛', path:p?`/pages/post-detail/post-detail?id=${p.id}`:'/pages/community/community' }
   }
--- a/pages/community/community.wxml
+++ b/pages/community/community.wxml
@@ -1,5 +1,6 @@
 <view class="community-page">
   <view class="nav" style="padding-top:{{statusBarHeight}}px;height:{{navBarHeight}}px"><view class="back" catchtap="goBack">‹</view><view class="nav-title">校园论坛</view><view class="post-entry" catchtap="openPublish">发帖 <text>＋</text></view></view>
+  <offline-banner local="{{offline}}" />
   <view wx:if="{{offline}}" class="offline">离线演示模式 · 接入后端即可同步真实帖子</view>
   <!-- 狐友式大号主tab：active加粗变黑，无下划线 -->
   <scroll-view class="tabs" scroll-x="true" enhanced="true" show-scrollbar="false"><view class="tab-row"> <view wx:for="{{tabs}}" wx:key="*this" class="tab {{activeTab===index?'active':''}}" data-index="{{index}}" catchtap="onTabTap">{{item}}</view></view></scroll-view>
@@ -36,3 +37,5 @@
   </scroll-view>
   <view class="fab" catchtap="openPublish"><text>＋</text><label>发帖</label></view>
 </view>
+
+<ui-popup id="ui-popup" visible="{{uiPopup.visible}}" mode="{{uiPopup.mode}}" tone="{{uiPopup.tone}}" title="{{uiPopup.title}}" message="{{uiPopup.message}}" editable="{{uiPopup.editable}}" placeholder="{{uiPopup.placeholder}}" inputValue="{{uiPopup.inputValue}}" actions="{{uiPopup.actions}}" show-cancel="{{uiPopup.showCancel}}" confirm-text="{{uiPopup.confirmText}}" bindconfirm="onPopupConfirm" bindcancel="onPopupCancel" bindaction="onPopupAction" bindinput="onPopupInput" bindclose="onPopupClose" />
--- a/pages/discover/discover.js
+++ b/pages/discover/discover.js
@@ -8,6 +8,7 @@
   const likes = post.likeCount || 0
   return {
     id: post.id,
+    authorId: post.authorId,
     author: post.authorNickname || '校园同学',
     avatar: post.authorAvatar || '',
     avatarText: (post.authorNickname || '同')[0],
@@ -59,7 +60,7 @@
     ...getNavigationMetrics(),
     // 活动轮播位：优先取后端 banner 表，取不到时用这份兜底
     heroBanners: [
-      { theme: 'pink', eyebrow: 'CAMPUS IDOL', title: '校草校花评选', badge: '即将开始', subtitle: '报名即将开始 · 上传照片赢校园人气王', linkType: 'none', linkValue: '' },
+      { theme: 'pink', eyebrow: 'CAMPUS IDOL', title: '校草校花评选', badge: '人气评选', subtitle: '上传照片，参与校园人气评选', linkType: 'page', linkValue: '/pages/activity-idol/activity-idol' },
       { theme: 'blue', eyebrow: 'CAMPUS LIVE', title: '校园歌手大赛', badge: '12.05', subtitle: '决赛夜 · 大礼堂 19:00 · 现场投票', linkType: 'page', linkValue: '/pages/calendar/calendar' }
     ],
     topics: [
@@ -130,24 +131,7 @@
     try {
       const res = await api.getForumPosts({ scope: 'hot', page: 1, size: 50 })
       const hotPosts = (res.list || []).map(discoverCard)
-      const localPosts = wx.getStorageSync('mock_posts') || []
-      const normalizedLocal = localPosts.map((item) => ({
-        id: item.id,
-        author: '林知夏',
-        avatar: '/assets/social/profile-avatar.webp',
-        badge: '刚刚发布',
-        time: '刚刚',
-        title: item.title,
-        content: item.content,
-        image: item.media && item.media[0] ? item.media[0] : '',
-        location: item.location || '内蒙古大学',
-        likes: 0,
-        comments: 0,
-        liked: false,
-        followed: true,
-        tag: item.typeLabel || '校园日常'
-      }))
-      const allPosts = normalizedLocal.concat(hotPosts)
+      const allPosts = hotPosts
       this.setData({ posts: allPosts, loading: false, offline: !!res.offline })
       this.applyFilter(this.data.activeCategory, allPosts)
     } catch (error) {
@@ -172,27 +156,32 @@
     this.setData({ visiblePosts: posts.filter(filters[index] || filters[0]) })
   },
 
-  toggleLike(event) {
-    const id = Number(event.currentTarget.dataset.id)
-    const posts = this.data.posts.map((item) => {
-      if (item.id !== id) return item
-      return {
-        ...item,
-        liked: !item.liked,
-        likes: item.likes + (item.liked ? -1 : 1)
-      }
-    })
-    this.setData({ posts })
-    this.applyFilter(this.data.activeCategory, posts)
-  },
-
-  toggleFollow(event) {
-    const id = Number(event.currentTarget.dataset.id)
-    const posts = this.data.posts.map((item) =>
-      item.id === id ? { ...item, followed: !item.followed } : item
-    )
-    this.setData({ posts })
-    this.applyFilter(this.data.activeCategory, posts)
+  async toggleLike(event) {
+    const id = event.currentTarget.dataset.id
+    const post = this.data.posts.find(item => String(item.id) === String(id))
+    if (!post || this._likePending) return
+    this._likePending = true
+    try {
+      await api.likePost(id)
+      const posts = this.data.posts.map(item => String(item.id) === String(id) ? { ...item, liked: !item.liked, likes: Math.max(0, item.likes + (item.liked ? -1 : 1)) } : item)
+      this.setData({ posts })
+      this.applyFilter(this.data.activeCategory, posts)
+    } catch (error) { popup.toast(this, error.message || '点赞失败', { tone: 'error' }) }
+    finally { this._likePending = false }
+  },
+
+  async toggleFollow(event) {
+    const id = event.currentTarget.dataset.id
+    const post = this.data.posts.find(item => String(item.id) === String(id))
+    if (!post || this._followPending) return
+    this._followPending = true
+    try {
+      await api.followUser(post.authorId || post.id)
+      const posts = this.data.posts.map(item => String(item.authorId || item.id) === String(post.authorId || post.id) ? { ...item, followed: !post.followed } : item)
+      this.setData({ posts })
+      this.applyFilter(this.data.activeCategory, posts)
+    } catch (error) { popup.toast(this, error.message || '关注失败', { tone: 'error' }) }
+    finally { this._followPending = false }
   },
 
   openSearch() {
@@ -205,16 +194,14 @@
       confirm: (res) => {
         const keyword = (res.inputValue || '').trim()
         if (!keyword) return
-        const visiblePosts = this.data.posts.filter((item) =>
-          `${item.title}${item.content}${item.tag}`.includes(keyword)
-        )
-        this.setData({ visiblePosts, activeCategory: 0 })
+        if (keyword.length > 64) { popup.toast(this, '关键词不能超过64字'); return }
+        wx.navigateTo({ url: `/pages/search/search?keyword=${encodeURIComponent(keyword)}` })
       }
     })
   },
 
   openPost(event) {
-    popup.toast(this, event.currentTarget.dataset.title)
+    wx.navigateTo({ url: `/pages/post-detail/post-detail?id=${encodeURIComponent(event.currentTarget.dataset.id)}` })
   },
 
   onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
--- a/pages/discover/discover.wxml
+++ b/pages/discover/discover.wxml
@@ -9,6 +9,7 @@
       </view>
     </view>
   </view>
+  <offline-banner local="{{offline}}" />
 
   <view class="discover-content">
     <!-- 顶部轮播：课表 / 校历 / 活动位（校草校花等引流活动放这里） -->
@@ -75,7 +76,7 @@
       wx:key="id"
       class="feed-card"
       bindtap="openPost"
-      data-title="{{item.title}}"
+      data-id="{{item.id}}"
     >
       <view class="author-row">
         <image wx:if="{{item.avatar}}" class="author-avatar" src="{{item.avatar}}" mode="aspectFill" />
@@ -118,6 +119,9 @@
     editable="{{uiPopup.editable}}"
     placeholder="{{uiPopup.placeholder}}"
     inputValue="{{uiPopup.inputValue}}"
+    actions="{{uiPopup.actions}}"
+    show-cancel="{{uiPopup.showCancel}}"
+    cancel-text="{{uiPopup.cancelText}}"
     confirm-text="{{uiPopup.confirmText}}"
     bindconfirm="onPopupConfirm"
     bindcancel="onPopupCancel"
--- a/pages/food-list/food-list.wxml
+++ b/pages/food-list/food-list.wxml
@@ -1,5 +1,6 @@
 <view class="food-page">
 <view class="nav" style="padding-top:{{statusBarHeight}}px;height:{{navBarHeight}}px;padding-right:{{capsuleReservedRight}}px"><view class="back" catchtap="goBack">‹</view><view class="nav-title">校园外卖</view></view>
+  <offline-banner />
   <view class="hero card"><image class="hero-image" src="/assets/home/food-fresh.webp" mode="aspectFill"/><view class="hero-copy"><text class="eyebrow">下课后的好食光</text><text class="hero-title">好好吃饭
 也好好生活</text><text class="hero-note">校园好味 · 送到楼下</text></view></view>
   <view class="search-box card"><text class="search-mark">⌕</text><input class="search-input" value="{{keyword}}" placeholder="今天想吃哪一家？" placeholder-class="search-placeholder" bindinput="search" confirm-type="search" maxlength="40"/><view wx:if="{{keyword}}" class="search-clear" bindtap="clearSearch">×</view></view>
--- a/pages/food-order/food-order.wxml
+++ b/pages/food-order/food-order.wxml
@@ -1,5 +1,6 @@
 <view class="food-page order-page">
 <view class="nav" style="padding-top:{{statusBarHeight}}px;height:{{navBarHeight}}px;padding-right:{{capsuleReservedRight}}px"><view class="back" catchtap="goBack">‹</view><view class="nav-title">确认订单</view></view>
+  <offline-banner />
   <view wx:if="{{loading}}" class="state">正在确认餐食与配送信息…</view>
   <view wx:elif="{{error}}" class="state"><text class="state-title">{{error}}</text><view bindtap="goBack">返回选购</view></view>
   <block wx:else>
--- a/pages/food-shop/food-shop.wxml
+++ b/pages/food-shop/food-shop.wxml
@@ -1,5 +1,6 @@
 <view class="food-page shop-page">
 <view class="nav" style="padding-top:{{statusBarHeight}}px;height:{{navBarHeight}}px;padding-right:{{capsuleReservedRight}}px"><view class="back" catchtap="goBack">‹</view><view class="nav-title">店铺点餐</view></view>
+  <offline-banner />
   <view wx:if="{{loading}}" class="state">正在加载菜单…</view>
   <view wx:elif="{{error}}" class="state"><text class="state-title">{{error}}</text><view class="muted" bindtap="goBack">返回重新选一家</view></view>
   <block wx:else>
--- a/pages/idol-join/idol-join.wxml
+++ b/pages/idol-join/idol-join.wxml
@@ -4,6 +4,7 @@
     <view class="back" catchtap="goBack">‹</view>
     <view class="nav-title">报名参选</view>
   </view>
+  <offline-banner />
 
   <view class="join-hero">
     <text class="join-hero-title">上传照片，赢校园人气王</text>
@@ -51,6 +52,9 @@
     tone="{{uiPopup.tone}}"
     title="{{uiPopup.title}}"
     message="{{uiPopup.message}}"
+    actions="{{uiPopup.actions}}"
+    show-cancel="{{uiPopup.showCancel}}"
+    cancel-text="{{uiPopup.cancelText}}"
     confirm-text="{{uiPopup.confirmText}}"
     bindconfirm="onPopupConfirm"
     bindcancel="onPopupCancel"
--- a/pages/index/index.js
+++ b/pages/index/index.js
@@ -1,5 +1,22 @@
 const app = getApp()
 const popup = require('../../utils/ui-popup-helper')
+const api = require('../../utils/api')
+
+// key 与首页 data-label 精确一致；动态目标由 action 校验真实数据后跳转。
+const FEATURE_ROUTES = {
+  '校园论坛': { url: '/pages/community/community' },
+  '外卖': { url: '/pages/food-list/food-list' },
+  '二手市场': { url: '/pages/market-list/market-list' },
+  '兼职': { url: '/pages/jobs/jobs' },
+  '季节活动': { url: '/pages/activities/activities' },
+  '作业答疑': { url: '/pages/community/community?tab=2' },
+  '论文指导': { url: '/pages/community/community?tab=4' },
+  '开学优惠': { url: '/pages/activities/activities' },
+  '学习好物': { url: '/pages/market-list/market-list?category=' + encodeURIComponent('学习好物') },
+  '奶茶优惠': { url: '/pages/food-shop/food-shop?id=1' },
+  '闲置台灯': { action: 'openRecommendedLamp' },
+  '联想小新校园优惠': { url: '/pages/campus-offer/campus-offer' }
+}
 
 Page({
   data: {
@@ -8,6 +25,10 @@
     navBarHeight: 44,
     capsuleReservedRight: 16,
     activeTab: 'home',
+    recommendations: [
+      { label: '奶茶优惠', image: '/assets/home/recommend-milktea.jpg', tag: '美食', title: '校内新开奶茶店', subtitle: '查看店铺菜单与优惠', location: '紫荆食堂', heat: 236 },
+      { label: '闲置台灯', image: '/assets/home/recommend-lamp.jpg', tag: '闲置', title: '几乎全新护眼台灯', subtitle: '可小刀 自提优先', location: '学生公寓', heat: 128 }
+    ],
     uiPopup: null
   },
 
@@ -66,52 +87,47 @@
   },
 
   submitSearch(event) {
-    const keyword = (event.detail.value || '').trim()
-    if (!keyword) return
-    popup.toast(this, `搜索：${keyword}`)
+    const keyword = String(event.detail.value || '').trim()
+    if (!keyword) { popup.toast(this, '请输入搜索关键词'); return }
+    if (keyword.length > 64) { popup.toast(this, '关键词不能超过64字'); return }
+    wx.navigateTo({ url: `/pages/search/search?keyword=${encodeURIComponent(keyword)}` })
   },
 
   openNotifications() {
-    popup.toast(this, '暂无新通知')
+    wx.navigateTo({ url: '/pages/messages/messages?tab=notice' })
   },
 
   openFeature(event) {
-    const label = event.currentTarget.dataset.label || '功能'
-    if (label === '校园论坛') {
-      wx.navigateTo({ url: '/pages/community/community' })
+    const label = event.currentTarget.dataset.label || ''
+    const route = Object.prototype.hasOwnProperty.call(FEATURE_ROUTES, label) && FEATURE_ROUTES[label]
+    if (!route) {
+      console.warn(`[首页路由] 该入口未配置路由：${label || '(空标签)'}`)
+      popup.toast(this, '入口暂不可用，请稍后再试', { tone: 'warn' })
       return
     }
-    // 外卖模块：店铺列表 → 点菜 → 下单
-    if (label === '外卖') {
-      wx.navigateTo({ url: '/pages/food-list/food-list' })
-      return
-    }
-    if (label === '二手市场') {
-      wx.navigateTo({ url: '/pages/market-list/market-list' })
-      return
-    }
-    if (label === '兼职') {
-      wx.navigateTo({ url: '/pages/jobs/jobs' })
-      return
-    }
-    if (label === '季节活动') {
-      wx.navigateTo({ url: '/pages/activities/activities' })
-      return
-    }
-    // 学习专区：作业/论文求助直接进社区对应板块发帖提问
-    if (label === '作业答疑') {
-      wx.navigateTo({ url: '/pages/community/community?tab=2' })
-      return
-    }
-    if (label === '论文指导') {
-      wx.navigateTo({ url: '/pages/community/community?tab=4' })
-      return
-    }
-    popup.toast(this, `${label}功能开发中`)
+    if (route.action) return this[route.action]()
+    wx.navigateTo({ url: route.url })
+  },
+
+  async openRecommendedLamp() {
+    if (this._openingLamp) return
+    this._openingLamp = true
+    try {
+      const goods = await api.getGoodsList(1)
+      const lamp = goods.find(item => /台灯/.test(item.title || ''))
+      // 已售出或未初始化种子时展示市场，不能虚构详情 id。
+      const url = lamp
+        ? `/pages/market-detail/market-detail?id=${encodeURIComponent(lamp.id)}`
+        : '/pages/market-list/market-list?category=' + encodeURIComponent('数码')
+      wx.navigateTo({ url })
+    } catch (error) {
+      popup.toast(this, error.message || '推荐加载失败，请稍后再试', { tone: 'error' })
+    } finally { this._openingLamp = false }
   },
 
   changeRecommendations() {
-    popup.toast(this, '已换一批')
+    const rows = this.data.recommendations
+    if (rows.length > 1) this.setData({ recommendations: rows.slice(1).concat(rows[0]) })
   },
 
   openPublish() {
@@ -129,20 +145,6 @@
     })
   },
 
-  switchTab(event) {
-    const tab = event.currentTarget.dataset.tab
-    if (tab === 'publish') {
-      this.openPublish()
-      return
-    }
-    if (tab === 'home') {
-      this.setData({ activeTab: 'home' })
-      return
-    }
-    this.setData({ activeTab: tab })
-    popup.toast(this, `${event.currentTarget.dataset.label}页面开发中`)
-  },
-
   onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
   onPopupCancel() { this._popupCancel && this._popupCancel() },
   onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
--- a/pages/index/index.wxml
+++ b/pages/index/index.wxml
@@ -15,8 +15,9 @@
         <image class="search-icon" src="/assets/icons/search.svg" mode="aspectFit" />
         <input
           class="search-input"
-          placeholder="搜索外卖、闲置、兼职或话题"
+          placeholder="搜索校园帖子标题或内容"
           placeholder-class="search-placeholder"
+          maxlength="64"
           confirm-type="search"
           bindconfirm="submitSearch"
         />
@@ -27,6 +28,7 @@
       </view>
     </view>
   </view>
+  <offline-banner />
 
   <view class="content">
     <view class="hero-card" bindtap="openFeature" data-label="开学优惠">
@@ -135,45 +137,18 @@
       </view>
 
       <view class="recommend-grid">
-        <view class="recommend-card" bindtap="openFeature" data-label="奶茶优惠">
+        <view wx:for="{{recommendations}}" wx:key="label" class="recommend-card" bindtap="openFeature" data-label="{{item.label}}">
           <view class="recommend-image-wrap">
-            <image class="recommend-image" src="/assets/home/recommend-milktea.jpg" mode="aspectFill" />
-            <text class="recommend-tag">美食</text>
+            <image class="recommend-image" src="{{item.image}}" mode="aspectFill" />
+            <text class="recommend-tag">{{item.tag}}</text>
           </view>
           <view class="recommend-title">
-            <text class="recommend-line">校内新开奶茶店</text>
-            <text class="recommend-line">学生专享第二杯半价</text>
+            <text class="recommend-line">{{item.title}}</text>
+            <text class="recommend-line">{{item.subtitle}}</text>
           </view>
           <view class="recommend-meta">
-            <view class="meta-side">
-              <image class="meta-icon" src="/assets/icons/pin.svg" mode="aspectFit" />
-              <text>紫荆食堂</text>
-            </view>
-            <view class="meta-side meta-hot">
-              <image class="meta-icon fire-icon" src="/assets/icons/fire.svg" mode="aspectFit" />
-              <text>236</text>
-            </view>
-          </view>
-        </view>
-
-        <view class="recommend-card" bindtap="openFeature" data-label="闲置台灯">
-          <view class="recommend-image-wrap">
-            <image class="recommend-image" src="/assets/home/recommend-lamp.jpg" mode="aspectFill" />
-            <text class="recommend-tag">闲置</text>
-          </view>
-          <view class="recommend-title">
-            <text class="recommend-line">几乎全新护眼台灯</text>
-            <text class="recommend-line">可小刀 自提优先</text>
-          </view>
-          <view class="recommend-meta">
-            <view class="meta-side">
-              <image class="meta-icon" src="/assets/icons/pin.svg" mode="aspectFit" />
-              <text>学生公寓</text>
-            </view>
-            <view class="meta-side meta-hot">
-              <image class="meta-icon fire-icon" src="/assets/icons/fire.svg" mode="aspectFit" />
-              <text>128</text>
-            </view>
+            <view class="meta-side"><image class="meta-icon" src="/assets/icons/pin.svg" mode="aspectFit" /><text>{{item.location}}</text></view>
+            <view class="meta-side meta-hot"><image class="meta-icon fire-icon" src="/assets/icons/fire.svg" mode="aspectFit" /><text>{{item.heat}}</text></view>
           </view>
         </view>
 
@@ -201,6 +176,9 @@
     editable="{{uiPopup.editable}}"
     placeholder="{{uiPopup.placeholder}}"
     inputValue="{{uiPopup.inputValue}}"
+    actions="{{uiPopup.actions}}"
+    show-cancel="{{uiPopup.showCancel}}"
+    cancel-text="{{uiPopup.cancelText}}"
     confirm-text="{{uiPopup.confirmText}}"
     bindconfirm="onPopupConfirm"
     bindcancel="onPopupCancel"
--- a/pages/jobs/jobs.wxml
+++ b/pages/jobs/jobs.wxml
@@ -1,5 +1,6 @@
 <view class="jobs-page">
   <view class="nav" style="padding-top:{{statusBarHeight}}px;height:{{navBarHeight}}px;padding-right:{{capsuleReservedRight}}px"><view class="back" catchtap="goBack">‹</view><view class="nav-title">校园兼职</view></view>
+  <offline-banner />
   <scroll-view class="chips" scroll-x enhanced show-scrollbar="{{false}}"><view class="chip-row"><view wx:for="{{settles}}" wx:key="value" class="chip {{settle===item.value?'active':''}}" data-settle="{{item.value}}" bindtap="chooseSettle">{{item.label}}</view></view></scroll-view>
   <view wx:if="{{loading}}" class="state">正在找合适的兼职</view>
   <view wx:elif="{{!jobs.length}}" class="state"><text class="state-title">暂时没有符合条件的岗位</text>稍后再来看看</view>
--- a/pages/market-detail/market-detail.js
+++ b/pages/market-detail/market-detail.js
@@ -7,12 +7,12 @@
   data: { ...getNavigationMetrics(), item: null, loading: true, error: '', uiPopup: null },
   onLoad(options) {
     this._alive = true
-    this.id = Number(options.id)
+    this.id = String(options.id || '')
     this.loadDetail()
   },
   onUnload() { this._alive = false },
   async loadDetail() {
-    if (!Number.isSafeInteger(this.id) || this.id < 1) { this.setData({ loading: false, error: '物品不存在' }); return }
+    if (!(/^[1-9]\d*$/.test(this.id) || /^local-\d+$/.test(this.id))) { this.setData({ loading: false, error: '物品不存在' }); return }
     try {
       const item = await api.getGoodsDetail(this.id)
       if (!this._alive) return
--- a/pages/market-detail/market-detail.wxml
+++ b/pages/market-detail/market-detail.wxml
@@ -1,5 +1,6 @@
 <view class="detail-page">
   <view class="nav" style="padding-top:{{statusBarHeight}}px;height:{{navBarHeight}}px;padding-right:{{capsuleReservedRight}}px"><view class="back" catchtap="goBack">‹</view><view class="nav-title">物品详情</view></view>
+  <offline-banner />
   <view wx:if="{{loading}}" class="state">正在打开物品详情</view>
   <view wx:elif="{{error}}" class="state"><text class="state-title">{{error}}</text><view bindtap="goBack">返回市场</view></view>
   <block wx:elif="{{item}}">
--- a/pages/market-list/market-list.js
+++ b/pages/market-list/market-list.js
@@ -15,7 +15,14 @@
     loading: true,
     uiPopup: null
   },
-  onLoad() { this._alive = true },
+  onLoad(options) {
+    this._alive = true
+    // 学习好物是首页展示名，沿用数据库已有的图书分类。
+    let category = options.category || '全部'
+    try { category = decodeURIComponent(category) } catch (error) {}
+    if (category === '学习好物') category = '图书'
+    if (this.data.categories.includes(category)) this.setData({ category })
+  },
   onShow() { this.loadGoods() },
   onUnload() { this._alive = false },
   onPullDownRefresh() { this.loadGoods().finally(() => wx.stopPullDownRefresh()) },
--- a/pages/market-list/market-list.wxml
+++ b/pages/market-list/market-list.wxml
@@ -2,6 +2,7 @@
   <view class="nav" style="padding-top:{{statusBarHeight}}px;height:{{navBarHeight}}px;padding-right:{{capsuleReservedRight}}px">
     <view class="back" catchtap="goBack">‹</view><view class="nav-title">二手市场</view>
   </view>
+  <offline-banner />
   <view class="mode-tabs"><view wx:for="{{modes}}" wx:key="value" class="mode-tab {{mode===item.value?'active':''}}" data-mode="{{item.value}}" bindtap="chooseMode">{{item.label}}</view></view>
   <scroll-view class="chips" scroll-x enhanced show-scrollbar="{{false}}"><view class="chip-row"><view wx:for="{{categories}}" wx:key="*this" class="chip {{category===item?'active':''}}" data-category="{{item}}" bindtap="chooseCategory">{{item}}</view></view></scroll-view>
   <view wx:if="{{loading}}" class="state">正在翻找校园里的好物</view>
--- a/pages/market-publish/market-publish.js
+++ b/pages/market-publish/market-publish.js
@@ -26,8 +26,8 @@
     if (description.length > 200) return popup.toast(this, '描述不能超过200字', { tone: 'warn' })
     this.setData({ submitting: true })
     try {
-      await api.publishGoods({ userId: api.CURRENT_USER_ID, title, price, conditionLevel: this.data.conditionLevel, category: this.data.category, images: this.data.images, image: this.data.images[0], description })
-      popup.toast(this, '发布成功', { tone: 'success' })
+      const result = await api.publishGoods({ userId: api.CURRENT_USER_ID, title, price, conditionLevel: this.data.conditionLevel, category: this.data.category, images: this.data.images, image: this.data.images[0], description })
+      popup.toast(this, result.offline ? '已保存到本机演示，未发布到后端' : '发布成功', { tone: result.offline ? 'warn' : 'success' })
       setTimeout(() => wx.navigateBack(), 550)
     } catch (error) {
       this.setData({ submitting: false })
--- a/pages/market-publish/market-publish.wxml
+++ b/pages/market-publish/market-publish.wxml
@@ -1,6 +1,7 @@
 <page-meta page-style="background:#F7F9FC;" />
 <view class="publish-page">
   <view class="nav" style="padding-top:{{statusBarHeight}}px;height:{{navBarHeight}}px;padding-right:{{capsuleReservedRight}}px"><view class="back" catchtap="goBack">‹</view><view class="nav-title">发布闲置</view></view>
+  <offline-banner />
 
   <view class="publish-intro">
     <view><text class="intro-title">挂个闲置</text><text class="intro-subtitle">让闲置流转，遇见刚好需要的同学</text></view>
--- a/pages/messages/messages.js
+++ b/pages/messages/messages.js
@@ -1,48 +1,69 @@
 const api = require('../../utils/api')
 const { getNavigationMetrics } = require('../../utils/navigation')
+const { formatTime } = require('../../utils/format-time')
 const popup = require('../../utils/ui-popup-helper')
 
 Page({
-  data: {
-    ...getNavigationMetrics(),
-    activeTab: 'chat',
-    conversations: [],
-    notifications: [],
-    unreadTotal: 3,
-    uiPopup: null
+  data: { ...getNavigationMetrics(), activeTab: 'chat', conversations: [], notifications: [], visibleNotifications: [], noticeFilter: '', unreadTotal: 0, likeUnread: 0, commentUnread: 0, loading: false, error: '', uiPopup: null },
+  onLoad(options) { this._alive = true; if (options.tab === 'notice') this.setData({ activeTab: 'notice' }) },
+  onShow() { this.loadMessages() },
+  onUnload() { this._alive = false; this._seq = (this._seq || 0) + 1 },
+  onPullDownRefresh() { this.loadMessages().finally(() => wx.stopPullDownRefresh()) },
+  async loadMessages() {
+    const seq = this._seq = (this._seq || 0) + 1
+    this.setData({ loading: true, error: '' })
+    try {
+      const [conversations, notifications] = await Promise.all([api.getConversations(), api.getNotifications()])
+      if (!this._alive || seq !== this._seq) return
+      this.setData({ conversations: conversations.map(row => ({ ...row, time: row.createdAt ? formatTime(row.createdAt) : row.time })), notifications: notifications.map(row => ({ ...row, time: row.createdAt ? formatTime(row.createdAt) : row.time })) })
+      this.applyNotices()
+    } catch (error) {
+      if (this._alive && seq === this._seq) this.setData({ error: error.message || '消息加载失败，请重试' })
+    } finally { if (this._alive && seq === this._seq) this.setData({ loading: false }) }
   },
-
-  async onLoad() {
-    const [conversations, notifications] = await Promise.all([
-      api.getConversations(),
-      api.getNotifications()
-    ])
-    this.setData({ conversations, notifications })
+  applyNotices() {
+    const notifications = this.data.notifications
+    const filter = this.data.noticeFilter
+    this.setData({
+      visibleNotifications: notifications.filter(row => !filter || (filter === 'like' ? ['like', 'favorite', 'follow'].includes(row.category) : row.category === filter)),
+      unreadTotal: this.data.conversations.reduce((sum, row) => sum + (row.muted ? 0 : Number(row.unread) || 0), 0) + notifications.filter(row => row.unread).length,
+      likeUnread: notifications.filter(row => row.unread && ['like', 'favorite', 'follow'].includes(row.category)).length,
+      commentUnread: notifications.filter(row => row.unread && row.category === 'comment').length
+    })
   },
-
   switchMessageTab(event) {
-    this.setData({ activeTab: event.currentTarget.dataset.tab })
+    this.setData({ activeTab: event.currentTarget.dataset.tab, noticeFilter: '' })
+    this.applyNotices()
   },
-
-  markAllRead() {
-    const conversations = this.data.conversations.map((item) => ({ ...item, unread: 0 }))
-    this.setData({ conversations, unreadTotal: 0 })
-    popup.toast(this, '已全部标记为已读')
+  async markAllRead() {
+    if (this._marking) return
+    this._marking = true
+    try {
+      const result = await api.markAllMessagesRead()
+      if (!this._alive) return
+      this.setData({ conversations: this.data.conversations.map(row => ({ ...row, unread: 0 })), notifications: this.data.notifications.map(row => ({ ...row, unread: false })) })
+      this.applyNotices()
+      popup.toast(this, result && result.offline ? '本机演示记录已标记为已读' : '已全部标记为已读')
+    } catch (error) {
+      if (this._alive) popup.toast(this, error.message || '标记失败，请重试', { tone: 'error' })
+    } finally { this._marking = false }
   },
-
   openChat(event) {
     const { id, name, color, avatar } = event.currentTarget.dataset
-    const url = `/pages/chat/chat?id=${id}&name=${encodeURIComponent(name)}&color=${encodeURIComponent(color || '#7EB6F6')}&avatar=${encodeURIComponent(avatar || name.slice(0, 1))}`
-    wx.navigateTo({ url })
+    wx.navigateTo({ url: `/pages/chat/chat?id=${encodeURIComponent(id)}&name=${encodeURIComponent(name || '同学')}&color=${encodeURIComponent(color || '#7EB6F6')}&avatar=${encodeURIComponent(avatar || (name || '同')[0])}` })
   },
-
   openQuick(event) {
-    popup.toast(this, event.currentTarget.dataset.label)
+    const filters = { '赞和收藏': 'like', '评论和回复': 'comment', '系统通知': 'system' }
+    this.setData({ activeTab: 'notice', noticeFilter: filters[event.currentTarget.dataset.label] || '' })
+    this.applyNotices()
   },
-
-  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
+  openNotice(event) {
+    const id = event.currentTarget.dataset.postId
+    if (id) wx.navigateTo({ url: `/pages/post-detail/post-detail?id=${encodeURIComponent(id)}` })
+  },
+  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event) },
   onPopupCancel() { this._popupCancel && this._popupCancel() },
-  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
-  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
+  onPopupAction(event) { this._popupAction && this._popupAction(event) },
+  onPopupInput(event) { this._popupInput && this._popupInput(event) },
   onPopupClose() { popup.hide(this) }
 })
--- a/pages/messages/messages.json
+++ b/pages/messages/messages.json
@@ -4,5 +4,6 @@
   "usingComponents": {
     "bottom-nav": "/components/bottom-nav/index",
     "ui-popup": "/components/ui-popup/index"
-  }
+  },
+  "enablePullDownRefresh": true
 }
--- a/pages/messages/messages.wxml
+++ b/pages/messages/messages.wxml
@@ -6,6 +6,7 @@
       <text class="read-all" bindtap="markAllRead">全部已读</text>
     </view>
   </view>
+  <offline-banner />
 
   <view class="messages-content">
     <view class="quick-grid">
@@ -13,13 +14,13 @@
         <view class="quick-icon heart-icon">♥</view>
         <text class="quick-name">赞和收藏</text>
         <text class="quick-desc">查看互动</text>
-        <view class="quick-dot"></view>
+        <view wx:if="{{likeUnread}}" class="quick-dot"></view>
       </view>
       <view class="quick-card" bindtap="openQuick" data-label="评论和回复">
         <view class="quick-icon reply-icon">•••</view>
         <text class="quick-name">评论和回复</text>
-        <text class="quick-desc">3条新消息</text>
-        <view class="quick-badge">3</view>
+        <text class="quick-desc">{{commentUnread}}条新消息</text>
+        <view wx:if="{{commentUnread}}" class="quick-badge">{{commentUnread}}</view>
       </view>
       <view class="quick-card" bindtap="openQuick" data-label="系统通知">
         <view class="quick-icon notice-icon">✦</view>
@@ -33,6 +34,10 @@
       <view class="message-tab {{activeTab === 'notice' ? 'selected' : ''}}" bindtap="switchMessageTab" data-tab="notice">通知</view>
     </view>
 
+    <view wx:if="{{loading}}" class="message-state">正在加载消息…</view>
+    <view wx:if="{{error}}" class="message-state" bindtap="loadMessages">{{error}} · 点击重试</view>
+    <view wx:if="{{!loading && !error && activeTab === 'chat' && !conversations.length}}" class="message-state">还没有聊天，去论坛认识新同学吧</view>
+    <view wx:if="{{!loading && !error && activeTab === 'notice' && !visibleNotifications.length}}" class="message-state">暂无相关通知</view>
     <view class="conversation-list" wx:if="{{activeTab === 'chat'}}">
       <view
         wx:for="{{conversations}}"
@@ -50,7 +55,7 @@
         </view>
         <view class="conversation-copy">
           <view class="conversation-top">
-            <text class="conversation-name">{{item.name}}</text>
+            <text class="conversation-name">{{item.name}}{{item.muted ? ' · 免打扰' : ''}}</text>
             <text class="conversation-time">{{item.time}}</text>
           </view>
           <view class="conversation-bottom">
@@ -62,10 +67,10 @@
     </view>
 
     <view class="notice-list" wx:else>
-      <view wx:for="{{notifications}}" wx:key="id" class="notice-item">
+      <view wx:for="{{visibleNotifications}}" wx:key="id" class="notice-item" data-post-id="{{item.postId}}" bindtap="openNotice">
         <view class="notice-symbol {{item.tone}}">✦</view>
         <view class="notice-copy">
-          <text class="notice-title">{{item.title}}</text>
+          <text class="notice-title">{{item.unread ? '• ' : ''}}{{item.title}}</text>
           <text class="notice-content">{{item.content}}</text>
           <text class="notice-time">{{item.time}}</text>
         </view>
@@ -84,6 +89,9 @@
     editable="{{uiPopup.editable}}"
     placeholder="{{uiPopup.placeholder}}"
     inputValue="{{uiPopup.inputValue}}"
+    actions="{{uiPopup.actions}}"
+    show-cancel="{{uiPopup.showCancel}}"
+    cancel-text="{{uiPopup.cancelText}}"
     confirm-text="{{uiPopup.confirmText}}"
     bindconfirm="onPopupConfirm"
     bindcancel="onPopupCancel"
--- a/pages/messages/messages.wxss
+++ b/pages/messages/messages.wxss
@@ -307,3 +307,5 @@
   font-size: 13rpx;
   color: #a1a8ae;
 }
+
+.message-state{padding:44rpx 24rpx;color:#99a3ad;text-align:center;font-size:25rpx}
--- a/pages/post-detail/post-detail.js
+++ b/pages/post-detail/post-detail.js
@@ -1,8 +1,14 @@
+const popup = require('../../utils/ui-popup-helper')
 const api = require('../../utils/api')
 const { formatTime } = require('../../utils/format-time')
 const { getNavigationMetrics } = require('../../utils/navigation')
 Page({
-  data:{...getNavigationMetrics(),post:null,comments:[],loading:true,commentsLoading:false,commentDraft:'',offline:false},
+  data:{...getNavigationMetrics(),post:null,comments:[],loading:true,commentsLoading:false,commentDraft:'',offline:false,uiPopup:null,commentSending:false},
+  onPopupConfirm(e){this._popupConfirm&&this._popupConfirm(e)},
+  onPopupCancel(){this._popupCancel&&this._popupCancel()},
+  onPopupAction(e){this._popupAction&&this._popupAction(e)},
+  onPopupInput(e){this._popupInput&&this._popupInput(e)},
+  onPopupClose(){popup.hide(this)},
   onLoad(q){
     this.postId = q.id
     this.fetch()
@@ -12,8 +18,8 @@
     api.getPost(this.postId).then(p=>{
       const avatarText=(p.authorNickname||'同')[0]
       // 详情页展示全文，不做3行截断
-      this.setData({post:{...p, expanded:true, avatarText, createdAt:formatTime(p.createdAt)}, offline:false})
-      api.viewPost(this.postId)
+      this.setData({post:{...p, expanded:true, avatarText, createdAt:formatTime(p.createdAt)}, offline:false,uiPopup:null,commentSending:false})
+      api.viewPost(this.postId).catch(error=>console.warn('[浏览上报]',error.message))
     }).catch(()=>this.setData({loading:false,offline:true})).finally(()=>this.fetchComments())
   },
   fetchComments(){
@@ -29,15 +35,16 @@
   },
   openAuthor(){ const p=this.data.post; if(!p)return; wx.navigateTo({url:`/pages/user-home/user-home?id=${p.authorId||0}`}) },
   goBack(){const pages=getCurrentPages();if(pages.length>1){wx.navigateBack()}else{wx.reLaunch({url:'/pages/index/index'})}},
-  onFollow(){
-    const p=this.data.post;if(!p)return
-    this.setData({[`post.followed`]:!p.followed});api.followUser(p.authorId||p.id).catch(()=>{})
+  async onFollow(){
+    const p=this.data.post;if(!p||this._following)return;this._following=true
+    try{await api.followUser(p.authorId||p.id);this.setData({'post.followed':!p.followed})}
+    catch(error){popup.toast(this,error.message||'关注失败',{tone:'error'})}finally{this._following=false}
   },
   noop(){},
-  onLike(){
-    const p=this.data.post;if(!p)return
-    this.setData({[`post.liked`]:!p.liked,[`post.likeCount`]:Math.max(0,(p.likeCount||0)+(p.liked?-1:1))})
-    api.likePost(p.id).catch(()=>{})
+  async onLike(){
+    const p=this.data.post;if(!p||this._liking)return;this._liking=true
+    try{await api.likePost(p.id);this.setData({'post.liked':!p.liked,'post.likeCount':Math.max(0,(p.likeCount||0)+(p.liked?-1:1))})}
+    catch(error){popup.toast(this,error.message||'点赞失败',{tone:'error'})}finally{this._liking=false}
   },
   // 收藏：先本地生效再上报，失败回滚
   async onFavorite(){
@@ -46,20 +53,18 @@
     this.setData({[`post.favored`]:favored})
     try{
       await api.toggleFavorite(p.id)
-      wx.showToast({ title: favored?'已加入收藏':'已取消收藏', icon:'none' })
+      popup.toast(this,favored?'已加入收藏':'已取消收藏')
     }catch(e){
       this.setData({[`post.favored`]:!favored})
-      wx.showToast({ title: e.message||'操作失败', icon:'none' })
+      popup.toast(this,e.message||'操作失败',{tone:'error'})
     }
   },
   onDraftInput(e){this.setData({commentDraft:e.detail.value})},
-  submitComment(){
-    const {post,commentDraft}=this.data
-    if(!post||!commentDraft.trim())return
-    const content=commentDraft.trim()
-    const c={id:Date.now(),content,authorNickname:'我',avatarText:'我',createdAt:'刚刚'}
-    this.setData({comments:this.data.comments.concat(c),commentDraft:'',[`post.commentCount`]:((post.commentCount)||0)+1})
-    api.addComment(post.id,content).catch(()=>{})
+  async submitComment(){
+    const {post,commentDraft}=this.data;if(!post||!commentDraft.trim()||this.data.commentSending)return
+    const content=commentDraft.trim();this.setData({commentSending:true})
+    try{const result=await api.addComment(post.id,content);const c={id:result.id,content,authorNickname:'我',avatarText:'我',createdAt:'刚刚'};this.setData({comments:this.data.comments.concat(c),commentDraft:'','post.commentCount':(post.commentCount||0)+1})}
+    catch(error){popup.toast(this,error.message||'评论失败',{tone:'error'})}finally{this.setData({commentSending:false})}
   },
   chatWithAuthor(){
     const p=this.data.post;if(!p)return
--- a/pages/post-detail/post-detail.wxml
+++ b/pages/post-detail/post-detail.wxml
@@ -4,6 +4,7 @@
     <view class="back" catchtap="goBack">‹</view>
     <view class="nav-title">帖子详情</view>
   </view>
+  <offline-banner local="{{offline}}" />
   <view class="detail-body">
     <view class="author-row" catchtap="openAuthor">
       <image wx:if="{{post.authorAvatar}}" class="avatar" src="{{post.authorAvatar}}" mode="aspectFill"/>
@@ -41,3 +42,5 @@
 </view>
 <view wx:if="{{!loading && !post}}" class="tip page-tip">帖子不存在或已被删除</view>
 <view wx:if="{{loading && !post}}" class="tip page-tip">加载中…</view>
+
+<ui-popup id="ui-popup" visible="{{uiPopup.visible}}" mode="{{uiPopup.mode}}" tone="{{uiPopup.tone}}" title="{{uiPopup.title}}" message="{{uiPopup.message}}" editable="{{uiPopup.editable}}" placeholder="{{uiPopup.placeholder}}" inputValue="{{uiPopup.inputValue}}" actions="{{uiPopup.actions}}" show-cancel="{{uiPopup.showCancel}}" confirm-text="{{uiPopup.confirmText}}" bindconfirm="onPopupConfirm" bindcancel="onPopupCancel" bindaction="onPopupAction" bindinput="onPopupInput" bindclose="onPopupClose" />
--- a/pages/profile/profile.js
+++ b/pages/profile/profile.js
@@ -3,7 +3,6 @@
 const { getTermWeek, daysUntil } = require('../../utils/term')
 const { formatTime } = require('../../utils/format-time')
 const popup = require('../../utils/ui-popup-helper')
-const mock = require('../../utils/mock-data')
 
 const CURRENT_USER_ID = 1
 
@@ -37,20 +36,9 @@
   // 真实资料：昵称/学校/头像/统计来自后端，学院与学号暂无对应字段沿用本地
   async loadProfile() {
     try {
-      const remote = await api.getUserProfile(CURRENT_USER_ID)
-      this.setData({
-        profile: {
-          ...mock.profile,
-          ...remote,
-          name: remote.nickname || mock.profile.name,
-          school: remote.school || mock.profile.school
-        },
-        'services[0].desc': `${remote.posts || 0}条内容`
-      })
-    } catch (error) {
       const profile = await api.getProfile()
-      this.setData({ profile, 'services[0].desc': `${profile.posts}条内容` })
-    }
+      this.setData({ profile, 'services[0].desc': `${profile.posts || 0}条内容` })
+    } catch (error) { popup.toast(this, error.message || '资料加载失败', { tone: 'error' }) }
   },
 
   // 各入口的真实计数：收藏/浏览/地址/优惠券
@@ -70,16 +58,19 @@
   },
 
   // 课表/校历菜单行的动态值：教学周、今日课程数、距期中天数
-  applyScheduleMeta() {
-    const today = new Date()
-    today.setHours(0, 0, 0, 0)
-    const todayIdx = (today.getDay() + 6) % 7
-    const weekNo = getTermWeek(today)
-    const todayCourses = mock.timetableCourses.filter((course) => course.day === todayIdx).length
-    this.setData({
-      'menus[0].value': `第${weekNo}周 · 今天${todayCourses ? `${todayCourses}节` : '无课'}`,
-      'menus[1].value': `距期中还有${daysUntil(mock.termInfo.midtermStart, today)}天`
-    })
+  async applyScheduleMeta() {
+    try {
+      const { termInfo, courses } = await api.getTimetable()
+      const today = new Date()
+      today.setHours(0, 0, 0, 0)
+      const todayIdx = (today.getDay() + 6) % 7
+      const weekNo = getTermWeek(today, termInfo.termStart)
+      const todayCourses = courses.filter(course => course.day === todayIdx).length
+      this.setData({
+        'menus[0].value': `第${weekNo}周 · 今天${todayCourses ? `${todayCourses}节` : '无课'}`,
+        'menus[1].value': `距期中还有${daysUntil(termInfo.midtermStart, today)}天`
+      })
+    } catch (error) { this.setData({ 'menus[0].value': '点击查看课表', 'menus[1].value': '点击查看校历' }) }
   },
 
   openService(event) {
--- a/pages/profile/profile.wxml
+++ b/pages/profile/profile.wxml
@@ -6,6 +6,7 @@
       <view class="settings-button" bindtap="openSettings">⚙</view>
     </view>
   </view>
+  <offline-banner />
 
   <view class="profile-content">
     <view class="profile-hero">
@@ -83,6 +84,9 @@
     editable="{{uiPopup.editable}}"
     placeholder="{{uiPopup.placeholder}}"
     inputValue="{{uiPopup.inputValue}}"
+    actions="{{uiPopup.actions}}"
+    show-cancel="{{uiPopup.showCancel}}"
+    cancel-text="{{uiPopup.cancelText}}"
     confirm-text="{{uiPopup.confirmText}}"
     bindconfirm="onPopupConfirm"
     bindcancel="onPopupCancel"
--- a/pages/publish/publish.js
+++ b/pages/publish/publish.js
@@ -71,6 +71,7 @@
   },
 
   async submitPost() {
+    if (this.data.submitting) return
     const { title, content, media, activeType, types, location, visibleToSchool } = this.data
     if (!title.trim()) {
       popup.toast(this, '请填写标题', { tone: 'warn' })
@@ -92,12 +93,17 @@
       visibleToSchool,
       createdAt: Date.now()
     }
-    const result = await api.publishPost(payload)
-    const saved = wx.getStorageSync('mock_posts') || []
-    wx.setStorageSync('mock_posts', [result].concat(saved).slice(0, 20))
-    this.setData({ submitting: false })
-    popup.toast(this, '发布成功', { tone: 'success' })
-    setTimeout(() => wx.redirectTo({ url: '/pages/discover/discover' }), 650)
+    try {
+      const result = await api.publishPost(payload)
+      // 仅离线发布写入 mock，真实帖子由后端列表返回，避免混入 localForumPosts。
+      if (result.offline) {
+        const saved = wx.getStorageSync('mock_posts') || []
+        wx.setStorageSync('mock_posts', [result].concat(saved).slice(0, 20))
+      }
+      popup.toast(this, result.offline ? '已保存到本机演示帖子，未发布到后端' : '发布成功', { tone: result.offline ? 'warn' : 'success' })
+      setTimeout(() => wx.redirectTo({ url: '/pages/discover/discover' }), 650)
+    } catch (error) { popup.toast(this, error.message || '发布失败，请重试', { tone: 'error' }) }
+    finally { this.setData({ submitting: false }) }
   },
 
   onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
--- a/pages/publish/publish.wxml
+++ b/pages/publish/publish.wxml
@@ -6,6 +6,7 @@
       <text class="draft-link" bindtap="openDrafts">草稿箱</text>
     </view>
   </view>
+  <offline-banner />
 
   <view class="publish-content">
     <view class="publish-intro">
@@ -103,6 +104,9 @@
     editable="{{uiPopup.editable}}"
     placeholder="{{uiPopup.placeholder}}"
     inputValue="{{uiPopup.inputValue}}"
+    actions="{{uiPopup.actions}}"
+    show-cancel="{{uiPopup.showCancel}}"
+    cancel-text="{{uiPopup.cancelText}}"
     confirm-text="{{uiPopup.confirmText}}"
     bindconfirm="onPopupConfirm"
     bindcancel="onPopupCancel"
--- a/pages/timetable/timetable.js
+++ b/pages/timetable/timetable.js
@@ -1,6 +1,6 @@
 const { getNavigationMetrics } = require('../../utils/navigation')
 const { getTermWeek, startOfWeek } = require('../../utils/term')
-const mock = require('../../utils/mock-data')
+const api = require('../../utils/api')
 
 // 一天 5 个大节
 const PERIODS = [
@@ -15,7 +15,8 @@
 Page({
   data: {
     ...getNavigationMetrics(),
-    termLabel: mock.termInfo.termLabel,
+    termLabel: '',
+    loading: true, error: '', offline: false,
     periods: PERIODS,
     days: DAYS,
     grid: [],
@@ -24,26 +25,35 @@
     header: ''
   },
 
-  onLoad() {
-    const today = new Date()
-    today.setHours(0, 0, 0, 0)
-    const monday = startOfWeek(today)
-    const todayIdx = (today.getDay() + 6) % 7
-    const weekDates = DAYS.map((_, index) => {
-      const day = new Date(monday)
-      day.setDate(monday.getDate() + index)
-      return `${('0' + (day.getMonth() + 1)).slice(-2)}/${('0' + day.getDate()).slice(-2)}`
-    })
-    // grid[节次][星期] → 课程
-    const grid = PERIODS.map((_, pi) =>
-      DAYS.map((_, di) => mock.timetableCourses.find((c) => c.day === di && c.period === pi) || null)
-    )
-    this.setData({
-      grid,
-      todayIdx,
-      weekDates,
-      header: `${today.getMonth() + 1}月${today.getDate()}日 周${DAYS[todayIdx]} · 第${getTermWeek(today)}教学周`
-    })
+  onLoad() { this._alive = true; this.loadTimetable() },
+  onUnload() { this._alive = false },
+  onPullDownRefresh() { this.loadTimetable().finally(() => wx.stopPullDownRefresh()) },
+  async loadTimetable() {
+    this.setData({ loading: true, error: '' })
+    try {
+      const result = await api.getTimetable()
+      if (!this._alive) return
+      const { termInfo, courses } = result
+      const today = new Date()
+      today.setHours(0, 0, 0, 0)
+      const monday = startOfWeek(today)
+      const todayIdx = (today.getDay() + 6) % 7
+      const weekDates = DAYS.map((_, index) => {
+        const day = new Date(monday)
+        day.setDate(monday.getDate() + index)
+        return `${('0' + (day.getMonth() + 1)).slice(-2)}/${('0' + day.getDate()).slice(-2)}`
+      })
+      // grid[节次][星期] → 课程
+      const grid = PERIODS.map((_, pi) =>
+        DAYS.map((_, di) => courses.find((c) => c.day === di && c.period === pi) || null)
+      )
+      this.setData({
+        grid, termLabel: termInfo.termLabel, offline: !!result.offline, loading: false,
+        todayIdx,
+        weekDates,
+        header: `${today.getMonth() + 1}月${today.getDate()}日 周${DAYS[todayIdx]} · 第${getTermWeek(today, termInfo.termStart)}教学周`
+      })
+    } catch (error) { if (this._alive) this.setData({ loading: false, error: error.message || '课表加载失败' }) }
   },
 
   goBack() {
--- a/pages/timetable/timetable.json
+++ b/pages/timetable/timetable.json
@@ -1,3 +1,4 @@
 {
-  "backgroundColor": "#F6F8FB"
+  "backgroundColor": "#F6F8FB",
+  "enablePullDownRefresh": true
 }
--- a/pages/timetable/timetable.wxml
+++ b/pages/timetable/timetable.wxml
@@ -4,13 +4,16 @@
     <view class="back" catchtap="goBack">‹</view>
     <view class="nav-title">课表</view>
   </view>
+  <offline-banner local="{{offline}}" />
 
-  <view class="tt-head">
+  <view wx:if="{{loading}}" class="tt-tip">正在加载课表…</view>
+  <view wx:if="{{error}}" class="tt-tip" bindtap="loadTimetable">{{error}} · 点击重试</view>
+  <view class="tt-head" wx:if="{{!loading && !error}}">
     <text class="tt-term">{{termLabel}}</text>
     <text class="tt-today">{{header}}</text>
   </view>
 
-  <view class="tt-grid">
+  <view class="tt-grid" wx:if="{{!loading && !error}}">
     <view class="tt-row tt-head-row">
       <view class="tt-time-cell"></view>
       <view wx:for="{{days}}" wx:key="*this" class="tt-day {{index === todayIdx ? 'today' : ''}}">
@@ -32,5 +35,5 @@
     </view>
   </view>
 
-  <view class="tt-tip">· 演示课表，接入教务系统后自动同步 ·</view>
+  <view class="tt-tip">{{offline ? '· 本地演示课表 ·' : '· 课表由校园后台维护，请以教务安排为准 ·'}}</view>
 </view>
--- a/pages/user-home/user-home.js
+++ b/pages/user-home/user-home.js
@@ -1,14 +1,20 @@
+const popup = require('../../utils/ui-popup-helper')
 const api = require('../../utils/api')
 const { formatTime } = require('../../utils/format-time')
 const { getNavigationMetrics } = require('../../utils/navigation')
 Page({
-  data:{...getNavigationMetrics(),user:null,posts:[],loading:true},
+  data:{...getNavigationMetrics(),user:null,posts:[],loading:true,uiPopup:null},
+  onPopupConfirm(e){this._popupConfirm&&this._popupConfirm(e)},
+  onPopupCancel(){this._popupCancel&&this._popupCancel()},
+  onPopupAction(e){this._popupAction&&this._popupAction(e)},
+  onPopupInput(e){this._popupInput&&this._popupInput(e)},
+  onPopupClose(){popup.hide(this)},
   onLoad(q){
     this.userId=q.id
     this.fetch()
   },
   fetch(){
-    this.setData({loading:true})
+    this.setData({loading:true,uiPopup:null})
     api.getForumPosts({page:1,size:50}).then(res=>{
       const list=res.list||[]
       const own=list.filter(x=>String(x.authorId)===String(this.userId))
@@ -30,10 +36,10 @@
   },
   openDetail(e){ wx.navigateTo({url:`/pages/post-detail/post-detail?id=${e.currentTarget.dataset.id}`}) },
   goBack(){const pages=getCurrentPages();if(pages.length>1){wx.navigateBack()}else{wx.reLaunch({url:'/pages/index/index'})}},
-  onFollow(){
-    const u=this.data.user;if(!u)return
-    this.setData({[`user.followed`]:!u.followed,[`user.followers`]:Math.max(0,(u.followers||0)+(u.followed?-1:1))})
-    api.followUser(u.id).catch(()=>{})
+  async onFollow(){
+    const u=this.data.user;if(!u||this._following)return;this._following=true
+    try{await api.followUser(u.id);this.setData({'user.followed':!u.followed,'user.followers':Math.max(0,(u.followers||0)+(u.followed?-1:1))})}
+    catch(error){popup.toast(this,error.message||'关注失败',{tone:'error'})}finally{this._following=false}
   },
   chat(){
     const u=this.data.user;if(!u)return
--- a/pages/user-home/user-home.wxml
+++ b/pages/user-home/user-home.wxml
@@ -1,6 +1,7 @@
 <view class="uh-page" wx:if="{{user}}">
   <!-- 自定义导航：状态栏/胶囊避让 + 返回键，与社区页同款 -->
   <view class="nav" style="padding-top:{{statusBarHeight}}px;height:{{navBarHeight}}px"><view class="back" catchtap="goBack">‹</view><view class="nav-title">个人主页</view></view>
+  <offline-banner />
   <view class="uh-head">
     <image wx:if="{{user.avatar}}" class="uh-avatar" src="{{user.avatar}}" mode="aspectFill"/>
     <view wx:else class="uh-avatar fallback">{{user.initial}}</view>
@@ -23,3 +24,5 @@
   </view>
 </view>
 <view wx:else class="uh-empty page-tip">加载中…</view>
+
+<ui-popup id="ui-popup" visible="{{uiPopup.visible}}" mode="{{uiPopup.mode}}" tone="{{uiPopup.tone}}" title="{{uiPopup.title}}" message="{{uiPopup.message}}" editable="{{uiPopup.editable}}" placeholder="{{uiPopup.placeholder}}" inputValue="{{uiPopup.inputValue}}" actions="{{uiPopup.actions}}" show-cancel="{{uiPopup.showCancel}}" confirm-text="{{uiPopup.confirmText}}" bindconfirm="onPopupConfirm" bindcancel="onPopupCancel" bindaction="onPopupAction" bindinput="onPopupInput" bindclose="onPopupClose" />
--- a/project.config.json
+++ b/project.config.json
@@ -5,6 +5,14 @@
       {
         "type": "folder",
         "value": "docs"
+      },
+      {
+        "type": "folder",
+        "value": "backend"
+      },
+      {
+        "type": "folder",
+        "value": "tests"
       }
     ]
   },
--- a/utils/api.js
+++ b/utils/api.js
@@ -1,10 +1,11 @@
 const mock = require('./mock-data')
+const dataMode = require('./data-mode')
 
 const BASE_URL = 'http://127.0.0.1:8080/api'
 const CURRENT_USER_ID = 1
 
 function clone(data) { return JSON.parse(JSON.stringify(data)) }
-function wait(data, delay = 160) { return new Promise(resolve => setTimeout(() => resolve(clone(data)), delay)) }
+function wait(data, delay = 160) { dataMode.setDemo(true); return new Promise(resolve => setTimeout(() => resolve(clone(data)), delay)) }
 function request(url, method = 'GET', data = {}) {
   return new Promise((resolve, reject) => {
     wx.request({
@@ -15,10 +16,10 @@
       header: { 'content-type': 'application/json' },
       success(res) {
         const body = res.data || {}
-        if (res.statusCode >= 200 && res.statusCode < 300 && body.code === 0) resolve(body.data)
-        else reject(Object.assign(new Error(body.msg || `请求失败（${res.statusCode}）`), { server: true }))
+        if (res.statusCode >= 200 && res.statusCode < 300 && body.code === 0) { dataMode.connection(url, true); resolve(body.data) }
+        else { dataMode.connection(url, true); reject(Object.assign(new Error(body.msg || `请求失败（${res.statusCode}）`), { server: true })) }
       },
-      fail: reject
+      fail(error) { dataMode.connection(url, false); reject(error) }
     })
   })
 }
@@ -54,6 +55,33 @@
   return request(`/posts?tag=${encodeURIComponent(tag)}&scope=${encodeURIComponent(scope)}&userId=${userId}&page=${page}&size=${size}`, 'GET')
     .catch(() => wait({...mockForum(tag,page,size,scope), offline:true}))
 }
+// LIKE %keyword%：与 MySQL 一致支持 % / _ 通配符和反斜线转义；标题与正文分别匹配。
+function keywordMatcher(keyword) {
+  let pattern = '^'
+  const text = '%' + keyword + '%'
+  const escape = char => char.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
+  for (let i = 0; i < text.length; i++) {
+    const char = text[i]
+    if (char === '\\' && i + 1 < text.length) pattern += escape(text[++i])
+    else if (char === '%') pattern += '[\\s\\S]*'
+    else if (char === '_') pattern += '[\\s\\S]'
+    else pattern += escape(char)
+  }
+  return new RegExp(pattern + '$', 'iu')
+}
+function searchPosts(params = {}) {
+  const keyword = String(params.keyword || '').trim()
+  if (!keyword || keyword.length > 64) return Promise.reject(new Error(!keyword ? '请输入搜索关键词' : '关键词不能超过64字'))
+  const page = Math.max(1, Math.floor(Number(params.page) || 1))
+  const size = Math.min(50, Math.max(1, Math.floor(Number(params.size) || 10)))
+  const userId = params.userId || CURRENT_USER_ID
+  return request(`/search?keyword=${encodeURIComponent(keyword)}&page=${page}&size=${size}&userId=${userId}`, 'GET').catch(error => {
+    if (error.server) throw error
+    const matcher = keywordMatcher(keyword)
+    const all = mockForum('', 1, Number.MAX_SAFE_INTEGER, '').list.filter(post => matcher.test(post.title || '') || matcher.test(post.content || ''))
+    return wait({ list: all.slice((page - 1) * size, page * size), total: all.length, offline: true })
+  })
+}
 // 单帖详情：优先走后端 /posts/{id}，失败时退化为在列表里查找
 function getPost(id, userId=CURRENT_USER_ID) {
   return request(`/posts/${id}?userId=${userId}`, 'GET').then(p => {
@@ -63,11 +91,11 @@
     getForumPosts({ page:1, size:50, userId }).then(res => {
       const found = (res.list || []).find(x => String(x.id) === String(id))
       if (!found) throw new Error('帖子不存在')
-      return found
+      return { ...found, offline: !!res.offline }
     })
   )
 }
-function likePost(id, userId=CURRENT_USER_ID) { return request(`/posts/${id}/like`, 'POST', {userId}).catch(() => wait({liked:true})) }
+function likePost(id, userId=CURRENT_USER_ID) { return request(`/posts/${id}/like`, 'POST', {userId}).catch(error => { if (error.server) throw error; return wait({liked:true}) }) }
 function mockComments(id) {
   const pool = [
     { authorNickname: '林知夏', content: '求清单！正好考前复习用 🙋' },
@@ -77,24 +105,24 @@
   return pool.map((c, i) => ({ id: `${id}-${i + 1}`, ...c, createdAt: '刚刚' }))
 }
 function getPostComments(id) { return request(`/posts/${id}/comments`, 'GET').catch(() => wait(mockComments(id))) }
-function addComment(id, content, userId=CURRENT_USER_ID) { return request(`/posts/${id}/comments`, 'POST', {content,userId}).catch(() => wait({id:Date.now(),content})) }
-function followUser(id, userId=CURRENT_USER_ID) { return request(`/users/${id}/follow`, 'POST', {userId}).catch(() => wait({followed:true})) }
+function addComment(id, content, userId=CURRENT_USER_ID) { return request(`/posts/${id}/comments`, 'POST', {content,userId}).catch(error => { if (error.server) throw error; return wait({id:Date.now(),content}) }) }
+function followUser(id, userId=CURRENT_USER_ID) { return request(`/users/${id}/follow`, 'POST', {userId}).catch(error => { if (error.server) throw error; return wait({followed:true}) }) }
 function getHotTopics() { return request('/topics/hot').catch(() => wait([{tag:'校园日常',count:18},{tag:'学习',count:12},{tag:'社团活动',count:9}])) }
 // 活动轮播位：运营在 banner 表加行即可上线，无需发版；失败返回空数组，页面用本地默认
 function getBanners(slot='discover') { return request(`/banners?slot=${encodeURIComponent(slot)}`, 'GET').catch(() => wait([])) }
 // 浏览上报：详情页打开时调用，后端 view_count +1 并写浏览历史
-function viewPost(id, userId=CURRENT_USER_ID) { return request(`/posts/${id}/view?userId=${userId}`, 'POST').catch(() => wait({})) }
+function viewPost(id, userId=CURRENT_USER_ID) { return request(`/posts/${id}/view?userId=${userId}`, 'POST').catch(error => { if (error.server) throw error; return wait({}) }) }
 
 // ---- 「我的」页用户域 ----
 function getUserProfile(userId=CURRENT_USER_ID) { return request(`/users/${userId}/profile`, 'GET') }
 function updateUserProfile(userId=CURRENT_USER_ID, nickname='') { return request(`/users/${userId}/profile`, 'POST', {nickname}) }
-function toggleFavorite(postId, userId=CURRENT_USER_ID) { return request(`/users/${userId}/favorites`, 'POST', {postId}).catch(() => wait({favored:true})) }
+function toggleFavorite(postId, userId=CURRENT_USER_ID) { return request(`/users/${userId}/favorites`, 'POST', {postId}).catch(error => { if (error.server) throw error; return wait({favored:true}) }) }
 function getFavorites(userId=CURRENT_USER_ID) { return request(`/users/${userId}/favorites`, 'GET').catch(() => wait([])) }
 function getMyViews(userId=CURRENT_USER_ID) { return request(`/users/${userId}/views`, 'GET').catch(() => wait([])) }
 function getCoupons(userId=CURRENT_USER_ID) { return request(`/users/${userId}/coupons`, 'GET').catch(() => wait([])) }
 function getAddresses(userId=CURRENT_USER_ID) { return request(`/users/${userId}/addresses`, 'GET').then(list => { cacheFoodAddresses(userId,list); return list }).catch(() => wait(localFoodAddresses(userId))) }
 function addAddress(userId=CURRENT_USER_ID, detail='') { return request(`/users/${userId}/addresses`, 'POST', {detail}).then(address => { cacheFoodAddresses(userId,[address].concat(localFoodAddresses(userId).filter(a => String(a.id)!==String(address.id)))); return address }).catch(error => { if (error.server) throw error; const address=mock.makeFoodAddress(detail); cacheFoodAddresses(userId,[address].concat(localFoodAddresses(userId))); return wait(address) }) }
-function submitFeedback(userId=CURRENT_USER_ID, content='') { return request('/feedback', 'POST', {userId, content}).catch(() => wait({received:true})) }
+function submitFeedback(userId=CURRENT_USER_ID, content='') { return request('/feedback', 'POST', {userId, content}).catch(error => { if (error.server) throw error; return wait({received:true}) }) }
 
 // ---- 校草校花评选 ----
 function getIdolActivity() { return request('/idol/activity', 'GET').catch(() => wait({})) }
@@ -106,11 +134,27 @@
 function voteIdol(id, userId=CURRENT_USER_ID) { return request(`/idol/contestants/${id}/vote`, 'POST', {userId}) }
 function joinIdol(payload) {
   return request('/idol/contestants', 'POST', { userId: CURRENT_USER_ID, ...payload })
-    .catch(() => wait({ id: Date.now(), ...payload }))
-}
-function getConversations() { return wait(mock.conversations) }
-function getNotifications() { return wait(mock.notifications) }
-function getProfile() { return wait(mock.profile) }
+    .catch(error => { if (error.server) throw error; return wait({ ...payload, id: Date.now(), offline: true }) })
+}
+function getConversations(userId = CURRENT_USER_ID) {
+  return request(`/conversations?userId=${userId}`).then(rows => rows.map(row => ({ ...row, time: row.createdAt || '' }))).catch(error => {
+    if (error.server) throw error
+    const rows = readLocal('chat_conversations_' + userId, userId === CURRENT_USER_ID ? mock.conversations : [])
+    return wait(rows)
+  })
+}
+function getNotifications(userId = CURRENT_USER_ID) {
+  return request(`/notifications?userId=${userId}`).then(rows => rows.map(row => ({ ...row, time: row.createdAt || '' }))).catch(error => {
+    if (error.server) throw error
+    return wait(readLocal('chat_notifications_' + userId, userId === CURRENT_USER_ID ? mock.notifications : []))
+  })
+}
+function getProfile() {
+  return getUserProfile().then(profile => ({ ...profile, name: profile.nickname || '校园同学', major: '学院信息未填写', studentId: '校园号 ' + profile.id })).catch(error => {
+    if (error.server) throw error
+    return wait(mock.profile)
+  })
+}
 // 发布：后端仅支持单图 image 与 tag 字段，media 多图取第一张；失败时退回本地 mock
 function publishPost(payload, userId=CURRENT_USER_ID) {
   return request('/posts', 'POST', {
@@ -120,9 +164,9 @@
     image: payload.media && payload.media[0] ? payload.media[0] : '',
     tag: payload.typeLabel || '校园日常',
     location: payload.location || ''
-  }).catch(() => wait({ id: Date.now(), ...payload }, 280))
-}
-module.exports = { BASE_URL, request, getConversations, getNotifications, getProfile, publishPost, getForumPosts, getPost, likePost, addComment, getPostComments, followUser, getHotTopics, getBanners, viewPost, getIdolActivity, getIdolContestants, voteIdol, joinIdol, getUserProfile, updateUserProfile, toggleFavorite, getFavorites, getMyViews, getCoupons, getAddresses, addAddress, submitFeedback }
+  }).catch(error => { if (error.server) throw error; return wait({ ...payload, id: Date.now(), offline: true }, 280) })
+}
+module.exports = { BASE_URL, request, searchPosts, getConversations, getNotifications, getProfile, publishPost, getForumPosts, getPost, likePost, addComment, getPostComments, followUser, getHotTopics, getBanners, viewPost, getIdolActivity, getIdolContestants, voteIdol, joinIdol, getUserProfile, updateUserProfile, toggleFavorite, getFavorites, getMyViews, getCoupons, getAddresses, addAddress, submitFeedback }
 
 
 // 外卖：读取接口均离线兜底；写入接口仅在网络失败时演示，保留服务端拒单消息。
@@ -142,6 +186,10 @@
   })
 }
 function getGoodsDetail(id) {
+  if (/^local-\d+$/.test(String(id))) {
+    const item = mock.goods.find(row => String(row.id) === String(id))
+    return item ? wait({ ...item, offline: true }) : Promise.reject(new Error('物品不存在'))
+  }
   return request(`/goods/${id}`, 'GET').catch(error => {
     if (error.server) throw error
     const item = mock.goods.find(row => String(row.id) === String(id))
@@ -174,3 +222,100 @@
   })
 }
 Object.assign(module.exports, { getGoodsList, getGoodsDetail, publishGoods, getJobs, getActivities, enrollActivity })
+
+
+// 消息离线数据与真实服务分开保存，恢复网络后不会冒充已送达或自动补发。
+function readLocal(key, fallback) {
+  try { const data = wx.getStorageSync(key); return Array.isArray(data) ? data : clone(fallback) } catch (error) { return clone(fallback) }
+}
+function saveLocal(key, value) {
+  try { wx.setStorageSync(key, value) } catch (error) { throw new Error('本机保存失败，请检查存储空间') }
+}
+function chatKey(peerId, userId) { return `chat_messages_${userId}_${peerId}` }
+function localMessages(peerId, userId) { return readLocal(chatKey(peerId, userId), userId === CURRENT_USER_ID ? (mock.chatMessages[peerId] || []) : []) }
+function rememberConversation(peerId, userId, name, message, remove = false) {
+  const key = 'chat_conversations_' + userId
+  let rows = readLocal(key, userId === CURRENT_USER_ID ? mock.conversations : [])
+  const previous = rows.find(row => String(row.id) === String(peerId))
+  rows = rows.filter(row => String(row.id) !== String(peerId))
+  if (!remove) rows.unshift({ ...(previous || {}), id: Number(peerId), name: name || (previous && previous.name) || '校园同学', avatarText: (name || (previous && previous.name) || '同')[0], color: '#7EB6F6', online: false, unread: 0, preview: message.type === 'image' ? '[图片]' : message.content, time: '本机演示', offline: true })
+  saveLocal(key, rows)
+}
+function getChatMessages(peerId, params = {}, userId = CURRENT_USER_ID) {
+  const size = Math.min(50, Math.max(1, Number(params.size) || 30))
+  const cursor = params.beforeId ? `&beforeId=${encodeURIComponent(params.beforeId)}` : ''
+  return request(`/conversations/${peerId}/messages?userId=${userId}&size=${size}${cursor}`).catch(error => {
+    if (error.server) throw error
+    const all = localMessages(peerId, userId).filter(row => !params.beforeId || Number(row.id) < Number(params.beforeId))
+    let muted = false
+    try { muted = !!wx.getStorageSync(`chat_muted_${userId}_${peerId}`) } catch (error) {}
+    return wait({ list: all.slice(-size), hasMore: all.length > size, muted, offline: true })
+  })
+}
+function sendChatMessage(peerId, content, params = {}, userId = CURRENT_USER_ID) {
+  const text = String(content || '').trim()
+  if (!text || text.length > 1000) return Promise.reject(new Error(!text ? '请输入消息' : '消息不能超过1000字'))
+  const clientId = params.clientId || `chat-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
+  return request(`/conversations/${peerId}/messages`, 'POST', { userId, content: text, clientId }).catch(error => {
+    if (error.server) throw error
+    const rows = localMessages(peerId, userId)
+    const existing = rows.find(row => row.clientId === clientId)
+    if (existing) return wait(existing)
+    const message = { id: Math.max(Date.now(), Number(rows.length ? rows[rows.length - 1].id : 0) + 1), senderId: userId, receiverId: Number(peerId), mine: true, type: 'text', content: text, createdAt: new Date().toISOString(), clientId, offline: true }
+    saveLocal(chatKey(peerId, userId), rows.concat(message))
+    rememberConversation(peerId, userId, params.name, message)
+    return wait(message)
+  })
+}
+function markChatRead(peerId, upToId, userId = CURRENT_USER_ID, offline = false) {
+  const local = () => {
+    const key = 'chat_conversations_' + userId
+    const rows = readLocal(key, userId === CURRENT_USER_ID ? mock.conversations : []).map(row => String(row.id) === String(peerId) ? { ...row, unread: 0 } : row)
+    saveLocal(key, rows)
+    return wait({ offline: true })
+  }
+  if (offline) return local()
+  return request(`/conversations/${peerId}/read`, 'POST', { userId, upToId }).catch(error => { if (error.server) throw error; return local() })
+}
+function markAllMessagesRead(userId = CURRENT_USER_ID) {
+  return request('/messages/read-all', 'POST', { userId }).catch(error => {
+    if (error.server) throw error
+    saveLocal('chat_conversations_' + userId, readLocal('chat_conversations_' + userId, userId === CURRENT_USER_ID ? mock.conversations : []).map(row => ({ ...row, unread: 0 })))
+    saveLocal('chat_notifications_' + userId, readLocal('chat_notifications_' + userId, userId === CURRENT_USER_ID ? mock.notifications : []).map(row => ({ ...row, unread: false })))
+    return wait({ offline: true })
+  })
+}
+function clearChatMessages(peerId, userId = CURRENT_USER_ID, offline = false) {
+  const local = () => {
+    saveLocal(chatKey(peerId, userId), [])
+    rememberConversation(peerId, userId, '', {}, true)
+    return wait({ offline: true })
+  }
+  if (offline) return local()
+  return request(`/conversations/${peerId}/clear`, 'POST', { userId }).catch(error => { if (error.server) throw error; return local() })
+}
+function muteChat(peerId, muted, userId = CURRENT_USER_ID, offline = false) {
+  const local = () => {
+    saveLocal(`chat_muted_${userId}_${peerId}`, !!muted)
+    const key = 'chat_conversations_' + userId
+    saveLocal(key, readLocal(key, userId === CURRENT_USER_ID ? mock.conversations : []).map(row => String(row.id) === String(peerId) ? { ...row, muted: !!muted } : row))
+    return wait({ offline: true })
+  }
+  if (offline) return local()
+  return request(`/conversations/${peerId}/mute?userId=${userId}&muted=${!!muted}`, 'POST').catch(error => { if (error.server) throw error; return local() })
+}
+function getTimetable(params = {}, userId = CURRENT_USER_ID) {
+  const week = params.week ? `&week=${encodeURIComponent(params.week)}` : ''
+  return request(`/timetable?userId=${userId}${week}`).catch(error => {
+    if (error.server) throw error
+    const currentWeek = params.week || require('./term').getTermWeek(new Date(), mock.termInfo.termStart)
+    return wait({ termInfo: mock.termInfo, week: currentWeek, courses: currentWeek <= 21 ? mock.timetableCourses : [], offline: true })
+  })
+}
+function getCalendar(userId = CURRENT_USER_ID) {
+  return request(`/calendar?userId=${userId}`).catch(error => {
+    if (error.server) throw error
+    return wait({ termInfo: mock.termInfo, events: mock.calendarEvents, offline: true })
+  })
+}
+Object.assign(module.exports, { getChatMessages, sendChatMessage, markChatRead, markAllMessagesRead, clearChatMessages, muteChat, getTimetable, getCalendar })
--- a/utils/mock-data.js
+++ b/utils/mock-data.js
@@ -69,16 +69,26 @@
 ]
 
 const conversations = [
-  { id: 1, name: '周予安', avatarText: '周', color: '#7EB6F6', preview: '台灯还在的，今晚可以宿舍楼下自提', time: '刚刚', unread: 2, online: true },
-  { id: 2, name: '春日音乐社', avatarText: '音', color: '#F39AB8', preview: '欢迎加入！本周四晚上第一次排练', time: '18:42', unread: 1, online: false },
-  { id: 3, name: '陈一川', avatarText: '陈', color: '#FFB56A', preview: '好的，东区二号场见', time: '昨天', unread: 0, online: true },
-  { id: 4, name: '论文辅导中心', avatarText: '论', color: '#62C894', preview: '你的格式检查结果已经生成', time: '周一', unread: 0, online: false }
-]
+  { id: 2, name: '周予安', avatarText: '周', color: '#7EB6F6', preview: '好的，那晚上七点见！', time: '刚刚', unread: 3, online: false },
+  { id: 4, name: '春日音乐社', avatarText: '音', color: '#F39AB8', preview: '欢迎加入！本周四晚上第一次排练', time: '18:42', unread: 1, online: false },
+  { id: 3, name: '陈一川', avatarText: '陈', color: '#FFB56A', preview: '好的，东区二号场见', time: '昨天', unread: 1, online: false }
+]
+const chatMessages = {
+  2: [
+    { id: 1, mine: false, type: 'text', content: '你好，看到你收藏了我的台灯～' },
+    { id: 2, mine: true, type: 'text', content: '你好，请问台灯还在吗？晚上方便自提吗？' },
+    { id: 3, mine: false, type: 'text', content: '还在的，今晚可以宿舍楼下自提。' },
+    { id: 4, mine: false, type: 'image', image: '/assets/home/recommend-lamp.jpg' },
+    { id: 5, mine: true, type: 'text', content: '好的，那晚上七点见！' }
+  ],
+  4: [{ id: 6, mine: false, type: 'text', content: '欢迎加入！本周四晚上第一次排练' }],
+  3: [{ id: 7, mine: false, type: 'text', content: '好的，东区二号场见' }]
+}
 
 const notifications = [
-  { id: 1, title: '校园市集提醒', content: '你收藏的校园市集将在明天下午开始', time: '今天 18:30', tone: 'green' },
-  { id: 2, title: '内容审核通过', content: '你发布的“几乎全新护眼台灯”已经展示', time: '今天 12:08', tone: 'blue' },
-  { id: 3, title: '系统维护通知', content: '本周日凌晨将进行短时服务升级', time: '昨天 20:15', tone: 'yellow' }
+  { id: 1, title: '校园市集提醒', content: '你收藏的校园市集将在明天下午开始', time: '今天 18:30', tone: 'green', category: 'system', unread: true },
+  { id: 2, title: '内容审核通过', content: '你发布的“几乎全新护眼台灯”已经展示', time: '今天 12:08', tone: 'blue', category: 'system', unread: true },
+  { id: 3, title: '系统维护通知', content: '本周日凌晨将进行短时服务升级', time: '昨天 20:15', tone: 'yellow', category: 'system', unread: true }
 ]
 
 const profile = {
@@ -120,7 +130,7 @@
   { start: '2026-11-02', end: '2026-11-06', title: '期中考试周', note: '具体安排以教务系统通知为准' },
   { start: '2026-12-05', end: '2026-12-05', title: '校园歌手大赛决赛', note: '大礼堂，晚上 19:00' },
   { start: '2027-01-04', end: '2027-01-15', title: '期末考试周', note: '考试周图书馆延长开放至 23:00' },
-  { start: '2027-01-18', end: '2027-01-18', title: '寒假开始', note: '下学期 2027-03-01 报到' }
+  { start: '2027-01-23', end: '2027-01-23', title: '寒假开始', note: '演示日期，与学期结束日期保持一致，实际以教务通知为准' }
 ]
 
 // 校草校花选手（离线兜底）：gender 1=校草组 2=校花组
@@ -130,12 +140,12 @@
   { id: 3, name: '陈知意', gender: 2, college: '经济管理学院', photo: '/assets/social/flea-market.webp', slogan: '市集淘物专家', voteCount: 531, voted: false },
   { id: 4, name: '白若曦', gender: 2, college: '艺术学院', photo: '/assets/social/profile-avatar.webp', slogan: '画室里的小太阳', voteCount: 418, voted: false },
   { id: 5, name: '陆知远', gender: 1, college: '计算机学院', photo: '/assets/social/basketball.webp', slogan: '球场见真章', voteCount: 774, voted: false },
-  { id: 6, name: '沈亦辰', gender: 1, college: '体育学院', photo: '/assets/social/hero-campus.webp', slogan: '七点东操等我', voteCount: 590, voted: false },
-  { id: 7, name: '顾言', gender: 1, college: '文学院', photo: '/assets/social/thesis-books.webp', slogan: '会写诗的投手', voteCount: 456, voted: false },
-  { id: 8, name: '周叙白', gender: 1, college: '建筑学院', photo: '/assets/social/spring-event.webp', slogan: '画图到天亮', voteCount: 387, voted: false }
-]
-
-module.exports = { discoveryPosts, conversations, notifications, profile, termInfo, timetableCourses, calendarEvents, idolContestants }
+  { id: 6, name: '沈亦辰', gender: 1, college: '体育学院', photo: '/assets/home/hero-campus.webp', slogan: '七点东操等我', voteCount: 590, voted: false },
+  { id: 7, name: '顾言', gender: 1, college: '文学院', photo: '/assets/home/thesis-books.webp', slogan: '会写诗的投手', voteCount: 456, voted: false },
+  { id: 8, name: '周叙白', gender: 1, college: '建筑学院', photo: '/assets/home/spring-event.webp', slogan: '画图到天亮', voteCount: 387, voted: false }
+]
+
+module.exports = { discoveryPosts, chatMessages, conversations, notifications, profile, termInfo, timetableCourses, calendarEvents, idolContestants }
 
 
 // 外卖离线数据：与本模块 SQL 的店铺、菜单、费用规则一致。
@@ -179,7 +189,7 @@
 Object.assign(module.exports, { foodShops, foodMenus, foodAddresses, makeFoodAddress, makeFoodOrder })
 
 // 模块二至四离线数据：与 module-market-jobs-activities.sql 的种子保持一致。
-const goods = [
+const seedGoods = [
   { id: 1, mode: 1, title: '九成新护眼台灯', price: 45, conditionLevel: '九成新', category: '数码', image: '/assets/home/recommend-lamp.jpg', description: '宿舍自用半年，亮度可调，晚上看书很舒服。', seller: { id: 1, nickname: '林知夏' }, createdAt: '2026-10-09 18:20:00' },
   { id: 2, mode: 1, title: '高数教材全套', price: 25, conditionLevel: '九成新', category: '图书', image: '/assets/home/thesis-books.webp', description: '课本和习题册一套，笔记很少，适合期中复习。', seller: { id: 2, nickname: '周予安' }, createdAt: '2026-10-09 17:10:00' },
   { id: 3, mode: 1, title: '羽毛球拍', price: 60, conditionLevel: '九成新', category: '运动', image: '/assets/social/basketball.webp', description: '轻量拍，打过几次，送一只备用手胶。', seller: { id: 3, nickname: '陈一川' }, createdAt: '2026-10-09 16:05:00' },
@@ -187,6 +197,9 @@
   { id: 5, mode: 1, title: '春日野餐垫', price: 15, conditionLevel: '九成新', category: '生活', image: '/assets/social/spring-picnic.webp', description: '折叠方便，适合草坪活动，欢迎自提。', seller: { id: 4, nickname: '春日音乐社' }, createdAt: '2026-10-08 15:30:00' },
   { id: 6, mode: 2, title: '求购二手吉他', price: 300, conditionLevel: '九成新', category: '其他', image: '/assets/social/flea-market.webp', description: '预算300元左右，民谣吉他即可，校内面交。', seller: { id: 2, nickname: '周予安' }, createdAt: '2026-10-08 12:00:00' }
 ]
+let storedGoods = []
+try { const rows = wx.getStorageSync('mock_goods'); if (Array.isArray(rows)) storedGoods = rows } catch (error) {}
+const goods = storedGoods.concat(seedGoods)
 const jobs = [
   { id: 1, title: '图书馆图书整理员', daySalary: 100, settle: '日结', location: '逸夫图书馆', timeDesc: '周一到周五 14:00-17:00', status: '招聘中' },
   { id: 2, title: '奶茶店店员', daySalary: 90, settle: '日结', location: '紫荆食堂旁', timeDesc: '周末两班轮换', status: '招聘中' },
@@ -201,6 +214,7 @@
 function makeGoods(payload) {
   const row = { id: 'local-' + Date.now(), mode: 1, ...payload, price: Number(payload.price), seller: { id: 1, nickname: '林知夏' }, createdAt: '刚刚' }
   goods.unshift(row)
+  try { wx.setStorageSync('mock_goods', goods.filter(item => String(item.id).startsWith('local-'))) } catch (error) { goods.shift(); throw new Error('本机物品保存失败') }
   return row
 }
 function enrollActivity(id) {
--- a/utils/term.js
+++ b/utils/term.js
@@ -1,5 +1,5 @@
 // 学期小工具：教学周、日期解析（课表 / 校历 / 发现页横幅共用）
-const mock = require('./mock-data')
+const DEFAULT_TERM_START = '2026-08-31'
 
 function parseDate(text) {
   const [year, month, day] = text.split('-').map(Number)
@@ -15,9 +15,9 @@
 }
 
 // 第几教学周（按周一算周界，开学前按第 1 周处理）
-function getTermWeek(date) {
+function getTermWeek(date, termStartText = DEFAULT_TERM_START) {
   const monday = startOfWeek(date)
-  const termStart = startOfWeek(parseDate(mock.termInfo.termStart))
+  const termStart = startOfWeek(parseDate(termStartText))
   return Math.max(1, Math.round((monday - termStart) / 604800000) + 1)
 }
 
--- a/utils/ui-popup-helper.js
+++ b/utils/ui-popup-helper.js
@@ -35,11 +35,13 @@
     const current = page.data.uiPopup || {}
     hide(page)
     if (typeof options[callbackName] !== 'function') return
-    const detail = (event && event.detail) || {}
+    const detail = (event && event.detail) || event || {}
     const payload = { ...current, ...detail }
     if (detail.value !== undefined) payload.inputValue = detail.value
     if (callbackName === 'action' && Array.isArray(current.actions)) payload.item = current.actions[detail.index]
-    options[callbackName](payload)
+    Promise.resolve().then(() => options[callbackName](payload)).catch(error => {
+      module.exports.toast(page, error.message || '操作失败，请重试', { tone: 'error' })
+    })
   }
 }
 

```
