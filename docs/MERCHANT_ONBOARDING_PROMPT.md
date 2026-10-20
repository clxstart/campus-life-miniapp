# Web 端商家入驻 · GPT 提示词

> 工程目录：`E:\_verify_completed\campus-life-miniapp`
> 目标：让校外商家能自己申请入驻、自己管理店铺和菜品
> 前置：外卖支付已完成（订单和支付表已存在）

---

=== 提示词开始 ===

## 一、你的任务

给校园外卖加**商家入驻**能力。现在店铺全是平台运营手工录的，商家没法自己管理。要做成：

1. 商家在网页上提交入驻申请（不用登录）
2. 平台运营审核（通过 / 拒绝）
3. 通过后自动开通商家账号
4. 商家登录自己的后台，管店铺、管菜品、管订单

**关键约束：商家只能看到自己那一家店的数据，不能看到别人的。**

## 二、项目背景

- 后端：Spring Boot 3.2.5 + MyBatis-Plus 3.5.6 + MySQL 8，包名 `com.campuslife`
- 管理端：Vue 3 + Vite + Tailwind，**不用 Element Plus**
- 数据库：`campus_life`，账号 `root`
- 统一返回 `{code, msg, data}`，成功 `code=0`
- 项目约定：读接口兜底、写接口不兜底
- 现有管理端菜单 13 个，商家后台是新的一套

## 三、动手前必须先读这些文件

1. `backend/.../config/AdminInterceptor.java` —— 管理端鉴权（重点，要扩展）
2. `backend/.../service/AdminAuthService.java` —— 登录与 JWT 签发
3. `backend/.../service/AdminModuleService.java` —— 现有店铺/菜品管理逻辑
4. `backend/.../controller/AdminModuleController.java` —— 现有管理端接口
5. `backend/.../service/UploadService.java` —— 上传（注意有 `storePrivate` 私有上传）
6. `web/src/views/Shops.vue` —— 现有外卖管理页（392 行，样式参考）
7. `web/src/views/Login.vue` —— 登录页
8. `web/src/router/index.js` —— 路由与菜单
9. `web/src/api/index.js` —— 前端接口封装
10. `backend/src/main/resources/db/` —— 迁移脚本风格

## 四、现有结构（重要）

### 4.1 管理员账号表 `admin_user`

```sql
id            bigint      主键
username      varchar(32) 唯一
password_hash varchar(128)
real_name     varchar(32)
role          varchar(16) 默认 'school'   -- 目前只有 super / school
school_id     bigint      NULL
status        tinyint     默认 1
fail_count    int
locked_until  datetime
last_login_at datetime
created_at    datetime
```

**现状：只有 `super`（超管）和 `school`（校管）两种角色，没有商家角色。**

### 4.2 店铺表 `shop`

```sql
id, name, cover, category, rating, monthly_sales,
min_price, delivery_fee, delivery_time, discount, notice,
discount_threshold, discount_amount, status, order_count,
view_count, school_id
```

**缺的字段：** 没有联系方式、没有地址、没有营业执照、没有归属商家。

### 4.3 菜品表 `shop_goods`

```sql
id, shop_id, category, name, price, monthly_sales, image, sort_num, status
```

### 4.4 鉴权现状

`AdminInterceptor` 拦截 `/api/admin/**`，校验 JWT 里的 `role`，放行登录接口。

**需要扩展：** 现在所有 `/api/admin/**` 只要有 token 就过，没有按角色细分。商家角色必须只能访问 `/api/merchant/**`。

## 五、要做什么

### 5.1 数据库迁移

新建 `backend/src/main/resources/db/module-merchant.sql`，**可重复执行**（用 `information_schema` 判断，参考 `module-learn.sql`）。

**A. `admin_user` 加字段**

```sql
shop_id bigint NULL   -- 商家角色绑定的店铺 ID；超管/校管为 NULL
```

**B. `admin_user.role` 支持新值 `merchant`**

不需要改表结构（varchar 够用），但代码里要认这个值。

