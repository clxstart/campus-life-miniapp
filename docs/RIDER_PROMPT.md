# Web 骑手端 · GPT 提示词

> 工程目录：`E:\_verify_completed\campus-life-miniapp`
> 目标：给外卖加配送环节，骑手在网页上抢单、取货、送达
> 前置：外卖支付已完成（订单表和支付表已存在）

---

=== 提示词开始 ===

## 一、你的任务

给校园外卖加**骑手端**。骑手是学生兼职或校内配送员，在电脑网页上操作。

**只做三件事：**

1. **抢单** —— 看到商家已接单、还没人送的订单，抢下来
2. **取货** —— 到店拿到餐，点「已取货」
3. **送达** —— 送到学生手上，点「已送达」

**明确不做：**

- ❌ 实时位置追踪（不上报 GPS、不显示地图）
- ❌ 路线规划和导航
- ❌ 配送进度条 / 预计到达时间
- ❌ 骑手评分和奖惩

骑手只需要知道「去哪个店拿、送到哪、电话多少」，点三个按钮就行。

## 二、项目背景

- 后端：Spring Boot 3.2.5 + MyBatis-Plus 3.5.6 + MySQL 8，包名 `com.campuslife`
- 管理端：Vue 3 + Vite + Tailwind，**不用 Element Plus**
- 数据库：`campus_life`，账号 `root`
- 统一返回 `{code, msg, data}`，成功 `code=0`
- 项目约定：读接口兜底、写接口不兜底
- 商家端已经在做（`/api/merchant/**`），骑手端是另一套 `/api/rider/**`

## 三、动手前必须先读这些文件

1. `backend/.../config/AdminInterceptor.java` —— 现有鉴权（要扩展骑手角色）
2. `backend/.../service/AdminAuthService.java` —— 登录与 JWT
3. `backend/.../service/BizService.java` —— 订单逻辑
4. `backend/.../entity/Order.java`（或对应实体）—— 订单实体
5. `web/src/router/index.js` —— 路由
6. `web/src/views/Shops.vue` —— 样式参考
7. `web/src/views/Login.vue` —— 登录页
8. `backend/src/main/resources/db/` —— 迁移脚本风格
9. `docs/FOOD_PAYMENT_PROMPT.md` —— 支付和订单状态机（如果已实现，看现有状态值）

## 四、现有结构

### 4.1 订单表 `user_order`

```sql
id, user_id, shop_id, items, address, remark,
subtotal, packing_fee, delivery_fee, discount_amount, total,
status varchar(16) DEFAULT 'created', created_at
```

如果支付功能已做完，还会有：`pay_status`、`paid_at`、`out_trade_no`、`transaction_id` 等。

**缺的字段：** 没有骑手归属、没有取货时间、没有送达时间。

### 4.2 账号表 `admin_user`

```sql
id, username, password_hash, real_name, role, school_id,
status, fail_count, locked_until, last_login_at, created_at
```

`role` 目前有 `super` / `school`，商家端会加 `merchant`。**骑手要加 `rider`。**

### 4.3 店铺表 `shop`

有 `name`、`address`（商家端会加）、`contact_phone`（商家端会加）。

**如果商家端还没做完，`address` 和 `contact_phone` 可能不存在**——那就先在本提示词里补上，或者骑手端只显示店铺名。

## 五、要做什么

### 5.1 数据库迁移

新建 `backend/src/main/resources/db/module-rider.sql`，**可重复执行**。

**A. `user_order` 加字段**

```sql
rider_id     bigint   NULL   -- 接单的骑手（admin_user.id）
rider_name   varchar(32) DEFAULT ''   -- 冗余存名字，方便列表展示
accepted_at  datetime NULL   -- 骑手接单时间
picked_at    datetime NULL   -- 取货时间
delivered_at datetime NULL   -- 送达时间
```

索引：`idx_rider (rider_id, status)`

**B. `admin_user` 加字段**

