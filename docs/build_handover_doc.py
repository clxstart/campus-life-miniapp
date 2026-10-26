# -*- coding: utf-8 -*-
"""生成项目交接文档（Word）"""
import os
from PIL import Image
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT

ROOT = r"E:\_verify_completed\campus-life-miniapp"
SHOT = os.path.join(ROOT, "docs", "screenshots")
OUT = os.path.join(ROOT, "docs", "项目交接文档.docx")

# ---------- 1. 裁切小程序截图 ----------
src = os.path.join(SHOT, "mp-00-devtools.png")
mp_home = os.path.join(SHOT, "mp-01-home.png")
if os.path.exists(src):
    im = Image.open(src)
    # 去掉左侧工具栏和手机外壳黑边
    im.crop((1400, 130, 1790, 1080)).save(mp_home)
    print("裁切完成:", mp_home, Image.open(mp_home).size)

# ---------- 2. 建文档 ----------
doc = Document()

# 中文字体
style = doc.styles["Normal"]
style.font.name = "微软雅黑"
style.font.size = Pt(10.5)
style._element.rPr.rFonts.set(
    __import__("docx").oxml.ns.qn("w:eastAsia"), "微软雅黑"
)


def h(text, level=1):
    p = doc.add_heading(text, level=level)
    for run in p.runs:
        run.font.name = "微软雅黑"
        run._element.rPr.rFonts.set(
            __import__("docx").oxml.ns.qn("w:eastAsia"), "微软雅黑"
        )
    return p


def para(text, bold=False, size=10.5):
    p = doc.add_paragraph()
    run = p.add_run(text)
    run.bold = bold
    run.font.size = Pt(size)
    run.font.name = "微软雅黑"
    run._element.rPr.rFonts.set(
        __import__("docx").oxml.ns.qn("w:eastAsia"), "微软雅黑"
    )
    return p


def bullet(text):
    p = doc.add_paragraph(text, style="List Bullet")
    for run in p.runs:
        run.font.name = "微软雅黑"
        run._element.rPr.rFonts.set(
            __import__("docx").oxml.ns.qn("w:eastAsia"), "微软雅黑"
        )
    return p


def table(headers, rows, widths=None):
    t = doc.add_table(rows=1, cols=len(headers))
    t.style = "Light Grid Accent 1"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, cell in enumerate(t.rows[0].cells):
        cell.text = headers[i]
        for p in cell.paragraphs:
            for r in p.runs:
                r.bold = True
                r.font.size = Pt(9.5)
                r.font.name = "微软雅黑"
    for row in rows:
        cells = t.add_row().cells
        for i, val in enumerate(row):
            cells[i].text = str(val)
            for p in cells[i].paragraphs:
                for r in p.runs:
                    r.font.size = Pt(9)
                    r.font.name = "微软雅黑"
    doc.add_paragraph()
    return t


def pic(filename, caption, width=6.2):
    path = os.path.join(SHOT, filename)
    if not os.path.exists(path):
        para(f"[缺少截图 {filename}]")
        return
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.add_run().add_picture(path, width=Inches(width))
    c = doc.add_paragraph()
    c.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = c.add_run(caption)
    r.font.size = Pt(9)
    r.font.color.rgb = RGBColor(0x66, 0x66, 0x66)
    r.font.name = "微软雅黑"


# ==================== 封面 ====================
for _ in range(6):
    doc.add_paragraph()
t = doc.add_paragraph()
t.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = t.add_run("校园生活小程序\n项目交接文档")
r.font.size = Pt(28)
r.bold = True
r.font.name = "微软雅黑"
r._element.rPr.rFonts.set(__import__("docx").oxml.ns.qn("w:eastAsia"), "微软雅黑")

doc.add_paragraph()
sub = doc.add_paragraph()
sub.alignment = WD_ALIGN_PARAGRAPH.CENTER
r2 = sub.add_run("微信小程序 + Spring Boot 后端 + Vue 3 管理端")
r2.font.size = Pt(14)
r2.font.color.rgb = RGBColor(0x55, 0x55, 0x55)
r2.font.name = "微软雅黑"

