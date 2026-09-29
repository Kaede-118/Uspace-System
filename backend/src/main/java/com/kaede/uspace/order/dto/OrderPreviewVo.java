package com.kaede.uspace.order.dto;

import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.order.OrderStatus;
import com.kaede.uspace.order.entity.Order;
import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 结账预览 —— 用户点「结账」时看到的账单，也是那个确认环节本身。
 *
 * <p><b>它只回答一个问题：此刻停止计时的话，要付多少。</b>计时并不停止、订单状态并不改变、
 * 门锁密码也不撤销 —— 用户看完这一屏再点「停止计时」才算真的结束
 * （见 {@code POST /api/orders/{id}/settle}）。按已确认的产品决定，
 * 这一屏就是确认环节：看完点「停止计时」即视为确认离场，
 * 后端不设二次确认弹窗，也没有反悔路径。
 *
 * <p><b>为什么不复用 {@link OrderSettleVo}</b>：「这次结算」与「如果现在结算」是两回事。
 * <ul>
 *   <li>预览时订单还在使用中，计时没有停；结算时已转待支付或已支付</li>
 *   <li><b>预览没有「离场时刻」</b>：{@code end_time} 还没写库，也不会因为看了预览就写。
 *       计费只截止到 {@link #previewAt}，若把这个假设时刻叫成 {@code endTime}，
 *       前端迟早会把它当成最终账单上的那个时刻 —— 而两者只差几秒，肉眼分辨不出来</li>
 *   <li>{@code OrderSettleVo} 的状态字段只能表达「结算后已经是什么状态」，
 *       而预览要表达的是「停止后<b>将会</b>是什么状态」（{@link #statusAfterStop}）</li>
 * </ul>
 * 若把这些字段塞进结算视图，它们在结算响应里就永远是 null 或误导值。
 * 这与 {@code OrderVo} 刻意排除 {@code passcode} 是同一种考虑：
 * <b>边界放在 VO 上，比放在「记得别读那个字段」上可靠</b>。
 *
 * <p><b>本视图与真实结算同源</b>：金额由 {@code OrderService} 内那个「算哪一段」
 * 的方法算出，与结算走的是同一条链路。预览价必须等于实际价 ——
 * 两处若各写一份剪切与计费逻辑，会算出看起来合理的错价而不会报任何错。
 */
@Data
public class OrderPreviewVo {

    /** 订单 ID */
    private Long orderId;

    /** 订单号，前端拿它与轮询响应比对，确认是当前这笔单 */
    private String orderNo;

    /** 计费起点 = 点击开门的时刻 */
    private LocalDateTime startTime;

    /**
     * 预览时刻 = 本次计算的截止时刻，也是「此刻点停止计时」的假设离场时刻。
     *
     * <p>金额随时长增长，所以前端要持续轮询刷新本接口。靠它可以做两件事：
     * 在页面上显示「更新于 …」，以及<b>丢弃乱序到达的旧响应</b> ——
     * 网络慢时先发的请求可能后到，不比对就会用旧金额覆盖掉新金额。
     */
    private LocalDateTime previewAt;

    /**
     * 在店时长（分钟）= 预览时刻 − 开门时刻，向下取整。
     *
     * <p><b>与账单里的 {@code totalMinutes}（计费时长）不是一回事</b>：
     * 计费时长是把包场时段剪掉之后的余量，整段落在包场里时是 0，
     * 而在店时长可能有好几个小时。只给计费时长的话，
     * 包场用户会看到「本次用时 0 分钟」，看起来像个 bug。
     */
    private long stayMinutes;

    /**
     * 实际参与计费的起点。通常等于 {@link #startTime}；命中包场时晚于它。
     *
     * <p><b>不要从它反推「是否减免了时长」</b>：包场人提前到店时，
     * 计费区间是「包场前 + 包场后」两段，第一段仍从开门时刻起算，
     * 本字段等于 {@code startTime}，但一整段包场时段确实被免掉了。
     * 要看 {@link #freeByBooking}。
     *
     * <p>另注意：整段被包场覆盖时（零账单）它等于 {@link #previewAt}，
     * 那是零账单的既定约定，含义是「这段没有任何计费」，不是「计费从此刻开始」。
     */
    private LocalDateTime billingStart;

    /** 本次是否因包场而减免了时长。为 true 时账单页要说明「包场时段不计费」 */
    private boolean freeByBooking;

    /**
     * 当前账单是否已全部达到封顶价 —— 各计费段的实收金额都已等于其封顶值。
     *
     * <p><b>不要与计费段的 {@code capped} 混用</b>：那个表示「封顶前金额已超过封顶」，
     * 要到第 11 档（5 小时 6 分）起才为 true；而「金额已经到顶、不会再涨」
     * 从第 10 档（4 小时 36 分）就成立了 —— 那段时间 {@code capped} 是 false，
     * 靠它判断会漏报。段级的「已达封顶」用「实收 ≥ 封顶」判，同一个道理。
     *
     * <p>前端据此提示「当前已到封顶价」。<b>措辞不要写成「继续玩也不会更贵」</b>：
     * 本字段只对当前时刻的账单成立，跨过日场与夜场的边界会产生新的一段、
     * 从那一刻起重新计费。整段被包场覆盖时没有计费段，本字段为 false。
     */
    private boolean cappedNow;

    /**
     * 停止计时后是否无需支付、直接结清（账单金额为 0）。
     *
     * <p>为 true 时前端应提示「本次无需支付，点「停止计时」即结束」，
     * 不要引导用户去「去支付」—— 0 元单没有可支付的通道。
     * 判定与结算共用同一处实现，不会出现「预览说不用付、结算却要收钱」。
     *
     * <p><b>它描述的是预览时刻，不是承诺</b>：金额随时长增长，
     * 跨过档位边界（5、35、65… 分钟）后原本 0 元的账单可能变成几元。
     * 前端<b>必须以下一步「停止计时」返回的账单为准</b>，
     * 不能拿这个字段决定「不用去支付页了」。
     */
    private boolean willAutoSettle;

    /**
     * 停止计时后订单将处于的状态名，取值 {@code PENDING_PAYMENT} 或 {@code PAID}。
     *
     * <p>由后端直接给出，而不是让前端按金额自己判：0 元直通已支付是<b>结算的规则</b>，
     * 只应有一处实现 —— 前端自己判的那份会在规则变化时静默变错。
     */
    private String statusAfterStop;

    /** {@link #statusAfterStop} 的中文说明，供前端直接展示 */
    private String statusAfterStopText;

    /**
     * 分段账单。结构与结算返回的完全一致（同一个 {@link BillingResult}），
     * 前端可以复用同一套账单组件按段渲染。
     */
    private BillingResult bill;

    /**
     * 由实体与账单构造预览视图。
     *
     * <p>三个派生值由调用方算好后传入，本方法不再判断任何业务规则 ——
     * 「0 元直通已支付」这类规则只应存在于结算那一段代码里。
     * {@code willAutoSettle} 由 {@code statusAfterStop} 推出而不单独传：
     * 两者是同一件事的两种说法，分开传就可能对不上。
     *
     * @param order           订单（<b>只读</b>，本方法不改它的任何字段）
     * @param previewAt       预览时刻
     * @param bill            分段账单
     * @param freeByBooking   本次是否命中包场而减免了时长
     * @param cappedNow       当前账单是否已全部达到封顶
     * @param statusAfterStop 停止计时后订单将处于的状态名
     * @return 结账预览视图
     */
    public static OrderPreviewVo of(Order order, LocalDateTime previewAt, BillingResult bill,
                                    boolean freeByBooking, boolean cappedNow,
                                    String statusAfterStop) {
        OrderPreviewVo vo = new OrderPreviewVo();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStartTime(order.getStartTime());
        vo.setPreviewAt(previewAt);
        // 向下取整，与计费取时长的口径保持一致；兜底 Math.max(0, …) 只为挡住脏数据
        // （开门时刻晚于此刻），正常流程下不可能是负数
        vo.setStayMinutes(Math.max(0, Duration.between(order.getStartTime(), previewAt).toMinutes()));
        vo.setBillingStart(bill.getStartTime());
        vo.setFreeByBooking(freeByBooking);
        vo.setCappedNow(cappedNow);
        vo.setStatusAfterStop(statusAfterStop);
        vo.setStatusAfterStopText(OrderStatus.labelOf(statusAfterStop));
        vo.setWillAutoSettle(OrderStatus.PAID.name().equals(statusAfterStop));
        vo.setBill(bill);
        return vo;
    }
}