```sql
rider_status varchar(16) DEFAULT 'idle'   -- idle 空闲 / busy 配送中
```

**C. 插入测试骑手账号（可选）**

用户名 `rider01`，密码用 BCrypt 加密的 `rider123`，role=`rider`，school_id=1。

### 5.2 订单状态机扩展

现在状态是：`created → paid → accepted → delivering → completed`

**要插入骑手环节，改成：**

```
created（待支付）
  ↓ 支付成功
paid（待商家接单）
  ↓ 商家接单
accepted（待骑手接单）      ← 进入骑手抢单池
  ↓ 骑手抢单
delivering（骑手配送中）
  ↓ 骑手到店取货
picked（已取货，配送中）
  ↓ 骑手送达
completed（已完成）

异常分支：
cancelled（已取消）
refunded（已退款）
```

**关键：`accepted` 状态 = 商家已接单、还没骑手，这个状态才进抢单池。**

**状态流转校验：**

```java
// 只有 accepted 且 rider_id 为空的订单能被抢
// 只有 delivering 状态能点「已取货」
// 只有 picked 状态能点「已送达」
// 骑手只能操作自己接的单
```

### 5.3 骑手端页面

新建目录 `web/src/views/rider/`，四个页面。

#### 5.3.1 登录跳转

骑手用同一个登录页 `/login`。登录后按角色跳转：

```js
if (role === 'rider') {
  router.push('/rider')
} else if (role === 'merchant') {
  router.push('/merchant')
} else {
  router.push('/dashboard')
}
```

**骑手访问 `/dashboard`、`/api/admin/**` 要拦回 `/rider`。**

#### 5.3.2 骑手首页 `rider/Home.vue`

**路由：** `/rider`

**布局：** 顶部显示「骑手工作台」+ 骑手姓名 + 退出按钮。左侧竖排菜单（待抢单 / 我的配送 / 配送记录）。

**首页内容：**

```
┌─────────────────────────────────────┐
│ 我的状态：空闲中 / 配送中            │
├─────────────────────────────────────┤
│ 今日数据                             │
│ 已完成 8 单 | 配送中 2 单 | 待抢 5 单│
├─────────────────────────────────────┤
│ 快捷入口                             │
│ [去抢单] [我的配送]                  │
└─────────────────────────────────────┘
```

#### 5.3.3 待抢单池 `rider/Pool.vue`

**这是骑手最常用的页面。**

**列表内容（每张卡片）：**

| 信息 | 说明 |
|---|---|
| 店铺名 + 店铺地址 | 去哪里取餐 |
| 收货地址 | 送到哪里 |
| 学生电话 | 到楼下打电话（**脱敏显示 138****8000，点击可看完整**） |
| 菜品摘要 | 如「黄焖鸡×1、可乐×1」 |
| 距离/预计 | **不做，留空或显示「约 1.5km」静态文案** |
| 配送费 | 骑手能赚多少 |
| 按钮 | **抢单** |

**排序：** 按下单时间正序（先下单的先送）。

**筛选：** 按学校（骑手只看本校的单）。

**刷新：** 自动每 10 秒刷新一次（骑手盯着屏幕等单），页面隐藏时停止。

**抢单逻辑：**

```js
async grab(orderId) {
  try {
    await api.grabOrder(orderId)
    popup.toast(this, '抢单成功', { tone: 'success' })
    this.loadPool()      // 刷新池子
    this.loadMine()      // 刷新我的配送
  } catch (error) {
    // 别人抢走了
    popup.toast(this, error.message || '手慢了，这单被抢了', { tone: 'warn' })
    this.loadPool()
  }
}
```

**并发控制**：后端抢单必须用乐观锁或 `UPDATE ... WHERE rider_id IS NULL`，防止两个人同时抢到。

#### 5.3.4 我的配送 `rider/Mine.vue`

显示 `rider_id = 我` 且状态是 `delivering` 或 `picked` 的订单。

**按状态分两栏：**

**第一栏：待取货（`delivering`）**

