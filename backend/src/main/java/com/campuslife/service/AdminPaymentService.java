package com.campuslife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campuslife.entity.PaymentRecord;
import com.campuslife.entity.RefundRecord;
import com.campuslife.entity.Shop;
import com.campuslife.entity.User;
import com.campuslife.entity.UserOrder;
import com.campuslife.mapper.PaymentRecordMapper;
import com.campuslife.mapper.RefundRecordMapper;
import com.campuslife.mapper.ShopMapper;
import com.campuslife.mapper.UserMapper;
import com.campuslife.mapper.UserOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminPaymentService {
    private final UserOrderMapper orderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final ShopMapper shopMapper;
    private final UserMapper userMapper;
    private final PayService payService;
    private final com.campuslife.mapper.AdminPaymentMapper paymentMapper;

    /** 关键词最长 32 字符（昵称上限 32、订单号/交易号 ≤ 32），超出直接拒绝，避免超长模糊匹配 */
    static final int MAX_KEYWORD = 32;
    /** 深分页上限：OFFSET 过大时扫描行数线性增长，引导用户缩小日期范围 */
    static final long MAX_OFFSET = 10_000;
    /** 单次导出上限；超过时明确拒绝并提示缩小范围，而不是静默截断（finding-06） */
    public static final int MAX_EXPORT_ROWS = 50_000;
    static final int EXPORT_BATCH = 500;

    /** 筛选条件归一化后的形态，列表/计数/导出共用，保证三者口径一致 */
    record Filter(List<Long> shopIds, String payStatus, LocalDateTime from, LocalDateTime to, String kw, boolean nickname) {}

    Filter filter(Long schoolId, String role, String payStatus, Long shopId, String startDate, String endDate, String keyword) {
        List<Long> shopIds = accessibleShopIds(schoolId, role, shopId);
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.length() > MAX_KEYWORD) throw new IllegalArgumentException("关键词最多 " + MAX_KEYWORD + " 个字符");
        // 形如单号（字母数字、≥8 位）的关键词只走可索引的前缀匹配；其余才额外匹配昵称
        boolean nickname = !kw.isEmpty() && !kw.matches("^[A-Za-z0-9_-]{8,}$");
        String status = payStatus == null || payStatus.isBlank() ? null : payStatus.trim();
        return new Filter(shopIds, status, AdminOrderService.parseStart(startDate), AdminOrderService.parseEnd(endDate),
            kw.isEmpty() ? null : escapeLike(kw), nickname);
    }

    public Map<String, Object> list(Long schoolId, String role, String payStatus, Long shopId, String startDate, String endDate, String keyword, int page, int size) {
        Filter f = filter(schoolId, role, payStatus, shopId, startDate, endDate, keyword);
        int pageSize = Math.min(100, Math.max(1, size));
        long current = Math.max(1, page);
        if (f.shopIds().isEmpty()) return Map.of("list", List.of(), "total", 0, "page", current, "size", pageSize);
        long offset = (current - 1) * pageSize;
        if (offset > MAX_OFFSET) throw new IllegalArgumentException("翻页过深，请缩小日期范围或使用导出");
        // 固定 2 条 SQL（分页 + 计数），用户/店铺/流水核验状态由联表一次取回
        List<Map<String, Object>> rows = paymentMapper.page(f.shopIds(), f.payStatus(), f.from(), f.to(), f.kw(), f.nickname(), pageSize, offset);
        long total = paymentMapper.count(f.shopIds(), f.payStatus(), f.from(), f.to(), f.kw(), f.nickname());
        return Map.of("list", rows.stream().map(AdminPaymentService::present).toList(), "total", total, "page", current, "size", pageSize);
    }

    /** 补充金额（元）字段，保持与旧响应字段语义一致 */
    static Map<String, Object> present(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        Object cents = row.get("amountCents");
        out.put("amount", BigDecimal.valueOf(cents instanceof Number n ? n.longValue() : 0L).movePointLeft(2));
        return out;
    }

    /** LIKE 通配符转义，防止用户输入 % _ 造成全表模糊匹配 */
    static String escapeLike(String kw) { return kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }

    /**
     * 支付统计。
     *
     * 改造点：全部改成数据库聚合，不再把订单读进内存循环，也不再有逐条 selectById 的 N+1。
     * 改造前：1 万笔订单 = 1 万条对象进堆 + 未核验/退款各一轮 N+1 查询；
     * 改造后：固定 3 条聚合 SQL，耗时与数据量基本无关。
     */
    public Map<String, Object> stats(Long schoolId, String role, String startDate, String endDate) {
        List<Long> shopIds = accessibleShopIds(schoolId, role, null);
        LocalDateTime from = AdminOrderService.parseStart(startDate), to = AdminOrderService.parseEnd(endDate);
        // 超管不按店铺限制；其余角色没有任何可见店铺时不该看到数据
        boolean confined = !"super".equalsIgnoreCase(role);
        if (confined && shopIds.isEmpty()) {
            return Map.of("todayAmountCents", 0, "todayOrderCount", 0, "totalAmountCents", 0,
                "unverifiedCount", 0, "refundAmountCents", 0);
        }
        List<Long> scope = confined ? shopIds : null;

        Map<String, Object> total = orderMapper.sumPaid(scope, from, to);
        int totalAmount = num(total, "amount");
        int totalCount = num(total, "cnt");

        LocalDateTime today = LocalDateTime.now().toLocalDate().atStartOfDay();
        Map<String, Object> todayRow = orderMapper.sumPaid(scope, today, null);
        int todayAmount = num(todayRow, "amount");
        int todayCount = num(todayRow, "cnt");

        long unverified = orderMapper.countUnverifiedPayments(scope, from, to);
        long refundAmount = orderMapper.sumRefunded(scope, from, to);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("todayAmountCents", todayAmount);
        out.put("todayOrderCount", todayCount);
        out.put("totalAmountCents", totalAmount);
        out.put("totalOrderCount", totalCount);
        out.put("unverifiedCount", unverified);
        out.put("refundAmountCents", refundAmount);
        return out;
    }

    private static int num(Map<String, Object> row, String key) {
        if (row == null) return 0;
        Object value = row.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value == null) return 0;
        try { return new java.math.BigDecimal(String.valueOf(value)).intValue(); } catch (Exception e) { return 0; }
    }

    public Map<String, Object> detail(String outTradeNo, Long schoolId, String role) {
        UserOrder order = orderMapper.selectOne(new LambdaQueryWrapper<UserOrder>().eq(UserOrder::getOutTradeNo, outTradeNo).last("LIMIT 1"));
        if (order == null) throw new IllegalArgumentException("订单不存在");
        if (!accessibleShopIds(schoolId, role, order.getShopId()).contains(order.getShopId())) throw new com.campuslife.config.ForbiddenException("无权查看该订单");
        return payService.detail(order);
    }

    /** 导出超限时抛出，控制器转成 413 + 明确提示 */
    public static class ExportTooLargeException extends IllegalArgumentException {
        public ExportTooLargeException(long total) {
            super("符合条件的流水共 " + total + " 条，超过单次导出上限 " + MAX_EXPORT_ROWS + " 条，请缩小日期范围后分批导出");
        }
    }

    /** 导出前计数：与列表同一筛选口径；超限直接拒绝，不产生被截断的文件 */
    public long exportCount(Long schoolId, String role, String payStatus, Long shopId, String startDate, String endDate, String keyword) {
        Filter f = filter(schoolId, role, payStatus, shopId, startDate, endDate, keyword);
        if (f.shopIds().isEmpty()) return 0;
        long total = paymentMapper.count(f.shopIds(), f.payStatus(), f.from(), f.to(), f.kw(), f.nickname());
        if (total > MAX_EXPORT_ROWS) throw new ExportTooLargeException(total);
        return total;
    }

    /**
     * 流式导出（finding-06）：按 id 键集分页逐批写出，内存占用与总行数无关，
     * 写出行数达到计数时停止，返回实际写出行数。
     */
    public long exportCsv(Long schoolId, String role, String payStatus, Long shopId, String startDate, String endDate, String keyword,
                          java.io.Writer out) throws java.io.IOException {
        Filter f = filter(schoolId, role, payStatus, shopId, startDate, endDate, keyword);
        out.write("\uFEFF订单号,微信交易号,用户ID,店铺,金额(元),支付状态,对账状态,支付时间\n");
        if (f.shopIds().isEmpty()) return 0;
        long written = 0;
        Long cursor = null;
        while (written < MAX_EXPORT_ROWS) {
            List<Map<String, Object>> batch = paymentMapper.exportBatch(f.shopIds(), f.payStatus(), f.from(), f.to(), f.kw(), f.nickname(), cursor, EXPORT_BATCH);
            for (Map<String, Object> raw : batch) {
                Map<String, Object> row = present(raw);
                out.write(csv(row.get("outTradeNo")) + ',' + csv(row.get("transactionId")) + ',' + csv(row.get("userId")) + ','
                    + csv(row.get("shopName")) + ',' + csv(row.get("amount")) + ',' + csv(row.get("payStatus")) + ','
                    + csv(row.get("verified")) + ',' + csv(row.get("paidAt")) + '\n');
                written++;
            }
            if (batch.size() < EXPORT_BATCH) break;
            cursor = ((Number) batch.get(batch.size() - 1).get("id")).longValue();
        }
        out.flush();
        return written;
    }
    private List<Long> accessibleShopIds(Long schoolId, String role, Long requestedShopId) { LambdaQueryWrapper<Shop> w = new LambdaQueryWrapper<Shop>().eq(Shop::getStatus, 1); if (!"super".equalsIgnoreCase(role)) w.eq(Shop::getSchoolId, schoolId); if (requestedShopId != null) w.eq(Shop::getId, requestedShopId); return shopMapper.selectList(w).stream().map(Shop::getId).toList(); }
    /** CSV 转义；以 = + - @ 开头的单元格前置单引号，防止在 Excel 中被当作公式执行（CSV 注入） */
    static String csv(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
