package com.kaede.uspace.product;

import java.util.Arrays;

/**
 * 商品购买单状态。取值与建表脚本里 {@code biz_product_order.status} 的注释一一对应。
 *
 * <p>状态流转：
 * <pre>
 *   PENDING_PAYMENT 待支付 ──支付成功──> PAID 已支付（同时扣减库存）
 *          │                                    │  ↑
 *          └── 用户放弃付款 ──> CLOSED          │  │
 *                                   管理员驳回 ──┘  │
 *                                        ↓          │
 *                          REJECTED 凭证未通过 ─────┘
 *                              （用户重传 + 复核通过）
 * </pre>
 *
 * <p><b>前两个状态名刻意与订单、包场、月卡购买单保持一致</b>
 * （{@code PENDING_PAYMENT} / {@code PAID}）：支付侧的 {@code PaymentTarget}
 * 用字符串比较判断「是否待支付、是否已支付」，四张收款表用同一套字面量，
 * 回调代码才不必知道自己处理的是哪一种。
 * 改这里的名字会让商品的支付回调静默失效 —— 钱收了，单子不过账。
 *
 * <p><b>为什么没有「已交付」</b>：无人值守店里没有店员，付了钱自己取。
 * 见 {@code package-info} 里那段说明 —— 这是明知的取舍。
 */
public enum ProductOrderStatus {

    /** 待支付。创建出来即处于此状态，等支付回调 */
    PENDING_PAYMENT("待支付"),

    /** 已支付。同一事务里已扣减库存 */
    PAID("已支付"),

    /** 已关闭。用户主动取消，或长时间未付款 */
    CLOSED("已关闭"),

    /**
     * 付款凭证被管理员驳回，等待用户重新提交。
     *
     * <p><b>为什么不复用 {@link #PENDING_PAYMENT}</b>：与订单那边同一条理由 ——
     * 一个状态承载两件处置方式不同的事：「他还没付钱」（去支付）与
     * 「他付过了、只是凭证没通过」（重新上传一张截图）。合并的话，
     * 用户看到「待支付」很可能再下一次单、再付一次钱。
     *
     * <p><b>库存不退</b>：无人值守店里付了钱自己取，而驳回发生在管理员有空
     * 复核的时候（往往隔了一两天），那时货多半已经被取走了。
     * 退回去等于记一笔假账 —— 见 {@code ProductPaymentTargetHandler#revertDelivery}。
     *
     * <p>它<b>不算「还占着货的未付款单」</b>：{@code countPendingByProduct}
     * 只认 {@code PENDING_PAYMENT}。被驳回的单子货可能已经出去了，
     * 再把它算进「未付款占用」会让可售量虚低 —— 而那个数量是给顾客看的。
     */
    REJECTED("凭证未通过");

    /** 面向用户的中文说明，供前端展示 */
    private final String label;

    ProductOrderStatus(String label) {
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