**C. 新建入驻申请表 `merchant_apply`**

```sql
CREATE TABLE merchant_apply (
    id             bigint PRIMARY KEY AUTO_INCREMENT,
    shop_name      varchar(64)  NOT NULL,        -- 店铺名
    category       varchar(32)  NOT NULL,        -- 分类：快餐/奶茶/水果/超市
    contact_name   varchar(32)  NOT NULL,        -- 联系人
    contact_phone  varchar(20)  NOT NULL,        -- 联系电话
    address        varchar(128) NOT NULL,        -- 店铺地址
    school_id      bigint       NOT NULL,        -- 所属学校
    license_no     varchar(64)  NOT NULL,        -- 营业执照号
    license_image  varchar(255) NOT NULL,        -- 营业执照照片（私有路径）
    shop_image     varchar(255) NOT NULL,        -- 店面照片
    intro          varchar(300),                 -- 店铺简介
    status         varchar(16)  DEFAULT 'pending',  -- pending/approved/rejected
    reject_reason  varchar(200),                 -- 拒绝原因
    audit_by       bigint,                       -- 审核人
    audit_at       datetime,                     -- 审核时间
    created_shop_id bigint,                      -- 通过后生成的店铺 ID
    created_at     datetime DEFAULT CURRENT_TIMESTAMP,
    KEY idx_status (status),
    KEY idx_school (school_id)
);
```

**D. `shop` 加字段**

```sql
contact_phone varchar(20)  DEFAULT ''     -- 联系电话
address       varchar(128) DEFAULT ''     -- 店铺地址
owner_name    varchar(32)  DEFAULT ''     -- 老板姓名
intro         varchar(300) DEFAULT ''     -- 简介
```

**E. 插入一条测试数据（可选）**

一条待审核的入驻申请，方便验证页面。

### 5.2 权限设计（最关键，做错会有安全事故）

现在只有一个拦截器，需要拆成两层：

```
/api/merchant/**  → 必须是 merchant 角色
                    且强制只能操作自己 shop_id 的数据
/api/admin/**     → 必须是 super 或 school 角色
                    商家账号访问直接 403
```

**实现方式（二选一，推荐第二种）：**

**方案一：新增一个 `MerchantInterceptor`**

```java
// 拦截 /api/merchant/**
// 校验 token → role 必须是 merchant
// 从 token 取 shopId，塞进 request attribute
request.setAttribute("shopId", claims.get("shopId", Long.class));
```

然后所有商家接口从 `request.getAttribute("shopId")` 取店铺 ID，**绝不接受前端传的 shopId**。

**方案二：扩展现有 `AdminInterceptor`**

在 `AdminInterceptor` 里判断路径前缀：
- `/api/merchant/` 开头 → 要求 `role == merchant`
- `/api/admin/` 开头 → 要求 `role == super || school`

**无论选哪个，铁律是：**

> **商家接口里的 shop_id 必须从 token 里取，不能从请求参数里取。**
> 否则商家 A 改个参数就能操作商家 B 的店。

**JWT 需要加 `shopId`**：`AdminAuthService` 登录时，如果角色是 merchant，把 `shop_id` 写进 token。

### 5.3 公开入驻申请页 `web/src/views/MerchantApply.vue`

**这个页面不需要登录**，路由加 `meta: { public: true }`。

**路由：**

```js
{
  path: '/merchant/apply',
  name: 'MerchantApply',
  component: () => import('../views/MerchantApply.vue'),
  meta: { public: true, title: '商家入驻' }
}
```

**表单字段：**

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| 店铺名称 | 输入框 | 是 | 2-64 字 |
| 经营分类 | 下拉 | 是 | 快餐/奶茶/水果/超市/其他 |
| 所属学校 | 下拉（读 `/api/schools`） | 是 | |
| 联系人 | 输入框 | 是 | 2-32 字 |
| 联系电话 | 输入框 | 是 | 11 位手机号 |
| 店铺地址 | 输入框 | 是 | 最多 128 字 |
| 营业执照号 | 输入框 | 是 | 最多 64 字 |
| 营业执照照片 | 上传 | 是 | 图片，≤5MB |
| 店面照片 | 上传 | 是 | 图片，≤5MB |
| 店铺简介 | 多行文本 | 否 | 最多 300 字 |

