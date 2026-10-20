# 外卖支付功能开发 · GPT 提示词

> 工程目录：`E:\_verify_completed\campus-life-miniapp`
> 支付方式：微信小程序 JSAPI 支付
> 技术选型：**WxJava（weixin-java-pay）**

---

=== 提示词开始 ===

## 一、你的任务

给一个已有的校园生活小程序，接上**微信支付**。范围只做「**外卖下单**」这一个场景：

学生点外卖 → 提交订单 → 拉起微信支付 → 付完钱 → 订单进入商家待接单状态。

**不要做分账**（给商家/骑手分钱）——这个需要商户号满 90 天才能申请，现在做不了。钱先全部进平台账户，商家结算走线下人工。

## 二、项目背景

- 小程序：微信原生（WXML/WXSS/JS/JSON），AppID `touristappid`，共 28 个页面
- 后端：Spring Boot 3.2.5 + MyBatis-Plus 3.5.6 + MySQL 8，包名 `com.campuslife`
- 管理端：Vue 3 + Vite + Tailwind
- 数据库：`campus_life`，账号 `root`
- 统一返回格式：`{code, msg, data}`，成功时 `code=0`

## 三、动手前必须先读这些文件

1. `backend/.../controller/BizController.java` —— 现在只有 `POST /api/orders` 一个下单接口
2. `backend/.../service/BizService.java` —— 下单逻辑
3. `backend/.../dto/CreateOrderRequest.java` —— 下单入参
4. `pages/food-order/food-order.js` + `.wxml` —— 结算页，支付要在这里触发
5. `pages/food-shop/food-shop.js` —— 店铺与菜单
6. `utils/api.js` —— 前端接口封装（注意「读兜底、写不兜底」写法）
7. `backend/src/main/resources/application.yml` —— 配置项放这里
8. `backend/.../config/GlobalExceptionHandler.java` —— 异常处理
9. `backend/src/main/resources/db/` —— 数据库脚本风格（按模块分文件、可重复执行）

## 四、现有数据库结构（重要）

`user_order` 表**已经存在**，当前字段：

```sql
id              bigint        主键自增
user_id         bigint        下单用户
shop_id         bigint        店铺
items           text          菜品明细(JSON)
address         varchar(128)  收货地址
remark          varchar(200)  备注
subtotal        decimal(10,2) 小计
packing_fee     decimal(10,2) 打包费
delivery_fee    decimal(10,2) 配送费
discount_amount decimal(10,2) 优惠
total           decimal(10,2) 合计
status          varchar(16)   状态，默认 'created'
created_at      datetime      创建时间
```

**三个必须注意的点：**

1. **没有任何支付字段** —— 需要新增（见下面迁移脚本）
2. **金额是 `decimal(10,2)`，单位是「元」** —— 微信支付要求单位是「分」（整数）。这是最容易出错的地方，差 100 倍。
3. **`status` 是自由文本** —— 现在没有状态机约束，需要规范化

## 五、要做什么

### 5.1 引入 WxJava

在 `backend/pom.xml` 加依赖（版本请选当前稳定版，例如 4.6.x）：

```xml
<dependency>
    <groupId>com.github.binarywang</groupId>
    <artifactId>weixin-java-pay</artifactId>
    <version>4.6.0</version>
</dependency>
```

**为什么要用它**：它把签名、验签、平台证书自动更新、V3 接口都封装好了。自己手写签名极容易出错，且证书 5 年一换，手动维护会挂。

### 5.2 配置项

在 `application.yml` 加一段（**不要把真实密钥写进代码**）：

```yaml
wxpay:
  enabled: false          # 没配好商户号时保持 false，用模拟支付
  app-id: ''              # 小程序 AppID
  mch-id: ''              # 商户号
  api-v3-key: ''          # APIv3 密钥（32位）
  cert-serial-no: ''      # 商户证书序列号
  private-key-path: ''    # 商户私钥 apiclient_key.pem 路径
  notify-url: 'https://你的域名/api/pay/notify'   # 必须 HTTPS 且备案
  sandbox: false
```

**关键设计**：加一个 `enabled` 开关。没配好商户号时，支付接口走「模拟成功」分支，这样开发和演示不受阻。这是必须的，否则没商户号就完全测不了。

### 5.3 数据库迁移

新建 `backend/src/main/resources/db/module-payment.sql`，要求**可重复执行**（用 `information_schema` 判断字段/表是否存在，参考项目里 `module-learn.sql` 的写法）：

**A. 给 `user_order` 加字段**

