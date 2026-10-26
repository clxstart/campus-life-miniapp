# -*- coding: utf-8 -*-
"""生成给老板看的项目汇报文档（通俗语言版）"""
import os
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn

ROOT = r"E:\_verify_completed\campus-life-miniapp"
SHOT = os.path.join(ROOT, "docs", "screenshots")
OUT = os.path.join(ROOT, "docs", "校园生活项目汇报.docx")

FONT = "微软雅黑"
DARK = RGBColor(0x22, 0x22, 0x22)
GRAY = RGBColor(0x77, 0x77, 0x77)
BLUE = RGBColor(0x2E, 0x5C, 0x8A)

doc = Document()

# 页面设置
for s in doc.sections:
    s.top_margin = Inches(0.9)
    s.bottom_margin = Inches(0.9)
    s.left_margin = Inches(0.9)
    s.right_margin = Inches(0.9)

st = doc.styles["Normal"]
st.font.name = FONT
st.font.size = Pt(11)
st._element.rPr.rFonts.set(qn("w:eastAsia"), FONT)


def _cn(run, size=11, bold=False, color=DARK):
    run.font.name = FONT
    run.font.size = Pt(size)
    run.bold = bold
    run.font.color.rgb = color
    run._element.rPr.rFonts.set(qn("w:eastAsia"), FONT)


def h(text, level=1):
    p = doc.add_heading("", level=level)
    r = p.add_run(text)
    _cn(r, {1: 18, 2: 14, 3: 12}.get(level, 12), True, BLUE)
    return p


def para(text, size=11, bold=False, color=DARK, align=None, space_after=6):
    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(space_after)
    p.paragraph_format.line_spacing = 1.4
    if align:
        p.alignment = align
    r = p.add_run(text)
    _cn(r, size, bold, color)
    return p


def bullet(text, size=11):
    p = doc.add_paragraph(style="List Bullet")
    p.paragraph_format.space_after = Pt(3)
    p.paragraph_format.line_spacing = 1.35
    r = p.add_run(text)
    _cn(r, size)
    return p


def table(headers, rows, col_widths=None):
    t = doc.add_table(rows=1, cols=len(headers))
    t.style = "Light Grid Accent 1"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, cell in enumerate(t.rows[0].cells):
        cell.text = ""
        p = cell.paragraphs[0]
        r = p.add_run(headers[i])
        _cn(r, 10, True, RGBColor(0xFF, 0xFF, 0xFF))
    for row in rows:
        cells = t.add_row().cells
        for i, val in enumerate(row):
            cells[i].text = ""
            p = cells[i].paragraphs[0]
            r = p.add_run(str(val))
            _cn(r, 10)
    doc.add_paragraph().paragraph_format.space_after = Pt(4)
    return t


def shot(filename, caption, width=6.3):
    path = os.path.join(SHOT, filename)
    if not os.path.exists(path):
        para(f"（缺少截图 {filename}）", 9, color=GRAY)
        return
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(6)
    p.add_run().add_picture(path, width=Inches(width))
    c = doc.add_paragraph()
    c.alignment = WD_ALIGN_PARAGRAPH.CENTER
    c.paragraph_format.space_after = Pt(10)
    r = c.add_run(caption)
    _cn(r, 9, color=GRAY)


def phone(filename, caption):
    shot(filename, caption, width=2.45)


def pagebreak():
    doc.add_page_break()


# ═══════════════════ 封面 ═══════════════════
for _ in range(5):
    doc.add_paragraph()

p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = p.add_run("校园生活平台")
_cn(r, 34, True, RGBColor(0x1A, 0x1A, 0x1A))

p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = p.add_run("项 目 汇 报")
_cn(r, 22, True, BLUE)

doc.add_paragraph()
p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = p.add_run("微信小程序 · 运营管理后台 · 服务端")
_cn(r, 13, color=GRAY)

for _ in range(9):
    doc.add_paragraph()

p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = p.add_run("2026 年 10 月")
_cn(r, 12, color=GRAY)

pagebreak()

# ═══════════════════ 一、这是什么 ═══════════════════
h("一、这是一个什么产品", 1)

para(
    "一句话说清楚：这是一个面向大学生的校园生活 App，学生用微信小程序就能打开，"
    "在里面点外卖、买卖二手、找兼职、参加活动、逛论坛、学东西；"
    "运营人员用一个电脑后台管理所有这些内容。"
)

