# 功能状态清单（补齐后）

日期：2026-10-14。以本次源码为准。代码完成与运行验收分开记录；静态检查不代表 MySQL/微信真机联调完成。

| 项目 | 代码状态 | 实现位置 |
|---|---|---|
| P0-1 首页路由表 | 已完成，原7条不变，12个标签覆盖，未知入口 warn | pages/index/index.js |
| P0-2 搜索 | 已完成，GET /api/search、分页页与两处入口、LIKE 离线过滤 | ForumController/ForumService、utils/api.js、pages/search |
| P1-1 开学优惠 | 已接活动页 | FEATURE_ROUTES |
| P1-2 学习好物 | 已接二手列表，category 参数映射已有图书分类 | index、market-list |
| P1-3 奶茶优惠 | 已接真实种子店铺1（可在店铺页显示不存在态） | index、food-shop |
| P1-4 闲置台灯 | 查询在售台灯真实 id；找不到时去数码市场 | openRecommendedLamp |
| P1-5 联想广告 | 已接专题页；官方链接尚未提供，明确提示 | pages/campus-offer |
| P1-6 通知铃铛 | 打开消息页通知 tab，通知现由数据库读取 | messages?tab=notice |
| P1-7 换一批 | 真正轮换 recommendations 数组顺序 | index |
| P1-8 switchTab | 确认无绑定/调用后已删除 | index；底部导航在组件内 |
| P1-9 发现页 banner | 本地评选 banner 已接真实页；运营 linkType=none 仍是合理待配置状态 | discover |
| P2-1 聊天 | 文本持久化、历史分页、5秒轮询、已读/免打扰/个人清空；离线本机存储 | CampusController/Service/Mapper、chat、api |
| P2-2 会话 | 已请求后端；对方用户 ID 与私聊入口统一 | api.getConversations |
| P2-3 通知/全部已读 | 真实表与接口，论坛交互生成通知，已读可持久化 | api.getNotifications、messages/read-all |
| P2-4 用户资料兜底 | getProfile 先请求后端；个人中心只走 API | api、profile |
| P2-5 课表 | 按用户/学期/周次从数据库读取；无外部教务同步 | timetable、academic_term/course_schedule |
| P2-6 校历 | 真实表与接口；默认种子仍为演示数据 | calendar、calendar_event |
| P3-1 followUser | 源码兼容 body 优先于 query；前端请求体已测，真实 HTTP 待本地验收 | ForumController.follow |
| P3-2 根目录旧文件 | 确认 app/require/WXML 无引用后删除 index.js、api.js | 根目录 |
| P3-4 全局演示提示 | 23页均挂组件，离线写入明确提示，不自动补发 | offline-banner/data-mode |
| P3-5 表数量说明 | 原 schema 是20表，本次新增6表，共26表 | README_BACKEND |

## 原清单需要纠正的内容

- 发现页旧搜索有当前列表的本地过滤，并非只弹 toast；缺的是完整搜索接口、结果页及分页。
- 原消息通知直接来自 mock，“那里已经有真实通知”的说法不成立；本次新增真实通知存储与读取。
- 首页原有七条真实路由，原清单有一处写成六条。
- “无兜底分支”并不准确，实际已有 toast 兜底，缺少开发者告警。
- “9 个入口”混入了无绑定的 switchTab 和运营 banner 待配置；不能等同于 9 个真实按钮。
- 原 schema 实际20表，而不是五表、13表或21表。原32端点计数正确，本次新增11个，共43个。
- “所有写接口均透传业务错误”“后端未启动所有功能仍可用”都过于乐观；旧论坛/反馈/报名等存在吞错误，投票、资料编辑本来没有兜底。

## 本次额外修复

弹层 action 属性和回调形状；发现帖点击和真实点赞/关注；在线帖子重复/错误混入 mock；分享 id 暂时性死区；帖子详情 loading 不收尾；本机二手详情 ID/持久化；错误图片路径；演示放假日期与学期结束日期矛盾；小程序打包包含后端。

## 验证与保留边界

18项 Node 行为回归通过；23页四件套、JS/JSON/WXML、组件、路由、资源、SQL参数与幂等结构静态检查通过，SQL 未执行。后端 Maven 编译、实际 MySQL/HTTP 和微信视觉/真机验收待本地完成，附只读 backend_smoke.py。

未扩展原项目的登录鉴权、远程图片上传、语音/图片私信、外部教务连接、广告官方活动链接、推荐算法服务。个人主页仍有最多50帖筛选和粉丝演示初值的旧限制。它们需要独立产品/接口或账号配置，不属于本次列出的补缺范围。