```sql
-- 金额一律改成「分」，整数。原 total 等字段保留做展示，另存 *_cents
pay_amount_cents   int          应付金额(分)
out_trade_no       varchar(64)  平台订单号(唯一)
transaction_id     varchar(64)  微信交易号(唯一，回调幂等用)
prepay_id          varchar(64)  预支付ID
pay_status         varchar(16)  支付状态: unpaid/paid/refunding/refunded/closed
paid_at            datetime     支付成功时间
cancelled_at       datetime     取消时间
cancel_reason      varchar(100) 取消原因
```

索引：`out_trade_no` 唯一索引、`transaction_id` 唯一索引、`pay_status` 普通索引

**B. 新建 `payment_record`（回调流水表）**

```sql
id              bigint      主键
out_trade_no    varchar(64) 平台订单号
transaction_id  varchar(64) 微信交易号(唯一)
amount_cents    int         金额(分)
trade_state     varchar(32) 微信返回的交易状态
raw_body        text        原始回调报文(排障用)
verified        tinyint     是否验签通过
created_at      datetime
```

**为什么要有这张表**：回调可能重复、可能伪造。留原始报文，出问题时能查。

**C. 新建 `refund_record`（退款表）**

```sql
id              bigint      主键
order_id        bigint      订单
out_refund_no   varchar(64) 退款单号
refund_id       varchar(64) 微信退款单号
amount_cents    int         退款金额(分)
reason          varchar(200) 退款原因
status          varchar(16) pending/success/failed
created_at      datetime
```

### 5.4 订单状态机

必须严格按这个流转，不允许跳步：

```
created（待支付）
   ├─ 支付成功 → paid（待接单）
   ├─ 用户取消 → cancelled
   └─ 超时15分钟 → closed（自动关闭）

paid（待接单）
   ├─ 商家接单 → accepted
   └─ 商家拒单/用户退款 → refunding → refunded

accepted（已接单）→ delivering（配送中）→ completed（已完成）
```

**实现要求**：写一个 `OrderStatus` 枚举 + `canTransit(from, to)` 校验方法。任何状态变更必须走校验，非法流转抛异常。

### 5.5 后端接口

在新建的 `PayController`（`@RequestMapping("/api")`）里实现：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/orders/{id}/pay` | 发起支付，返回小程序拉起支付所需参数 |
| POST | `/pay/notify` | 微信异步回调（**必须免登录鉴权**） |
| GET | `/orders/{id}` | 查订单详情（含支付状态） |
| POST | `/orders/{id}/cancel` | 取消未支付订单 |
| POST | `/orders/{id}/refund` | 申请退款 |

管理端（`/api/admin`，需鉴权）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/orders` | 订单列表（按状态/店铺/时间筛选、分页） |
| POST | `/admin/orders/{id}/status` | 改状态（接单/拒单/完成） |
| POST | `/admin/orders/{id}/refund` | 管理端退款 |

**`POST /orders/{id}/pay` 返回结构**（小程序 `wx.requestPayment` 需要这几个字段）：

```json
{
  "code": 0,
  "data": {
    "timeStamp": "1729000000",
    "nonceStr": "abc123",
    "package": "prepay_id=wx1234567890",
    "signType": "RSA",
    "paySign": "xxxxx"
  }
}
```

### 5.6 小程序端

在 `pages/food-order/food-order.js` 里，用户点「提交订单」后：

```js
// 1. 先创建订单（复用现有 POST /api/orders）
const order = await api.createOrder({...})

// 2. 再发起支付
const payParams = await api.payOrder(order.id)

// 3. 拉起微信支付
wx.requestPayment({
  ...payParams,
  success: () => {
    // 注意：这里只是「用户点了确认」，真正到账要等后端回调
    // 所以轮询查订单状态，不要直接认为支付成功
    this.pollOrderStatus(order.id)
  },
  fail: (err) => {
    if (err.errMsg.includes('cancel')) {
      popup.toast(this, '已取消支付', { tone: 'info' })
    } else {
      popup.toast(this, '支付失败，请重试', { tone: 'error' })
    }
  }
})
```

**关键**：`wx.requestPayment` 的 success **不等于**支付成功，只是用户完成了操作。必须以**后端回调**为准。所以要轮询查订单状态（每 2 秒查一次，最多 10 次）。

在 `utils/api.js` 加：

```js
// 发起支付：写接口，不能兜底
function payOrder(orderId) {
  return request(`/orders/${orderId}/pay`, 'POST', {})
}

// 查订单状态：读接口，可以兜底
function getOrderDetail(orderId) {
  return request(`/orders/${orderId}`, 'GET').catch(error => {
    if (error.server) throw error
    return wait({ id: orderId, payStatus: 'unknown' })
  })
}
```