for _ in range(8):
    doc.add_paragraph()
info = doc.add_paragraph()
info.alignment = WD_ALIGN_PARAGRAPH.CENTER
r3 = info.add_run("代码位置：E:\\_verify_completed\\campus-life-miniapp\n文档版本：v1.0\n更新日期：2026-10-18")
r3.font.size = Pt(11)
r3.font.name = "微软雅黑"

doc.add_page_break()

# ==================== 一、项目概况 ====================
h("一、项目概况", 1)
para(
    "这是一个面向高校的校园生活平台，由三个部分组成：学生用的微信小程序、"
    "给运营用的 Web 管理后台、以及支撑两者的 Java 后端。"
)
doc.add_paragraph()

table(
    ["项目", "说明"],
    [
        ["项目名称", "campus-life-miniapp（校园生活）"],
        ["代码位置", "E:\\_verify_completed\\campus-life-miniapp"],
        ["小程序", "微信原生小程序（非 uni-app / Taro）"],
        ["后端", "Spring Boot 3.2.5 + MyBatis-Plus + MySQL 8"],
        ["管理端", "Vue 3 + Vite + Tailwind CSS"],
        ["小程序页面数", "28 个"],
        ["管理端页面数", "11 个菜单"],
        ["自动化测试", "271 个用例全部通过"],
    ],
)

para("核心功能模块", bold=True)
for x in [
    "校园论坛：发帖、点赞、评论、关注、浏览统计",
    "圈子：按校隔离的圈子，可加入、发帖",
    "学习专区：代课互助 / 论文指导 / 就业实习 / 考研 / 考公 五个板块",
    "外卖：店铺列表、菜单、下单",
    "二手市场：发布、分类、收藏",
    "兼职：岗位列表、按结算方式筛选",
    "季节活动：活动列表、报名",
    "校园评选：校草校花投票",
    "课表 / 校历：支持学生自建课程、作息可改",
    "AI 校园助手：关键词匹配的陪聊机器人，能查真实店铺/岗位/活动",
    "多学校：内容按校隔离，用户可切换当前学校",
]:
    bullet(x)

doc.add_page_break()

# ==================== 二、环境与启动 ====================
h("二、环境与启动", 1)

h("2.1 环境要求", 2)
table(
    ["依赖", "版本要求", "说明"],
    [
        ["JDK", "17 或更高", "实测用 JDK 25，编译目标 17"],
        ["Maven", "3.8+", "路径 D:\\maven\\apache-maven-3.8.3"],
        ["MySQL", "8.0+", "数据库名 campus_life"],
        ["Node.js", "18+", "管理端用，实测 v24"],
        ["微信开发者工具", "Stable 2.01+", "需要登录，AppID 用测试号 touristappid"],
    ],
)

h("2.2 启动后端", 2)
para("在 backend 目录下执行（注意必须带 -P !jdk-1.8 和正确的 JAVA_HOME）：", bold=True)
code = doc.add_paragraph()
rc = code.add_run(
    'set JAVA_HOME=C:\\Program Files\\Eclipse Adoptium\\jdk-25.0.3.9-hotspot\n'
    'D:\\maven\\apache-maven-3.8.3\\bin\\mvn.cmd -o spring-boot:run -P !jdk-1.8'
)
rc.font.name = "Consolas"
rc.font.size = Pt(9)
doc.add_paragraph()
para(
    "⚠️ 为什么必须加 -P !jdk-1.8：Maven 全局 settings.xml 里有一个 activeByDefault 的 jdk-1.8 "
    "profile，不加这个参数会用 JDK 8 编译，Lombok 直接崩。"
)
para("启动成功后监听 8080，日志出现 “Started CampusLifeApplication”。")