| 信息 | 说明 |
|---|---|
| 店铺名 + 地址 + 电话 | 去这里取餐 |
| 收货地址 + 学生电话 | 送到这里 |
| 菜品 | |
| 下单时间 | |
| 按钮 | **已取货** |

**第二栏：配送中（`picked`）**

| 信息 | 说明 |
|---|---|
| 收货地址 + 学生电话 | 送到这里 |
| 菜品 | |
| 取货时间 | |
| 按钮 | **已送达** |

**操作：**

- 点「已取货」→ 订单状态变 `picked`，`picked_at` 记时间
- 点「已送达」→ 订单状态变 `completed`，`delivered_at` 记时间，骑手状态变回 `idle`

**都要二次确认**，避免误点。

#### 5.3.5 配送记录 `rider/History.vue`

- 已完成订单列表
- 筛选：日期范围
- 显示：订单号、店铺、地址、送达时间、配送费
- 底部汇总：本月完成 N 单，共赚 ¥XXX

### 5.4 后端接口

#### 骑手接口（`/api/rider`，仅 `rider` 角色）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/rider/stats` | 今日数据（完成数、配送中、待抢数） |
| GET | `/rider/pool` | 待抢单池（`accepted` + `rider_id IS NULL`，按校过滤） |
| POST | `/rider/orders/{id}/grab` | 抢单 |
| GET | `/rider/orders/mine` | 我的配送（`delivering` + `picked`） |
| POST | `/rider/orders/{id}/pick` | 已取货 |
| POST | `/rider/orders/{id}/deliver` | 已送达 |
| GET | `/rider/orders/history` | 配送记录（`completed`，分页） |

#### 管理端接口（`/api/admin`，super/school）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/riders` | 骑手列表 |
| POST | `/admin/riders` | 新增骑手（用户名/姓名/学校/密码） |
| POST | `/admin/riders/{id}/status` | 启用/停用 |
| GET | `/admin/orders` | 订单列表（已有的话复用，加骑手筛选） |

**管理端要有「骑手管理」页面** `web/src/views/Riders.vue`：

- 列表：用户名、姓名、学校、状态、今日单量、操作
- 新增弹窗：用户名、姓名、学校、初始密码
- 操作：启用 / 停用 / 重置密码

### 5.5 抢单的并发安全（重点）

两个骑手同时点「抢单」，不能都成功。

**正确写法：**

```java
// 用条件更新，只有 rider_id 还是空的时候才更新成功
int rows = orderMapper.grabOrder(orderId, riderId, riderName);
if (rows == 0) {
    throw new IllegalStateException("手慢了，这单已被抢走");
}
```

```sql
UPDATE user_order
SET rider_id = #{riderId},
    rider_name = #{riderName},
    status = 'delivering',
    accepted_at = NOW()
WHERE id = #{orderId}
  AND status = 'accepted'
  AND rider_id IS NULL
```

**`rows == 0` 就说明被抢了。** 不要先查再改，那是竞态。

### 5.6 和小程序端的联动

骑手操作后，学生要能看到状态变化。

| 骑手操作 | 小程序表现 |
|---|---|
| 抢单 | 学生订单变「骑手已接单，正在赶来」 |
| 已取货 | 学生订单变「骑手已取餐，正在配送」 |
| 已送达 | 学生订单变「已完成」 |

**实现方式：** 学生订单详情页每 3 秒轮询（支付提示词里已经写了轮询方案），不需要 WebSocket。

**学生订单页要显示骑手信息：** 骑手姓名 + 电话（脱敏），方便联系。

### 5.7 状态文案对照

| 后端状态 | 骑手端显示 | 学生端显示 |
|---|---|---|
| `accepted` | 待抢单 | 商家已接单，等待骑手 |
| `delivering` | 待取货 | 骑手已接单，正在赶来 |
| `picked` | 配送中 | 骑手已取餐，正在配送 |
| `completed` | 已完成 | 已送达 |

