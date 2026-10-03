package com.kaede.uspace.qqbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.PaidCategory;
import com.kaede.uspace.order.PaymentTargetHandler;
import com.kaede.uspace.order.PaymentTargetType;
import com.kaede.uspace.order.dto.PaymentTarget;
import com.kaede.uspace.qqbot.protocol.OneBotEvent;
import com.kaede.uspace.qqbot.protocol.OneBotMessageSegment;
import com.kaede.uspace.user.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QqPaymentProofService} 的单元测试：<b>群里的图什么时候算付款凭证</b>。
 *
 * <p><b>重点守的是 2026-10-04 现场撞到的那个 bug</b>：等待表的键曾经一边用
 * {@code sys_user.id}、一边用 QQ 号，两者永远对不上 ——
 * 表现是「发完指令再发图毫无反应」，而且<b>一行日志都没有</b>
 * （「没在等」那条分支是刻意静默的）。所以本类里 {@link #QQ} 与 {@link #USER_ID}
 * <b>刻意取两个完全不同的数</b>，谁把它们当成同一个，用例立刻红。
 *
 * <p>本类是纯单测，不连库、不起 Spring：出站与「这笔账还在不在待支付」
 * 都由下面两个桩顶替。目标的状态校验走的是模块 8 已有的
 * {@link PaymentTargetHandler}，所以桩只需覆写 {@code loadForProof} 一个方法 ——
 * 不必再去组装真实的订单服务（那是 {@code OrderServiceTests} 的活）。
 */
class QqPaymentProofServiceTests {

    /** 群事件里的 {@code user_id}，也就是 QQ 号 */
    private static final Long QQ = 2198047522L;

    /** {@code sys_user.id}。⚠️ 刻意与 QQ 差得远 —— 混用时才能暴露出来 */
    private static final Long USER_ID = 1249L;

    /** 别人（另一个群成员）的 QQ */
    private static final Long OTHER_QQ = 1241397393L;

    private static final Long GROUP_ID = 10001L;

    private static final Long ORDER_ID = 2516L;

    /** 商品购买单 ID，与订单 ID 刻意不同 */
    private static final Long PRODUCT_ORDER_ID = 8021L;

    /** 时刻固定，用例因此与运行时刻无关 */
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-04T01:20:00Z"), ZoneId.of("Asia/Shanghai"));

    private RecordingClient client;

    private StubTargetHandler orderHandler;

    private StubTargetHandler productHandler;

    private QqPaymentProofService service;

    private SysUser user;

    @BeforeEach
    void setUp() {
        client = new RecordingClient();
        orderHandler = new StubTargetHandler(PaymentTargetType.ORDER);
        productHandler = new StubTargetHandler(PaymentTargetType.PRODUCT);
        service = new QqPaymentProofService(client, null, null, null,
                List.of(orderHandler, productHandler), CLOCK);

        user = new SysUser();
        user.setId(USER_ID);
        user.setQq(String.valueOf(QQ));
    }

    // ==================================================================
    // 等待表的键：QQ 号
    // ==================================================================

    @Test
    @DisplayName("⚠️ 回归：登记之后，本人发来的图必须被认出来（不是静默丢弃）")
    void handleImages_matchesByQq() {
        service.expect(user, PaymentTargetType.ORDER, ORDER_ID);
        // 让流程停在「载入目标」那一步 —— 本用例要证的只是「等待表命中了」，
        // 后面的取图、提交另有别的用例与集成链路覆盖
        orderHandler.answer = BizResult.fail(ErrorCode.ORDER_NOT_FOUND);

        service.handleImages(imageEvent(QQ));

        assertEquals(1, client.groupMessages.size(),
                "登记之后发图必须至少有一句回话。一个字都没有，就说明等待表的键写错了 —— "
                        + "expect 用 sys_user.id、handleImages 用 QQ 号时就是这个症状"
                        + "（两者是不同的数：id=" + USER_ID + "、qq=" + QQ + "）");
        assertTrue(client.groupMessages.get(0).contains("没找到那笔待付的账"),
                client.groupMessages.get(0));
    }

    @Test
    @DisplayName("载入目标用的是 sys_user.id，不是 QQ 号")
    void handleImages_loadsTargetByUserId() {
        service.expect(user, PaymentTargetType.ORDER, ORDER_ID);
        orderHandler.answer = BizResult.fail(ErrorCode.ORDER_NOT_FOUND);

        service.handleImages(imageEvent(QQ));

        assertEquals(USER_ID, orderHandler.lastUserId,
                "拿 QQ 号去载入目标会查不到（两者是不同的数），而且查不到只会回一句"
                        + "「没找到那笔待付的账」，看起来像「单子丢了」而不是「ID 传错了」");
        assertEquals(ORDER_ID, orderHandler.lastTargetId);
        assertNull(productHandler.lastTargetId, "登记的是订单，就不该去问商品那个处理器");
    }

    // ==================================================================
    // 两类账各走各的处理器
    // ==================================================================

    @Test
    @DisplayName("登记的是商品时，问的是商品那一个处理器")
    void handleImages_routesByTargetType() {
        service.expect(user, PaymentTargetType.PRODUCT, PRODUCT_ORDER_ID);
        productHandler.answer = BizResult.fail(ErrorCode.PRODUCT_ORDER_NOT_FOUND);

        service.handleImages(imageEvent(QQ));

        assertEquals(PRODUCT_ORDER_ID, productHandler.lastTargetId);
        assertEquals(USER_ID, productHandler.lastUserId);
        assertNull(orderHandler.lastTargetId,
                "⚠️ 类型的路由写错的话，拿商品单 ID 去订单表里查 —— 查到的是「不存在」，"
                        + "用户看到的是「没找到那笔待付的账」，而单子明明就在那里");
    }

    @Test
    @DisplayName("目标已不是待支付时说清楚，不重复提交")
    void handleImages_reportsWhenTargetAlreadyPaid() {
        service.expect(user, PaymentTargetType.ORDER, ORDER_ID);
        orderHandler.answer = BizResult.ok(target(PaymentTarget.STATUS_PAID));

        service.handleImages(imageEvent(QQ));

        assertEquals(1, client.groupMessages.size());
        assertTrue(client.groupMessages.get(0).contains("已经不是待支付状态"),
                "他在网页端付过了 —— 要说明白，别让他以为图没发出去：" + client.groupMessages.get(0));
    }

    // ==================================================================
    // 安全边界：不在等，就一个字都不回
    // ==================================================================

    @Test
    @DisplayName("没登记过时，群里的图一律静默（表情包不该被当成凭证）")
    void handleImages_silentWhenNotWaiting() {
        service.handleImages(imageEvent(QQ));

        assertTrue(client.groupMessages.isEmpty(),
                "群里绝大多数图是闲聊，而凭证是提交即落账的 —— 没在等就必须完全不作声");
    }

    @Test
    @DisplayName("登记之后，别人发的图不算数 —— 认的是发送者 QQ")
    void handleImages_ignoresOtherSenders() {
        service.expect(user, PaymentTargetType.ORDER, ORDER_ID);
        orderHandler.answer = BizResult.fail(ErrorCode.ORDER_NOT_FOUND);

        service.handleImages(imageEvent(OTHER_QQ));
        assertTrue(client.groupMessages.isEmpty(), "等的是「他」的那张图，不是群里随便谁发的");

        service.handleImages(imageEvent(QQ));
        assertEquals(1, client.groupMessages.size(), "他自己发仍然要认");
    }

    // ==================================================================
    // 只受理一张
    // ==================================================================

    @Test
    @DisplayName("一张图受理完，这次等待就结束了 —— 第二张不再认")
    void handleImages_onlyFirstImageCounts() {
        service.expect(user, PaymentTargetType.ORDER, ORDER_ID);
        orderHandler.answer = BizResult.fail(ErrorCode.ORDER_NOT_FOUND);

        service.handleImages(imageEvent(QQ));
        service.handleImages(imageEvent(QQ));

        assertEquals(1, client.groupMessages.size(),
                "一次指令只该收一张图：等待若不在失败分支上结束，他会一直以为自己还欠一张");
    }

    // ==================================================================
    // 桩
    // ==================================================================

    /**
     * 只记下发出的群消息、不碰连接的出站桩。
     */
    private static class RecordingClient extends OneBotClient {

        private final List<String> groupMessages = new ArrayList<>();

        RecordingClient() {
            // 这个 ObjectMapper 只在真正发送时才用得到，本桩不会走到那里
            super(new ObjectMapper());
        }

        @Override
        public boolean sendGroupMessage(Long groupId, String text) {
            groupMessages.add(text);
            return true;
        }
    }

    /**
     * 只覆写 {@code loadForProof} 的收款处理器桩 —— 用例自己决定「这笔账还在不在待支付」。
     *
     * <p>其余方法一律抛 {@link UnsupportedOperationException}：真被碰到时是响亮的失败，
     * 不会静默通过。
     */
    private static class StubTargetHandler implements PaymentTargetHandler {

        private final PaymentTargetType type;

        private BizResult<PaymentTarget> answer = BizResult.fail(ErrorCode.ORDER_NOT_FOUND);

        private Long lastTargetId;

        private Long lastUserId;

        StubTargetHandler(PaymentTargetType type) {
            this.type = type;
        }

        @Override
        public PaymentTargetType type() {
            return type;
        }

        @Override
        public PaidCategory paidCategory() {
            return PaidCategory.ORDER;
        }

        @Override
        public boolean deliverOnSubmit() {
            return true;
        }

        @Override
        public BizResult<PaymentTarget> loadForProof(Long id, Long userId) {
            this.lastTargetId = id;
            this.lastUserId = userId;
            return answer;
        }

        @Override
        public BizResult<PaymentTarget> loadForPay(Long id, Long userId) {
            throw new UnsupportedOperationException("本桩只用于提交凭证那条路");
        }

        @Override
        public PaymentTarget loadByOutTradeNo(String outTradeNo) {
            throw new UnsupportedOperationException("本桩只用于提交凭证那条路");
        }

        @Override
        public boolean markPaid(PaymentTarget target, com.kaede.uspace.order.PaymentChannel channel,
                                String transactionNo, java.time.LocalDateTime paidAt,
                                Long confirmedBy) {
            throw new UnsupportedOperationException("本桩只用于提交凭证那条路");
        }
    }

    /**
     * 造一个处于指定状态的收款目标。
     *
     * @param status 状态名，见 {@link PaymentTarget#STATUS_PENDING_PAYMENT}
     * @return 目标
     */
    private static PaymentTarget target(String status) {
        PaymentTarget target = new PaymentTarget();
        target.setType(PaymentTargetType.ORDER);
        target.setId(ORDER_ID);
        target.setUserId(USER_ID);
        target.setAmount(new BigDecimal("9.00"));
        target.setOutTradeNo("OD202610040116074492");
        target.setStatus(status);
        return target;
    }

    /**
     * 造一条「某人发了一张图」的群事件。
     *
     * <p>用 {@code message_sent} 而不是 {@code message}：本机就是这样 ——
     * NapCat 开着 {@code reportSelfMessage}，运营者用同一个 QQ 在手机上发消息，
     * 走的是「自己那一侧」那条路。
     *
     * @param senderQq 发送者 QQ
     * @return 带一个图片段的事件
     */
    private static OneBotEvent imageEvent(Long senderQq) {
        OneBotMessageSegment segment = new OneBotMessageSegment();
        segment.setType(OneBotMessageSegment.TYPE_IMAGE);
        segment.setData(Map.of("file", "proof.jpg", "url", "http://127.0.0.1:9999/proof.jpg"));

        OneBotEvent event = new OneBotEvent();
        event.setPostType(OneBotEvent.POST_TYPE_MESSAGE_SENT);
        event.setMessageType(OneBotEvent.MESSAGE_TYPE_GROUP);
        event.setGroupId(GROUP_ID);
        event.setUserId(senderQq);
        event.setSelfId(QQ);
        event.setMessageId(1L);
        event.setRawMessage("[CQ:image,file=proof.jpg]");
        event.setMessage(List.of(segment));
        return event;
    }
}
