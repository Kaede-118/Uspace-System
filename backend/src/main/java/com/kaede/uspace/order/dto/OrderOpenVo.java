package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.entity.Order;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 点击开门的返回体 —— <b>唯一携带门锁密码的视图之一</b>（另一个是查看密码）。
 *
 * <p>「点击开门」这个动作在本系统里的含义是<b>展示密码</b>，不是远程开锁。
 * 密码在订单创建时就下发到锁上了，这里只是把它显示给用户看。
 * 之所以不做「点一次发一次」，有两个具体代价：
 * <ul>
 *   <li>通通锁免费额度只有 30,000 次/月，每次点击都调接口会飞快烧完</li>
 *   <li>密码会在锁上越积越多，还得配一套「删旧密码」的流程</li>
 * </ul>
 *
 * <p>已知副作用：密码固定，截图转发给别人后对方也能用。但风险有限 ——
 * 转发之后对方仍然必须<b>人到门口</b>。真要更严就改成「点一次换一次」，代价是额度。
 */
@Data
public class OrderOpenVo {

    /**
     * 提示语里的时间格式。
     *
     * <p>必须与 {@code JacksonConfig} 配的全局格式一致 ——
     * 同一份响应里出现两种时间写法（{@code 2026-09-29T03:23:55} 与
     * {@code 2026-09-29 03:23:55}）会让前端直接展示这条消息时显得很突兀。
     */
    private static final DateTimeFormatter MESSAGE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 订单 ID */
    private Long orderId;

    /** 订单号 */
    private String orderNo;

    /**
     * 门锁密码。用户拿它在门口的数字键盘上输入即可开门。
     *
     * <p>6 位数字（位数由锁型号决定）。<b>它是一串限时密码</b>：
     * 只在 {@link #passcodeEnd} 之前有效，过了就失效。
     */
    private String passcode;

    /** 密码生效时间 */
    private LocalDateTime passcodeStart;

    /**
     * 密码失效时间。
     *
     * <p>前端应当把它显示给用户（「密码有效至 XX:XX」），
     * 让用户知道「这串数字不是永久有效的」，而不是等他在门口输不进去才发现。
     * 过期后回到页面再点一次即可自动续期。
     */
    private LocalDateTime passcodeEnd;

    /** 计费起点（点击开门的时刻） */
    private LocalDateTime startTime;

    /** 本次是否做了续期（密码此前已过期） */
    private boolean renewed;

    /**
     * 续期时是否重新下发了新密码。
     *
     * <p>正常续期<b>不换密码</b>（改的是有效期窗口），所以这里通常是 false。
     * 只有当续期失败、降级为「撤销旧密码 + 下发新密码」时才是 true ——
     * 那种情况下用户手上截图里的旧密码已经作废，必须以本次返回的为准。
     */
    private boolean regenerated;

    /**
     * 给用户看的一句话提示。
     *
     * <p>由 Service 组装，因为只有它知道本次是新开门、续期还是换密码 ——
     * 这三种情形用户要做的事不同（后两种需要留意密码可能变了）。
     */
    private String message;

    /**
     * <b>此刻</b>是不是正处在某场包场时段内。
     *
     * <p>用来在密码弹窗上多显示一句「包场参与者全员都要开门计时，离开时各自离店结账」
     * —— 那条规则只在包场时段内成立：散客不需要知道，包场前那段普通消费也不需要。
     *
     * <p>⚠️ <b>判据是「此刻落在包场时段内」，不是「订单挂没挂 bookingId」</b>：
     * 参与者比包场早到很多时（比如提前一天来踩点），订单照样会挂上 bookingId
     * （见 {@code BookingService#findUpcomingBookingForParticipant}），
     * 但他此刻就是在普通消费 —— 弹窗上冒出一句包场的话，会让他以为自己在包场里，
     * 进而以为现在不计费。<b>这个错误不报任何警，只是让他误会。</b>
     *
     * <p>⚠️ 这个定性必须由<b>后端</b>给：与 {@code BookingScheduleVo.ongoing} 同一条理由 ——
     * 客户端时钟不可信，口径只能有一处定义。
     */
    private boolean inBooking;

    /**
     * 由实体构造视图。
     *
     * @param order       订单实体
     * @param renewed     本次是否续期过
     * @param regenerated 续期是否换了新密码
     * @param inBooking   <b>此刻</b>是否落在某场包场时段内 ——
     *                    由调用方查库判定后传入，本类不查库也不认识「包场」这个业务名词
     * @return 开门结果视图
     */
    public static OrderOpenVo of(Order order, boolean renewed, boolean regenerated,
                                 boolean inBooking) {
        OrderOpenVo vo = new OrderOpenVo();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setPasscode(order.getPasscode());
        vo.setPasscodeStart(order.getPasscodeStart());
        vo.setPasscodeEnd(order.getPasscodeEnd());
        vo.setStartTime(order.getStartTime());
        vo.setRenewed(renewed);
        vo.setRegenerated(regenerated);
        vo.setMessage(messageOf(order, renewed, regenerated));
        vo.setInBooking(inBooking);
        return vo;
    }

    /**
     * 组装面向用户的提示语。
     *
     * @param order       订单实体
     * @param renewed     本次是否续期过
     * @param regenerated 续期是否换了新密码
     * @return 中文提示
     */
    private static String messageOf(Order order, boolean renewed, boolean regenerated) {
        // 用与全局一致的格式，而不是 LocalDateTime.toString() ——
        // 后者的输出形如 2026-09-29T03:23:55，与本响应里其它时间字段的
        // 2026-09-29 03:23:55 不一致，前端直接展示这条消息时会显得很突兀
        String until = order.getPasscodeEnd() == null
                ? "有效期截止前" : MESSAGE_TIME.format(order.getPasscodeEnd());
        if (regenerated) {
            return "原密码已失效，这是新的开门密码，请在 " + until + " 前使用";
        }
        if (renewed) {
            return "密码已延长有效期至 " + until + "，密码本身不变";
        }
        return "请在 " + until + " 前到门口输入密码开门，密码有效期内可重复使用";
    }
}