**上传注意：营业执照属于敏感证件，必须走私有上传接口 `/api/admin/upload/private`（已存在），不要走公开的 `/api/upload`。**

**提交后：**

显示成功页：「申请已提交，我们会在 1-3 个工作日内审核，请留意电话通知。」

**页面风格：** 简单干净的居中表单，不要做成后台那种表格风格。顶部放个标题「商家入驻」+ 一句说明「加入校园外卖，触达 X 名学生」。

### 5.4 管理端审核页 `web/src/views/MerchantAudit.vue`

**路由：**

```js
{
  path: 'merchant-audit',
  name: 'merchant-audit',
  component: () => import('../views/MerchantAudit.vue'),
  meta: { title: '商家入驻审核', icon: '⊞' }
}
```

`superOnly: false`（校管也要审自己学校的）。

**页面结构：**

```
统计卡片：待审核 | 已通过 | 已拒绝
Tab：待审核 | 已通过 | 已拒绝
表格
```

**表格列：**

| 列 | 内容 |
|---|---|
| 店铺名称 | |
| 分类 | 标签 |
| 联系人 | 姓名 + 电话 |
| 学校 | |
| 提交时间 | |
| 操作 | 查看 / 通过 / 拒绝 |

**查看详情抽屉：**

展示全部申请信息，营业执照和店面照片用大图预览（点击放大）。

**通过：**

二次确认「确认通过？将自动创建店铺和商家账号」

通过后后端要做三件事：
1. 在 `shop` 表创建店铺（`status=0` 未上架，等商家自己补全信息再上架）
2. 在 `admin_user` 创建账号（`role='merchant'`, `shop_id=新店铺ID`）
3. 更新 `merchant_apply.status='approved'`, `created_shop_id`

**账号规则：**
- 用户名：`m` + 手机号后 8 位（如 `m13800138`），重复则加序号
- 初始密码：手机号后 6 位
- 页面上显示「账号：m13800138 / 初始密码：001380，请转告商家及时修改」

**拒绝：**

弹窗填拒绝原因（必填），改 `status='rejected'`。

### 5.5 商家后台（独立一套页面）

新建目录 `web/src/views/merchant/`，三个页面：

#### 5.5.1 商家首页 `merchant/Home.vue`

- 路由：`/merchant`
- 显示：今日订单数、今日营业额、待接单数、店铺状态
- 快捷入口：去改店铺、去管菜品、去看订单

#### 5.5.2 店铺设置 `merchant/Shop.vue`

- 能改：店铺名、分类、封面、联系电话、地址、简介、起送价、配送费、配送时间、公告、满减设置
- **不能改**：`school_id`（所属学校由平台定）、`status`（上架状态要平台审核）
- 保存后立即生效

#### 5.5.3 菜品管理 `merchant/Goods.vue`

- 列表：图片、名称、分类、价格、月售、状态、排序
- 操作：新增、编辑、上下架、删除、调整排序
- 批量操作：批量上架/下架
- 参考现有 `web/src/views/Shops.vue` 里的菜品弹窗写法

#### 5.5.4 订单管理 `merchant/Orders.vue`

- 列表：订单号、用户、商品、金额、状态、下单时间
- 操作：接单 / 拒单 / 完成
- **只能看自己店的订单**

**商家后台的导航布局**：左侧竖排菜单（首页/店铺/菜品/订单），顶部显示店铺名 + 退出按钮。和管理端分开，不要混在一起。

### 5.6 后端接口

#### 公开接口（无需登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/merchant/apply` | 提交入驻申请 |
| GET | `/api/merchant/apply/status?phone=` | 查询申请进度（可选） |