## 六、验收标准

| # | 检查项 | 通过标准 |
|---|---|---|
| 1 | 后端能编译 | Maven BUILD SUCCESS |
| 2 | 迁移脚本可重复执行 | 连跑两次不报错 |
| 3 | 骑手能登录 | 用 rider01/rider123 登录，跳转 `/rider` |
| 4 | 骑手进不了管理端 | 访问 `/api/admin/posts` 返回 403 |
| 5 | 商家接单后进池 | 商家接单，订单出现在骑手待抢池 |
| 6 | 能抢单 | 点抢单，订单变 `delivering`，从池子里消失 |
| 7 | **并发抢单只有一人成功** | 两个骑手同时抢，只有一个成功，另一个提示「手慢了」 |
| 8 | 能取货 | 点已取货，状态变 `picked` |
| 9 | 能送达 | 点已送达，状态变 `completed`，骑手状态回 `idle` |
| 10 | **只能操作自己的单** | 骑手 A 请求骑手 B 的订单，被拒绝 |
| 11 | 学生能看到状态 | 骑手取货后，学生订单页 3 秒内变「正在配送」 |
| 12 | 管理端能建骑手 | 新增骑手账号，能用它登录 |
| 13 | 池子按校隔离 | 骑手只看本校的订单 |

## 七、不要做的事

- **不要做实时位置追踪**（不做 GPS 上报、不做地图、不做路线）
- **不要做配送进度条或预计到达时间**
- 不要让骑手访问 `/api/admin/**`
- 不要让骑手操作别人的订单
- 不要用「先查再改」实现抢单（必须用条件 UPDATE）
- 不要在学生端显示骑手完整手机号（脱敏）
- 不要引入 Element Plus
- 不要和商家端混在同一套布局（骑手是独立的一套）

## 八、交付顺序

1. 数据库迁移 + 状态机扩展 + 权限（地基）
2. 骑手登录跳转 + 空壳页面
3. 待抢单池 + 抢单接口（含并发控制）
4. 我的配送 + 取货/送达接口
5. 配送记录 + 统计
6. 管理端骑手管理页
7. 学生端状态联动

**第 3 步做完先做并发测试，两个浏览器同时抢，只允许一个成功。**

=== 提示词结束 ===

---

## 附：给你的说明

### 为什么不做位置追踪

位置追踪要三样东西：GPS 上报（耗电、要权限）、地图服务（要钱）、实时推送（要 WebSocket）。

校园场景其实不需要——学校就那么大，骑手知道店在哪、宿舍在哪就够了。

**砍掉这些，工作量从 20 天降到 5 天左右。**

### 为什么抢单要写并发控制

这是最容易出事的地方。如果不做，两个骑手同时抢同一单，两个都显示「抢单成功」，结果一个人白跑一趟。

提示词里我写的是条件 UPDATE（`WHERE rider_id IS NULL`），`rows==0` 就说明被别人抢了。

### 骑手账号从哪来

建议先做「平台在管理端创建骑手账号」，不做骑手自己注册。

原因：骑手涉及钱（配送费），需要审核身份，不能谁都能注册。等规模大了再做注册申请。

### 骑手能赚多少钱

提示词里「配送费」显示的是订单的 `delivery_fee`。实际给骑手多少，可以：

- 全额给骑手（简单，平台不抽）
- 按比例抽成（如平台抽 20%）
- 固定单价（如每单 2 元，跟配送费无关）

**建议先做「全额给骑手」**，简单清晰，后面要抽成再加个配置。

### 工作量

| 部分 | 时间 |
|---|---|
| 数据库 + 状态机 + 权限 | 1 天 |
| 待抢单池 + 抢单（含并发） | 1 天 |
| 我的配送 + 取货/送达 | 1 天 |
| 配送记录 + 统计 | 0.5 天 |
| 管理端骑手管理 | 1 天 |
| 学生端联动 | 0.5 天 |
| **合计** | **约 5 天** |
