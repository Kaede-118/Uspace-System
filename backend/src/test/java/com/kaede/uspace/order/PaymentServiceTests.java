package com.kaede.uspace.order;

import com.kaede.uspace.common.config.WebProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.CreatePaymentRequest;
import com.kaede.uspace.order.dto.PaymentCreateVo;
import com.kaede.uspace.order.dto.PaymentNotifyRequest;
import com.kaede.uspace.order.dto.PaymentNotifyResult;
import com.kaede.uspace.order.dto.PaymentQueryResult;
import com.kaede.uspace.order.dto.PaymentStatusVo;
import com.kaede.uspace.order.dto.PaymentTarget;
import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.promotion.CardOrderStatus;
import com.kaede.uspace.promotion.FakeMonthlyCardMapper;
import com.kaede.uspace.promotion.FakeMonthlyCardOrderMapper;
import com.kaede.uspace.promotion.MonthlyCardNo;
import com.kaede.uspace.promotion.MonthlyCardStatus;
import com.kaede.uspace.promotion.MonthlyCardType;
import com.kaede.uspace.promotion.PromotionProperties;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.promotion.entity.MonthlyCardOrder;
import com.kaede.uspace.space.BookingService;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.ClosureService;
import com.kaede.uspace.space.FakeBookingMapper;
import com.kaede.uspace.space.FakeBookingParticipantMapper;
import com.kaede.uspace.space.FakeClosureMapper;
import com.kaede.uspace.space.FakeStoreMapper;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.user.FakeSysUserMapper;
import com.kaede.uspace.user.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaymentService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>支付网关由 {@link FakePaymentGateway}
 * 顶替 —— 它把「返回什么」变成测试可以直接摆布的东西，于是验签失败、
 * 金额不符、查单补偿这些分支都能被精确构造出来。
 *
 * <p>本类覆盖的三块重点：
 * <ol>
 *   <li><b>回调幂等</b> —— 支付平台会重推，重复回调绝不能把用户消费额记两次。
 *       这里既测「已支付时直接返回」，也测「条件更新返回 0 行时不累加」这条并发路径</li>
 *   <li><b>金额核对</b> —— 回调端点匿名可达，验签是主要防线，金额核对是第二道。
 *       金额不符必须拒绝入账，而不是按回调的金额记账</li>
 *   <li><b>补偿路径与回调走同一段逻辑</b> —— 否则两条链路迟早在某处漂移，
 *       而那种偏差不会报错，只会让某个用户的账单悄悄对不上</li>
 * </ol>
 */
class PaymentServiceTests {

    private static final Long USER_ID = 1001L;
    private static final Long OTHER_USER = 1002L;
    private static final Long STORE_ID = 1L;
    private static final BigDecimal AMOUNT = new BigDecimal("22.00");

    /** 全天月卡的售价，与默认配置一致 */
    private static final BigDecimal CARD_PRICE = new BigDecimal("600.00");

    private final FakeOrderMapper orderMapper = new FakeOrderMapper();
    private final FakeBookingMapper bookingMapper = new FakeBookingMapper();
    private final FakeSysUserMapper userMapper = new FakeSysUserMapper();
    private final FakePaymentGateway gateway = new FakePaymentGateway();

    /**
     * 参与者表。与 {@code bookingMapper} 共享数据 —— 包场付款成功时
     * {@code BookingPaymentTargetHandler} 会往里写一行 HOST，下面的用例要断言它。
     */
    private final FakeBookingParticipantMapper participantMapper =
            new FakeBookingParticipantMapper(bookingMapper);

    /**
     * 邀请令牌服务靠它组装参与者名单。
     *
     * <p>本测试只用得到 {@code generate()}（处理器付款时生成令牌），
     * 用不到名单与加入那两条路径 —— 这里的 BookingService 是为了满足构造，
     * 依赖是真的，不是替身。
     */
    private final FakeStoreMapper storeMapper = new FakeStoreMapper();
    private final ClosureService closureService =
            new ClosureService(new FakeClosureMapper().asMapper(), storeMapper.asMapper());
    private final BookingService bookingService = new BookingService(
            bookingMapper.asMapper(), storeMapper.asMapper(), closureService,
            userMapper.asMapper(), participantMapper.asMapper());

    private final InviteTokenService inviteTokenService = new InviteTokenService(
            bookingMapper.asMapper(), bookingService, new WebProperties());

    /** 月卡的两张表与配置，供月卡处理器使用 */
    private final FakeMonthlyCardMapper cardMapper = new FakeMonthlyCardMapper();
    private final FakeMonthlyCardOrderMapper cardOrderMapper = new FakeMonthlyCardOrderMapper();
    private final PromotionProperties promotionProperties = new PromotionProperties();