h("2.3 启动管理端", 2)
para("在 web 目录下执行：", bold=True)
code2 = doc.add_paragraph()
rc2 = code2.add_run("node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5180")
rc2.font.name = "Consolas"
rc2.font.size = Pt(9)
doc.add_paragraph()
para("启动后访问 http://127.0.0.1:5180，用下面账号登录：")
table(
    ["角色", "账号", "密码", "可见菜单"],
    [
        ["超级管理员", "admin", "admin123", "11 个（全部）"],
        ["学校管理员", "neimenggu", "school123", "9 个（无学校管理/管理员/AI助手/校园评选）"],
    ],
)

h("2.4 启动小程序", 2)
para("用微信开发者工具打开项目根目录 E:\\_verify_completed\\campus-life-miniapp，按 Ctrl+B 编译。")
para(
    "首次打开会强制选择学校（这是设计行为）。后端没启动时小程序会自动回落到本地演示数据，"
    "并显示离线横幅——这是刻意的设计，保证没有后端也能演示。"
)

doc.add_page_break()

# ==================== 三、目录结构 ====================
h("三、目录结构", 1)
para("只列关键目录，node_modules / target 已省略。")
code3 = doc.add_paragraph()
rc3 = code3.add_run(
    """campus-life-miniapp/
├── pages/                  小程序页面（28 个，每个四件套）
│   ├── index/              首页（学校切换、功能区、今日推荐）
│   ├── discover/           圈子页
│   ├── community/          校园论坛
│   ├── learn/              学习专区 ★新增
│   ├── learn-publish/      学习专区发帖 ★新增
│   ├── ai-chat/            AI 校园助手
│   ├── food-list/          外卖
│   ├── market-list/        二手市场
│   ├── jobs/               兼职
│   ├── activities/         季节活动
│   ├── timetable/          课表
│   └── ...
├── components/             公共组件（ui-popup / offline-banner / bottom-nav）
├── utils/
│   ├── api.js              所有接口封装（读兜底、写不兜底）
│   ├── school.js           当前选中学校 + 学校作息/地点
│   ├── constants.js        公共枚举（分类/地点/成色）
│   ├── learn.js            学习专区板块定义 ★新增
│   └── ...
├── assets/                 静态图片（被管理端复用）
├── backend/                Spring Boot 后端
│   └── src/main/
│       ├── java/com/campuslife/
│       │   ├── controller/  接口层
│       │   ├── service/     业务层
│       │   ├── mapper/      数据访问层
│       │   ├── entity/      实体
│       │   ├── dto/         出入参
│       │   └── config/      鉴权拦截器、异常处理
│       └── resources/
│           ├── application.yml
│           └── db/          数据库脚本（按模块分文件）
├── web/                    Vue 3 管理端
│   └── src/
│       ├── views/           11 个页面
│       ├── components/      NoTable / NoModal / NoImageUpload
│       ├── api/index.js     接口封装
│       └── router/index.js  路由与菜单
├── tests/                  自动化测试（Node + Python）
└── docs/                   文档与截图
"""
)
rc3.font.name = "Consolas"
rc3.font.size = Pt(8)

doc.add_page_break()

# ==================== 四、技术栈 ====================
h("四、技术栈与关键约定", 1)

h("4.1 技术栈", 2)
table(
    ["层", "技术", "备注"],
    [
        ["小程序", "微信原生（WXML/WXSS/JS/JSON）", "零 npm 依赖"],
        ["后端", "Spring Boot 3.2.5", "Java 17 编译目标"],
        ["ORM", "MyBatis-Plus 3.5.6", "注解式 SQL"],
        ["数据库", "MySQL 8", "库名 campus_life"],
        ["鉴权", "JWT（jjwt 0.12.6）", "用户端 / 管理端两套 token"],
        ["密码", "BCrypt", "spring-security-crypto"],
        ["管理端", "Vue 3 + Vite + Tailwind 3.4", "未用 Element Plus"],
    ],
)

h("4.2 必须遵守的约定", 2)
para("这些是项目里踩过坑总结出来的，破坏会出问题：", bold=True)

