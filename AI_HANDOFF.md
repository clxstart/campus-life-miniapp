# 项目交接说明（补齐后）

本项目是原生微信小程序 + Java 17 / Spring Boot 3.2.5 / MyBatis-Plus 3.5.6 / MySQL 8，测试 AppID 为 touristappid。前端现有 23 页，后端 5 个 Controller、43 个端点。不要换成 uni-app/Taro，不要重新实现已接通的外卖、二手、论坛等模块。

## 数据与交互约定

- 页面通过 `utils/api.js` 取数；弹层通过 `utils/ui-popup-helper.js` 和 `components/ui-popup`。
- 统一响应 `{code:0,msg:"ok",data:...}`，HTTP 非 2xx 或 code 非 0 标记 `error.server=true`。
- 新增读接口在网络失败时使用本地演示数据，服务端错误显示失败状态。已有部分读接口保留原来的广义失败兜底。
- 带兜底的写接口仅网络失败时演示，`error.server` 必须继续抛出；投票和资料编辑仍需后端。离线写入不自动同步。
- `components/offline-banner` 在全部页面提供演示提示，`utils/data-mode.js` 按接口记录连接异常，避免其他成功请求掩盖失败接口。
- `app.json` 为 custom 导航。新增页面使用 `utils/navigation.js`。

## 已补齐

1. 首页 `FEATURE_ROUTES` 表驱动，12 个标签均覆盖，未配置入口 warn + 用户提示；原七条路由保持不变。
2. `GET /api/search` + 搜索结果四件套，首页/发现页接线，真实分页、加载/空/错误态、本地 LIKE 兜底。
3. 首页活动、图书分类、奶茶店、动态台灯详情、广告专题、通知 tab 与推荐重排；二手页现在读取 category，学习好物映射图书。
4. 消息/会话/通知后端，聊天文本持久化、游标分页、轮询、已读、免打扰和个人清空游标；离线本机持久化。
5. 课表/校历数据库与接口，页面和个人中心课程摘要接线，按真实学期起始日计算周次。
6. 修复若干旧写接口吞业务错误、弹层 actions 缺少绑定与 detail 传法、发现页帖子只弹 toast、在线发布混入本机 mock、社区分享临时性死区错误、三处错误资源路径及本机二手 local-id 详情问题。
7. 无引用的根目录 index.js/api.js 与首页 switchTab 已移除。后端/测试目录不进入小程序打包。

## SQL 与运行

全新库：手动执行 `backend/src/main/resources/db/schema.sql`（原有 20 表），然后 `module-messages-academics.sql`（新增 6 表）。已有库只执行新增脚本。原 schema 部分旧种子不幂等，新脚本可重复执行。工程不会自动执行 SQL。

配置 application.yml 数据库账号，`cd backend && mvn spring-boot:run`；微信开发者工具导入根目录即可。BASE_URL 仍为 `http://127.0.0.1:8080/api`。

## 验证与边界

`node --test tests/regression.test.js` 和 `python tests/validate_project.py` 已通过。`python tests/backend_smoke.py` 为启动后端后的只读检查，交付环境没有可用 Maven/JDK、MySQL 和微信开发者工具，不能声称后端编译/数据库联调/真机验收已经通过。

- 消息路由 id 和接口 peerId 均指对方用户 ID。避免恢复旧 mock 的会话序号。
- 教学数据由校园后台表维护，未接入外部教务系统。初始 SQL 是演示种子，不是已核验的真实校历。
- 广告专题未配置官方活动链接，不提供虚构的活动价格、资格或外链。
- 登录鉴权、持久图片上传仍为原项目边界；userId=1 是演示约定。
- 个人主页仍只筛选最多 50 条帖子、粉丝初值为旧演示数字；个人中心统计来自真实接口。这一旧主页的数据完整性问题没有扩大改造。

详见 `docs/IMPLEMENTATION_REPORT.md`：逐任务说明、完整新增源码、修改 diff、手动 SQL 与人工验收清单。
