package com.kaede.uspace.space;

import java.util.Arrays;
import java.util.Set;

/**
 * 包场状态。
 *
 * <p>取值与建表脚本里 {@code biz_booking.status} 的注释一一对应。
 * 做成枚举而不是散落的字符串常量，是为了让「合法取值有哪些」
 * 在代码里有一处权威定义 —— 校验非法状态时不必再维护第二份清单。
 *
 * <p><b>状态流转</b>：
 * <pre>
 *   PENDING_PAYMENT 待付款 ──付款成功──> PAID 已付款（准入生效）
 *          │                                  │
 *          │                            时段结束
 *          │                                  ↓
 *          └──管理员取消──> CANCELLED       CLOSED 已结束
 * </pre>
 *
 * <p>付款相关的流转（{@code PENDING_PAYMENT → PAID}）属于模块 8，
 * 模块 3 只负责创建（总是从 {@code PENDING_PAYMENT} 起）与取消。
 */
public enum BookingStatus {

    /** 待付款。管理员已排期，但包场人尚未付款，此时准入不生效 */
    PENDING_PAYMENT,

    /** 已付款。包场生效，包场人与被邀请者可凭链接进入 */
    PAID,

    /** 已取消。管理员取消了这场包场 */
    CANCELLED,

    /** 已结束。包场时段已过，归档状态 */
    CLOSED;

    /**
     * 判断一个字符串是否为合法状态名。
     *
     * <p>用于校验接口入参，避免把非法值写进数据库 ——
     * 状态列是 {@code VARCHAR} 而非 {@code ENUM}，库不会替我们挡住。
     *
     * @param name 待校验的状态名，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(s -> s.name().equals(name));
    }

    /**
     * 判断某个状态是否还需要占用时段。
     *
     * <p>{@code PENDING_PAYMENT} 与 {@code PAID} 都算「占着这个时段」——
     * 前者虽未付款，但管理员排期时已经把它许出去了，
     * 若此时再排一场重叠的包场，付款后会撞车。
     *
     * @param name 状态名
     * @return 占时段返回 true
     */
    public static boolean occupiesSlot(String name) {
        return Set.of(PENDING_PAYMENT.name(), PAID.name()).contains(name);
    }
}
