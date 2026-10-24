import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const read = (path) => readFileSync(resolve(root, path), 'utf8')

test('merchant and admin routes have mutually exclusive server-side role gates', () => {
  const interceptor = read('backend/src/main/java/com/campuslife/config/AdminInterceptor.java')
  const config = read('backend/src/main/java/com/campuslife/config/WebConfig.java')
  assert.match(config, /addPathPatterns\("\/api\/admin\/\*\*",\s*"\/api\/merchant\/\*\*"\)/)
  assert.match(interceptor, /\/api\/merchant\//)
  assert.match(interceptor, /"merchant"\.equals/)
  assert.match(interceptor, /"super"\.equals/)
  assert.match(interceptor, /"school"\.equals/)
  assert.match(interceptor, /shopId/)
})

test('merchant records are scoped by the token shop id in SQL', () => {
  const mapper = read('backend/src/main/java/com/campuslife/mapper/MerchantMapper.java')
  assert.match(mapper, /shop_id\s*=\s*#\{shopId\}/)
  assert.match(mapper, /user_order[\s\S]*shop_id\s*=\s*#\{shopId\}/)
  assert.match(mapper, /shop_goods[\s\S]*shop_id\s*=\s*#\{shopId\}/)
})

test('admin JWT can carry the merchant shop binding', () => {
  const jwt = read('backend/src/main/java/com/campuslife/config/AdminJwtUtil.java')
  const auth = read('backend/src/main/java/com/campuslife/service/AdminAuthService.java')
  assert.match(jwt, /Long shopId/)
  assert.match(jwt, /claim\("shopId",\s*shopId\)/)
  assert.match(auth, /admin\.getShopId\(\)/)
})

test('merchant schema migration guards each added column for repeat execution', () => {
  const sql = read('backend/src/main/resources/db/module-merchant.sql')
  for (const column of ['shop_id', 'contact_phone', 'address', 'owner_name', 'intro', 'open_time', 'close_time', 'is_open']) {
    assert.ok(sql.includes(`column_name='${column}'`), `missing idempotent guard for ${column}`)
  }
  assert.match(sql, /CREATE TABLE IF NOT EXISTS merchant_apply/)
  assert.match(sql, /CREATE TABLE IF NOT EXISTS refund_record/)
  assert.match(sql, /amount_cents INT NOT NULL/)
  assert.match(sql, /out_refund_no VARCHAR\(64\) NOT NULL/)
})

test('public onboarding uploads license images privately without forwarding a stale admin token', () => {
  const view = read('web/src/views/MerchantApply.vue')
  const client = read('web/src/api/index.js')
  const interceptor = read('backend/src/main/java/com/campuslife/config/AdminInterceptor.java')
  const service = read('backend/src/main/java/com/campuslife/service/MerchantService.java')
  assert.match(view, /\/admin\/upload\/private/)
  assert.match(view, /skipAuth:\s*true/)
  assert.match(client, /config\.skipAuth/)
  assert.match(interceptor, /api\/admin\/upload\/private.*noToken\(request\)/)
  assert.match(service, /licenseImage\.startsWith\("\/private\/"\)/)
})

test('order reads use only an authenticated user id, never a query parameter', () => {
  const controller = read('backend/src/main/java/com/campuslife/controller/BizController.java')
  assert.match(controller, /authenticatedUserId\(request\)/)
  assert.match(controller, /request\.setUserId\(authenticatedUserId\(servletRequest\)\)/)
  assert.match(controller, /UnauthorizedException/)
  assert.doesNotMatch(controller, /authenticated\s*==\s*null\s*\?\s*userId/)
})

test('school reviewers without a school binding are denied and merchants can only action paid orders', () => {
  const service = read('backend/src/main/java/com/campuslife/service/MerchantService.java')
  const auth = read('backend/src/main/java/com/campuslife/service/AdminAuthService.java')
  const mapper = read('backend/src/main/java/com/campuslife/mapper/MerchantMapper.java')
  assert.match(auth, /"school"\.equals\(admin\.getRole\(\)\).{0,180}getSchoolId\(\)/s)
  assert.match(service, /"school"\.equals\(role\)\s*&&\s*\(schoolId == null/)
  assert.match(mapper, /status='paid'/)
  assert.match(mapper, /o\.status (?:<>|!=) 'created'/)
})

test('anonymous private uploads are rate limited by the server connection address', () => {
  const upload = read('backend/src/main/java/com/campuslife/controller/UploadController.java')
  assert.match(upload, /limiter\.acquire\(privateUploadKey\(request\)\)/)
  assert.match(upload, /private-guest:" \+ request\.getRemoteAddr\(\)/)
  assert.match(upload, /guest:" \+ request\.getRemoteAddr\(\)/)
  assert.doesNotMatch(upload, /private static String clientKey[\s\S]*?X-Forwarded-For/)
})

test('merchant order filter uses the paid state for waiting orders', () => {
  const view = read('web/src/views/merchant/Orders.vue')
  assert.match(view, /value:\s*'paid',\s*label:\s*'待接单'/)
})

test('paid merchant rejections create one refund and submit it only after cancellation commits', () => {
  const service = read('backend/src/main/java/com/campuslife/service/MerchantService.java')
  const mapper = read('backend/src/main/java/com/campuslife/mapper/MerchantMapper.java')
  const refund = read('backend/src/main/java/com/campuslife/service/MerchantRefundService.java')
  assert.match(service, /TransactionSynchronizationManager\.registerSynchronization/)
  assert.match(service, /public void afterCommit\(\)[\s\S]*?refunds\.submitAfterCommit\(id\)/)
  assert.match(service, /"MR" \+ id/)
  assert.doesNotMatch(service, /updatePayStatus\(id,\s*"refunding"\)/)
  assert.match(mapper, /INSERT IGNORE INTO refund_record/)
  assert.match(refund, /setRefund\(amountCents\)/)
  assert.match(refund, /setTotal\(amountCents\)/)
  assert.match(refund, /queryByOutRefundNo/)
})

test('WeChat refund callbacks are verified, amount-checked, and only success marks payment refunded', () => {
  const callback = read('backend/src/main/java/com/campuslife/controller/MerchantRefundNotifyController.java')
  const states = read('backend/src/main/java/com/campuslife/service/MerchantRefundStatusService.java')
  const config = read('backend/src/main/java/com/campuslife/config/WxPayConfiguration.java')
  assert.match(callback, /new NotificationParser\(config\)\.parse/)
  assert.match(callback, /statusService\.applyNotification\(notification\)/)
  assert.match(states, /n\.longValue\(\) != refundCents \|\| n\.longValue\(\) != totalCents/)
  assert.match(states, /case SUCCESS -> "success"/)
  assert.match(states, /case CLOSED, ABNORMAL -> "failed"/)
  assert.match(states, /case "success" -> "refunded"/)
  assert.match(config, /ConditionalOnProperty\(prefix = "campus\.wxpay", name = "enabled", havingValue = "true"\)/)
})