table(
    ["约定", "说明"],
    [
        ["读兜底、写不兜底", "读接口失败回落本地数据（保证离线可用）；写接口必须把错误抛给用户"],
        ["导航栏 box-sizing", "app.wxss 全局是 border-box，.nav 必须写 content-box，否则高度被 padding 压扁"],
        ["导航栏分隔线", "用 box-shadow，不要用 border-bottom（border 会撑开 content-box 高度）"],
        ["事件绑定", "卡片用 bindtap，子按钮用 catchtap（防止重复触发）"],
        ["wx:elif 相邻", "wx:elif 必须紧跟 wx:if/wx:elif，中间不能插注释"],
        ["按钮文字居中", "有 height 的按钮必须配 line-height 或 flex 居中"],
        ["WXSS 禁标签选择器", "不能写 view{} text{}，只能用 class"],
        ["不硬编码学校名", "一律从 utils/school.js 取当前学校"],
        ["统一返回格式", "{code, msg, data}，成功时 code=0"],
    ],
)

doc.add_page_break()

# ==================== 五、数据库设计 ====================
h("五、数据库设计", 1)
para("数据库 campus_life，共 33 张表。按模块分组：")

table(
    ["模块", "表名", "说明"],
    [
        ["用户", "user", "用户主表，含 school_id"],
        ["用户", "follow", "关注关系"],
        ["内容", "post", "帖子（论坛/圈子/学习专区共用）"],
        ["内容", "comment / post_like / post_view", "评论 / 点赞 / 浏览"],
        ["内容", "favorite", "收藏"],
        ["圈子", "circle / circle_member", "圈子 / 成员"],
        ["二手", "goods", "二手商品"],
        ["兼职", "job", "兼职岗位"],
        ["活动", "campus_activity / activity_enroll", "活动 / 报名"],
        ["外卖", "shop / shop_goods / user_order", "店铺 / 菜品 / 订单"],
        ["评选", "idol_contestant / idol_vote", "选手 / 投票"],
        ["教务", "academic_term / calendar_event / course_schedule", "学期 / 校历 / 课表"],
        ["消息", "chat_message / notification", "私信 / 通知"],
        ["多校", "school / school_location", "学校 / 每校地点"],
        ["管理端", "admin_user / admin_log / banner", "管理员 / 操作日志 / 轮播"],
        ["AI", "ai_chat_rule / ai_chat_config / ai_chat_log", "规则 / 话术配置 / 对话日志"],
    ],
)

para("★ 学习专区复用了 post 表，只加了一个字段：", bold=True)
table(
    ["字段", "类型", "说明"],
    [
        ["learn_category", "varchar(16) NULL", "daike / paper / job / kaoyan / kaogong，为空表示不是学习专区内容"],
    ],
)
para(
    "这样做的原因：点赞、评论、浏览数、详情页全部自动复用，不需要另造一套。"
    "索引 idx_post_learn 建在 (school_id, learn_category, status, id) 上。"
)

para("数据库脚本位置：backend/src/main/resources/db/", bold=True)
for x in [
    "schema.sql —— 基础表结构",
    "module-learn.sql —— 学习专区字段与索引（可重复执行）",
    "module-learn-demo.sql —— 学习专区演示数据",
    "module-ai-chat.sql / module-ai-config.sql —— AI 助手规则与配置",
    "module-school-location.sql —— 每校地点",
    "module-multischool-admin.sql —— 多学校 + 管理端表",
    "其余按模块命名",
]:
    bullet(x)

doc.add_page_break()

# ==================== 六、已实现功能（截图） ====================
h("六、已实现功能（含截图）", 1)
para("下面按页面逐个展示当前实际效果，截图均为真实运行截图。")

h("6.1 小程序首页", 2)
pic("mp-01-home.png", "小程序首页：学校切换、搜索、功能宫格、学习专区入口、今日推荐", width=2.6)
para(
    "首页顶部可切换学校（首次进入强制选择）。功能区含外卖、二手、论坛、兼职、季节活动；"
    "「学习专区」卡片整块可点，进去是独立页面。"
)

