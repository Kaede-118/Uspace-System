package com.kaede.uspace.order;

import java.util.Arrays;
import java.util.Set;

/**
 * 订单状态。
 *
 * <p>取值与建表脚本里 {@code biz_order.status} 的注释一一对应。
 * 做成枚举而不是散落的字符串常量，是为了让「合法取值有哪些」
 * 在代码里有一处权威定义 —— 校验非法状态时不必再维护第二份清单。
 *
 * <p><b>状态流转只有两跳</b>：
 * <pre>
 *   （点击开门 → 创建订单即进入）IN_USE 使用中
 *          │
 *       结束使用
 *          ↓
 *    PENDING_PAYMENT 待支付 ──支付成功──> PAID 已支付
 *          │                                  ↑
 *          └────── 结算为 0 元时直通 ──────────┘
 * </pre>
 *
 * <p><b>为什么没有 CREATED</b>：点一次「开门」就同时完成
 * 「创建订单 + 下发密码 + 开始计费」，订单不存在「已创建但没开门」的挂起态。
 * 那种状态既没有用户价值，又留下「下了单不进门」的垃圾订单需要清理。
 * 表结构里 {@code status} 的默认值仍保留 {@code 'CREATED'}（见建表脚本的说明），
 * 但业务代码从不产生它 —— 它出现即代表有人手工插了数据。
 *
 * <p><b>为什么没有 CANCELLED</b>：一步到位之后不存在「下错单要取消」的场景 ——
 * 误点一下不想进店，点「结束使用」即可，5 分钟内落在免费档、0 元自动结清。
 * 因此也没有「取消订单」接口。
 *
 * <p>结算为 0 元时直接跳到 {@code PAID}：0 元单没有任何可支付的通道，
 * 让它卡在 {@code PENDING_PAYMENT} 只会让用户看到一个「0 元去支付」的按钮。
 */
public enum OrderStatus {

    /** 使用中。用户已点击开门、密码已下发，计费进行中 */
    IN_USE("使用中"),

    /** 待支付。用户已结束使用、账单已算出，等待付款 */
    PENDING_PAYMENT("待支付"),

    /** 已支付。支付成功或结算为 0 元自动结清 */
    PAID("已支付");

    /** 面向用户的中文说明，供前端展示 */
    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    /**
     * 取中文说明。
     *
     * @return 状态的中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 判断一个字符串是否为合法状态名。
     *
     * <p>用于校验接口入参（如后台按状态筛选），避免把非法值拼进 SQL ——
     * 状态列是 {@code VARCHAR} 而非 {@code ENUM}，库不会替我们挡住。
     *
     * @param name 待校验的状态名，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(s -> s.name().equals(name));
    }

    /**
     * 判断某个状态是否属于「尚未付款」。
     *
     * <p>即 {@link #IN_USE} 与 {@link #PENDING_PAYMENT}。
     * 供管理员人工调整时长时判定可否操作 —— 这两种状态改动金额没有退款问题，
     * 而 {@link #PAID} 已经入账，调整要走人工退款流程。
     *
     * @param name 状态名，可为 null
     * @return 未付款返回 true；null 或已支付返回 false
     */
    public static boolean isUnpaid(String name) {
        return Set.of(IN_USE.name(), PENDING_PAYMENT.name()).contains(name);
    }

    /**
     * 把状态名转成中文说明。
     *
     * <p>状态列是 {@code VARCHAR}，库里存的可能是保留值（{@code CREATED} /
     * {@code CANCELLED}，当前流程不产生），也可能被人手工改成了别的字符串。
     * 这些情况下<b>原样返回状态名</b>，让异常数据在界面上一眼看得出来 ——
     * 伪装成一个正常的中文状态反而会把它藏起来。
     *
     * @param name 状态名，可为 null
     * @return 中文说明；认不出的状态名原样返回
     */
    public static String labelOf(String name) {
        return isValid(name) ? valueOf(name).getLabel() : name;
    }
}
