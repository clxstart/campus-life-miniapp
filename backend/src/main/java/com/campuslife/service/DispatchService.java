package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.entity.Building;
import com.campuslife.mapper.BuildingMapper;
import com.campuslife.mapper.DispatchMapper;
import com.campuslife.mapper.EarningsLedgerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 校园外卖楼栋派单。
 *
 * 规则（方案 §7）：
 *   1. 每栋楼配默认责任骑手，订单地址匹配到楼栋后**自动派给该楼栋当前可用的默认骑手**；
 *   2. 缺配楼栋、默认骑手请假/忙碌、地址匹配不上 → 进「异常待处理队列」，由负责人改派；
 *   3. 不开放全校自由抢单（抢单接口另有开关，默认关闭）；
 *   4. 改派/接管要留痕：谁操作的、原责任人、新责任人、原因、时间；
 *   5. 同一订单只有一个有效责任配送人 —— 靠 user_order.rider_id 单列自然成立，
 *      旧骑手的 pick/deliver SQL 带 rider_id 条件，改派后立即命中 0 行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchService {

    private final DispatchMapper dispatch;
    private final BuildingMapper buildings;
    private final EarningsLedgerMapper ledger;

    /**
     * 指向自身代理的引用：批量补派里逐单调用 autoDispatch 必须走代理，
     * 否则类内自调用会绕过 @Transactional(REQUIRES_NEW)，单笔失败的半截写入会跟着整批提交。
     * 用 @Lazy 字段注入，避免构造期循环依赖。
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private DispatchService self;

    /** 派单结果，给调用方和测试一个明确结论。 */
    public record Outcome(String result, Long riderId, String reason) {
        public boolean assigned() { return "auto".equals(result); }
    }

    /**
     * 订单进入可派单状态（商家已接单）后调用。
     * 失败只记录并进入队列，**绝不抛异常影响主流程**——派单不该让支付/接单失败。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Outcome autoDispatch(Long orderId) {
        Map<String, Object> order = dispatch.orderForDispatch(orderId);
        if (order == null) return new Outcome("skipped", null, "订单不存在");
        Long riderId = num(order.get("riderId"));
        if (riderId != null && riderId > 0) return new Outcome("skipped", riderId, "已有责任骑手");
        if (!"accepted".equals(str(order.get("status")))) return new Outcome("skipped", null, "订单状态不可派单");

        Long schoolId = num(order.get("schoolId"));
        Building building = matchBuilding(schoolId, str(order.get("address")));
        if (building == null) return queue(orderId, "地址未匹配到楼栋，等待负责人分配");
        if (building.getDefaultRiderId() == null) return queue(orderId, "楼栋「" + building.getName() + "」未配置默认骑手，等待负责人分配");

        Long defaultRiderId = building.getDefaultRiderId();
        Map<String, Object> rider = dispatch.riderInfo(defaultRiderId);
        if (rider == null) return queue(orderId, "默认骑手账号不存在，等待负责人改派");
        if (!"rider".equals(str(rider.get("role")))) return queue(orderId, "默认骑手账号角色异常，等待负责人改派");
        if (num(rider.get("status")) != null && num(rider.get("status")) == 0) return queue(orderId, "默认骑手已停用，等待负责人改派");
        Long riderSchool = num(rider.get("schoolId"));
        // fail-closed：未绑定学校的骑手视为不属于本校，不能被任何学校自动派单
        if (riderSchool == null || !riderSchool.equals(schoolId)) return queue(orderId, "默认骑手未绑定本校，等待负责人改派");
        String riderStatus = str(rider.get("riderStatus"));
        if (!available(riderStatus)) return queue(orderId, "默认骑手当前不可用（" + riderStatus + "），等待负责人改派");

        String riderName = str(rider.get("realName"));
        int affected = dispatch.assignAuto(orderId, defaultRiderId, riderName, building.getId());
        if (affected != 1) {
            // 被并发抢先派单，或者状态已经变了
            return new Outcome("skipped", null, "订单已被其他流程处理");
        }
        dispatch.refreshRiderStatus(defaultRiderId);
        dispatch.logDispatch(orderId, "auto", null, defaultRiderId, "system", null,
            "楼栋「" + building.getName() + "」自动派单");
        log.info("[campus] 订单 {} 自动派给楼栋「{}」的骑手 {}", orderId, building.getName(), riderName);
        return new Outcome("auto", defaultRiderId, "已派给楼栋默认骑手");
    }

    /**
     * 批量补派：给历史上已接单但没有责任骑手的订单补一次派单。
     * 本方法不开事务：每单经 self 代理各自开 REQUIRES_NEW 事务，单笔失败只回滚这一单。
     */
    public int dispatchAwaiting() {
        int assigned = 0;
        List<Long> ids = dispatch.awaitingDispatchIds();
        if (ids == null) return 0;
        for (Long id : ids) {
            try {
                if (self.autoDispatch(id).assigned()) assigned++;
            } catch (Exception e) {
                log.warn("[campus] 订单 {} 补派失败: {}", id, e.getMessage());
            }
        }
        return assigned;
    }

    private Outcome queue(Long orderId, String reason) {
        dispatch.markQueued(orderId, reason);
        dispatch.logDispatch(orderId, "queue", null, null, "system", null, reason);
        log.info("[campus] 订单 {} 进入异常待处理队列: {}", orderId, reason);
        return new Outcome("queued", null, reason);
    }

    /**
     * 负责人改派 / 接管。
     *
     * @param action  reassign（改派给别人）或 takeover（负责人自己接过来）
     */
    @Transactional
    public Map<String, Object> assign(Long orderId, Long riderId, String action, String reason, Long adminId, boolean countPay,
                                      Long scopeSchoolId) {
        if (orderId == null || orderId < 1) throw new IllegalArgumentException("订单编号无效");
        if (riderId == null || riderId < 1) throw new IllegalArgumentException("请选择负责配送的骑手");
        String act = "takeover".equals(action) ? "takeover" : "reassign";
        Map<String, Object> rider = dispatch.riderInfo(riderId);
        if (rider == null) throw new IllegalArgumentException("骑手不存在");
        if (!"rider".equals(str(rider.get("role")))) throw new IllegalArgumentException("该账号不是骑手，不能承接配送");
        if (num(rider.get("status")) != null && num(rider.get("status")) == 0) throw new IllegalArgumentException("该骑手账号已停用");

        Map<String, Object> order = dispatch.orderForDispatch(orderId);
        if (order == null) throw new IllegalArgumentException("订单不存在");
        Long orderSchool = num(order.get("schoolId"));
        if (scopeSchoolId != null && !scopeSchoolId.equals(orderSchool)) {
            throw new ForbiddenException("只能调度本校订单");
        }
        Long riderSchool = num(rider.get("schoolId"));
        // fail-closed：骑手未绑定学校、或与订单学校不一致，都不能承接
        if (orderSchool == null || riderSchool == null || !orderSchool.equals(riderSchool)) {
            throw new IllegalArgumentException("该骑手不属于订单所在学校，不能承接配送");
        }
        Long from = num(order.get("riderId"));
        String note = reason == null ? "" : reason.trim();
        if (note.length() > 200) note = note.substring(0, 200);
        if (note.isEmpty() && "takeover".equals(act)) note = "负责人接管配送";

        int affected = dispatch.reassign(orderId, riderId, str(rider.get("realName")), act, note, countPay ? 1 : 0);
        if (affected != 1) throw new IllegalStateException("订单当前状态不允许改派（可能已完成或已退款）");

        if (from != null && !from.equals(riderId)) dispatch.refreshRiderStatus(from);
        dispatch.refreshRiderStatus(riderId);
        dispatch.logDispatch(orderId, act, from, riderId, "admin", adminId,
            note + (countPay ? "" : "（不计配送报酬）"));
        log.info("[campus] 管理员 {} {} 订单 {}：{} -> {}", adminId, act, orderId, from, riderId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("action", act);
        out.put("fromRiderId", from);
        out.put("toRiderId", riderId);
        out.put("toRiderName", str(rider.get("realName")));
        out.put("reason", note);
        out.put("countPay", countPay);
        return out;
    }

    /**
     * 送达后记配送报酬。只记一次：
     *   delivery_pay_recorded 条件更新（0→1）+ earnings_ledger.biz_no 唯一键，双保险。
     * 接管且明确不计报酬的订单不记收入，由调用方传 countPay=false。
     */
    @Transactional
    public boolean recordDeliveryIncome(Long orderId, Long actualRiderId) {
        if (orderId == null || orderId < 1) return false;
        if (dispatch.markDeliveryPayRecorded(orderId) != 1) return false;  // 已经记过

        Map<String, Object> pay = dispatch.payInfo(orderId);
        boolean countPay = pay == null || num(pay.get("payCounted")) == null || num(pay.get("payCounted")) != 0;
        Long rider = pay == null ? actualRiderId : num(pay.get("actualRiderId"));
        if (rider == null) rider = actualRiderId;

        if (!countPay) {
            // 负责人接管且明确不计报酬：按方案 §7.2 这笔配送费不计入骑手收入，
            // 仍留一条 0 元 void 记录，保证"处理过"这件事可追溯。
            String bizNo = "delivery:" + orderId + ":review";
            int written = ledger.record(bizNo, "delivery", orderId, rider,
                "delivery_income", 0, "void", "负责人接管且不计配送报酬", null);
            ensureLedgerWritten(written, bizNo, orderId);
            return false;
        }
        int cents = pay == null ? 0 : (int) Math.max(0, num2(pay.get("payCents")));
        String bizNo = "delivery:" + orderId + ":income";
        int written = ledger.record(bizNo, "delivery", orderId, rider,
            "delivery_income", cents, "recorded", "配送报酬（履约账单，非余额）", null);
        ensureLedgerWritten(written, bizNo, orderId);
        return true;
    }

    /**
     * INSERT IGNORE 会把唯一键冲突以外的错误（如截断、非法值）也降级成 0 行警告。
     * 0 行时只有“这条 biz_no 确实已存在”才是合法幂等；否则抛异常让送达整体回滚，
     * 避免出现 delivery_pay_recorded=1 但没有任何账单行的静默漏记。
     */
    private void ensureLedgerWritten(int written, String bizNo, Long orderId) {
        if (written == 1) return;
        if (ledger.countByBizNo(bizNo) > 0) return;
        throw new IllegalStateException("订单 " + orderId + " 配送报酬记账失败，请稍后重试");
    }

    public List<Map<String, Object>> queue(Long schoolId) { return dispatch.dispatchQueue(schoolId); }

    /** 调度日志：校管只能看本校订单，避免用订单 id 探测他校配送信息。 */
    public List<Map<String, Object>> log(Long orderId, Long scopeSchoolId) {
        if (orderId == null || orderId < 1) throw new IllegalArgumentException("订单编号无效");
        Map<String, Object> order = dispatch.orderForDispatch(orderId);
        if (order == null) throw new IllegalArgumentException("订单不存在");
        if (scopeSchoolId != null && !scopeSchoolId.equals(num(order.get("schoolId")))) {
            throw new ForbiddenException("只能查看本校订单的调度记录");
        }
        return dispatch.dispatchLog(orderId);
    }

    /**
     * 地址匹配楼栋：先去掉所有空白字符再比，因为用户填的地址空格位置完全随机
     * （"学生公寓6号楼312" / "紫荆公寓 2 栋 508" / "Student Dorm 1 Room 101"）。
     * 多个关键词命中时取最长匹配，避免"1号楼"误命中"11号楼"。
     */
    public Building matchBuilding(Long schoolId, String address) {
        if (schoolId == null) return null;
        String target = normalize(address);
        if (target.isEmpty()) return null;
        List<Building> list = buildings.listBySchool(schoolId);
        if (list == null || list.isEmpty()) return null;
        Building best = null;
        int bestLen = 0;
        for (Building b : list) {
            for (String candidate : candidates(b)) {
                if (candidate.isEmpty()) continue;
                if (target.contains(candidate) && candidate.length() > bestLen) {
                    best = b;
                    bestLen = candidate.length();
                }
            }
        }
        return best;
    }

    private static List<String> candidates(Building b) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String name = normalize(b.getName());
        if (!name.isEmpty()) out.add(name);
        String keywords = b.getMatchKeywords();
        if (keywords != null) {
            for (String part : keywords.split("[,，;；\\s]+")) {
                String k = normalize(part);
                if (!k.isEmpty()) out.add(k);
            }
        }
        return out;
    }

    /** 去掉空白与常见分隔符，统一小写，其余原样保留（中文数字、英文都支持）。 */
    static String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\s\\u3000·•\\-—_/\\\\]+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean available(String riderStatus) {
        if (riderStatus == null) return true;
        String s = riderStatus.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() || "idle".equals(s) || "available".equals(s) || "online".equals(s);
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    private static Long num(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return null;
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }

    private static double num2(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0;
        try { return Double.parseDouble(String.valueOf(v).trim()); } catch (Exception e) { return 0; }
    }
}