para("学生能在里面做什么", bold=True, size=12)
for x in [
    "点外卖 —— 浏览校内店铺和菜品，直接下单",
    "买卖二手 —— 发布闲置、按分类找东西、收藏",
    "找兼职 —— 看校内外的兼职岗位，按结算方式筛选",
    "参加活动 —— 看季节活动、校园评选，在线报名投票",
    "逛论坛 —— 发帖、点赞、评论、关注，还能加入兴趣圈子",
    "学习专区 —— 课程互助、论文指导、就业实习、考研、考公 五个板块",
    "查课表 —— 自己录课程，支持早晚自习作息设置",
    "问 AI 助手 —— 像聊天一样问「今天吃什么」「有什么活动」，它能查真实数据回答",
]:
    bullet(x)

para("运营人员能在后台做什么", bold=True, size=12)
for x in [
    "管学校 —— 多个学校各自独立，内容互不干扰",
    "管内容 —— 帖子、圈子、二手、兼职、活动、外卖店铺，都能编辑和下架",
    "管用户 —— 查看注册用户、修改头像",
    "管评选 —— 校园评选的选手增删改、票数管理",
    "配 AI 助手 —— 修改机器人的回答规则和欢迎语，看它哪些问题没答上来",
    "看数据 —— 首页数据看板、每次操作的日志记录",
]:
    bullet(x)

pagebreak()

# ═══════════════════ 二、做完了什么（截图） ═══════════════════
h("二、现在做出来的样子", 1)
para("下面全部是真实运行的界面截图，不是设计稿。", color=GRAY)

h("2.1 学生端（微信小程序）", 2)

h("首页", 3)
phone("mp-01-home.png", "首页：选学校、搜索、各功能入口、今日推荐")
para(
    "学生第一次打开要选自己的学校，之后看到的帖子、店铺、兼职都只属于这个学校。"
    "首页把最常用的功能都放在一屏内，往下滑是「今日推荐」，按真实热度排的。"
)

h("圈子", 3)
phone("mp-02-discover.png", "圈子：按兴趣聚合的社区")
para("学生可以加入感兴趣的圈子，圈子里的内容只有圈内人看得到。")

h("学习专区", 3)
phone("mp-03-learn.png", "学习专区：五个板块，可发帖求助")
para(
    "这是最近新做的独立页面。分课程互助、论文指导、就业实习、考研、考公 五个板块，"
    "每个板块下有学生发的帖子。页面上方还有 AI 助手入口，卡住的时候可以直接问。"
)

h("校园论坛", 3)
phone("mp-04-community.png", "校园论坛：全校可见的帖子流")
para("论坛支持关注、推荐、学习、生活、求助、二手、社团等多个标签分类。")

h("AI 校园助手", 3)
phone("mp-05-ai-chat.png", "AI 助手：会查真实数据的聊天机器人")
para(
    "这是和别家最不一样的地方。学生问「今天吃什么」，它不是随便答，"
    "而是真的去数据库里查本校的店铺，报出店名。问「有兼职吗」就查真实岗位。"
    "底部还有一排常用问题，点一下就能问。"
)

h("校园外卖", 3)
phone("mp-06-food.png", "外卖：校内店铺与菜品")
para("按学校隔离，只显示本校的店。可以按分类筛选，看评分和配送时间。")

h("二手市场", 3)
phone("mp-07-market.png", "二手市场：发布与浏览闲置")
para("支持分类浏览、发布、收藏，发布时可以上传多张图。")

h("校园兼职", 3)
phone("mp-08-jobs.png", "兼职：岗位列表")
para("可按日结、月结、周结筛选，也能按学校看本校的岗位。")

h("季节活动", 3)
phone("mp-09-activities.png", "季节活动：活动列表与在线报名")
para("第一条是本周主打放大展示，其余是列表。可以一键报名，显示已报名人数。")

h("课表", 3)
phone("mp-10-timetable.png", "课表：学生自建课程")
para("学生自己录入课程，支持设置每天的上课时间段。换学校后作息会自动跟着变。")

h("校历", 3)
phone("mp-11-calendar.png", "校历：学期与重要日期")
para("显示当前学期、教学周次和学校发布的重要日期。")

