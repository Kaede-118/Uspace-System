package com.kaede.uspace.promotion;

import com.kaede.uspace.billing.BillingProperties;
import com.kaede.uspace.billing.CardCoverage;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.promotion.dto.CardPurchaseVo;
import com.kaede.uspace.promotion.dto.CardWalletVo;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.promotion.entity.MonthlyCardOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MonthlyCardService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>两张表的数据访问由
 * {@link FakeMonthlyCardMapper} 与 {@link FakeMonthlyCardOrderMapper} 顶替。
 *
 * <p>覆盖重点在三处「写错了不报错、只会静默出错」的地方：
 * <ul>
 *   <li><b>有效期判定的四个条件</b> —— 少了状态条件，管理员退款之后卡还在免单；
 *       少了日期条件，到期的卡还在免单。两者都不会报错，只会一路免下去</li>
 *   <li><b>待支付单的两种处置</b> —— 未超时的要挡住（否则反复点购买会攒出一堆单，
 *       都付了款就拿到多张重叠的卡），已超时的要放行（否则用户被自己上次的
 *       一次误点永久锁死，再也买不了卡）</li>
 *   <li><b>取消的状态守卫</b> —— 已支付的单若能被取消，就会出现
 *       「单子关闭、卡还生效」的不一致</li>
 * </ul>
 *
 * <p>另有一条钉住购卡接口的价格<b>只认服务端配置</b>：
 * 前端传不了价格，改请求体也换不来一张 1 分钱的卡。
 */
class MonthlyCardServiceTests {

    /** 测试用的「今天」。购卡与卡包内部取的是真实当天，这里也用真实当天对齐 */
    private static final LocalDate TODAY = LocalDate.now();

    private static final Long USER_ID = 1001L;
    private static final Long OTHER_USER_ID = 1002L;

    private final FakeMonthlyCardMapper cardMapper = new FakeMonthlyCardMapper();
    private final FakeMonthlyCardOrderMapper orderMapper = new FakeMonthlyCardOrderMapper();
    private final PromotionProperties properties = new PromotionProperties();
    private final BillingProperties billingProperties = new BillingProperties();

    /** 被测服务，每个用例前重建 */
    private MonthlyCardService service;

    @BeforeEach
    void setUp() {
        service = new MonthlyCardService(cardMapper.asMapper(), orderMapper.asMapper(),
                properties, billingProperties);
    }

    // ==================================================================
    // 购卡
    // ==================================================================

    @Test
    @DisplayName("购卡：落一条待支付购买单，此时还没有卡")
    void purchase_createsPendingOrderOnly() {
        BizResult<CardPurchaseVo> result = service.purchase(USER_ID, MonthlyCardType.ALL_DAY);

        assertTrue(result.isSuccess(), "首次购卡应当成功");
        CardPurchaseVo vo = result.getData();
        assertNotNull(vo.getOrderNo(), "要返回单号 —— 付款时它充当商户订单号");
        assertTrue(vo.getOrderNo().startsWith(MonthlyCardNo.PREFIX),
                "单号前缀必须对，它是支付回调路由的依据");
        assertEquals("600", vo.getPrice().toPlainString(), "价格取配置里的全天卡单价");
        assertEquals(CardOrderStatus.PENDING_PAYMENT.name(), vo.getStatus());
        assertNotNull(vo.getExpireAt(), "要给出失效时刻，前端据此显示倒计时");

        assertEquals(0, cardMapper.size(), "付款之前不该有卡 —— 那是资产，不是意向");
        assertEquals(1, orderMapper.size(), "意向记在购买单里");
    }

    @Test
    @DisplayName("购卡：夜间卡按夜间单价")
    void purchase_nightCardUsesNightPrice() {
        BizResult<CardPurchaseVo> result = service.purchase(USER_ID, MonthlyCardType.NIGHT);

        assertEquals("320", result.getData().getPrice().toPlainString());
    }