### 5.7 管理端：订单页 + 支付流水页

管理端要做**两个页面**，视角不同：

| 页面 | 谁用 | 看什么 |
|---|---|---|
| 订单管理 `Orders.vue` | 运营 | 订单流转：接单、拒单、配送、完成 |
| 支付流水 `Payments.vue` | 财务/老板 | 钱的进出：交易记录、对账、退款 |

两个页面都要做，不能只做一个。

#### 5.7.1 订单管理页 `web/src/views/Orders.vue`

**路由注册**（`web/src/router/index.js`）：

```js
{
  path: '/orders',
  name: 'Orders',
  component: () => import('../views/Orders.vue'),
  meta: { title: '订单管理', icon: '☰', superOnly: false }
}
```

**筛选栏：**
- 订单状态下拉：全部 / 待支付 / 待接单 / 已接单 / 配送中 / 已完成 / 已取消 / 已退款
- 店铺下拉
- 日期范围（下单时间）
- 关键词搜索（订单号 / 用户昵称）
- 重置按钮

**表格列：**

| 列 | 内容 |
|---|---|
| 订单号 | `out_trade_no`，点击可看详情 |
| 用户 | 昵称 + ID |
| 店铺 | 店铺名 |
| 商品 | 菜品数量摘要，如「3 件」 |
| 金额 | `¥29.90` |
| 状态 | 彩色标签 |
| 下单时间 | `yyyy-MM-dd HH:mm` |
| 操作 | 接单 / 拒单 / 完成 / 退款 |

**操作规则：**
- 只有 `paid`（待接单）状态能「接单」或「拒单」
- 只有 `accepted` / `delivering` 状态能「完成」
- 只有 `paid` 及之后的状态能「退款」
- 已完成的订单操作按钮置灰

**状态标签配色：**

| 状态 | 颜色 |
|---|---|
| created（待支付） | 灰 `bg-gray-100 text-gray-600` |
| paid（待接单） | 橙 `bg-orange-100 text-orange-700` |
| accepted（已接单） | 蓝 `bg-blue-100 text-blue-700` |
| delivering（配送中） | 靛 `bg-indigo-100 text-indigo-700` |
| completed（已完成） | 绿 `bg-green-100 text-green-700` |
| cancelled（已取消） | 灰 `bg-gray-100 text-gray-500` |
| refunded（已退款） | 红 `bg-red-100 text-red-700` |

**分页**：每页 20 条。**空状态**：「暂时没有订单」。

#### 5.7.2 支付流水页 `web/src/views/Payments.vue`（重点，别漏了）

**这是财务视角的页面**，老板要看「这个月收了多少钱、有没有对不上的账」。

**路由注册**：

```js
{
  path: '/payments',
  name: 'Payments',
  component: () => import('../views/Payments.vue'),
  meta: { title: '支付流水', icon: '¥', superOnly: false }
}
```

**页面布局：**

```
┌──────────────────────────────────────────────┐
│ 标题：支付流水                                │
│ 副标题：交易记录 · 对账 · 退款                │
├──────────────────────────────────────────────┤
│ 统计卡片（5 个横排）                          │
│ 今日交易额 | 今日订单 | 累计交易额 | 待对账 | 退款金额 │
├──────────────────────────────────────────────┤
│ 筛选栏：日期范围 / 支付状态 / 店铺 / 关键词    │
│                              [导出 CSV] 按钮  │
├──────────────────────────────────────────────┤
│ 表格                                          │
├──────────────────────────────────────────────┤
│ 分页                                          │
└──────────────────────────────────────────────┘
```

**统计卡片（5 个）：**

| 卡片 | 数据来源 | 说明 |
|---|---|---|
| 今日交易额 | 今日 `pay_status='paid'` 的 `pay_amount_cents` 求和 | 转成元显示，如 `¥1,286.50` |
| 今日订单数 | 今日支付的订单条数 | 整数 |
| 累计交易额 | 全部已支付订单金额求和 | 转成元 |
| 待对账 | `payment_record` 中 `verified=0` 的条数 | 红色数字，>0 时高亮 |
| 退款金额 | `refund_record` 中 `status='success'` 的金额求和 | 转成元 |

**筛选栏：**
- 日期范围（支付时间，默认最近 7 天）
- 支付状态下拉：全部 / 已支付 / 已退款 / 已关闭
- 店铺下拉
- 关键词搜索（订单号 / 微信交易号 / 用户昵称）
- 「导出 CSV」按钮

**表格列：**