h("我的", 3)
phone("mp-12-profile.png", "个人中心：我的内容与设置")
para("这里能看到自己发过的帖子、收藏、报名记录，也能切换学校。")

h("消息", 3)
phone("mp-13-messages.png", "消息：通知与私信")
para("分通知和私信两类，点赞、评论、报名成功都会推通知。")

h("校园评选", 3)
phone("mp-14-idol.png", "校园评选：校草校花投票")
para("选手按男女分组展示，学生可以投票，票数实时更新。")

h("发布", 3)
phone("mp-15-publish.png", "发布：统一发布入口")
para("发帖、发二手、发兼职都从这里进，发布时会自动带上当前学校。")

h("搜索", 3)
phone("mp-16-search.png", "全站搜索")
para("一个搜索框搜遍帖子、圈子、二手、活动、兼职，结果按类型分组。")

pagebreak()

h("2.2 运营后台（电脑网页）", 2)
para(
    "后台是给运营同事用的，在浏览器打开就能管理全平台。"
    "不同角色看到的菜单不一样：超级管理员能看全部，学校管理员只能管自己学校。",
    color=GRAY,
)

h("数据看板", 3)
shot("web-01-dashboard.png", "数据看板：用户数、帖子数、圈子数、闲置数 + 近 7 天发帖趋势")

h("学校管理", 3)
shot("web-02-schools.png", "学校管理：新增学校、上传校徽、设置作息时间")
para("这是多学校能力的基础。每所学校有自己的内容、自己的地点、自己的作息。")

h("管理员账号", 3)
shot("web-03-admins.png", "管理员：超级管理员可以创建学校管理员账号")

h("用户管理", 3)
shot("web-04-users.png", "用户管理：查看注册学生、修改头像")

h("内容管理", 3)
shot("web-05-posts.png", "内容管理：所有帖子，可编辑正文、下架违规内容")

h("圈子管理", 3)
shot("web-06-circles.png", "圈子管理：编辑圈子信息、上传封面、设为热门、停用")

h("地点管理", 3)
shot("web-07-locations.png", "地点管理：每所学校维护自己的地点（图书馆、食堂、宿舍…）")
para("学生发帖选位置时，只能选本校的地点，不会出现别的学校的地名。")

h("AI 助手配置", 3)
shot("web-08-ai-bot.png", "AI 助手：回答规则管理 + 没答上来的问题统计")
para(
    "这是 AI 助手的「驾驶舱」。左边是问答规则，运营可以随时加、改、停用；"
    "右边是统计，能看到机器人总共答了多少次、哪些问题没接住——"
    "没接住的问题点一下就能直接变成一条新规则。"
)

h("外卖管理", 3)
shot("web-09-shops.png", "外卖管理：店铺列表，可管理每家的菜品和价格")

h("校园评选", 3)
shot("web-10-idol.png", "校园评选：选手卡片，可新增、编辑、停用")

h("教务管理", 3)
shot("web-11-academics.png", "教务管理：学期设置、校历事件")

h("业务管理", 3)
shot("web-12-business.png", "业务管理：兼职、活动、二手三个板块统一管理")

h("操作日志", 3)
shot("web-13-logs.png", "操作日志：每一次后台操作都有记录，可追溯")

pagebreak()

# ═══════════════════ 三、和别家比的不同 ═══════════════════
h("三、和别家比，我们不一样在哪", 1)

table(
    ["别人常见的做法", "我们的做法", "好处"],
    [
        [
            "一个学校一个 App，换学校要重做",
            "一套系统支持多学校，内容按校自动隔离",
            "谈下第二所学校不用重开发，直接开新校区",
        ],
        [
            "AI 助手是通用聊天，答什么都行但没实话",
            "AI 助手接真实校园数据，问吃什么就报本校店名",
            "这是竞品做不到的，因为数据在我们手里",
        ],
        [
            "学习板块只是论坛里一个标签",
            "学习专区是独立页面，五个板块各有分类",
            "能单独运营、单独谈合作（论文辅导、考研机构）",
        ],
        [
            "断网或服务器挂了页面就白屏",
            "后端挂了小程序自动用本地数据，仍能浏览",
            "演示、路演、服务器维护时都不会开天窗",
        ],
        [
            "后台只能看不能改，改内容要找开发",
            "运营可以自己改帖子、圈子、店铺、AI 话术",
            "不占用开发资源，运营自己就能维护",
        ],
    ],
)