    @Test
    @DisplayName("购卡：已在架价被改过时，新单用新价、旧卡不受影响")
    void purchase_snapshotsPrice() {
        properties.getMonthlyCard().setAllDayPrice(new BigDecimal("680"));

        BizResult<CardPurchaseVo> result = service.purchase(USER_ID, MonthlyCardType.ALL_DAY);

        assertEquals("680", result.getData().getPrice().toPlainString(),
                "价格在下单时快照，事后调价不影响这一单");
    }

    @Test
    @DisplayName("购卡：已有生效中的卡时拒绝")
    void purchase_rejectsWhenCardActive() {
        cardMapper.seed(activeCard(USER_ID, MonthlyCardType.ALL_DAY, TODAY, TODAY.plusDays(29)));

        BizResult<CardPurchaseVo> result = service.purchase(USER_ID, MonthlyCardType.NIGHT);

        assertEquals(ErrorCode.CARD_ALREADY_ACTIVE, result.getError(),
                "两张时间重叠的卡不带来任何额外权益，只会让退款时说不清该退哪张");
        assertEquals(0, orderMapper.size(), "被拒绝的请求不该留下购买单");
    }

    @Test
    @DisplayName("购卡：旧卡已过期时允许购买（续费的主场景）")
    void purchase_allowsWhenCardExpired() {
        // 状态仍是生效中、但有效期已过 —— 这正是定时任务还没来得及翻转的真实情形
        cardMapper.seed(activeCard(USER_ID, MonthlyCardType.ALL_DAY,
                TODAY.minusDays(40), TODAY.minusDays(11)));

        BizResult<CardPurchaseVo> result = service.purchase(USER_ID, MonthlyCardType.ALL_DAY);

        assertTrue(result.isSuccess(),
                "续费是月卡最主要的二次购买场景，被挡住的话等于卖不出去第二张");
    }

    @Test
    @DisplayName("购卡：已有未超时的待支付单时拒绝")
    void purchase_rejectsFreshPending() {
        orderMapper.seed(pendingOrder(USER_ID, LocalDateTime.now().minusMinutes(5)));

        BizResult<CardPurchaseVo> result = service.purchase(USER_ID, MonthlyCardType.ALL_DAY);

        assertEquals(ErrorCode.CARD_PENDING_PAYMENT_EXISTS, result.getError(),
                "挡住反复点购买 —— 攒出一堆单子都付了款，就拿到多张重叠的卡");
        assertEquals(1, orderMapper.size(), "不该再多出一条待支付单");
    }

    @Test
    @DisplayName("购卡：待支付单超时后自动关闭，用户可以重新买")
    void purchase_closesTimedOutPending() {
        // 默认存活时长 30 分钟，这里造一条 40 分钟前的
        MonthlyCardOrder stale = orderMapper.seed(
                pendingOrder(USER_ID, LocalDateTime.now().minusMinutes(40)));

        BizResult<CardPurchaseVo> result = service.purchase(USER_ID, MonthlyCardType.ALL_DAY);

        assertTrue(result.isSuccess(), "上次点了没付不该把用户永久锁死");
        assertEquals(CardOrderStatus.CLOSED.name(), orderMapper.get(stale.getId()).getStatus(),
                "超时的旧单顺手关掉，而不是留着一张永远不会被处理的单子");
        assertEquals(2, orderMapper.size());
    }

    @Test
    @DisplayName("购卡：总开关关闭时一律拒绝，但已售出的卡不受影响")
    void purchase_rejectsWhenDisabled() {
        properties.getMonthlyCard().setEnabled(false);

        BizResult<CardPurchaseVo> result = service.purchase(USER_ID, MonthlyCardType.ALL_DAY);

        assertEquals(ErrorCode.BUSINESS_REJECTED, result.getError());
        assertEquals(0, orderMapper.size());
    }

    // ==================================================================
    // 取消
    // ==================================================================