h("6.2 管理端 · 数据看板", 2)
pic("web-01-dashboard.png", "数据看板：用户/帖子/圈子/闲置概览 + 近 7 天发帖趋势")

h("6.3 管理端 · 学校管理", 2)
pic("web-02-schools.png", "学校管理：可增删改学校，配置 logo 与作息")

h("6.4 管理端 · 管理员", 2)
pic("web-03-admins.png", "管理员：超管可建校管账号（账号限制字母数字）")

h("6.5 管理端 · 用户管理", 2)
pic("web-04-users.png", "用户管理：查看用户、改头像")

h("6.6 管理端 · 内容管理", 2)
pic("web-05-posts.png", "内容管理：帖子列表，可编辑、下架、删除")

h("6.7 管理端 · 圈子管理", 2)
pic("web-06-circles.png", "圈子管理：可编辑、设热门、上传封面、停用")

h("6.8 管理端 · 地点管理", 2)
pic("web-07-locations.png", "地点管理：每所学校维护自己的地点列表")

h("6.9 管理端 · AI 助手", 2)
pic("web-08-ai-bot.png", "AI 助手：规则管理 + 没接住的问题统计 + 改欢迎语")

h("6.10 管理端 · 外卖管理", 2)
pic("web-09-shops.png", "外卖管理：店铺列表，可管理菜品")

h("6.11 管理端 · 校园评选", 2)
pic("web-10-idol.png", "校园评选：选手卡片，可增删改、停用")

h("6.12 管理端 · 教务管理", 2)
pic("web-11-academics.png", "教务管理：学期与校历事件")

h("6.13 管理端 · 业务管理", 2)
pic("web-12-business.png", "业务管理：兼职/活动/二手三个 tab")

h("6.14 管理端 · 操作日志", 2)
pic("web-13-logs.png", "操作日志：每次管理端写操作都有记录")

doc.add_page_break()

# ==================== 七、接口清单 ====================
h("七、接口清单", 1)
para("统一前缀 /api，统一返回 {code, msg, data}。")

h("7.1 小程序端（无需登录）", 2)
table(
    ["方法", "路径", "说明"],
    [
        ["GET", "/circles", "圈子列表（分页、按校）"],
        ["GET", "/circles/hot", "热门圈子（首页轮播）"],
        ["GET", "/circles/categories", "圈子分类统计"],
        ["GET", "/posts", "帖子列表（tag/scope/分页）"],
        ["GET", "/posts/{id}", "帖子详情"],
        ["POST", "/posts", "发帖（可带 learnCategory）"],
        ["GET", "/learn/topics", "学习专区列表 ★"],
        ["GET", "/learn/counts", "学习专区各板块数量 ★"],
        ["GET", "/shops", "外卖店铺"],
        ["GET", "/shops/{id}/menu", "店铺菜单"],
        ["GET", "/goods", "二手商品"],
        ["GET", "/jobs", "兼职岗位"],
        ["GET", "/activities", "校园活动"],
        ["GET", "/timetable", "课表"],
        ["GET", "/calendar", "校历"],
        ["GET", "/schools", "学校列表"],
        ["GET", "/schools/{id}/locations", "学校地点"],
        ["POST", "/ai/chat", "AI 助手对话"],
        ["GET", "/ai/welcome", "AI 欢迎语"],
        ["GET", "/recommend/home", "首页今日推荐"],
        ["POST", "/upload", "上传图片"],
    ],
)