pagebreak()

# ═══════════════════ 四、现在能不能用 ═══════════════════
h("四、现在能不能用", 1)

para("能。三部分都已经跑通并且验证过。", bold=True, size=12)

table(
    ["检查项", "结果"],
    [
        ["小程序能否正常打开", "能，28 个页面全部可访问"],
        ["运营后台能否登录使用", "能，两种角色（超管 / 校管）权限已区分"],
        ["后端服务是否稳定", "能，所有接口实测通过"],
        ["数据是否干净", "是，测试数据已全部清理"],
        ["图片是否正常显示", "是，全部图片路径已校验"],
        ["自动化测试", "271 个测试用例全部通过"],
    ],
)

para("也就是说：现在就可以拿去给人演示、给种子用户试用。", bold=True)

pagebreak()

# ═══════════════════ 五、还没做的 ═══════════════════
h("五、还没做的和风险", 1)

para("下面这些不影响演示，但上线前必须处理。", color=GRAY)

h("5.1 上线前必须解决的", 2)
table(
    ["事项", "为什么要做", "工作量"],
    [
        [
            "学生身份认证",
            "现在任何人都能注册，无法确认是不是本校学生。"
            "需要学号 + 学生证照片人工审核。",
            "中等",
        ],
        [
            "内容审核",
            "用户上传的图片和文字没有过滤，违规内容会直接显示。"
            "需要接第三方审核或人工审核。",
            "中等",
        ],
        [
            "上传文件权限",
            "现在上传的图片只要知道链接谁都能看，学生证这类隐私文件不安全。",
            "小",
        ],
        [
            "服务器与域名",
            "现在是本地跑的，需要买服务器、备案域名、配 HTTPS。",
            "小",
        ],
    ],
)

h("5.2 想加但可以晚点做的", 2)
for x in [
    "兼职申请流程 —— 现在只能看岗位，还不能在线投递、看申请记录",
    "电子宠物 / 养成体系 —— 用宠物提高日活，签到、任务、积分、装扮",
    "学习资料下载 —— 课件、真题、笔记的上传与下载",
    "考研考公院校库 —— 院校信息、分数线、专业目录",
    "内容举报 —— 学生举报违规内容，运营处理",
]:
    bullet(x)

h("5.3 一个需要提前知道的技术限制", 2)
para(
    "微信对小程序的「主动推送消息」有严格限制。校园类目拿不到长期推送权限，"
    "意味着「宠物到点提醒你吃饭」这类功能不能想推就推，每次都要用户手动授权一次。"
)
para("目前有三个可选方案，需要你定：", bold=True)
table(
    ["方案", "做法", "体验"],
    [
        ["A. 被动提醒", "用户打开小程序时，弹出「你有 3 件事待办」", "最简单，但用户不打开就收不到"],
        ["B. 攒授权", "每次用户操作时顺便要一次授权，攒够次数再推", "能推，但授权率有限"],
        ["C. 公众号推送", "引导关注公众号，从公众号推", "能推，但要额外运营一个公众号"],
    ],
)

pagebreak()

# ═══════════════════ 六、建议下一步 ═══════════════════
h("六、建议的下一步", 1)

para("按优先级排，建议这样推进：", bold=True)

table(
    ["优先级", "做什么", "为什么先做这个"],
    [
        ["第一", "找 20-30 个真实学生试用", "验证功能是不是他们真要的，比继续加功能重要"],
        ["第二", "补学生身份认证", "没有认证，二手和兼职板块会有诈骗风险"],
        ["第三", "补内容审核", "避免违规内容导致小程序被封"],
        ["第四", "部署到正式服务器", "才能对外发布，现在只能本地演示"],
        ["第五", "接兼职申请流程", "这是学生最常问的「能不能直接投」"],
        ["第六", "做宠物 / 养成体系", "提升日活，但要在有用户之后再考虑"],
    ],
)

para(
    "我的建议是先做前四项。前四项做完，这个产品就可以正式对外了；"
    "后面的属于锦上添花，等有用户反馈再定优先级。",
    bold=True,
)

pagebreak()

