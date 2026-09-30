package com.kaede.uspace.order;

/**
 * 一笔收款计入哪一类<b>累计消费</b>。
 *
 * <p>用户的累计实付拆成两列存（{@code sys_user.order_paid} 与 {@code card_paid}，
 * 二者之和落在冗余列 {@code total_paid} 上）。分开存是为了将来能给两类消费
 * 各自定政策 —— 比如按累计房间消费给老客回馈，而不把买卡的钱也算进去。
 *
 * <p><b>为什么由处理器声明品类，而不是在支付服务里按目标类型分支</b>：
 * 支付服务一旦认识「月卡」这个词，每加一种收款方式就要改一次回调代码；
 * 而 {@link PaymentTargetHandler} 本来就是「这类收款的一切特殊处理」的归属地。
 * 由它声明品类，支付服务只负责「按品类调用对应的累加方法」，
 * 仍然不认识任何一种具体的业务。
 *
 * <p><b>为什么不做成 {@code PaymentTarget} 上的一个可空字段</b>：
 * 忘记设置字段只会在运行期静默按订单口径累加 —— 卡费被永久记进 {@code order_paid}，
 * 没有任何报错，账目从此对不上。声明成接口方法，漏实现就<b>编译不过</b>。
 * 两种做法的差别就在「错的后果是否可见」。
 */
public enum PaidCategory {

    /** 房间消费（订单、包场）。累加进 {@code order_paid} */
    ORDER("订单消费"),

    /**
     * 月卡充值。累加进 {@code card_paid}。
     *
     * <p>它<b>不计入</b>月度累计消费的优惠门槛 —— 月卡本身就是一项独立的优惠政策，
     * 再顶满门槛等于一笔钱吃两次优惠。那个门槛由 {@code biz_order} 按月聚合得出，
     * 天然不含卡费（月卡购买不生成订单）。
     */
    CARD("月卡充值");

    /** 中文说明，用于日志 */
    private final String label;

    PaidCategory(String label) {
        this.label = label;
    }

    /**
     * 取中文说明。
     *
     * @return 品类的中文名称
     */
    public String getLabel() {
        return label;
    }
}