h("7.2 管理端（需 Bearer token）", 2)
table(
    ["方法", "路径", "说明"],
    [
        ["POST", "/admin/login", "登录（唯一免鉴权）"],
        ["GET", "/admin/dashboard", "看板统计"],
        ["GET/POST/PUT", "/admin/schools", "学校管理"],
        ["GET/POST/PUT", "/admin/circles", "圈子管理"],
        ["GET/POST/PUT/DELETE", "/admin/school-locations", "地点管理"],
        ["GET", "/admin/users", "用户列表"],
        ["GET/PUT", "/admin/posts", "帖子列表 / 编辑"],
        ["POST", "/admin/posts/{id}/status", "下架/恢复"],
        ["GET/PUT", "/admin/goods", "二手列表 / 编辑"],
        ["GET/PUT", "/admin/activities", "活动列表 / 编辑"],
        ["GET/POST/PUT/DELETE", "/admin/shops", "外卖店铺"],
        ["GET/POST/PUT/DELETE", "/admin/shop-goods", "外卖菜品"],
        ["GET/POST/PUT/DELETE", "/admin/idol-contestants", "校园评选"],
        ["GET/POST/PUT/DELETE", "/admin/ai-rules", "AI 规则"],
        ["GET", "/admin/ai-stats", "AI 未命中统计"],
        ["PUT", "/admin/ai-welcome", "AI 欢迎语"],
        ["GET/POST/PUT/DELETE", "/admin/banners", "轮播图"],
        ["GET", "/admin/logs", "操作日志"],
        ["POST", "/admin/upload", "上传（鉴权）"],
        ["POST", "/admin/upload/private", "私有上传（学生证等）"],
        ["GET", "/admin/uploads/orphans", "孤儿文件清理"],
    ],
)

doc.add_page_break()

# ==================== 八、测试 ====================
h("八、自动化测试", 1)
para("共 271 个用例，全部通过。运行方式：")
code4 = doc.add_paragraph()
rc4 = code4.add_run(
    "# 小程序行为测试（45 个）\n"
    "node --test tests/regression.test.js\n\n"
    "# 后端集成测试（需后端运行）\n"
    "node --test tests/backend-school-scope.test.js\n"
    "node --test tests/backend-admin-edit.test.js\n"
    "node --test tests/learn.test.js\n"
    "# ... 共 16 个后端测试文件\n\n"
    "# 静态校验\n"
    "python tests/validate_project.py"
)
rc4.font.name = "Consolas"
rc4.font.size = Pt(9)
doc.add_paragraph()

table(
    ["测试文件", "用例数", "覆盖内容"],
    [
        ["regression.test.js", "45", "小程序各页面行为"],
        ["miniapp-school-picker / location / ai-chips", "26", "选学校、地点、AI 关键词"],
        ["audit-fixes / period-config / admin-username", "34", "同类问题修复、作息、账号校验"],
        ["backend-*-scope", "20", "按校隔离"],
        ["backend-admin-*", "73", "管理端增删改查"],
        ["backend-upload-security", "12", "上传安全"],
        ["backend-ai-chat / ai-admin", "33", "AI 对话与配置"],
        ["learn.test.js", "17", "学习专区 ★"],
    ],
)

para("⚠️ 注意：后端集成测试会真的往数据库写数据（标题带 E2E 前缀）。"
     "跑之前建议先记录数据量，跑完清理：", bold=True)
code5 = doc.add_paragraph()
rc5 = code5.add_run(
    "DELETE FROM post WHERE title LIKE 'E2E%';\n"
    "DELETE FROM job WHERE title LIKE 'E2E%';"
)
rc5.font.name = "Consolas"
rc5.font.size = Pt(9)

doc.add_page_break()

# ==================== 九、已知问题与待办 ====================
h("九、已知问题与待办", 1)

h("9.1 需要优先处理", 2)
table(
    ["#", "问题", "影响", "建议"],
    [
        ["1", "/uploads/** 完全公开", "任何人拿到 URL 就能访问上传的图片", "做学生证认证前必须加鉴权"],
        ["2", "上传无内容审核", "违规图片可直接传上来", "接第三方审核或人工审核"],
        ["3", "无上传频率限制之外的保护", "可脚本批量上传", "已有 60 秒 20 次限制，可再收紧"],
        ["4", "job.status 是中文文本", "应该是枚举", "改成 tinyint"],
        ["5", "上传文件无引用级联清理", "删了内容图片还在", "目前只能按时间清理孤儿文件"],
    ],
)