| 列 | 内容 | 说明 |
|---|---|---|
| 订单号 | `out_trade_no` | 等宽字体 |
| 微信交易号 | `transaction_id` | 等宽字体，过长省略中间 |
| 用户 | 昵称 + ID | |
| 店铺 | 店铺名 | |
| 金额 | `¥29.90` | 由 `pay_amount_cents / 100` 得来 |
| 支付状态 | 彩色标签 | paid / refunded / closed |
| 对账状态 | 正常 / **异常** | `verified=1` 显示「正常」绿标；`verified=0` 显示「**异常**」红色高亮 |
| 支付时间 | `yyyy-MM-dd HH:mm:ss` | |
| 操作 | 详情 / 退款 | |

**异常行整行标红**：当 `verified=0` 时，整行背景用 `bg-red-50`，对账状态列显示红色「异常」标签，鼠标悬停提示「**回调未验签，需人工核对**」。

**详情抽屉（点「详情」打开）：**

分三块展示：

1. **订单信息**
   - 订单号、微信交易号、prepay_id
   - 用户、店铺、收货地址
   - 商品明细（`items` JSON 解析后列表展示）
   - 金额明细：小计 / 打包费 / 配送费 / 优惠 / **实付**

2. **支付回调记录**（来自 `payment_record`）
   - 表格：回调时间、微信交易状态、金额、是否验签通过
   - 可展开查看 `raw_body` 原始报文（`<pre>` 等宽字体）
   - 如果 `verified=0`，顶部红色警告：「**这条回调未通过验签，可能是伪造请求或证书配置错误，需人工核对**」

3. **退款记录**（来自 `refund_record`）
   - 表格：退款单号、退款金额、原因、状态、时间
   - 没有则显示「无退款记录」

**导出 CSV：**

- 按当前筛选条件导出
- 列：订单号、微信交易号、用户ID、店铺、金额(元)、支付状态、对账状态、支付时间
- 文件名：`支付流水_2026-10-01_2026-10-19.csv`
- 用后端生成，前端触发下载（`window.open` 或 blob）

**后端接口（配套实现）：**

在 `AdminController` 或新建 `AdminPaymentController`（`@RequestMapping("/api/admin")`，走 `AdminInterceptor` 鉴权）：

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| GET | `/payments` | `startDate, endDate, payStatus, shopId, keyword, page, size` | 流水列表（分页） |
| GET | `/payments/stats` | `startDate, endDate` | 5 个统计卡片数据 |
| GET | `/payments/{outTradeNo}` | — | 详情（含 payment_record + refund_record） |
| GET | `/payments/export` | 同列表筛选参数 | 导出 CSV |

**列表返回结构：**

```json
{
  "code": 0,
  "data": {
    "list": [
      {
        "outTradeNo": "CL202610191530001",
        "transactionId": "4200001234202610191234567890",
        "userId": 1,
        "userName": "张三",
        "shopId": 2,
        "shopName": "二食堂快餐",
        "amountCents": 2990,
        "payStatus": "paid",
        "verified": 1,
        "paidAt": "2026-10-19 15:30:12"
      }
    ],
    "total": 128,
    "page": 1,
    "size": 20
  }
}
```

**统计返回结构：**

```json
{
  "code": 0,
  "data": {
    "todayAmountCents": 128650,
    "todayOrderCount": 43,
    "totalAmountCents": 5689200,
    "unverifiedCount": 2,
    "refundAmountCents": 4500
  }
}
```

**数据隔离：** 学校管理员只能看到**本校店铺**的流水（用 `shop.school_id` 关联过滤），超管看全部。

#### 5.7.3 样式要求（两个页面通用）

**不要引入 Element Plus**（项目刻意没用）。

复用项目已有风格：

- 卡片：`bg-white rounded-[2rem] shadow-sm p-6`
- 主色：`#3476f6`
- 文字：主 `#18212b`，次 `#737e88`
- 表格：参考 `web/src/views/Posts.vue` 的写法
- 弹窗/抽屉：用项目已有的 `NoModal` 组件，不要用原生 `alert` / `confirm`
- 标签：`px-2 py-1 rounded text-xs`
- 金额统一右对齐，等宽字体

#### 5.7.4 交互细节

- 所有写操作（接单/拒单/退款）都要**二次确认**
- 操作成功后**局部刷新**，不要整页 reload
- 操作失败要显示后端返回的 `msg`，不要只说「操作失败」
- 表格加载时显示骨架屏或 loading 遮罩
- 退款弹窗要填退款原因，并显示「将退款 ¥29.90」的确认文案

## 六、三条铁律（做不到就别上线）

### 铁律一：回调必须验签

