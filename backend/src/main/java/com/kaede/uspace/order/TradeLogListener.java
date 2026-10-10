package com.kaede.uspace.order;

import com.kaede.uspace.product.event.ProductOrderCancelledEvent;
import com.kaede.uspace.promotion.event.CardPurchaseCancelledEvent;
import com.kaede.uspace.space.event.BookingCancelledEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 把三个模块发来的「未付款单被取消」事件翻译成交易流水（模块 8）。
 *
 * <p><b>为什么取消那三类要走事件，而不是各自直接写流水</b>：
 * 商品、月卡购买单、包场分属 {@code product} / {@code promotion} / {@code space}
 * 三个包，而流水表与 {@code TradeLogService} 住在 {@code order}。
 * 业务包之间的依赖是单向的（见 CLAUDE.md「代码组织」），那三个包反向 import
 * {@code order} 就会成环。事件把这条边解掉：业务侧只发布自己包里的一个 record，
 * 本类负责听懂它。与 qqbot 的播报是同一套模式
 * （业务侧发事件、监听方在运行时才认识它）。
 *
 * <p>⚠️ <b>监听器用普通 {@code @EventListener}，不是
 * {@code @TransactionalEventListener(AFTER_COMMIT)}</b>，两处理由：
 * <ol>
 *   <li>流水必须与业务动作<b>同事务</b>落库 —— 要么都成、要么都不成。
 *       取 AFTER_COMMIT 的话，事务已经提交了，流水插入失败也不会回滚业务动作，
 *       正是「单取消了、流水没有」那一类半成品</li>
 *   <li>AFTER_COMMIT 相位上连接已经归还给连接池，监听器里写库会踩到
 *       「既不在事务里、也不会被提交」的坑（{@code QqBroadcastListener}
 *       的类注释里记着这条）</li>
 * </ol>
 *
 * <p>⚠️ <b>事件类住在发布方所在的包</b>（{@code product/event/}、
 * {@code promotion/event/}、{@code space/event/}），与 qqbot 播报同一规矩：
 * 事件一旦放进 order，那三个包就得反过来 import order。
 *
 * <p>本类的方法体<b>刻意不做任何判断</b> —— 事件里的字段已经是流水要的全部内容，
 * 翻译只有「哪一类目标」这一步。要加判断的话，先想清楚该不该加在发布方。
 */
@Slf4j
@Component
public class TradeLogListener {

    private final TradeLogService tradeLogService;

    /**
     * 构造器注入。
     *
     * @param tradeLogService 流水落账
     */
    public TradeLogListener(TradeLogService tradeLogService) {
        this.tradeLogService = tradeLogService;
    }

    /**
     * 商品购买单被取消（用户在网页端或群里点的）。
     *
     * @param event 事件
     */
    @EventListener
    public void onProductOrderCancelled(ProductOrderCancelledEvent event) {
        // 操作人恒为 null：商品单的取消只有用户自己能发（流水里的 user 就是他）
        tradeLogService.recordCancelUnpaid(PaymentTargetType.PRODUCT, event.orderNo(),
                event.userId(), event.amount(), event.source(), null);
    }

    /**
     * 月卡购买单被取消。
     *
     * @param event 事件
     */
    @EventListener
    public void onCardPurchaseCancelled(CardPurchaseCancelledEvent event) {
        // 操作人恒为 null：同上，月卡购买单的取消也只有用户自己能发
        tradeLogService.recordCancelUnpaid(PaymentTargetType.MONTHLY_CARD, event.orderNo(),
                event.userId(), event.amount(), event.source(), null);
    }

    /**
     * 未付款的包场被取消。
     *
     * <p>⚠️ 已付款的包场走的是「撤销 + 退款」（{@code BookingRefundService}），
     * 那不是「未付款取消」，<b>刻意不经过本类</b> —— 它的钱发生了进出，
     * 而流水目前只记到账与未付款取消两端（退款那条线在包场自己的退款四列里）。
     *
     * @param event 事件
     */
    @EventListener
    public void onBookingCancelled(BookingCancelledEvent event) {
        // 只有包场这一条会带操作人：它有两种取消入口 —— 管理员在后台取消
        // （operatorId = 管理员）与包场人在群里取消自己的场子（null，user 就是他）
        tradeLogService.recordCancelUnpaid(PaymentTargetType.BOOKING, event.bookingNo(),
                event.hostUserId(), event.amount(), event.source(), event.operatorId());
    }
}