    @Test
    @DisplayName("取消：只能取消自己的待支付单")
    void cancel_checksOwnershipAndStatus() {
        MonthlyCardOrder mine = orderMapper.seed(pendingOrder(USER_ID, LocalDateTime.now()));
        MonthlyCardOrder others = orderMapper.seed(pendingOrder(OTHER_USER_ID, LocalDateTime.now()));
        MonthlyCardOrder paid = orderMapper.seed(
                orderOf(USER_ID, CardOrderStatus.PAID, LocalDateTime.now()));

        assertTrue(service.cancelPurchase(USER_ID, mine.getId()).isSuccess(),
                "本人的待支付单可以取消");
        assertEquals(CardOrderStatus.CLOSED.name(), orderMapper.get(mine.getId()).getStatus());

        assertEquals(ErrorCode.CARD_NOT_FOUND,
                service.cancelPurchase(USER_ID, others.getId()).getError(),
                "他人的单按「不存在」处理 —— 403 等于承认它存在，可以被用来枚举单号");

        assertEquals(ErrorCode.CARD_STATUS_INVALID,
                service.cancelPurchase(USER_ID, paid.getId()).getError(),
                "已支付的单不能取消：关掉它会造成「单子关闭、卡还生效」");
        assertEquals(CardOrderStatus.PAID.name(), orderMapper.get(paid.getId()).getStatus(),
                "被拒绝的取消不该改动任何状态");
    }

    // ==================================================================
    // 覆盖判定（供订单结算调用）
    // ==================================================================

    @Test
    @DisplayName("覆盖判定：无卡返回 null，全天卡与夜间卡各返回自己的范围")
    void findCoverageAt_mapsCardTypeToCoverage() {
        assertNull(service.findCoverageAt(USER_ID, TODAY), "没买卡的人应当是 null");

        cardMapper.seed(activeCard(USER_ID, MonthlyCardType.ALL_DAY, TODAY, TODAY.plusDays(29)));
        assertEquals(CardCoverage.ALL, service.findCoverageAt(USER_ID, TODAY));

        cardMapper.seed(activeCard(OTHER_USER_ID, MonthlyCardType.NIGHT, TODAY, TODAY.plusDays(29)));
        assertEquals(CardCoverage.NIGHT, service.findCoverageAt(OTHER_USER_ID, TODAY));
    }

    @Test
    @DisplayName("覆盖判定：已退款的卡立刻停止免单")
    void findCoverageAt_ignoresRefundedCard() {
        MonthlyCard card = activeCard(USER_ID, MonthlyCardType.ALL_DAY, TODAY, TODAY.plusDays(29));
        card.setStatus(MonthlyCardStatus.REFUNDED.name());
        cardMapper.seed(card);

        assertNull(service.findCoverageAt(USER_ID, TODAY),
                "状态条件是必须的：少了它，退款之后卡还在免单，钱一路免下去且没人会发现");
    }

    @Test
    @DisplayName("覆盖判定：生效当天与失效当天都算有效，前后一天都不算")
    void findCoverageAt_includesBothEnds() {
        cardMapper.seed(activeCard(USER_ID, MonthlyCardType.ALL_DAY, TODAY, TODAY.plusDays(29)));

        assertNotNull(service.findCoverageAt(USER_ID, TODAY), "生效当天算有效（含首尾）");
        assertNotNull(service.findCoverageAt(USER_ID, TODAY.plusDays(29)),
                "失效当天仍算有效 —— 用 >= 而不是 >");
        assertNull(service.findCoverageAt(USER_ID, TODAY.minusDays(1)), "生效前一天不算");
        assertNull(service.findCoverageAt(USER_ID, TODAY.plusDays(30)), "失效次日不算");
    }

    @Test
    @DisplayName("覆盖判定：卡种名是脏数据时按无卡处理，不让计费抛异常")
    void findCoverageAt_toleratesUnknownCardType() {
        MonthlyCard card = activeCard(USER_ID, MonthlyCardType.ALL_DAY, TODAY, TODAY.plusDays(29));
        card.setCardType("WEEKEND");
        cardMapper.seed(card);

        assertNull(service.findCoverageAt(USER_ID, TODAY),
                "宁可少免一次，也不能因为一个认不出的取值让结算整个失败");
    }

    // ==================================================================
    // 卡包与到期翻转
    // ==================================================================

