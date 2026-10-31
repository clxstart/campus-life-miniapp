package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.mapper.MerchantMapper;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantService {
    private static final List<String> CATEGORIES = List.of("快餐", "奶茶", "水果", "超市", "其他", "快餐便当", "奶茶饮品", "水果轻食", "零食");
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private final MerchantMapper mapper;
    private final MerchantRefundService refunds;
    private final DispatchService dispatch;
    private final ObjectMapper json;

    public Map<String,Object> submit(Map<String,Object> body) {
        if (body == null) throw new IllegalArgumentException("请填写申请信息");
        String shopName = required(body, "shopName", "店铺名称", 2, 64);
        String category = required(body, "category", "经营分类", 1, 32);
        if (!CATEGORIES.contains(category)) throw new IllegalArgumentException("经营分类无效");
        String contact = required(body, "contactName", "联系人", 2, 32);
        String phone = required(body, "contactPhone", "联系电话", 11, 20);
        if (!PHONE.matcher(phone).matches()) throw new IllegalArgumentException("联系电话应为 11 位手机号");
        String address = required(body, "address", "店铺地址", 1, 128);
        Long schoolId = longValue(body.get("schoolId"), "请选择所属学校");
        if (mapper.activeSchool(schoolId) != 1) throw new IllegalArgumentException("所属学校不存在或已停用");
        String licenseNo = required(body, "licenseNo", "营业执照号", 1, 64);
        String licenseImage = required(body, "licenseImage", "营业执照照片", 1, 255);
        if (!licenseImage.startsWith("/private/")) throw new IllegalArgumentException("营业执照必须通过私有上传接口上传");
        String shopImage = required(body, "shopImage", "店面照片", 1, 255);
        if (!(shopImage.startsWith("/uploads/") || shopImage.startsWith("http://") || shopImage.startsWith("https://"))) {
            throw new IllegalArgumentException("店面照片必须通过上传接口提交");
        }
        String intro = text(body.get("intro")).trim();
        if (intro.length() > 300) throw new IllegalArgumentException("店铺简介不能超过 300 字");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("shopName", shopName); row.put("category", category); row.put("contactName", contact);
        row.put("contactPhone", phone); row.put("address", address); row.put("schoolId", schoolId);
        row.put("licenseNo", licenseNo); row.put("licenseImage", licenseImage); row.put("shopImage", shopImage);
        row.put("intro", intro);
        mapper.insertApply(row);
        return Map.of("id", row.get("id"), "status", "pending");
    }

    public Map<String,Object> applications(String role, Long schoolId, Long requestedSchoolId, String status, int page, int size) {
        String safeStatus = status == null || status.isBlank() ? null : status.trim();
        if (safeStatus != null && !List.of("pending", "approved", "rejected").contains(safeStatus)) throw new IllegalArgumentException("申请状态无效");
        if ("school".equals(role) && (schoolId == null || schoolId < 1)) throw new ForbiddenException("当前账号未绑定学校");
        if ("school".equals(role) && requestedSchoolId != null && !requestedSchoolId.equals(schoolId)) throw new ForbiddenException("无权查看其他学校的申请");
        Long scope = "school".equals(role) ? schoolId : requestedSchoolId;
        int p = Math.max(1, page), s = Math.min(100, Math.max(1, size));
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("list", mapper.applications(scope, safeStatus, s, (p - 1) * s));
        out.put("total", safeStatus == null ? mapper.countApplicationsAll(scope) : mapper.countApplications(scope, safeStatus));
        out.put("page", p); out.put("size", s);
        out.put("stats", Map.of("pending", mapper.countApplications(scope, "pending"),
            "approved", mapper.countApplications(scope, "approved"), "rejected", mapper.countApplications(scope, "rejected")));
        return out;
    }

    public Map<String,Object> application(Long id, String role, Long schoolId) {
        Map<String,Object> row = mapper.application(id);
        if (row == null) throw new IllegalArgumentException("申请不存在");
        if ("school".equals(role) && !Objects.equals(schoolId, number(row.get("schoolId")))) throw new ForbiddenException("无权查看其他学校的申请");
        return row;
    }

    @Transactional
    public Map<String,Object> approve(Long id, Long auditBy, String role, Long schoolId) {
        Map<String,Object> row = mapper.applicationForUpdate(id);
        if (row == null) throw new IllegalArgumentException("申请不存在");
        if ("school".equals(role) && !Objects.equals(schoolId, number(row.get("schoolId")))) throw new ForbiddenException("无权审核其他学校的申请");
        if (!"pending".equals(row.get("status"))) throw new IllegalStateException("该申请已审核");
        Map<String,Object> school = mapper.school(number(row.get("schoolId")));
        if (school == null) throw new IllegalStateException("申请所属学校已不存在");
        Map<String,Object> shop = new LinkedHashMap<>();
        shop.put("name", row.get("shopName")); shop.put("cover", row.get("shopImage")); shop.put("category", row.get("category"));
        shop.put("schoolId", row.get("schoolId")); shop.put("contactPhone", row.get("contactPhone"));
        shop.put("address", row.get("address")); shop.put("ownerName", row.get("contactName")); shop.put("intro", row.get("intro"));
        mapper.insertMerchantShop(shop);
        Long shopId = number(shop.get("id"));
        String phone = String.valueOf(row.get("contactPhone"));
        String usernameBase = "m" + phone.substring(phone.length() - 8);
        String username = usernameBase;
        int suffix = 1;
        while (mapper.usernameExists(username) > 0) username = usernameBase + suffix++;
        Map<String,Object> account = new LinkedHashMap<>();
        account.put("username", username); account.put("passwordHash", AdminAuthService.encode(phone.substring(phone.length() - 6)));
        account.put("realName", row.get("contactName")); account.put("shopId", shopId);
        mapper.insertMerchantUser(account);
        if (mapper.approveApply(id, auditBy, shopId) != 1) throw new IllegalStateException("申请状态已变化，请刷新后重试");
        return Map.of("shopId", shopId, "username", username, "initialPassword", phone.substring(phone.length() - 6));
    }

    @Transactional
    public void reject(Long id, Long auditBy, String role, Long schoolId, String reason) {
        String value = reason == null ? "" : reason.trim();
        if (value.isEmpty() || value.length() > 200) throw new IllegalArgumentException("拒绝原因必填，且不超过 200 字");
        Map<String,Object> row = application(id, role, schoolId);
        if (!"pending".equals(row.get("status"))) throw new IllegalStateException("该申请已审核");
        if (mapper.rejectApply(id, auditBy, value) != 1) throw new IllegalStateException("申请状态已变化，请刷新后重试");
    }

    public Map<String,Object> dashboard(Long shopId) {
        Map<String,Object> shop = requireShop(shopId);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("todayOrders", mapper.todayOrders(shopId)); out.put("todayRevenue", mapper.todayRevenue(shopId));
        out.put("pendingOrders", mapper.pendingOrders(shopId)); out.put("shop", shop);
        return out;
    }

    public Map<String,Object> shop(Long shopId) { return requireShop(shopId); }

    public void updateShop(Long shopId, Map<String,Object> body) {
        if (body == null) throw new IllegalArgumentException("店铺信息不能为空");
        if (body.containsKey("schoolId") || body.containsKey("school_id")) throw new ForbiddenException("所属学校由平台管理，不能修改");
        if (body.containsKey("status")) throw new ForbiddenException("上架状态由平台管理，不能修改");
        Map<String,Object> current = requireShop(shopId);
        Map<String,Object> row = new LinkedHashMap<>(); row.put("shopId", shopId);
        row.put("name", defaultText(body, "name", current, 64, true));
        row.put("category", defaultText(body, "category", current, 32, true));
        row.put("cover", defaultText(body, "cover", current, 255, true));
        row.put("contactPhone", defaultText(body, "contactPhone", current, 20, true));
        if (!PHONE.matcher(String.valueOf(row.get("contactPhone"))).matches()) throw new IllegalArgumentException("联系电话应为 11 位手机号");
        row.put("address", defaultText(body, "address", current, 128, true));
        row.put("intro", defaultText(body, "intro", current, 300, false));
        row.put("notice", defaultText(body, "notice", current, 200, false));
        row.put("minPrice", amount(body, current, "minPrice", "minPrice"));
        row.put("deliveryFee", amount(body, current, "deliveryFee", "deliveryFee"));
        row.put("discountThreshold", amount(body, current, "discountThreshold", "discountThreshold"));
        row.put("discountAmount", amount(body, current, "discountAmount", "discountAmount"));
        row.put("discount", defaultText(body, "discount", current, 32, false));
        row.put("deliveryTime", integer(body, current, "deliveryTime", 1, 240));
        String openTime = defaultText(body, "openTime", current, 5, true);
        String closeTime = defaultText(body, "closeTime", current, 5, true);
        if (!TIME.matcher(openTime).matches() || !TIME.matcher(closeTime).matches()) throw new IllegalArgumentException("营业时间格式应为 HH:mm");
        row.put("openTime", openTime); row.put("closeTime", closeTime);
        row.put("isOpen", body.get("isOpen") == null ? intNumber(current.get("isOpen"), 1) : ("1".equals(text(body.get("isOpen"))) || Boolean.TRUE.equals(body.get("isOpen")) ? 1 : 0));
        mapper.updateShop(row);
    }

    public List<Map<String,Object>> goods(Long shopId) { return mapper.goodsList(shopId); }

    public Map<String,Object> createGoods(Long shopId, Map<String,Object> body) {
        Map<String,Object> row = goodsPayload(shopId, body);
        row.put("status", 1);
        mapper.insertGoods(row);
        return Map.of("id", row.get("id"), "status", 1);
    }

    public void updateGoods(Long shopId, Long id, Map<String,Object> body) {
        if (mapper.goods(id, shopId) == null) throw new IllegalArgumentException("菜品不存在");
        Map<String,Object> row = goodsPayload(shopId, body); row.put("id", id);
        if (mapper.updateGoods(row) != 1) throw new IllegalArgumentException("菜品不存在");
    }

    public void goodsStatus(Long shopId, Long id, int status) {
        if (mapper.updateGoodsStatus(id, shopId, status == 0 ? 0 : 1) != 1) throw new IllegalArgumentException("菜品不存在");
    }

    public void goodsStatusBatch(Long shopId, List<?> rawIds, int status) {
        if (rawIds == null || rawIds.isEmpty() || rawIds.size() > 100) throw new IllegalArgumentException("请选择 1 到 100 道菜品");
        List<Long> ids = rawIds.stream().map(v -> longValue(v, "菜品编号无效")).distinct().toList();
        mapper.updateGoodsStatusBatch(shopId, ids, status == 0 ? 0 : 1);
    }

    public void deleteGoods(Long shopId, Long id) {
        if (mapper.deleteGoods(id, shopId) != 1) throw new IllegalArgumentException("菜品不存在");
    }

    public Map<String,Object> orders(Long shopId, String status, int page, int size) {
        if (status != null && !status.isBlank() && !List.of("created", "paid", "accepted", "cancelled", "completed").contains(status)) throw new IllegalArgumentException("订单状态无效");
        int p=Math.max(1,page), s=Math.min(100,Math.max(1,size));
        List<Map<String,Object>> list=mapper.orders(shopId,status,s,(p-1)*s);
        list.forEach(this::decorateOrder);
        return Map.of("list",list,"total",mapper.countOrders(shopId,status),"page",p,"size",s);
    }

    /**
     * 商家接单
     * 状态机校验：paid → accepted（OrderStatus 只允许这条边）
     */
    @Transactional
    public void accept(Long shopId, Long id) {
        Map<String,Object> order = requireOwnedOrder(shopId, id);
        // 显式走状态机，非法流转直接抛异常
        OrderStatus.requireTransition(String.valueOf(order.get("status")), OrderStatus.ACCEPTED.value());
        if (mapper.acceptOrder(id, shopId, OrderStatus.ACCEPTED.value()) != 1) {
            throw new IllegalStateException("订单状态不允许接单（可能已被处理，请刷新）");
        }
        // 商家接单后按楼栋责任区自动派单。
        // 派单失败只进异常队列，绝不能让"接单成功"因为派单问题而回滚或报错。
        try {
            dispatch.autoDispatch(id);
        } catch (Exception e) {
            log.warn("[campus] 订单 {} 自动派单异常（不影响接单）: {}", id, e.getMessage());
        }
    }

    @Transactional
    public Map<String,Object> rejectOrder(Long shopId, Long id, String reason) {
        String rejection = reason == null ? "" : reason.trim();
        if (rejection.length() > 200) throw new IllegalArgumentException("拒单原因不能超过 200 字");
        Map<String,Object> order=requireOwnedOrder(shopId,id);
        if (mapper.cancelOrder(id,shopId) != 1) throw new IllegalStateException("订单状态不允许拒单");
        String status="not_required";
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status", "cancelled");
        if ("paid".equals(order.get("status"))) {
            String outRefundNo = "MR" + id;
            String refundReason = "商家拒单" + (rejection.isEmpty() ? "" : "：" + rejection);
            int cents = order.get("payAmountCents") instanceof Number value ? value.intValue() : 0;
            mapper.insertRefund(id, outRefundNo, cents, refundReason.length() > 200 ? refundReason.substring(0, 200) : refundReason);
            status = "pending";
            result.put("refundStatus", status);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        result.put("refundStatus", refunds.submitAfterCommit(id));
                    } catch (RuntimeException uncertainResult) {
                        result.put("refundStatus", "pending");
                    }
                }
            });
        } else {
            result.put("refundStatus", status);
        }
        mapper.notifyUser(number(order.get("userId")),"订单已取消", "商家暂时无法接单，订单 #"+id+" 已取消"+("pending".equals(status)?"，退款申请处理中":""));
        return result;
    }

    /**
     * 商家标记订单完成（仅限无骑手的自配送场景）
     *
     * 业务规则：
     *   - 订单已经被骑手接单（rider_id 不为空）时，必须由骑手在骑手端点「送达」完成，
     *     商家不能跳过骑手直接把订单标记完成，否则骑手侧的待抢/配送列表会与订单状态不一致。
     *   - 只有尚未被任何骑手接单的订单，才允许商家自行完成（自配送兜底）。
     *
     * 状态流转：accepted → completed（仅当 rider_id IS NULL）
     */
    @Transactional
    public void complete(Long shopId, Long id) {
        Map<String,Object> order = requireOwnedOrder(shopId, id);

        // 已被骑手接单的订单，只能由骑手完成（RiderMapper.deliver 才会置 completed）
        Object riderId = order.get("riderId");
        boolean takenByRider = riderId != null && !String.valueOf(riderId).isBlank()
            && !"0".equals(String.valueOf(riderId));
        if (takenByRider) {
            throw new IllegalStateException("该订单已由骑手接单，请等待骑手送达后再标记完成");
        }

        // 状态机校验：accepted → completed
        OrderStatus.requireTransition(String.valueOf(order.get("status")), OrderStatus.COMPLETED.value());

        if (mapper.completeOrder(id, shopId) != 1) {
            throw new IllegalStateException("订单状态不允许完成（仅「已接单」状态的订单可完成）");
        }
    }

    public Map<String,Object> userOrders(Long userId, int page, int size) {
        if (userId == null || userId < 1) throw new IllegalArgumentException("用户编号无效");
        int p=Math.max(1,page), s=Math.min(100,Math.max(1,size));
        List<Map<String,Object>> list=mapper.userOrders(userId,s,(p-1)*s);
        list.forEach(this::decorateOrder);
        return Map.of("list",list,"total",mapper.countUserOrders(userId),"page",p,"size",s);
    }

    public Map<String,Object> userOrder(Long id, Long userId) {
        if (id == null || id < 1 || userId == null || userId < 1) throw new IllegalArgumentException("订单编号无效");
        Map<String,Object> row=mapper.userOrder(id,userId);
        if (row == null) throw new ForbiddenException("订单不存在或无权查看");
        decorateOrder(row);
        return row;
    }

    private Map<String,Object> requireOwnedOrder(Long shopId,Long id) {
        Map<String,Object> order=mapper.order(id,shopId);
        if(order==null) throw new IllegalArgumentException("订单不存在");
        return order;
    }

    private Map<String,Object> requireShop(Long shopId) {
        Map<String,Object> row=mapper.shop(shopId);
        if(row==null) throw new IllegalArgumentException("店铺不存在");
        return row;
    }

    private Map<String,Object> goodsPayload(Long shopId,Map<String,Object> body) {
        if(body==null) throw new IllegalArgumentException("菜品信息不能为空");
        String name=required(body,"name","菜品名称",1,64);
        String category=required(body,"category","菜品分类",1,32);
        BigDecimal price;
        try { price=new BigDecimal(text(body.get("price"))); } catch(Exception e) { throw new IllegalArgumentException("价格格式无效"); }
        if(price.signum()<0 || price.compareTo(new BigDecimal("999999.99"))>0) throw new IllegalArgumentException("价格超出范围");
        String image=required(body,"image","菜品图片",1,255);
        int sort=intNumber(body.get("sortNum"),0);
        Map<String,Object> row=new LinkedHashMap<>(); row.put("shopId",shopId); row.put("name",name); row.put("category",category); row.put("price",price); row.put("image",image); row.put("sortNum",sort);
        return row;
    }

    private void decorateOrder(Map<String,Object> row) {
        String items=text(row.get("items"));
        try { row.put("items", json.readValue(items, List.class)); }
        catch(Exception ignored) { row.put("items", List.of()); }
    }

    private static BigDecimal amount(Map<String,Object> body,Map<String,Object> current,String key,String currentKey) {
        Object raw=body.get(key); if(raw==null) raw=current.get(currentKey);
        try { BigDecimal n=new BigDecimal(text(raw)); if(n.signum()<0||n.compareTo(new BigDecimal("999999.99"))>0) throw new IllegalArgumentException(key+"超出范围"); return n; }
        catch(NumberFormatException e) { throw new IllegalArgumentException(key+"格式无效"); }
    }
    private static int integer(Map<String,Object> body,Map<String,Object> current,String key,int min,int max) {
        int value=intNumber(body.get(key),intNumber(current.get(key),min));
        if(value<min||value>max) throw new IllegalArgumentException(key+"超出范围"); return value;
    }
    private static String defaultText(Map<String,Object> body,String key,Map<String,Object> current,int max,boolean required) {
        String value=body.get(key)==null?text(current.get(key)):text(body.get(key)).trim();
        if(value.length()>max||(required&&value.isEmpty())) throw new IllegalArgumentException(key+"不能为空或超出长度限制"); return value;
    }
    private static String required(Map<String,Object> body,String key,String label,int min,int max) {
        String value=text(body.get(key)).trim(); if(value.length()<min||value.length()>max) throw new IllegalArgumentException(label+"长度无效"); return value;
    }
    private static Long longValue(Object value,String message) { try { long id=Long.parseLong(text(value)); if(id<1) throw new NumberFormatException(); return id; } catch(Exception e) { throw new IllegalArgumentException(message); } }
    private static Long number(Object value) { return value instanceof Number n?n.longValue():Long.valueOf(text(value)); }
    private static int intNumber(Object value,int fallback) { try { return value instanceof Number n?n.intValue():Integer.parseInt(text(value)); } catch(Exception e) { return fallback; } }
    private static String text(Object value) { return value==null?"":String.valueOf(value); }
}
