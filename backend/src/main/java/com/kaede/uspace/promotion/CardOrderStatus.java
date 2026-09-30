package com.kaede.uspace.promotion;

import java.util.Arrays;

/**
 * 月卡购买单状态。取值与建表脚本里 {@code biz_monthly_card_order.status} 的注释一一对应。
 *
 * <p>状态流转：
 * <pre>
 *   PENDING_PAYMENT 待支付 ──支付成功──> PAID 已支付（同一事务里生成一张月卡）
 *          │
 *          └── 用户取消 / 超过存活时长 ──> CLOSED 已关闭
 * </pre>
 *
 * <p><b>前两个状态名刻意与订单、包场保持一致</b>（{@code PENDING_PAYMENT} / {@code PAID}）：
 * 支付侧的 {@code PaymentTarget} 用字符串比较判断「是否待支付、是否已支付」，
 * 三张收款表用同一套字面量，回调代码才不必知道自己处理的是哪一种。
 * 改这里的名字会让月卡的支付回调静默失效 —— 钱收了，卡不生效。
 *
 * <p><b>为什么没有「已退款」</b>：退款退的是已经付出去的钱，
 * 对应的是「卡」，所以取值在 {@link MonthlyCardStatus} 上，不在这里。
 */
public enum CardOrderStatus {

    /** 待支付。创建出来即处于此状态，等支付回调 */
    PENDING_PAYMENT("待支付"),

    /** 已支付。同一事务里已生成对应的月卡 */
    PAID("已支付"),

    /** 已关闭。用户主动取消，或超过存活时长后被下次购卡顺手关掉 */
    CLOSED("已关闭");

    /** 面向用户的中文说明，供前端展示 */
    private final String label;

    CardOrderStatus(String label) {
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
     * @param name 待校验的状态名，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(s -> s.name().equals(name));
    }

    /**
     * 把状态名转成中文说明。
     *
     * <p>认不出的状态名<b>原样返回</b>，让异常数据在界面上一眼看得出来。
     *
     * @param name 状态名，可为 null
     * @return 中文说明；认不出时原样返回
     */
    public static String labelOf(String name) {
        return isValid(name) ? valueOf(name).getLabel() : name;
    }
}