#### 管理端接口（`/api/admin`，super/school）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/merchant-applies` | 申请列表（`status`/`schoolId`/分页） |
| GET | `/admin/merchant-applies/{id}` | 申请详情 |
| POST | `/admin/merchant-applies/{id}/approve` | 通过 |
| POST | `/admin/merchant-applies/{id}/reject` | 拒绝（`{reason}`） |

#### 商家接口（`/api/merchant`，仅 merchant）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/merchant/shop` | 我的店铺 |
| PUT | `/merchant/shop` | 改店铺信息（不含 school_id/status） |
| GET | `/merchant/goods` | 我的菜品列表 |
| POST | `/merchant/goods` | 新增菜品 |
| PUT | `/merchant/goods/{id}` | 改菜品 |
| DELETE | `/merchant/goods/{id}` | 删菜品 |
| POST | `/merchant/goods/{id}/status` | 上下架 |
| GET | `/merchant/orders` | 我的订单（分页+状态筛选） |
| POST | `/merchant/orders/{id}/accept` | 接单 |
| POST | `/merchant/orders/{id}/reject` | 拒单 |
| POST | `/merchant/orders/{id}/complete` | 完成 |

**所有商家接口都必须从 token 取 `shopId`，SQL 里强制加 `shop_id = #{shopId}` 条件。**

### 5.7 登录跳转

商家用同一套登录页（`/login`）。登录后按角色跳转：

```js
if (role === 'merchant') {
  router.push('/merchant')
} else {
  router.push('/dashboard')
}
```

**商家访问 `/dashboard` 等管理端页面要被拦回 `/merchant`。**

## 六、和小程序端的联动（必做，不是可选项）

商家在 web 端改的东西，小程序要**立刻**看到。这是这个功能真正的价值——商家自己上架一道菜，学生马上能点。

### 6.1 原则：同一份数据，不做同步任务

不要搞「商家端一张表、小程序一张表、定时同步」。两边读的是**同一张 `shop` / `shop_goods` 表**，商家改了小程序自然就变了。

唯一需要注意的是**缓存和状态判断**。

### 6.2 商家操作 → 小程序表现

| 商家在 web 端操作 | 小程序端必须表现 |
|---|---|
| 上架菜品（`shop_goods.status=1`） | 店铺页菜单里出现这道菜 |
| 下架菜品（`status=0`） | 菜单里**不显示**，已加购物车的也要剔除 |
| 改菜品价格 | 学生看到新价格，购物车金额重算 |
| 改起送价 / 配送费 | 结算页金额跟着变 |
| 改公告 | 店铺页顶部横幅变化 |
| 设为休息（`shop.status=0`） | 店铺列表灰掉 / 提示「休息中」，点不进点餐页 |
| 接单 | 学生订单状态变「商家已接单」 |
| 拒单 | 学生收到通知 + 自动退款 |
| 完成 | 学生订单状态变「已完成」 |

### 6.3 数据库要补的字段

现在 `shop` 表**没有营业时间**，只有 `status`（上架状态）。要加：

```sql
open_time   varchar(5)  DEFAULT '09:00'   -- 营业开始，如 "09:00"
close_time  varchar(5)  DEFAULT '21:00'   -- 营业结束
is_open     tinyint     DEFAULT 1         -- 商家手动开/关店（区别于平台下架 status）
```

**`status` 和 `is_open` 的区别：**

- `status` = 平台是否允许这家店展示（违规下架用）
- `is_open` = 商家今天开不开门（休息用）

**判断能不能点餐：`status=1 AND is_open=1 AND 当前时间在 open_time~close_time 之间`**

### 6.4 小程序端要改的 4 个地方

#### 改 1：店铺列表显示营业状态

`pages/food-list/food-list.js`：

- 每家店计算 `isOpen`（综合 status、is_open、时间）
- 休息的店：卡片变灰，显示「休息中」角标，点击提示「这家店正在休息」
- 不要直接隐藏，学生找不到会困惑

#### 改 2：店铺页过滤下架菜品

`pages/food-shop/food-shop.js` 现在的逻辑是：