```java
// 错误做法（会被人伪造支付成功，白送外卖）
String outTradeNo = request.getParameter("out_trade_no");
orderService.markPaid(outTradeNo);

// 正确做法
String body = readBody(request);
if (!wxPayService.verifyNotify(body, headers)) {
    return "FAIL";  // 验签失败直接拒
}
```

### 铁律二：回调必须幂等

微信会**重复推送**同一个回调。必须用 `transaction_id` 做唯一键：

```java
// 先查 payment_record 表里有没有这个 transaction_id
if (paymentRecordMapper.existsByTransactionId(transactionId)) {
    return "SUCCESS";  // 已处理过，直接返回成功，不要重复改订单
}
```

### 铁律三：必须主动对账

回调可能丢（网络问题、服务重启）。必须有一个定时任务：

```java
// 每分钟执行
// 找出 pay_status = 'unpaid' 且创建超过 5 分钟的订单
// 主动调用微信「查询订单」接口
// 如果微信说已支付，就补一条 payment_record 并改状态
```

**没有对账 = 用户付了钱但订单没变，这是最严重的生产事故。**

## 七、金额单位（最容易出错）

```java
// 数据库 decimal(10,2) 存的是「元」：29.90
// 微信要的是「分」：2990

int cents = new BigDecimal("29.90").multiply(new BigDecimal(100)).intValue();  // 2990

// 反方向展示
BigDecimal yuan = new BigDecimal(cents).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
```

**规则：所有跟微信交互的地方用「分」（int），数据库里展示用的字段保持「元」（decimal）。新增的 `pay_amount_cents` 存分。**

## 八、验收标准

| # | 检查项 | 通过标准 |
|---|---|---|
| 1 | 后端能编译 | Maven BUILD SUCCESS |
| 2 | 迁移脚本可重复执行 | 连跑两次不报错 |
| 3 | 没配商户号也能跑 | `wxpay.enabled=false` 时，下单后订单直接变 paid（模拟） |
| 4 | 配了商户号能拉起支付 | 小程序点支付弹出微信支付界面 |
| 5 | 回调验签 | 伪造的回调报文返回 FAIL，订单不变 |
| 6 | 回调幂等 | 同一回调推 3 次，订单只变一次 |
| 7 | 金额正确 | 订单 29.90 元，微信收到 2990 分 |
| 8 | 对账生效 | 手动把订单改成 unpaid 但微信已付，定时任务能修正 |
| 9 | 退款 | 能退全款，状态变 refunded |
| 10 | 管理端能看 | 订单列表、筛选、改状态都正常 |
| 11 | 状态机 | 非法流转（如 created 直接跳 completed）被拒绝 |

## 九、不要做的事

- 不要动现有的 `POST /api/orders` 接口签名（前端在调用）
- 不要为了省事跳过回调验签
- 不要用 `total`（元）直接传给微信
- 不要做分账（等商户号满 90 天）
- 不要把商户号、APIv3 密钥写进代码或提交到 git
- 不要在 `wx.requestPayment` 的 success 里直接标记订单已支付
- 不要改现有的 `user_order` 已有字段名（管理端在读取）

## 十、建议的交付顺序

1. 先做数据库迁移 + 状态机枚举（地基）
2. 再做「模拟支付」分支（`enabled=false`），让整个流程能跑通、能测试
3. 然后接真实 WxJava，配商户号验证
4. 最后做对账 + 退款 + 管理端页面

**这样即使商户号还没下来，前两步也能做完并测试。**

=== 提示词结束 ===

---

## 附：给你的说明

### 为什么强调「模拟支付」开关

你的商户号可能还要等几天。如果没有这个开关，GPT 写完代码你也测不了，只能干等。有了它，可以先把整个流程跑通、把前端调好，商户号下来只需填配置。

### 为什么不做分账

分账要求商户号满 90 天。现在做等于白做——代码写完也用不了。先让钱全额进平台账户，商家结算用微信/银行卡人工转，量小完全没问题。

### 90 天后要做什么

商户号满 90 天后，去微信商户平台申请「分账」权限，然后：
- 给每个商家建一个分账接收方
- 支付时标记「这笔钱里 X 元分给商家 Y」
- 微信直接把钱分到商家账户，平台只拿佣金

这部分提示词里没写，等 90 天后再让我补一份。

### 上线前必须确认

1. **域名备案了吗** —— 没备案，小程序连不上后端，回调也收不到
2. **HTTPS 证书配了吗** —— 微信支付回调必须 HTTPS
3. **商户号绑定小程序 AppID 了吗** —— 不绑定拉不起支付