h("9.2 功能待办", 2)
for x in [
    "兼职申请流程：投递、申请人列表、录用/拒绝、我的申请",
    "校园身份认证：学号 + 姓名 + 学生证照片人工审核（“都能用，认证只给标识”）",
    "电子宠物体系：领养、签到、任务、积分、饰品、续火花",
    "宠物定时提醒：受微信订阅消息限制，需先定方案（被动提醒 / 攒授权 / 公众号推送）",
    "学习资料下载、考研考公院校库",
    "内容举报与敏感词过滤",
    "多实例部署时的频率限制（现在用单机内存计数）",
]:
    bullet(x)

h("9.3 已知的技术债", 2)
table(
    ["项", "说明"],
    [
        ["shop.monthly_sales", "是平台种子数据，不是真实销量；热度排序用的是 order_count"],
        ["page.total（GET /posts）", "用的是全表 count，不是过滤后的 count"],
        ["AI 助手是单轮匹配", "没有上下文，说“那它呢”接不上"],
        ["AI 规则需人工维护", "未命中统计能指导补规则，但仍要人来做"],
    ],
)

doc.add_page_break()

# ==================== 十、交接注意事项 ====================
h("十、交接注意事项", 1)

h("10.1 接手后建议先做的三件事", 2)
for x in [
    "把项目跑起来：后端 → 管理端 → 小程序，确认三端都通",
    "跑一遍全量测试，确认基线是 271 个用例全绿",
    "读 utils/api.js 和 backend 的 ForumService，理解「读兜底、写不兜底」的设计",
]:
    bullet(x)

h("10.2 改动时的注意事项", 2)
table(
    ["场景", "注意点"],
    [
        ["新增小程序页面", "四件套齐全 + app.json 注册；.nav 记得写 box-sizing: content-box"],
        ["新增管理端页面", "在 router/index.js 注册；超管专属页面加 superOnly: true"],
        ["新增接口", "读接口要 catch 兜底，写接口必须 throw；管理端路径必须在 /api/admin/ 下"],
        ["改数据库", "脚本放 backend/src/main/resources/db/，写成可重复执行（用 information_schema 判断）"],
        ["改图片路径", "数据库存 /assets/xxx（小程序资源）或 /uploads/xxx（上传文件），两者都由 Vite 代理"],
        ["加测试", "后端测试会写真库，记得跑完清理"],
    ],
)

h("10.3 两个环境相关的坑", 2)
para("① 后端编译必须带 -P !jdk-1.8", bold=True)
para(
    "Maven 全局 settings.xml 里有 activeByDefault 的 jdk-1.8 profile，不加参数会用 JDK 8 编译，"
    "Lombok 直接崩。同时 JAVA_HOME 要指向 JDK 17+。"
)
para("② 管理端图片走 Vite 中间件", bold=True)
para(
    "web/vite.config.js 里有一个 serveMiniappAssets 插件，把 /assets/** 映射到小程序的 assets 目录。"
    "如果没有它，Vite 会把 /assets/xxx.webp 当成 SPA 路由返回 index.html，"
    "浏览器拿 HTML 当图片解码 → 所有图片显示成破图。"
)

h("10.4 数据库连接", 2)
para("配置文件：backend/src/main/resources/application.yml")
table(
    ["项", "值"],
    [
        ["地址", "127.0.0.1:3306"],
        ["库名", "campus_life"],
        ["账号", "root"],
        ["密码", "（见 application.yml，交接时单独告知）"],
    ],
)

doc.add_paragraph()
para("— 文档结束 —", bold=True)

doc.save(OUT)
print("✅ 文档已生成:", OUT)
print("   大小:", round(os.path.getsize(OUT) / 1024, 1), "KB")