```js
const [shops, menu] = await Promise.all([api.getFoodShops(), api.getFoodMenu(id)])
const decorated = menu.map(group => ({...group, goods: group.goods.map(g => ({...g, priceText: money(g.price)}))}))
```

**要改成：**

```js
// 过滤掉下架菜品，过滤后空的分类整组不显示
const decorated = menu
  .map(group => ({
    ...group,
    goods: group.goods
      .filter(g => Number(g.status) === 1)   // 只留下架的
      .map(g => ({ ...g, priceText: money(g.price) }))
  }))
  .filter(group => group.goods.length > 0)   // 空分类整组去掉
```

**后端 `getFoodMenu` 也要在 SQL 里加 `AND status = 1`**，前端过滤只是兜底。

**店铺休息时：** 页面顶部显示「本店休息中，可浏览但不能下单」，购物车按钮禁用。

#### 改 3：新增「我的订单」页（**当前完全没有**）

现在小程序只有 `food-order`（结算页），学生下完单就看不到进度了。必须新增：

**新页面：`pages/food-orders/food-orders`**（注意是复数，订单列表）

- 路由注册到 `app.json`
- 入口：`pages/profile/profile` 加一项「我的订单」，或者首页功能区加图标
- 列表：店铺名、菜品摘要、金额、状态、下单时间
- 状态标签：待支付 / 待接单 / 已接单 / 配送中 / 已完成 / 已取消 / 已退款
- 点进去看详情（可以复用 `food-order` 或新建 `food-order-detail`）
- 下拉刷新

**这是联动的关键页面**——没有它，商家接单了学生也不知道。

#### 改 4：订单状态要能刷新

小程序没有 WebSocket，用**轮询**：

- 订单详情页：每 3 秒查一次订单状态，`onHide` / `onUnload` 时停止
- 订单列表页：下拉刷新 + `onShow` 时重新拉

```js
// 订单详情页
startPolling() {
  this._timer = setInterval(async () => {
    if (!this._alive) return
    const order = await api.getFoodOrderDetail(this.data.orderId)
    if (order.status !== this.data.order.status) {
      this.setData({ order })
      // 状态变了给个提示
      popup.toast(this, statusText(order.status))
    }
  }, 3000)
},
stopPolling() {
  if (this._timer) { clearInterval(this._timer); this._timer = null }
},
onHide() { this.stopPolling() },
onUnload() { this._alive = false; this.stopPolling() }
```

### 6.5 需要新增的小程序接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/orders` | 我的订单列表（按 userId，分页） |
| GET | `/api/orders/{id}` | 订单详情（含状态） |
| GET | `/api/food/shops` | 店铺列表（**返回要带 `isOpen` 计算好的状态**） |
| GET | `/api/food/shops/{id}/menu` | 菜单（**SQL 里过滤 `status=1`**） |

### 6.6 拒单必须自动退款

商家拒单时，如果订单已支付，后端要：

1. 改订单状态 `cancelled`
2. 调用微信退款接口（用支付那套的 `refund_record`）
3. 退款成功后通知学生
4. 退款失败要记录，让运营人工处理

**这条不能漏**——学生付了钱商家拒单，钱不退就是事故。

### 6.7 联动的验收（单独列，和功能验收分开）

| # | 检查项 | 通过标准 |
|---|---|---|
| L1 | 商家上架菜 → 小程序能看到 | 商家 web 端加一道菜，小程序刷新后菜单出现 |
| L2 | 商家下架菜 → 小程序看不到 | 下架后小程序菜单里消失 |
| L3 | 改价格 → 小程序同步 | 改价后小程序显示新价，购物车金额重算 |
| L4 | 商家休息 → 小程序不能下单 | 列表显示「休息中」，点不进点餐 |
| L5 | 商家接单 → 学生能看到 | 学生订单页 3 秒内变「已接单」 |
| L6 | 商家拒单 → 学生看到 + 退款 | 订单变「已取消」，退款记录生成 |
| L7 | 已下架菜品在购物车里 | 加购后商家下架，结算时提示「该菜品已下架」 |

