package com.kaede.uspace.order;

import com.kaede.uspace.common.trade.TradeSource;
import com.kaede.uspace.order.entity.TradeLog;
import com.kaede.uspace.product.event.ProductOrderCancelledEvent;
import com.kaede.uspace.promotion.event.CardPurchaseCancelledEvent;
import com.kaede.uspace.space.event.BookingCancelledEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link TradeLogListener} 的单元测试。
 *
 * <p>它守的是三个包各发各的事件、翻译成同一本账这一段：
 * <b>类型、单号、金额、操作人</b>任何一项对错了都不会报错 ——
 * 账上只是悄悄记错一行，而这一本账本来就是给人复盘用的。
 */
class TradeLogListenerTests {

    /** 假流水表与真监听器 */
    private final FakeTradeLogMapper mapper = new FakeTradeLogMapper();

    private final TradeLogListener listener =
            new TradeLogListener(new TradeLogService(mapper.asMapper()));

    @Test
    @DisplayName("商品单取消 → 记成 PRODUCT，操作人为空（那一步是用户自己做的）")
    void productCancelled() {
        listener.onProductOrderCancelled(new ProductOrderCancelledEvent(
                "PD20261010001", 1001L, new BigDecimal("7.00"), TradeSource.WEB));

        TradeLog row = mapper.rows().get(0);
        assertEquals(TradeEventType.CANCEL_UNPAID.name(), row.getEventType());
        assertEquals(PaymentTargetType.PRODUCT.name(), row.getTargetType(),
                "类型记错会让复盘时去错误的表里找这笔单");
        assertEquals("PD20261010001", row.getTargetNo());
        assertEquals(1001L, row.getUserId());
        assertEquals(0, new BigDecimal("7.00").compareTo(row.getAmount()));
        assertEquals(TradeSource.WEB.name(), row.getSource());
        assertNull(row.getOperatorId(), "用户自己取消 —— 流水里的 user 就是他，没有代劳的人");
    }

    @Test
    @DisplayName("月卡购买单取消 → 记成 MONTHLY_CARD")
    void cardPurchaseCancelled() {
        listener.onCardPurchaseCancelled(new CardPurchaseCancelledEvent(
                "MC20261010001", 1002L, new BigDecimal("320.00"), TradeSource.QQ));

        TradeLog row = mapper.rows().get(0);
        assertEquals(PaymentTargetType.MONTHLY_CARD.name(), row.getTargetType());
        assertEquals("MC20261010001", row.getTargetNo());
        assertEquals(TradeSource.QQ.name(), row.getSource(), "群里取消的要记成 QQ");
        assertNull(row.getOperatorId());
    }

    @Test
    @DisplayName("⚠️ 守门：管理员取消包场 → 记下是哪个管理员（operator_id）")
    void bookingCancelledByAdmin() {
        listener.onBookingCancelled(new BookingCancelledEvent(
                "BK20261010001", 2002L, new BigDecimal("300.00"),
                TradeSource.ADMIN, 9L));

        TradeLog row = mapper.rows().get(0);
        assertEquals(PaymentTargetType.BOOKING.name(), row.getTargetType());
        assertEquals("BK20261010001", row.getTargetNo());
        assertEquals(2002L, row.getUserId(), "user 那一列是包场人");
        assertEquals(9L, row.getOperatorId(),
                "⚠️ 包场是四类里唯一管理员能亲手取消的 —— 账上要说清是谁取消的，"
                        + "否则事后只能凭「来源=管理后台」猜");
    }

    @Test
    @DisplayName("包场人在群里取消自己的场子 → 操作人为空，来源记 QQ")
    void bookingCancelledByHost() {
        listener.onBookingCancelled(new BookingCancelledEvent(
                "BK20261010002", 2003L, new BigDecimal("120.00"),
                TradeSource.QQ, null));

        TradeLog row = mapper.rows().get(0);
        assertEquals(TradeSource.QQ.name(), row.getSource());
        assertNull(row.getOperatorId(), "他取消的是自己的场子 —— 不是代劳");
    }

    @Test
    @DisplayName("三个包各发各的事件，落进同一张表（一本账，不是三本）")
    void allThreeLandInOneLedger() {
        listener.onProductOrderCancelled(new ProductOrderCancelledEvent(
                "PD-1", 1001L, new BigDecimal("3.50"), TradeSource.WEB));
        listener.onCardPurchaseCancelled(new CardPurchaseCancelledEvent(
                "MC-1", 1002L, new BigDecimal("600.00"), TradeSource.WEB));
        listener.onBookingCancelled(new BookingCancelledEvent(
                "BK-1", 1003L, new BigDecimal("300.00"), TradeSource.ADMIN, 9L));

        assertEquals(3, mapper.size(), "三条各自成行，顺序即发生顺序");
        assertEquals(PaymentTargetType.PRODUCT.name(), mapper.rows().get(0).getTargetType());
        assertEquals(PaymentTargetType.MONTHLY_CARD.name(), mapper.rows().get(1).getTargetType());
        assertEquals(PaymentTargetType.BOOKING.name(), mapper.rows().get(2).getTargetType(),
                "三种类型不能互相串 —— 串了不会有任何报错，只会让对账翻错表");
    }
}
