package com.kaede.uspace.order.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条订单结算完成、顾客离店（模块 8 发布，模块 11 的 QQ 群播报监听）。
 *
 * <p>类的位置与字段取舍理由见 {@link OrderEnteredEvent}，两者同源。
 *
 * @param orderId     订单 ID
 * @param userId      用户 ID
 * @param orderNo     订单号，只为日志
 * @param leftAt      离场时刻。<b>不一定是「刚才」</b> —— 包场清场路径下它是包场开始时刻，
 *                    管理员补录路径下可能是几天前
 * @param stayMinutes 在店时长（分钟）。这里带的是 {@code applySettlement} 已经算好的值，
 *                    不让监听器自己推 —— 推一遍就是同一条规则的第二份实现，
 *                    而「宽限 / 包场剪切 / 向下取整」这几条一旦漂移不会报任何错
 * @param amount      实付金额（{@code BigDecimal}，恒不为 null，0 元单体现在也是零）。
 *                    带上它而不是让监听器回查，是为了让<b>「金额离开了订单模块」
 *                    这件事在发布点上一眼可见</b> —— 恰恰因为它最敏感，
 *                    藏进一次隐式查询里反而不好审
 * @param free        是否无需支付（0 元单）。决定播报是「已结清」还是「待支付，请到网页端付款」
 * @param source      这条离店是哪条路径产生的，见 {@link Source}。<b>不能省</b>
 */
public record OrderLeftEvent(
        Long orderId,
        Long userId,
        String orderNo,
        LocalDateTime leftAt,
        int stayMinutes,
        BigDecimal amount,
        boolean free,
        Source source) {

    /**
     * 离店的三条来源路径。
     *
     * <p>⚠️ <b>这个字段不能省，而且不能用 {@code operatorId != null} 代替</b>：
     * {@code applySettlement} 被三条路径复用，其中「用户自助结算」与「包场清场」
     * <b>都传 operatorId = null</b>，只看它分不出这两者。
     *
     * <p>为什么非要分清：{@link #ADMIN_ADJUST} 是管理员<b>第二天补录</b>
     * 「昨晚忘了点结束使用」的单子。群里如果能收到一条「张三已离店」，
     * 而张三昨晚就走了，那是<b>假消息</b> —— 在一个有人长期看播报的群里，
     * 假消息比没有消息更糟，它会让人开始怀疑每一条播报。
     */
    public enum Source {

        /** 用户自己点「结束使用」*/
        USER,

        /** 包场开始，被清场调度自动结算 */
        BOOKING_CLEAR,

        /** 管理员人工调整时长 */
        ADMIN_ADJUST
    }
}
