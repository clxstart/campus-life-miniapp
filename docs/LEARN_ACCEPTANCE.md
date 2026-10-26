# 学习专区 · 交付与验收

本次以重新上传的原生微信工程为基准新增学习专区，采用蓝白浅色卡片、克制阴影和暖阳学习插画。新增独立的学习列表与学习发布页面，复用现有 post、post_like、comment、post_view 表和帖子详情能力，小程序端未增加 npm 依赖。

## 先运行什么

已有数据库只需新增执行：

```bash
mysql -u root -p campus_life < backend/src/main/resources/db/module-learn.sql
```

**先执行迁移，再启动新版后端。** 帖子实体增加了分类字段，未迁移就启动会导致帖子查询缺列。脚本可重复执行，不插入示例帖子，不覆盖现有内容，也没有改动数据库连接配置。

需要演示内容时，可再手动执行 `module-learn-demo.sql`；它与必需迁移分开，不会自动灌入真实数据库。

```bash
cd backend
mvn -o clean compile -P '!jdk-1.8'
mvn spring-boot:run -P '!jdk-1.8'
```

首次构建时若本地 Maven 依赖尚未缓存，先去掉 `-o` 联网构建。工程原有数据库初始化和其它模块迁移继续按 `README_BACKEND.md` 操作，本次没有重跑或替换用户数据库。

微信开发者工具导入项目根目录。从首页学习卡片进入；开发启动路径也可以直接设置为 `pages/learn/learn`，参数 `category=paper`。

## 功能与设计

- 整张首页学习卡片进入独立页面；保留原有 `bindtap="openLearnZone"` 和内部三个 `catchtap="openFeature"`。没有把事件类型互换，也没有新增会冒泡的绑定。
- 作业答疑进入课程互助，论文指导进入论文分类，学习好物继续进入原二手市场图书分类。
- 板块显示为「课程互助、论文指导、就业实习、考研、考公」，课程互助聚焦课程问题讨论，编码仍为 `daike/paper/job/kaoyan/kaogong`。
- 切换分类、下拉刷新、游标分页、空状态、离线横幅、学习建议、发帖、作者主页、详情、点赞与原有评论均已接好。
- 学习发布页独立承载分类选择、标题正文、单图上传和校内位置。原有 `pages/publish` 完全未改。
- 提交前重新读取当前选中学校；后端确认学校有效后将学习帖存到该学校，列表和数量也严格按学校查询。不会把其它学校或 school_id 为空的帖子混进学习专区。
- 列表按唯一递增帖子 ID 倒序，用最后一条 ID 继续加载；分页期间插入新帖或删除已读帖不会导致旧内容偏移。新帖在刷新时进入首屏。
- 发布、点赞和评论写失败直接抛错，保留输入并明确提示；没有本机伪成功。既有 API 函数签名不变。
- 后端不可用时，保留完整页面、生成插画、学习建议与离线空状态；不把建议卡片伪装成已发布帖子。

## 按需求逐项验收

| # | 检查项 | 本次结果与证据 |
|---|---|---|
| 1 | 后端能编译 | **通过**：Java 17 + Maven 实际执行离线 clean compile，输出 BUILD SUCCESS。 |
| 2 | 首页卡片进入独立页面 | **路由与页面结构通过**：整卡及内部入口事件绑定、页面注册及四件套均验证；微信模拟器/真机显示待验。 |
| 3 | 五板块切换 | **逻辑与接口通过**：分类切换、迟到响应保护、真实分类列表与计数均验证；独立预览脚本覆盖切换。 |
| 4 | 空库发帖后能看到 | **通过**：独立临时 MySQL 8.0 实例中先确认学习帖为空，再发布并从对应分类读取。 |
| 5 | 发布能选板块 | **通过**：预选、切换、真实保存与发布后回到所选板块均有测试。 |
| 6 | 能进详情 | **路由与真实接口通过**：复用原详情路由，详情作者、时间、点赞、评论、浏览数均实际验证；微信画面待验。 |
| 7 | 按校生效 | **通过**：同一账号选择另一所有效学校发帖，新帖只在所选学校出现。列表、计数均按校过滤。 |
| 8 | 离线不白屏 | **逻辑与 DOM 通过**：断网读兜底、横幅、建议和空状态；写失败保留输入。微信断网实机待验。 |
| 9 | 分页不重不漏 | **通过**：真实接口与模型均验证游标，覆盖翻页期间新增帖子、删除已读帖子和重复触底。 |
| 10 | 静态检查 | **通过**：项目原有 validate_project.py 输出 PASS，检查 28 个注册页面。 |
| 11 | 现有功能不受破坏 | **通过所执行回归**：118 项 Node 测试通过；真实论坛、活动、圈子、二手、外卖读取接口冒烟通过。相关既有页面逐字节保持不变。 |

真实接口与迁移测试使用新建的临时测试数据库，包含单独添加的第二所验收学校；未连接或修改用户电脑上的数据库。测试实例已停止。

**仍需在微信开发者工具和真机确认：** 原生 WXML 的最终像素渲染、不同机型胶囊避让、键盘弹起与图片选择、手势下拉及上拉操作。网页预览使用同一份页面结构、样式和控制逻辑，但浏览器并非微信运行环境。此环境 Chromium 启动受到运行限制，因此预览验证使用脚本与 DOM 执行，没有宣称完成浏览器截图或微信真机验收。

## 验证命令与记录

```bash
python tests/validate_project.py
python tests/validate_learn_model.py
node --test tests/learn.test.js tests/regression.test.js tests/audit-fixes.test.js tests/miniapp-school-picker.test.js tests/miniapp-school-location.test.js tests/miniapp-ai-chips.test.js tests/period-config.test.js
node docs/tools/validate-learn-preview.cjs
# 已启动后端时，下条只读检查不会修改内容
python tests/learn_smoke.py http://127.0.0.1:8080/api 1
```

`LEARN_VALIDATION.txt` 包含实际 Maven 成功输出、真实 MySQL/HTTP 验收、Node 回归、模型与静态检查结果。模型枚举了 544 组分类、学校、数量、游标和并发增删场景。

## 文件与接口

新增页面：`pages/learn/learn`、`pages/learn-publish/learn-publish`。配套配置在 `utils/learn.js`。

- `GET /api/learn/topics?category=paper&schoolId=1&size=10&userId=1`
- 下一页追加 `beforeId=上一页的nextCursor`
- 返回 `data={list,total,hasMore,nextCursor}`，统一外层 `{code,msg,data}` 不变。
- `GET /api/learn/counts?schoolId=1&userId=1`
- 发布继续使用 `POST /api/posts`，新增可选 `learnCategory` 和 `schoolId` 字段；学习帖仅校内可见。

所有 SQL 参数由 MyBatis-Plus 绑定。无效学习分类返回空列表；无效发布分类或学校返回参数错误；每页数量限制在合理范围内。

## 图片与预览

`assets/learn/hero.jpg` 为本次新生成头图；`desk.jpg` 与透明 `books.png` 沿用本会话中自行生成的配套素材。均已压缩并实际接入页面。提示词记录在 `LEARN_IMAGES.txt`。

`docs/learn-preview.html` 是自包含交互预览，无外部资源。可以切分类、切学校、模拟发帖回显，并检查空数据、离线和错误状态。它使用清楚标识的示例数据，不连接或提交到真实后端。