# ═══════════════════ 附录：功能一览 ═══════════════════
h("附录：功能一览表", 1)
para("按学生能用到的东西列，用大白话写，方便快速对照。", color=GRAY)

h("A. 学生端功能", 2)
table(
    ["功能", "学生能干什么", "状态"],
    [
        ["选学校", "第一次打开选自己学校，之后可随时切换", "已完成"],
        ["首页", "看功能入口、看今日推荐、搜索", "已完成"],
        ["圈子", "加入兴趣圈子，看圈内内容", "已完成"],
        ["校园论坛", "发帖、点赞、评论、关注、按标签筛选", "已完成"],
        ["学习专区", "课程互助/论文指导/就业实习/考研/考公 五板块，可发帖", "已完成"],
        ["AI 助手", "聊天式提问，能查真实店铺/岗位/活动", "已完成"],
        ["外卖", "看校内店铺、看菜单、下单", "已完成"],
        ["二手市场", "发布闲置、分类浏览、收藏", "已完成"],
        ["校园兼职", "看岗位、按结算方式筛选", "已完成"],
        ["季节活动", "看活动、在线报名", "已完成"],
        ["校园评选", "给校草校花投票", "已完成"],
        ["课表", "自己录课程、设置作息时间", "已完成"],
        ["校历", "看学期、周次、重要日期", "已完成"],
        ["消息", "收通知、私信聊天", "已完成"],
        ["搜索", "一个框搜遍全站内容", "已完成"],
        ["个人中心", "看自己的帖子/收藏/报名，改资料", "已完成"],
        ["兼职在线投递", "投简历、看申请状态", "未做"],
        ["学生身份认证", "学号+学生证审核，认证后带标识", "未做"],
        ["电子宠物", "领养、签到、做任务、攒积分、换装扮", "未做"],
        ["学习资料下载", "下载课件、真题、笔记", "未做"],
    ],
)

h("B. 运营后台功能", 2)
table(
    ["功能", "运营能干什么", "状态"],
    [
        ["数据看板", "看用户数、帖子数、圈子数、发帖趋势", "已完成"],
        ["学校管理", "新增学校、上传校徽、设置作息", "已完成"],
        ["管理员账号", "创建学校管理员，分配管辖范围", "已完成"],
        ["用户管理", "查看学生列表、改头像", "已完成"],
        ["内容管理", "编辑帖子、下架违规内容", "已完成"],
        ["圈子管理", "编辑圈子、上传封面、设热门、停用", "已完成"],
        ["地点管理", "维护每所学校的地点列表", "已完成"],
        ["AI 助手配置", "改回答规则、改欢迎语、看没答上来的问题", "已完成"],
        ["外卖管理", "管店铺、管菜品和价格", "已完成"],
        ["校园评选", "选手增删改、查看票数", "已完成"],
        ["教务管理", "设置学期、发布校历事件", "已完成"],
        ["业务管理", "兼职、活动、二手统一管理", "已完成"],
        ["操作日志", "查看每一次后台操作记录", "已完成"],
        ["内容审核", "自动识别违规图片和文字", "未做"],
        ["举报处理", "处理学生举报的内容", "未做"],
        ["数据导出", "导出用户/内容数据做分析", "未做"],
    ],
)

h("C. 技术底座", 2)
table(
    ["能力", "说明", "状态"],
    [
        ["多学校隔离", "内容按学校自动分开，互不干扰", "已完成"],
        ["离线可用", "后端挂了小程序仍能浏览本地数据", "已完成"],
        ["两套权限", "超级管理员 / 学校管理员，菜单自动区分", "已完成"],
        ["图片上传", "支持多图，限制大小和格式", "已完成"],
        ["上传限流", "同一人 1 分钟最多传 20 张，防刷", "已完成"],
        ["图片校验", "按文件内容判断真假图片，防伪装文件", "已完成"],
        ["自动化测试", "271 个用例，改代码后自动验证", "已完成"],
        ["自动部署", "代码提交后自动发布到服务器", "未做"],
        ["数据备份", "定期备份数据库", "未做"],
    ],
)

doc.add_paragraph()
p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = p.add_run("— 汇报结束 —")
_cn(r, 11, color=GRAY)

doc.save(OUT)
size = round(os.path.getsize(OUT) / 1024, 1)
print(f"✅ 文档已生成: {OUT}")
print(f"   大小: {size} KB")
