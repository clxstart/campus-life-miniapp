package com.campuslife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campuslife.entity.PaymentRecord;
import com.campuslife.entity.Shop;
import com.campuslife.entity.User;
import com.campuslife.entity.UserOrder;
import com.campuslife.mapper.PaymentRecordMapper;
import com.campuslife.mapper.ShopMapper;
import com.campuslife.mapper.UserMapper;
import com.campuslife.mapper.UserOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminOrderService {
    private final UserOrderMapper orderMapper;
    private final ShopMapper shopMapper;
    private final UserMapper userMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final PayService payService;

    public Map<String, Object> list(Long adminSchoolId, String role, String status, Long shopId, String startDate, String endDate, String keyword, int page, int size) {
        List<Long> shopIds = accessibleShopIds(adminSchoolId, role, shopId);
        if (shopIds.isEmpty()) return Map.of("list", List.of(), "total", 0, "page", page, "size", size);
        LambdaQueryWrapper<UserOrder> wrapper = new LambdaQueryWrapper<UserOrder>().in(UserOrder::getShopId, shopIds).orderByDesc(UserOrder::getCreatedAt);
        if (status != null && !status.isBlank()) wrapper.eq(UserOrder::getStatus, status.trim());
        addDate(wrapper, startDate, endDate);
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            List<Long> ids = userMapper.selectList(new LambdaQueryWrapper<User>().like(User::getNickname, kw)).stream().map(User::getId).toList();
            wrapper.and(w -> w.like(UserOrder::getOutTradeNo, kw).or().in(!ids.isEmpty(), UserOrder::getUserId, ids));
        }
        IPage<UserOrder> result = orderMapper.selectPage(new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))), wrapper);
        List<Map<String, Object>> rows = result.getRecords().stream().map(this::row).toList();
        return Map.of("list", rows, "total", result.getTotal(), "page", result.getCurrent(), "size", result.getSize());
    }

    public void assertAccessible(Long orderId, Long adminSchoolId, String role) {
        UserOrder order = orderMapper.selectById(orderId);
        if (order == null) throw new IllegalArgumentException("订单不存在");
        if (!"super".equalsIgnoreCase(role)) {
            Shop shop = shopMapper.selectById(order.getShopId());
            if (shop == null || adminSchoolId == null || !adminSchoolId.equals(shop.getSchoolId())) throw new com.campuslife.config.ForbiddenException("无权操作其他学校的订单");
        }
    }

    public String exportCsv(Long adminSchoolId, String role, String status, Long shopId, String startDate, String endDate, String keyword) {
        Map<String, Object> result = list(adminSchoolId, role, status, shopId, startDate, endDate, keyword, 1, 10000);
        StringBuilder csv = new StringBuilder("\uFEFF订单号,用户ID,用户昵称,店铺,金额(元),状态,支付状态,下单时间\n");
        for (Object value : (List<?>) result.get("list")) {
            Map<?, ?> row = (Map<?, ?>) value;
            csv.append(csv(row.get("outTradeNo"))).append(',').append(csv(row.get("userId"))).append(',').append(csv(row.get("userName"))).append(',').append(csv(row.get("shopName"))).append(',').append(csv(row.get("amount"))).append(',').append(csv(row.get("status"))).append(',').append(csv(row.get("payStatus"))).append(',').append(csv(row.get("createdAt"))).append('\n');
        }
        return csv.toString();
    }

    private Map<String, Object> row(UserOrder order) {
        Map<String, Object> row = new LinkedHashMap<>();
        User user = userMapper.selectById(order.getUserId()); Shop shop = shopMapper.selectById(order.getShopId());
        row.put("id", order.getId()); row.put("outTradeNo", order.getOutTradeNo()); row.put("userId", order.getUserId()); row.put("userName", user == null ? "" : user.getNickname()); row.put("shopId", order.getShopId()); row.put("shopName", shop == null ? "" : shop.getName()); row.put("itemCount", itemCount(order.getItems())); row.put("amountCents", order.getPayAmountCents()); row.put("amount", order.getTotal()); row.put("status", order.getStatus()); row.put("payStatus", order.getPayStatus()); row.put("createdAt", order.getCreatedAt()); row.put("paidAt", order.getPaidAt());
        return row;
    }

    private List<Long> accessibleShopIds(Long schoolId, String role, Long requestedShopId) {
        LambdaQueryWrapper<Shop> wrapper = new LambdaQueryWrapper<Shop>().eq(Shop::getStatus, 1);
        if (!"super".equalsIgnoreCase(role)) wrapper.eq(Shop::getSchoolId, schoolId);
        if (requestedShopId != null) wrapper.eq(Shop::getId, requestedShopId);
        return shopMapper.selectList(wrapper).stream().map(Shop::getId).toList();
    }

    private static void addDate(LambdaQueryWrapper<UserOrder> wrapper, String start, String end) {
        LocalDateTime from = parseStart(start), to = parseEnd(end);
        if (from != null) wrapper.ge(UserOrder::getCreatedAt, from);
        if (to != null) wrapper.lt(UserOrder::getCreatedAt, to);
    }
    static LocalDateTime parseStart(String value) { try { return value == null || value.isBlank() ? null : LocalDate.parse(value).atStartOfDay(); } catch (Exception e) { throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd"); } }
    static LocalDateTime parseEnd(String value) { try { return value == null || value.isBlank() ? null : LocalDate.parse(value).plusDays(1).atStartOfDay(); } catch (Exception e) { throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd"); } }
    private static int itemCount(String raw) { try { Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, List.class); return parsed instanceof List<?> list ? list.stream().mapToInt(item -> item instanceof Map<?, ?> m && m.get("count") instanceof Number n ? n.intValue() : 1).sum() : 0; } catch (Exception e) { return 0; } }
    private static String csv(Object value) { String s = value == null ? "" : String.valueOf(value); return "\"" + s.replace("\"", "\"\"") + "\""; }
}