## 七、验收标准

| # | 检查项 | 通过标准 |
|---|---|---|
| 1 | 后端能编译 | Maven BUILD SUCCESS |
| 2 | 迁移脚本可重复执行 | 连跑两次不报错 |
| 3 | 能提交申请 | 不登录打开申请页，填完能提交成功 |
| 4 | 营业执照走私有上传 | 上传后的 URL 是 `/private/xxx`，不是 `/uploads/xxx` |
| 5 | 管理端能审核 | 待审列表能看到、能看详情、能通过 |
| 6 | 通过后自动建号 | 自动创建店铺 + 商家账号，页面显示账号密码 |
| 7 | 商家能登录 | 用生成的账号登录，进商家后台 |
| 8 | **商家只能看自己店** | 商家 A 的接口拿不到商家 B 的菜品和订单 |
| 9 | **改参数越权失败** | 商家 A 请求里传 B 的 shopId，被忽略或拒绝 |
| 10 | 商家改不了学校 | PUT 店铺时传 school_id 无效 |
| 11 | 商家进不了管理端 | 访问 `/api/admin/posts` 返回 403 |
| 12 | 菜品增删改 | 商家能自己加菜、改价、上下架 |
| 13 | 订单能接单 | 商家能看到自己店的订单并接单 |
| 14 | 拒绝能填原因 | 拒绝后商家申请状态变 rejected 且有原因 |

## 八、不要做的事

- **不要让商家接口接受前端传的 shopId**（必须从 token 取）
- **不要让商家访问 `/api/admin/**`**
- 不要让商家改 `school_id` 和 `status`
- 不要把营业执照传到公开目录（用 `/private`）
- 不要引入 Element Plus
- 不要动现有 `Shops.vue` 的平台管理功能（商家后台是新增的，不是替换）
- 不要复用管理端的导航布局给商家（商家要独立的简洁布局）
- 不要在前端做权限判断（后端必须校验）

## 九、交付顺序

1. 先做数据库迁移 + 权限拦截器（地基，最重要）
2. 再做公开申请页 + 提交接口
3. 再做管理端审核页 + 通过/拒绝接口（含自动建号）
4. 再做商家后台三个页面 + 接口
5. 最后做登录跳转和越权测试

**第 1 步做完先自测越权，不通过就别往下做。**

=== 提示词结束 ===

---

## 附：给你的说明

### 为什么权限是重点

商家入驻最大的风险不是功能做不出来，是**越权**：

> 商家 A 登录后，把请求里的 `shopId=2` 改成 `shopId=3`，就能改别人店的菜品价格、看别人店的订单。

这在真实项目里是真实发生过的事故。提示词里我把「shopId 必须从 token 取」写成了铁律。

### 为什么营业执照要私有上传

项目现在的 `/uploads/**` 是完全公开的——任何人拿到链接就能看。

营业执照上有统一社会信用代码、法人姓名、经营地址，属于敏感信息。必须走 `/private` 目录（这个能力项目里已经有了，`UploadService.storePrivate()`）。

### 商家后台为什么要单独一套

管理端是「平台运营」用的，菜单 13 个；商家只需要 4 个（首页/店铺/菜品/订单）。

混在一起的话，商家会看到「用户管理」「内容审核」这些不该看的菜单。分开做更安全，体验也更好。

### 工作量

| 部分 | 时间 |
|---|---|
| 数据库 + 权限拦截器 | 1 天 |
| 公开申请页 | 1 天 |
| 管理端审核页 + 自动建号 | 2 天 |
| 商家后台 4 个页面 | 3 天 |
| 接口 + 联调 + 越权测试 | 2 天 |
| **合计** | **约 9 天** |

### 一个建议

先做「只有申请和审核」，商家后台可以晚一步。

因为申请审核是**获客入口**（商家能自己找上门），商家后台是**运营效率**（现在店少，平台代管也行）。

这样 4 天就能上线入驻能力。