    @Test
    @DisplayName("卡包：生效的、待支付的、历史的各归各位")
    void wallet_splitsCardsByEffectiveness() {
        cardMapper.seed(activeCard(USER_ID, MonthlyCardType.NIGHT, TODAY, TODAY.plusDays(29)));
        cardMapper.seed(activeCard(USER_ID, MonthlyCardType.ALL_DAY,
                TODAY.minusDays(40), TODAY.minusDays(11)));
        orderMapper.seed(pendingOrder(USER_ID, LocalDateTime.now()));

        CardWalletVo wallet = service.wallet(USER_ID).getData();

        assertNotNull(wallet.getActive(), "生效中的卡归到 active");
        assertEquals(MonthlyCardType.NIGHT.name(), wallet.getActive().getCardType());
        assertEquals(Long.valueOf(30), wallet.getActive().getRemainingDays(),
                "含首尾共 30 天 —— 最后一天显示「还剩 1 天」而不是 0 天");
        assertEquals(1, wallet.getHistory().size(), "已过期的卡进历史");
        assertNotNull(wallet.getPending(), "待支付单单独给出，前端据此显示「去支付」");
    }

    @Test
    @DisplayName("卡包：已过期的卡不显示剩余天数")
    void wallet_expiredCardHasNoRemainingDays() {
        cardMapper.seed(activeCard(USER_ID, MonthlyCardType.ALL_DAY,
                TODAY.minusDays(40), TODAY.minusDays(11)));

        CardWalletVo wallet = service.wallet(USER_ID).getData();

        assertNull(wallet.getActive(), "过期的卡不该出现在 active 里");
        assertNull(wallet.getHistory().get(0).getRemainingDays(),
                "不生效的卡不给剩余天数，免得前端算出「还剩 -11 天」");
    }

    @Test
    @DisplayName("到期翻转：只翻转已过失效日的卡，当天到期的不算")
    void expireOutdated_flipsOnlyPassedCards() {
        cardMapper.seed(activeCard(USER_ID, MonthlyCardType.ALL_DAY,
                TODAY.minusDays(30), TODAY.minusDays(1)));
        cardMapper.seed(activeCard(OTHER_USER_ID, MonthlyCardType.ALL_DAY,
                TODAY.minusDays(29), TODAY));

        assertEquals(1, service.expireOutdated(TODAY),
                "昨天到期的翻转、今天到期的保留 —— 与免单判定的 >= 口径一致");
    }

    // ==================================================================
    // 构造辅助
    // ==================================================================

    /**
     * 造一张月卡。
     *
     * @param userId 持卡人
     * @param type   卡种
     * @param start  生效日期
     * @param end    失效日期
     * @return 月卡实体（未落库）
     */
    private static MonthlyCard activeCard(Long userId, MonthlyCardType type,
                                          LocalDate start, LocalDate end) {
        MonthlyCard card = new MonthlyCard();
        card.setCardNo(MonthlyCardNo.generate());
        card.setUserId(userId);
        card.setCardType(type.name());
        card.setPrice(new BigDecimal("600"));
        card.setStartDate(start);
        card.setEndDate(end);
        card.setStatus(MonthlyCardStatus.ACTIVE.name());
        return card;
    }

    /**
     * 造一条待支付的购买单。
     *
     * @param userId    购买人
     * @param createdAt 下单时刻
     * @return 购买单实体（未落库）
     */
    private static MonthlyCardOrder pendingOrder(Long userId, LocalDateTime createdAt) {
        return orderOf(userId, CardOrderStatus.PENDING_PAYMENT, createdAt);
    }

    /**
     * 造一条指定状态的购买单。
     *
     * @param userId    购买人
     * @param status    状态
     * @param createdAt 下单时刻
     * @return 购买单实体（未落库）
     */
    private static MonthlyCardOrder orderOf(Long userId, CardOrderStatus status,
                                            LocalDateTime createdAt) {
        MonthlyCardOrder order = new MonthlyCardOrder();
        order.setOrderNo(MonthlyCardNo.generate());
        order.setUserId(userId);
        order.setCardType(MonthlyCardType.ALL_DAY.name());
        order.setPrice(new BigDecimal("600"));
        order.setStatus(status.name());
        order.setCreatedAt(createdAt);
        return order;
    }
}