    /**
     * 支付配置。
     *
     * <p><b>刻意把四条通道全开</b>：本类测的是支付链路本身（发起、回调、查单、核销），
     * 而 {@code PaymentProperties} 的默认值只开放扫码转账 —— 那是投产态的配置。
     * 不配置的话，本类里所有走线上通道的用例都会撞上
     * {@code PAYMENT_CHANNEL_DISABLED}。通道准入本身由
     * {@code PaymentChannelServiceTests} 单独覆盖，不在这里重复测。
     */
    private final PaymentProperties paymentProperties = allChannelsEnabled();

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(gateway, paymentProperties,
                List.of(new OrderPaymentTargetHandler(orderMapper.asMapper()),
                        new BookingPaymentTargetHandler(bookingMapper.asMapper(),
                                inviteTokenService, participantMapper.asMapper()),
                        new MonthlyCardPaymentTargetHandler(cardOrderMapper.asMapper(),
                                cardMapper.asMapper(), promotionProperties)),
                userMapper.asMapper());
    }

    /**
     * 造一份「四条通道全开」的配置。
     *
     * <p>用 {@code values()} 而不是逐个列出：新增通道时本类会自动跟上，
     * 不必记得回来改 —— 这个配置的意图本就是「不受通道开关影响」。
     *
     * @return 全部通道都启用的配置对象
     */
    private static PaymentProperties allChannelsEnabled() {
        PaymentProperties properties = new PaymentProperties();
        properties.setEnabledChannels(
                Arrays.stream(PaymentChannel.values()).map(Enum::name).toList());
        return properties;
    }

    // ==================================================================
    // 发起支付
    // ==================================================================

    @Test
    @DisplayName("发起：订单不属于本人时返回 404")
    void createPayment_returns404ForOthersOrder() {
        Order order = seedPendingOrder(USER_ID);

        BizResult<PaymentCreateVo> result = service.createPayment(
                OTHER_USER, request(PaymentTargetType.ORDER, order.getId(), PaymentChannel.WXPAY_H5), null);

        assertEquals(ErrorCode.ORDER_NOT_FOUND, result.getError(),
                "别人的订单不该能拿到支付参数 —— 虽然损失的是付款人自己，但足以造成对账混乱");
    }

    @Test
    @DisplayName("发起：订单不在待支付状态时拒绝")
    void createPayment_rejectsNonPendingOrder() {
        Order order = seedPendingOrder(USER_ID);
        order.setStatus(OrderStatus.IN_USE.name());

        BizResult<PaymentCreateVo> result = service.createPayment(
                USER_ID, request(PaymentTargetType.ORDER, order.getId(), PaymentChannel.WXPAY_H5), null);

        assertEquals(ErrorCode.ORDER_STATUS_INVALID, result.getError(), "还在玩的订单没有账单可付");
        assertEquals(0, gateway.createCalls(), "状态不对就不该去调网关");
    }

    @Test
    @DisplayName("发起：传给网关的单号与金额取自订单")
    void createPayment_passesOrderNoAndAmount() {
        Order order = seedPendingOrder(USER_ID);

        BizResult<PaymentCreateVo> result = service.createPayment(
                USER_ID, request(PaymentTargetType.ORDER, order.getId(), PaymentChannel.WXPAY_JSAPI), null);

        assertTrue(result.isSuccess(), "正常发起应当成功");
        assertEquals(order.getOrderNo(), gateway.lastCommand().getOutTradeNo(),
                "商户订单号直接用订单号，不另生成一个 —— 多一套编号就多一处对不上的可能");
        assertEquals(0, AMOUNT.compareTo(gateway.lastCommand().getAmount()),
                "金额取自 payable_amount，将来有优惠券时才与实收合计分叉");
    }

    @Test
    @DisplayName("发起：三个通道各自返回对应的凭据")
    void createPayment_returnsChannelSpecificPayload() {
        Order order = seedPendingOrder(USER_ID);

        BizResult<PaymentCreateVo> jsapi = service.createPayment(
                USER_ID, request(PaymentTargetType.ORDER, order.getId(), PaymentChannel.WXPAY_JSAPI), null);
        assertNotNull(jsapi.getData().getPrepayId(), "JSAPI 给的是预支付会话标识");
        assertNull(jsapi.getData().getH5Url(), "JSAPI 不该给跳转链接");

        BizResult<PaymentCreateVo> h5 = service.createPayment(
                USER_ID, request(PaymentTargetType.ORDER, order.getId(), PaymentChannel.WXPAY_H5), null);
        assertNotNull(h5.getData().getH5Url(), "H5 给的是跳转链接");
        assertNull(h5.getData().getPrepayId(), "H5 不该给预支付标识");

        BizResult<PaymentCreateVo> wap = service.createPayment(
                USER_ID, request(PaymentTargetType.ORDER, order.getId(), PaymentChannel.ALIPAY_WAP), null);
        assertNotNull(wap.getData().getFormHtml(), "支付宝给的是自动提交的表单");
    }

    @Test
    @DisplayName("发起：人工核销通道不调用支付网关")
    void createPayment_doesNotCallGatewayForQrUpload() {
        Order order = seedPendingOrder(USER_ID);

        BizResult<PaymentCreateVo> result = service.createPayment(
                USER_ID, request(PaymentTargetType.ORDER, order.getId(), PaymentChannel.QR_UPLOAD), null);

        assertTrue(result.isSuccess(), "选择人工核销不是错误，只是不走线上");
        assertEquals(0, gateway.createCalls(),
                "人工核销没有线上支付可发起，调网关只会白白失败一次");
    }

    @Test
    @DisplayName("发起：网关失败时返回 502，而不是语焉不详的 500")
    void createPayment_returns502WhenGatewayFails() {
        Order order = seedPendingOrder(USER_ID);
        gateway.failNextCreate();

        BizResult<PaymentCreateVo> result = service.createPayment(
                USER_ID, request(PaymentTargetType.ORDER, order.getId(), PaymentChannel.WXPAY_H5), null);

        assertEquals(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, result.getError(),
                "上游不可用要与用户操作不当区分开，否则监控里分不清该找谁");
    }

    @Test
    @DisplayName("发起：包场不属于本人时返回 404")
    void createPayment_returns404ForOthersBooking() {
        Booking booking = seedPendingBooking(USER_ID);

        BizResult<PaymentCreateVo> result = service.createPayment(
                OTHER_USER, request(PaymentTargetType.BOOKING, booking.getId(), PaymentChannel.WXPAY_H5), null);

        assertEquals(ErrorCode.BOOKING_NOT_FOUND, result.getError(),
                "包场只有包场人能付款 —— 被邀请者不需要付，包场费是预付款");
    }

    @Test
    @DisplayName("发起：包场已付款时拒绝重复支付")
    void createPayment_rejectsAlreadyPaidBooking() {
        Booking booking = seedPendingBooking(USER_ID);
        booking.setStatus(BookingStatus.PAID.name());

        BizResult<PaymentCreateVo> result = service.createPayment(
                USER_ID, request(PaymentTargetType.BOOKING, booking.getId(), PaymentChannel.WXPAY_H5), null);

        assertEquals(ErrorCode.BUSINESS_REJECTED, result.getError(), "已生效的包场不该再付一次");
    }

    // ==================================================================
    // 回调
    // ==================================================================

    @Test
    @DisplayName("回调：验签失败一律拒绝处理")
    void handleNotify_rejectsWhenSignatureInvalid() {
        gateway.withNotifyResult(FakePaymentGateway.invalidNotify("验签不通过"));

        boolean handled = service.handleWxpayNotify(new PaymentNotifyRequest());

        assertFalse(handled, "验签不过必须让平台重推并留下 error 日志");
    }

    @Test
    @DisplayName("回调：单号前缀认不出时不重推（重推也没用），但记 error 日志")
    void handleNotify_acknowledgesUnknownPrefix() {
        gateway.withNotifyResult(notify("XX20260928120000", AMOUNT));

        boolean handled = service.handleWxpayNotify(new PaymentNotifyRequest());

        assertTrue(handled, "前缀认不出说明单号规则改了或报文是伪造的，重推解决不了");
    }

    @Test
    @DisplayName("回调：单号在本系统不存在时不重推")
    void handleNotify_acknowledgesMissingOrder() {
        gateway.withNotifyResult(notify("OD202609281200000001", AMOUNT));

        boolean handled = service.handleWxpayNotify(new PaymentNotifyRequest());

        assertTrue(handled, "单号不存在，重推同样找不到，只能靠人工核对");
    }

    @Test
    @DisplayName("回调：订单已支付时幂等返回，且不重复累加消费额")
    void handleNotify_isIdempotentForPaidOrder() {
        Order order = seedPendingOrder(USER_ID);
        order.setStatus(OrderStatus.PAID.name());
        seedUser(USER_ID, BigDecimal.ZERO);
        gateway.withNotifyResult(notify(order.getOrderNo(), AMOUNT));

        boolean handled = service.handleWxpayNotify(new PaymentNotifyRequest());

        assertTrue(handled, "重复回调要返回成功，让平台别再推 —— 这是最常走到的分支");
        assertEquals(0, BigDecimal.ZERO.compareTo(userMapper.asMapper().selectById(USER_ID).getOrderPaid()),
                "已经记过一次的消费额不能再记一次");
    }

    @Test
    @DisplayName("回调：金额不符时拒绝入账，且不改订单状态")
    void handleNotify_rejectsAmountMismatch() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID, BigDecimal.ZERO);
        gateway.withNotifyResult(notify(order.getOrderNo(), new BigDecimal("0.01")));

        boolean handled = service.handleWxpayNotify(new PaymentNotifyRequest());

        assertFalse(handled, "金额对不上就绝不入账 —— 回调端点匿名可达，这是第二道防线");
        assertEquals(OrderStatus.PENDING_PAYMENT.name(), orderMapper.get(order.getId()).getStatus(),
                "状态不能被改动");
        assertEquals(0, BigDecimal.ZERO.compareTo(userMapper.asMapper().selectById(USER_ID).getOrderPaid()),
                "更不能累加消费额");
    }

    @Test
    @DisplayName("回调：订单处于不该收款的状态时拒绝，让平台持续重推以引起注意")
    void handleNotify_rejectsStatusConflict() {
        Order order = seedPendingOrder(USER_ID);
        order.setStatus(OrderStatus.IN_USE.name());
        gateway.withNotifyResult(notify(order.getOrderNo(), AMOUNT));

        boolean handled = service.handleWxpayNotify(new PaymentNotifyRequest());

        assertFalse(handled, "钱收了但单据状态不对，属于「钱与单不一致」，必须让人看见");
    }

    // ==================================================================
    // 月卡：记账口径与发卡
    // ==================================================================

    @Test
    @DisplayName("月卡：卡费记进 card_paid 而非 order_paid，并同时发一张卡")
    void monthCard_recordsAsCardPaidAndIssuesCard() {
        MonthlyCardOrder order = seedPendingCardOrder();
        seedUser(USER_ID, BigDecimal.ZERO);
        gateway.withNotifyResult(notify(order.getOrderNo(), CARD_PRICE));

        boolean handled = service.handleWxpayNotify(new PaymentNotifyRequest());

        assertTrue(handled, "月卡回调应当处理成功");

        SysUser user = userMapper.get(USER_ID);
        assertEquals(0, CARD_PRICE.compareTo(user.getCardPaid()), "卡费记进 card_paid");
        assertEquals(0, BigDecimal.ZERO.compareTo(user.getOrderPaid()),
                "关键：绝不能记进 order_paid —— 那是房间消费的口径。"
                        + "写错不会报任何错，只会让两个累计口径悄悄错位");
        assertEquals(0, CARD_PRICE.compareTo(user.getTotalPaid()), "总额是两类之和");

        assertEquals(1, cardMapper.size(), "付款成功要发一张卡");
        // 假 Mapper 的自增主键从 1 开始，且本用例只发了一张卡
        MonthlyCard card = cardMapper.get(1L);
        assertNotNull(card, "卡要落库");
        assertEquals(MonthlyCardStatus.ACTIVE.name(), card.getStatus());
        assertEquals(LocalDate.now(), card.getStartDate(), "生效日 = 支付当日");
        assertEquals(LocalDate.now().plusDays(29), card.getEndDate(),
                "含首尾共 30 天：30 → start + 29");
        assertEquals(order.getOrderNo(), card.getPayOrderNo(), "卡要记下是哪笔购买产生的");
        assertEquals(0, CARD_PRICE.compareTo(card.getPrice()), "卡价取下单时的快照");
    }

    @Test
    @DisplayName("月卡：重复回调只记一次账，也只发一张卡")
    void monthCard_duplicateNotifyIsIdempotent() {
        MonthlyCardOrder order = seedPendingCardOrder();
        seedUser(USER_ID, BigDecimal.ZERO);
        gateway.withNotifyResult(notify(order.getOrderNo(), CARD_PRICE));

        service.handleWxpayNotify(new PaymentNotifyRequest());
        service.handleWxpayNotify(new PaymentNotifyRequest());

        assertEquals(0, CARD_PRICE.compareTo(userMapper.get(USER_ID).getCardPaid()),
                "第二次回调不该再记一次卡费");
        assertEquals(1, cardMapper.size(),
                "更不能发第二张卡 —— 用户付一次钱拿到两张，是最典型的静默多发");
    }

    @Test
    @DisplayName("前缀：任意两个单号前缀互不为前缀，否则回调会被静默路由错")
    void targetTypePrefixes_areNotPrefixesOfEachOther() {
        for (PaymentTargetType outer : PaymentTargetType.values()) {
            for (PaymentTargetType inner : PaymentTargetType.values()) {
                if (outer == inner) {
                    continue;
                }
                assertFalse(inner.getOrderNoPrefix().startsWith(outer.getOrderNoPrefix()),
                        inner + " 的前缀 " + inner.getOrderNoPrefix()
                                + " 以 " + outer + " 的前缀 " + outer.getOrderNoPrefix()
                                + " 开头 —— 回调路由用的是 startsWith，"
                                + "这会让 " + inner + " 的回调被路由到 " + outer);
            }
        }
    }

    /**
     * 造一笔待支付的月卡购买单。
     *
     * @return 已落库的购买单（主键已分配）
     */
    private MonthlyCardOrder seedPendingCardOrder() {
        MonthlyCardOrder order = new MonthlyCardOrder();
        order.setOrderNo(MonthlyCardNo.generate());
        order.setUserId(USER_ID);
        order.setCardType(MonthlyCardType.ALL_DAY.name());
        order.setPrice(CARD_PRICE);
        order.setStatus(CardOrderStatus.PENDING_PAYMENT.name());
        return cardOrderMapper.seed(order);
    }

    @Test
    @DisplayName("回调：正常订单转已支付，写全支付字段并累加用户消费额")
    void handleNotify_marksOrderPaidAndAccumulates() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID, BigDecimal.ZERO);
        PaymentNotifyResult result = notify(order.getOrderNo(), AMOUNT);
        result.setTransactionNo("4200001234202609281234567890");
        result.setChannel(PaymentChannel.WXPAY_JSAPI);
        gateway.withNotifyResult(result);

        boolean handled = service.handleWxpayNotify(new PaymentNotifyRequest());

        assertTrue(handled, "正常回调应当处理成功");
        Order saved = orderMapper.get(order.getId());
        assertEquals(OrderStatus.PAID.name(), saved.getStatus(), "状态要流转为已支付");
        assertEquals("4200001234202609281234567890", saved.getPaymentNo(),
                "平台交易号要落库 —— 对账时拿它去支付平台后台能查到那一笔");
        assertEquals("WXPAY_JSAPI", saved.getPaymentMethod(), "通道也要记下，对账时能区分来源");
        assertNotNull(saved.getPaidAt(), "支付时刻要落库");
        assertNull(saved.getConfirmedBy(), "线上回调是系统自动确认，不是某个管理员核销的");

        SysUser user = userMapper.asMapper().selectById(USER_ID);
        assertEquals(0, AMOUNT.compareTo(user.getOrderPaid()), "订单消费额要累加");
        assertEquals(0, AMOUNT.compareTo(user.getTotalPaid()), "总额也要同步累加 —— 两列同源");
    }

    @Test
    @DisplayName("回调：重复投递同一条通知时只记账一次")
    void handleNotify_recordsOnlyOnceOnDuplicate() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID, BigDecimal.ZERO);
        PaymentNotifyResult result = notify(order.getOrderNo(), AMOUNT);
        result.setChannel(PaymentChannel.WXPAY_H5);
        gateway.withNotifyResult(result);

        service.handleWxpayNotify(new PaymentNotifyRequest());
        service.handleWxpayNotify(new PaymentNotifyRequest());

        SysUser user = userMapper.asMapper().selectById(USER_ID);
        assertEquals(0, AMOUNT.compareTo(user.getOrderPaid()),
                "两条回调进来，消费额只能加一次 —— 否则用户凭空多出一倍消费记录");
    }

    @Test
    @DisplayName("回调：包场付款成功后生成邀请令牌")
    void handleNotify_issuesInviteTokenForBooking() {
        Booking booking = seedPendingBooking(USER_ID);
        seedUser(USER_ID, BigDecimal.ZERO);
        PaymentNotifyResult result = notify(booking.getBookingNo(), new BigDecimal("500.00"));
        result.setChannel(PaymentChannel.WXPAY_JSAPI);
        gateway.withNotifyResult(result);

        boolean handled = service.handleWxpayNotify(new PaymentNotifyRequest());

        assertTrue(handled, "包场回调应当处理成功");
        Booking saved = bookingMapper.get(booking.getId());
        assertEquals(BookingStatus.PAID.name(), saved.getStatus(), "包场要转为已付款，排他性才生效");
        assertNotNull(saved.getInviteToken(),
                "付款必须与令牌生成一起完成 —— 少了令牌，包场生效了但没人拿得到邀请链接");
        assertEquals(43, saved.getInviteToken().length(), "令牌是 32 字节随机数的 URL-safe Base64");
        assertEquals(1, participantMapper.size(),
                "付款那一刻要把包场人写进参与者表 —— 少了这一行，"
                        + "「我参与的」列表与邀请页的名单里都不会出现发起人");
    }

    @Test
    @DisplayName("回调：重复回调不会给参与者表插第二行")
    void handleNotify_doesNotDuplicateHostParticipant() {
        Booking booking = seedPendingBooking(USER_ID);
        seedUser(USER_ID, BigDecimal.ZERO);
        PaymentNotifyResult result = notify(booking.getBookingNo(), new BigDecimal("500.00"));
        result.setChannel(PaymentChannel.WXPAY_JSAPI);
        gateway.withNotifyResult(result);

        service.handleWxpayNotify(new PaymentNotifyRequest());
        service.handleWxpayNotify(new PaymentNotifyRequest()); // 平台重推同一笔

        assertEquals(1, participantMapper.size(),
                "回调会重推，参与者行不能跟着多插一行 —— 第一道防线是"
                        + "「状态没被本次回调改动就直接返回」，第二道是唯一键");
    }

    @Test
    @DisplayName("回调：令牌撞唯一索引时重新生成并重试")
    void handleNotify_retriesOnDuplicateInviteToken() {
        Booking booking = seedPendingBooking(USER_ID);
        seedUser(USER_ID, BigDecimal.ZERO);
        bookingMapper.failNextMarkPaidWithDuplicateKey();
        PaymentNotifyResult result = notify(booking.getBookingNo(), new BigDecimal("500.00"));
        result.setChannel(PaymentChannel.WXPAY_JSAPI);
        gateway.withNotifyResult(result);

        boolean handled = service.handleWxpayNotify(new PaymentNotifyRequest());

        assertTrue(handled, "撞索引要重试，而不是让这笔已经收到的钱记不上账");
        assertNotNull(bookingMapper.get(booking.getId()).getInviteToken(), "重试后应当拿到新令牌");
    }

    @Test
    @DisplayName("回调：非成功通知（如交易关闭）不改状态，但让平台别再推")
    void handleNotify_acknowledgesNonPaidNotice() {
        Order order = seedPendingOrder(USER_ID);
        PaymentNotifyResult result = notify(order.getOrderNo(), AMOUNT);
        result.setPaid(false);
        gateway.withNotifyResult(result);

        boolean handled = service.handleWxpayNotify(new PaymentNotifyRequest());

        assertTrue(handled, "交易关闭也是真实通知，收到了就认，只是不需要动作");
        assertEquals(OrderStatus.PENDING_PAYMENT.name(), orderMapper.get(order.getId()).getStatus(),
                "没付钱就不该改状态 —— 把两者合成一个判断会导致退款通知把订单标成已支付");
    }

    // ==================================================================
    // 主动查单（补偿）
    // ==================================================================

    @Test
    @DisplayName("查单：非本人一律 404")
    void queryPayment_returns404ForOthersOrder() {
        Order order = seedPendingOrder(USER_ID);

        BizResult<PaymentStatusVo> result = service.queryPayment(
                OTHER_USER, order.getOrderNo(), PaymentChannel.WXPAY_H5);

        assertEquals(ErrorCode.NOT_FOUND, result.getError(),
                "查不到与不属于自己返回同一个结果，否则可以靠错误码枚举别人的单号");
    }

    @Test
    @DisplayName("查单：本地已支付时直接返回，不再调用网关")
    void queryPayment_returnsLocallyPaidWithoutCallingGateway() {
        Order order = seedPaidOrder(USER_ID);

        BizResult<PaymentStatusVo> result = service.queryPayment(
                USER_ID, order.getOrderNo(), PaymentChannel.WXPAY_H5);

        assertTrue(result.isSuccess(), "查询应当成功");
        assertTrue(result.getData().isPaid(), "本地已是已支付");
    }

    @Test
    @DisplayName("查单：平台说已支付而本地未更新时补写，走与回调相同的逻辑")
    void queryPayment_compensatesWhenCallbackLost() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID, BigDecimal.ZERO);
        PaymentQueryResult query = new PaymentQueryResult();
        query.setSuccess(true);
        query.setPaid(true);
        query.setOutTradeNo(order.getOrderNo());
        query.setTransactionNo("MOCK-TX-001");
        query.setAmount(AMOUNT);
        gateway.withQueryResult(query);

        BizResult<PaymentStatusVo> result = service.queryPayment(
                USER_ID, order.getOrderNo(), PaymentChannel.WXPAY_H5);

        assertTrue(result.isSuccess(), "补偿应当成功");
        assertTrue(result.getData().isPaid(), "补写后本地应当是已支付");
        Order saved = orderMapper.get(order.getId());
        assertEquals(OrderStatus.PAID.name(), saved.getStatus(),
                "「钱付了、回调没到」在生产是必然事件，必须有补偿路径");
        assertEquals("MOCK-TX-001", saved.getPaymentNo(), "补偿也要写全支付字段");
        assertEquals(0, AMOUNT.compareTo(userMapper.asMapper().selectById(USER_ID).getOrderPaid()),
                "补偿路径同样要累加消费额 —— 走的是同一段逻辑");
    }

    @Test
    @DisplayName("查单：平台说没付款时不动本地状态")
    void queryPayment_leavesStateWhenNotPaid() {
        Order order = seedPendingOrder(USER_ID);
        PaymentQueryResult query = new PaymentQueryResult();
        query.setSuccess(true);
        query.setPaid(false);
        query.setOutTradeNo(order.getOrderNo());
        gateway.withQueryResult(query);

        BizResult<PaymentStatusVo> result = service.queryPayment(
                USER_ID, order.getOrderNo(), PaymentChannel.WXPAY_H5);

        assertTrue(result.isSuccess(), "查询本身是成功的");
        assertFalse(result.getData().isPaid(), "平台说没付就是没付");
        assertEquals(OrderStatus.PENDING_PAYMENT.name(), orderMapper.get(order.getId()).getStatus(),
                "不该改动任何东西");
    }

    @Test
    @DisplayName("查单：网关调用失败时返回 502")
    void queryPayment_returns502WhenGatewayFails() {
        Order order = seedPendingOrder(USER_ID);
        gateway.withQueryResult(PaymentQueryResult.fail("模拟网络异常"));

        BizResult<PaymentStatusVo> result = service.queryPayment(
                USER_ID, order.getOrderNo(), PaymentChannel.WXPAY_H5);

        assertEquals(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, result.getError(),
                "「查询失败」与「查到了但没付款」是两回事，不能混成一个结果");
    }

    // ==================================================================
    // 凭证落账（扫码转账，2026-09-30 起投产的唯一收款方式）
    // ==================================================================

    @Test
    @DisplayName("凭证落账：写支付字段、记确认人并累加消费额")
    void settleByProof_marksPaidAndAccumulates() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID, BigDecimal.ZERO);

        service.settleByProof(targetOf(order), PaymentChannel.QR_UPLOAD, "WX-TX-9", 9L);

        Order saved = orderMapper.get(order.getId());
        assertEquals(OrderStatus.PAID.name(), saved.getStatus(), "落账后转已支付");
        assertEquals("QR_UPLOAD", saved.getPaymentMethod(), "通道记为扫码转账");
        assertEquals("WX-TX-9", saved.getPaymentNo(), "流水号原样写入，对账时要用到它");
        assertEquals(9L, saved.getConfirmedBy(),
                "记下是哪位管理员确认的 —— 订单「提交即交付」时这里是 null（系统自动）");
        assertEquals(0, AMOUNT.compareTo(userMapper.asMapper().selectById(USER_ID).getOrderPaid()),
                "凭证落账也要累加消费额，否则用户的累计消费会少算");
    }

    @Test
    @DisplayName("凭证落账：同一笔调两次只累加一次消费额")
    void settleByProof_accumulatesOnlyOnce() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID, BigDecimal.ZERO);
        PaymentTarget target = targetOf(order);

        service.settleByProof(target, PaymentChannel.QR_UPLOAD, "WX-TX-9", null);
        // 第二次拿的是同一个 target 对象（status 仍是 PENDING_PAYMENT 的快照），
        // 所以挡下它的是 markPaid 的状态守卫，而不是 isPaid() 那个前置判断 ——
        // 两道防线各测各的
        service.settleByProof(target, PaymentChannel.QR_UPLOAD, "WX-TX-9", null);

        assertEquals(0, AMOUNT.compareTo(userMapper.asMapper().selectById(USER_ID).getOrderPaid()),
                "重复累加会让用户的累计消费凭空翻倍，而订单本身看不出任何异常");
    }

    @Test
    @DisplayName("凭证落账：目标已是已支付时直接跳过（订单提交即交付后复核的那条路）")
    void settleByProof_skipsAlreadyPaidTarget() {
        Order order = seedPendingOrder(USER_ID);
        // 模拟「订单在用户提交凭证那一刻就已落账，管理员随后复核」的情形。
        // ⚠️ 改的是假 Mapper 里那一份而不是 seed 返回的那个对象 ——
        // targetOf 会按单号重新读库，改前者才有效果（踩过一次）
        orderMapper.get(order.getId()).setStatus(OrderStatus.PAID.name());
        seedUser(USER_ID, BigDecimal.ZERO);

        service.settleByProof(targetOf(order), PaymentChannel.QR_UPLOAD, "WX-TX-9", 9L);

        // 用 signum() 判「是不是零」而不是 equals —— BigDecimal 的 equals 连标度
        // 一起比，0 与 0.00 不相等，而这里只关心数值
        assertEquals(0, userMapper.asMapper().selectById(USER_ID).getOrderPaid().signum(),
                "已支付的一律跳过 —— 复核只是登记，不能把钱再记一遍");
    }

    // ==================================================================
    // 归属校验
    // ==================================================================

    @Test
    @DisplayName("归属：本人返回 true，他人与不存在的单号返回 false")
    void isOwnedBy_checksOwner() {
        Order order = seedPendingOrder(USER_ID);
        Booking booking = seedPendingBooking(USER_ID);

        assertTrue(service.isOwnedBy(USER_ID, order.getOrderNo()), "自己的订单应当认得出来");
        assertTrue(service.isOwnedBy(USER_ID, booking.getBookingNo()), "自己的包场同样认得出来");
        assertFalse(service.isOwnedBy(OTHER_USER, order.getOrderNo()), "别人的订单不该认");
        assertFalse(service.isOwnedBy(USER_ID, "OD000000000000000000"), "不存在的单号返回 false");
        assertFalse(service.isOwnedBy(USER_ID, "XX123"), "前缀认不出的单号返回 false");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 预置一条待支付的订单。
     *
     * @param userId 用户 ID
     * @return 订单
     */
    private Order seedPendingOrder(Long userId) {
        Order order = new Order();
        order.setOrderNo("OD" + System.nanoTime());
        order.setUserId(userId);
        order.setStoreId(STORE_ID);
        order.setStartTime(LocalDateTime.now().minusHours(2));
        order.setEndTime(LocalDateTime.now());
        order.setStatus(OrderStatus.PENDING_PAYMENT.name());
        order.setPayableAmount(AMOUNT);
        order.setTotalAmount(AMOUNT);
        order.setDiscountAmount(BigDecimal.ZERO);
        return orderMapper.seed(order);
    }

    /**
     * 预置一条已支付的订单。
     *
     * @param userId 用户 ID
     * @return 订单
     */
    private Order seedPaidOrder(Long userId) {
        Order order = seedPendingOrder(userId);
        order.setStatus(OrderStatus.PAID.name());
        order.setPaidAt(LocalDateTime.now());
        return order;
    }

    /**
     * 预置一场待支付的包场。
     *
     * @param hostUserId 包场人
     * @return 包场
     */
    private Booking seedPendingBooking(Long hostUserId) {
        Booking booking = new Booking();
        booking.setBookingNo("BK" + System.nanoTime());
        booking.setStoreId(STORE_ID);
        booking.setHostUserId(hostUserId);
        booking.setStartAt(LocalDateTime.now().plusHours(1));
        booking.setEndAt(LocalDateTime.now().plusHours(5));
        booking.setPrice(new BigDecimal("500.00"));
        booking.setStatus(BookingStatus.PENDING_PAYMENT.name());
        return bookingMapper.seed(booking);
    }

    /**
     * 预置一个用户，模拟其累计消费的起点。
     *
     * @param userId 用户 ID
     * @param paid   初始累计消费额
     */
    private void seedUser(Long userId, BigDecimal paid) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("user" + userId);
        user.setOrderPaid(paid);
        user.setCardPaid(paid);
        user.setTotalPaid(paid);
        userMapper.seed(user);
    }

    /**
     * 构造一个回调结果。
     *
     * @param outTradeNo 商户订单号
     * @param amount     金额
     * @return 回调结果
     */
    private static PaymentNotifyResult notify(String outTradeNo, BigDecimal amount) {
        PaymentNotifyResult result = FakePaymentGateway.paidNotify();
        result.setOutTradeNo(outTradeNo);
        result.setAmount(amount);
        result.setChannel(PaymentChannel.WXPAY_H5);
        result.setPaidAt(LocalDateTime.now());
        return result;
    }

    /**
     * 构造一个发起支付的请求。
     *
     * @param type    目标类型
     * @param id      目标 ID
     * @param channel 通道
     * @return 请求对象
     */
    private static CreatePaymentRequest request(PaymentTargetType type, Long id, PaymentChannel channel) {
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setTargetType(type);
        request.setTargetId(id);
        request.setChannel(channel);
        return request;
    }

    /**
     * 把订单实体翻译成支付目标，走真实的处理器。
     *
     * <p>不手搓一个 PaymentTarget：那个处理器是「实体 → 统一结构」的唯一真相，
     * 测试里再写一份的话，处理器改了而测试没改，两边就会各测各的。
     *
     * @param order 订单实体
     * @return 支付目标；订单不在 FakeMapper 里时返回 null
     */
    private PaymentTarget targetOf(Order order) {
        return new OrderPaymentTargetHandler(orderMapper.asMapper())
                .loadByOutTradeNo(order.getOrderNo());
    }
}
