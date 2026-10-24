package com.campuslife.service;

import java.util.EnumSet;
import java.util.Set;

/**
 * 外卖订单的合法状态流转。合并后完整履约链路：
 *
 * created → paid → accepted → delivering → picked → completed
 * （created/paid/accepted/delivering/picked 均可进入 refunding → refunded）
 *
 * 说明：delivering = 骑手已抢单、正在取餐；picked = 骑手已取到餐、正在配送。
 * 两者都算「配送中」，骑手端 activeCount 用的就是 ('delivering','picked')。
 */
public enum OrderStatus {
    CREATED("created"), PAID("paid"), ACCEPTED("accepted"), DELIVERING("delivering"),
    PICKED("picked"), COMPLETED("completed"), CANCELLED("cancelled"), CLOSED("closed"),
    REFUNDING("refunding"), REFUNDED("refunded");

    private final String value;
    OrderStatus(String value) { this.value = value; }
    public String value() { return value; }

    public static OrderStatus from(String value) {
        for (OrderStatus status : values()) if (status.value.equalsIgnoreCase(value)) return status;
        throw new IllegalArgumentException("未知订单状态: " + value);
    }

    public static boolean canTransit(String from, String to) {
        if (from == null || to == null || from.equals(to)) return false;
        OrderStatus source = from(from), target = from(to);
        Set<OrderStatus> allowed = switch (source) {
            case CREATED -> EnumSet.of(PAID, CANCELLED, CLOSED);
            case PAID -> EnumSet.of(ACCEPTED, REFUNDING);
            case ACCEPTED -> EnumSet.of(DELIVERING, REFUNDING);
            case DELIVERING -> EnumSet.of(PICKED, COMPLETED, REFUNDING);
            case PICKED -> EnumSet.of(COMPLETED, REFUNDING);
            case COMPLETED -> EnumSet.of(REFUNDING);
            case REFUNDING -> EnumSet.of(REFUNDED);
            case CANCELLED, CLOSED, REFUNDED -> EnumSet.noneOf(OrderStatus.class);
        };
        return allowed.contains(target);
    }

    public static void requireTransition(String from, String to) {
        if (!canTransit(from, to)) throw new IllegalStateException("订单状态不能从 " + from + " 流转到 " + to);
    }
}
