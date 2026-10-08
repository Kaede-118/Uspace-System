package com.kaede.uspace.order;

import com.kaede.uspace.common.config.UploadProperties;
import com.kaede.uspace.common.config.WebProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.order.dto.AdminProofVo;
import com.kaede.uspace.order.dto.ProofSubmitRequest;
import com.kaede.uspace.order.dto.ProofSubmitVo;
import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.order.entity.PayQr;
import com.kaede.uspace.order.entity.PaymentProof;
import com.kaede.uspace.order.event.PaymentProofRejectedEvent;
import com.kaede.uspace.order.ocr.OcrTextParser;
import com.kaede.uspace.product.FakeProductMapper;
import com.kaede.uspace.product.FakeProductOrderMapper;
import com.kaede.uspace.product.ProductOrderStatus;
import com.kaede.uspace.product.entity.Product;
import com.kaede.uspace.product.entity.ProductOrder;
import com.kaede.uspace.promotion.CardOrderStatus;
import com.kaede.uspace.promotion.FakeMonthlyCardMapper;
import com.kaede.uspace.promotion.FakeMonthlyCardOrderMapper;
import com.kaede.uspace.promotion.MonthlyCardNo;
import com.kaede.uspace.promotion.MonthlyCardType;
import com.kaede.uspace.promotion.PromotionProperties;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaymentProofService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>全部 Mapper 由假实现顶替，
 * 因此「目标处于什么状态、已有凭证是哪一种」这些分支可以被精确构造出来。
 *
 * <p>本类覆盖四块，每一块都对应一处「错了不报错」的风险：
 * <ol>
 *   <li><b>提交的五条分支</b> —— 目标待支付 / 已支付 + 待复核 / 已核对 /
 *       未通过 / 线上通道付过。分支走错的表现是「重复落账」或
 *       「用户改不了自己传错的图」，都不会抛异常</li>
 *   <li><b>交付时机分两层</b> —— 订单与商品提交即交付（当场落账），
 *       包场与月卡等复核。这条差异由 {@code deliverOnSubmit} 声明，
 *       测错方向的表现是「用户以为付完了、其实还在等」</li>
 *   <li><b>归属校验</b> —— 少了它，任何登录用户都能往别人的单子上传凭证，
 *       等于替别人伪造付款证明</li>
 *   <li><b>流水号重复检测</b> —— 一张截图付两单是纯信任制下最省事的作弊手法</li>
 * </ol>
 */
class PaymentProofServiceTests {

    private static final Long USER_ID = 1001L;
    private static final Long OTHER_USER = 1002L;
    private static final Long STORE_ID = 1L;
    private static final Long ADMIN_ID = 9L;
    private static final BigDecimal AMOUNT = new BigDecimal("22.00");
    private static final BigDecimal BOOKING_PRICE = new BigDecimal("500.00");
    private static final BigDecimal CARD_PRICE = new BigDecimal("600.00");

    /** 一张合法的截图路径。前缀必须与 {@code PaymentProofImageService.KIND_PROOF} 对得上 */
    private static final String OK_URL = "/uploads/proof/a1b2c3d4.jpg";

    // ==================================================================
    // 被测服务的依赖
    // ==================================================================

    private final FakePaymentProofMapper proofMapper = new FakePaymentProofMapper();
    private final FakePayQrMapper payQrMapper = new FakePayQrMapper();
    private final FakeOrderMapper orderMapper = new FakeOrderMapper();
    private final FakeBookingMapper bookingMapper = new FakeBookingMapper();
    private final FakeSysUserMapper userMapper = new FakeSysUserMapper();
    private final FakeMonthlyCardMapper cardMapper = new FakeMonthlyCardMapper();
    private final FakeMonthlyCardOrderMapper cardOrderMapper = new FakeMonthlyCardOrderMapper();
    private final FakeProductMapper productMapper = new FakeProductMapper();
    private final FakeProductOrderMapper productOrderMapper = new FakeProductOrderMapper();
    private final FakePaymentGateway gateway = new FakePaymentGateway();

    private final PromotionProperties promotionProperties = new PromotionProperties();
    private final PaymentProperties paymentProperties = new PaymentProperties();

    /**
     * 上传配置。
     *
     * <p>默认值 {@code /uploads} 就是 {@link #OK_URL} 用的前缀 ——
     * 两处对不上时用例会全红，而那正是「改了前缀忘了改测试」该有的表现。
     */
    private final UploadProperties uploadProperties = new UploadProperties();

    private final FakeBookingParticipantMapper participantMapper =
            new FakeBookingParticipantMapper(bookingMapper);
    private final FakeStoreMapper storeMapper = new FakeStoreMapper();
    private final ClosureService closureService =
            new ClosureService(new FakeClosureMapper().asMapper(), storeMapper.asMapper(), event -> { });
    private final BookingService bookingService = new BookingService(
            bookingMapper.asMapper(), storeMapper.asMapper(), closureService,
            userMapper.asMapper(), participantMapper.asMapper(), event -> { });
    private final InviteTokenService inviteTokenService = new InviteTokenService(
            bookingMapper.asMapper(), bookingService, new WebProperties());

    /** 四个真实的处理器 —— 本类测的正是它们的差异（{@code deliverOnSubmit}） */
    private final List<PaymentTargetHandler> handlers = List.of(
            new OrderPaymentTargetHandler(orderMapper.asMapper()),
            new BookingPaymentTargetHandler(bookingMapper.asMapper(),
                    inviteTokenService, participantMapper.asMapper(), event -> { }),
            new MonthlyCardPaymentTargetHandler(cardOrderMapper.asMapper(),
                    cardMapper.asMapper(), promotionProperties),
            new ProductPaymentTargetHandler(productOrderMapper.asMapper(),
                    productMapper.asMapper(), event -> { }));

    private PaymentService paymentService;
    private PaymentProofService service;

    /**
     * 收集被测代码发布的事件。
     *
     * <p>用一个真的收集器而不是 mock：本类要断言的是「驳回之后<b>发了</b>
     * 一条通知」—— 那正是「先交付后复核」那道缺口唯一的补法。漏掉它不会有
     * 任何编译错误，只表现为用户永远收不到提醒。
     */
    private final List<Object> publishedEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        publishedEvents.clear();
        paymentService = new PaymentService(gateway, paymentProperties,
                handlers, userMapper.asMapper());
        service = new PaymentProofService(proofMapper.asMapper(), payQrMapper.asMapper(),
                userMapper.asMapper(), uploadProperties, handlers, paymentService,
                publishedEvents::add);
    }

    // ==================================================================
    // 提交：五条分支
    // ==================================================================

    @Test
    @DisplayName("提交：订单待支付 → 写凭证并当场落账（提交即交付）")
    void submit_orderDeliversImmediately() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);

        BizResult<ProofSubmitVo> result = service.submit(USER_ID, request(PaymentTargetType.ORDER, order.getId()));

        assertTrue(result.isSuccess(), "提交应当成功");
        assertTrue(result.getData().isDelivered(),
                "订单是「提交即交付」—— 用户提交完就该能再开一单进店");
        assertEquals(OrderStatus.PAID.name(), orderMapper.get(order.getId()).getStatus(),
                "订单应当已经转已支付");
        assertEquals(0, userMapper.asMapper().selectById(USER_ID).getOrderPaid().compareTo(AMOUNT),
                "落账要累加消费额，否则用户的累计消费会少算");
        assertNotNull(proofMapper.find(PaymentTargetType.ORDER.name(), order.getId()),
                "凭证要落库 —— 管理员事后复核的就是它");
    }

    @Test
    @DisplayName("提交：包场待支付 → 只写凭证，不落账（等复核）")
    void submit_bookingWaitsForReview() {
        Booking booking = seedPendingBooking(USER_ID);
        seedUser(USER_ID);

        BizResult<ProofSubmitVo> result = service.submit(USER_ID,
                request(PaymentTargetType.BOOKING, booking.getId()));

        assertTrue(result.isSuccess(), "提交应当成功");
        assertFalse(result.getData().isDelivered(),
                "包场买的是一份排他性，发出去收不回来，必须等管理员扫一眼");
        assertEquals(BookingStatus.PENDING_PAYMENT.name(),
                bookingMapper.get(booking.getId()).getStatus(),
                "包场仍停在待付款 —— 邀请令牌还没生成");
        assertEquals(0, userMapper.asMapper().selectById(USER_ID).getOrderPaid().signum(),
                "还没交付就不该累加消费额");
    }

    @Test
    @DisplayName("提交：目标已支付且凭证待复核 → 只更新凭证，不重复落账")
    void submit_alreadyPaidOnlyUpdatesProof() {
        // 订单「提交即交付」，所以用户提交完之后再补个流水号就是这条路
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.ORDER, order.getId()));

        ProofSubmitRequest again = request(PaymentTargetType.ORDER, order.getId());
        again.setPaymentNo("WX-TX-2");
        BizResult<ProofSubmitVo> result = service.submit(USER_ID, again);

        assertTrue(result.isSuccess(), "改流水号是正常操作，不该被拒");
        PaymentProof proof = proofMapper.find(PaymentTargetType.ORDER.name(), order.getId());
        assertEquals("WX-TX-2", proof.getPaymentNo(), "流水号应当被更新");
        assertEquals(0, userMapper.asMapper().selectById(USER_ID).getOrderPaid().compareTo(AMOUNT),
                "只该累加一次 —— 重复累加会让用户的累计消费凭空翻倍");
    }

    @Test
    @DisplayName("提交：目标已支付且凭证已核对 → 幂等返回，不覆盖")
    void submit_confirmedIsIdempotent() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.ORDER, order.getId()));
        PaymentProof proof = proofMapper.find(PaymentTargetType.ORDER.name(), order.getId());
        proofMapper.confirm(proof.getId(), ADMIN_ID);
        proof.setProofUrl("/uploads/proof/old.jpg");

        BizResult<ProofSubmitVo> result = service.submit(USER_ID,
                request(PaymentTargetType.ORDER, order.getId()));

        assertTrue(result.isSuccess(), "重复提交应当幂等成功，而不是报错");
        assertEquals(PaymentProofStatus.CONFIRMED.name(), result.getData().getVerifyStatus());
        assertEquals("/uploads/proof/old.jpg",
                proofMapper.find(PaymentTargetType.ORDER.name(), order.getId()).getProofUrl(),
                "已核对的凭证不能被覆盖 —— 覆盖会把管理员的结论打回待复核");
    }

    @Test
    @DisplayName("提交：已支付且凭证曾被驳回（回退机制上线前的历史数据）→ 放行重交")
    void submit_rejectedOnPaidTargetIsAllowed() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.ORDER, order.getId()));
        PaymentProof proof = proofMapper.find(PaymentTargetType.ORDER.name(), order.getId());
        proofMapper.reject(proof.getId(), ADMIN_ID, "金额对不上");
        /*
         * 把订单按回「已支付」—— 这里模拟的正是「交付回退机制上线之前被驳回的
         * 那批数据」：凭证是 REJECTED，而订单还停在已支付上。
         *
         * 走 reject() 的话订单会被退成 REJECTED，那走的就是正常的待支付路径，
         * 这条分支根本到不了。
         */
        orderMapper.get(order.getId()).setStatus(OrderStatus.PAID.name());

        BizResult<ProofSubmitVo> result = service.submit(USER_ID,
                request(PaymentTargetType.ORDER, order.getId()));

        assertTrue(result.isSuccess(),
                "老数据的死结要解开：那笔单子既不能再付（系统认为不需要）、"
                        + "又不能重交（这里拦着）的话，用户在页面上点什么都没用，"
                        + "而管理员那边也没有重开复核的入口");
        assertEquals(PaymentProofStatus.SUBMITTED.name(),
                proofMapper.get(proof.getId()).getVerifyStatus(),
                "重交之后凭证翻回待复核，等管理员再看一次 —— "
                        + "订单则停在已支付上，那正是「提交即交付 + 等复核」的正常组合");
    }

    @Test
    @DisplayName("提交：已支付但没有凭证（线上通道付的）→ 拒绝")
    void submit_paidWithoutProofIsRefused() {
        Order order = seedPendingOrder(USER_ID);
        orderMapper.get(order.getId()).setStatus(OrderStatus.PAID.name());

        BizResult<ProofSubmitVo> result = service.submit(USER_ID,
                request(PaymentTargetType.ORDER, order.getId()));

        assertEquals(ErrorCode.PAYMENT_ALREADY_PAID, result.getError(),
                "线上付过的款没有凭证可言 —— 不该给出上传入口");
    }

    @Test
    @DisplayName("提交：目标不属于本人 → 拒绝（归属校验）")
    void submit_rejectsForeignTarget() {
        Order order = seedPendingOrder(OTHER_USER);

        BizResult<ProofSubmitVo> result = service.submit(USER_ID, request(PaymentTargetType.ORDER, order.getId()));

        assertEquals(ErrorCode.ORDER_NOT_FOUND, result.getError(),
                "少了这条校验，任何登录用户都能往别人的单子上传凭证");
        assertNull(proofMapper.find(PaymentTargetType.ORDER.name(), order.getId()),
                "被拒的提交不该留下任何痕迹");
    }

    @Test
    @DisplayName("提交：截图路径不是本服务上传的 → 拒绝")
    void submit_rejectsForeignImageUrl() {
        Order order = seedPendingOrder(USER_ID);

        // 三个反例：外链、别的 kind、绕前缀
        for (String bad : List.of(
                "https://evil.example.com/x.jpg",
                "/uploads/avatar/1001_1.jpg",
                "/uploads/proof/../avatar/1001_1.jpg")) {
            ProofSubmitRequest req = request(PaymentTargetType.ORDER, order.getId());
            req.setProofUrl(bad);

            BizResult<ProofSubmitVo> result = service.submit(USER_ID, req);

            assertEquals(ErrorCode.PAYMENT_PROOF_IMAGE_INVALID, result.getError(),
                    "不该接受 " + bad + " —— 外链会把「谁在什么时候付款」泄露给第三方");
        }
    }

    @Test
    @DisplayName("提交：目标状态不支持（如包场已取消）→ 拒绝")
    void submit_rejectsNonPendingTarget() {
        Booking booking = seedPendingBooking(USER_ID);
        bookingMapper.get(booking.getId()).setStatus(BookingStatus.CANCELLED.name());

        BizResult<ProofSubmitVo> result = service.submit(USER_ID,
                request(PaymentTargetType.BOOKING, booking.getId()));

        assertEquals(ErrorCode.PAYMENT_PROOF_TARGET_INVALID, result.getError(),
                "已取消的包场没有可付的款");
    }

    @Test
    @DisplayName("提交：同一流水号被两笔凭证引用 → 两条都标记风险")
    void submit_flagsDuplicatePaymentNo() {
        Order first = seedPendingOrder(USER_ID);
        Order second = seedPendingOrder(USER_ID);
        seedUser(USER_ID);

        ProofSubmitRequest a = request(PaymentTargetType.ORDER, first.getId());
        a.setPaymentNo("WX-TX-SAME");
        service.submit(USER_ID, a);

        ProofSubmitRequest b = request(PaymentTargetType.ORDER, second.getId());
        b.setPaymentNo("WX-TX-SAME");
        service.submit(USER_ID, b);

        // 先提交的那条也要标上 —— 冲突是双向的，谁先谁后没有意义
        assertEquals("DUPLICATE_PAYMENT_NO",
                proofMapper.find(PaymentTargetType.ORDER.name(), first.getId()).getRiskFlag(),
                "一张截图付两单是最该先看到的一类，两条都要标出来");
        assertEquals("DUPLICATE_PAYMENT_NO",
                proofMapper.find(PaymentTargetType.ORDER.name(), second.getId()).getRiskFlag());
    }

    @Test
    @DisplayName("提交：改掉流水号后风险标记被清掉")
    void submit_clearsRiskFlagOnResubmit() {
        Order first = seedPendingOrder(USER_ID);
        Order second = seedPendingOrder(USER_ID);
        seedUser(USER_ID);

        ProofSubmitRequest a = request(PaymentTargetType.ORDER, first.getId());
        a.setPaymentNo("WX-TX-SAME");
        service.submit(USER_ID, a);

        ProofSubmitRequest b = request(PaymentTargetType.ORDER, second.getId());
        b.setPaymentNo("WX-TX-SAME");
        service.submit(USER_ID, b);

        assertNotNull(proofMapper.find(PaymentTargetType.ORDER.name(), first.getId()).getRiskFlag(),
                "先确认它确实被标上了，否则下面那条断言等于没测");

        ProofSubmitRequest fixed = request(PaymentTargetType.ORDER, first.getId());
        fixed.setPaymentNo("WX-TX-CORRECTED");
        service.submit(USER_ID, fixed);

        assertNull(proofMapper.find(PaymentTargetType.ORDER.name(), first.getId()).getRiskFlag(),
                "抄错一位、改对之后就该恢复正常 —— 假警报积多了，管理员会干脆不看这个标记");
    }

    @Test
    @DisplayName("提交：不填流水号也能提交（有些收款方式确实没有）")
    void submit_allowsBlankPaymentNo() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);

        BizResult<ProofSubmitVo> result = service.submit(USER_ID, request(PaymentTargetType.ORDER, order.getId()));

        assertTrue(result.isSuccess(), "硬性必填只会逼着用户瞎填一串，比空着更糟");
        assertNull(proofMapper.find(PaymentTargetType.ORDER.name(), order.getId()).getPaymentNo(),
                "空串要归一成 null —— 存一个空串进去，对账时还得再判一次");
    }

    @Test
    @DisplayName("提交：月卡待支付 → 只写凭证，卡还没发")
    void submit_cardWaitsForReview() {
        MonthlyCardOrder order = seedPendingCardOrder();
        seedUser(USER_ID);

        BizResult<ProofSubmitVo> result = service.submit(USER_ID,
                request(PaymentTargetType.MONTHLY_CARD, order.getId()));

        assertTrue(result.isSuccess(), "提交应当成功");
        assertFalse(result.getData().isDelivered(),
                "月卡是一份 30 天的权益，发出去收不回来");
        assertEquals(CardOrderStatus.PENDING_PAYMENT.name(),
                cardOrderMapper.get(order.getId()).getStatus(), "购买单仍停在待支付");
        assertEquals(0, cardMapper.size(),
                "卡还没发 —— 对月卡来说「放行」与「落账」是同一件事，都在 markPaid 里");
    }

    @Test
    @DisplayName("提交：商品提交即交付，库存当场扣减")
    void submit_productDeliversImmediately() {
        ProductOrder order = seedPendingProductOrder();
        seedUser(USER_ID);

        BizResult<ProofSubmitVo> result = service.submit(USER_ID,
                request(PaymentTargetType.PRODUCT, order.getId()));

        assertTrue(result.isSuccess(), "提交应当成功");
        assertTrue(result.getData().isDelivered(), "商品与订单同为「提交即交付」");
        assertEquals(ProductOrderStatus.PAID.name(),
                productOrderMapper.get(order.getId()).getStatus(), "购买单应当已经转已支付");
    }

    // ==================================================================
    // 提交：OCR 识别结果落库
    // ==================================================================

    @Test
    @DisplayName("提交：带着识别结果 → 三列原样落库")
    void submit_persistsOcrFields() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);
        ProofSubmitRequest req = request(PaymentTargetType.ORDER, order.getId());
        req.setOcrPaymentNo("4200001234202609301234567890");
        req.setOcrAmount(new BigDecimal("8.00"));
        req.setOcrText("支付成功\n¥8.00\n交易单号\n4200001234202609301234567890");

        service.submit(USER_ID, req);

        PaymentProof proof = proofMapper.find(PaymentTargetType.ORDER.name(), order.getId());
        assertEquals("4200001234202609301234567890", proof.getOcrPaymentNo(),
                "识别出的单号要落库 —— 它是「用户为什么填成这个」的解释，"
                        + "也是识别质量的观测数据");
        assertEquals(0, proof.getOcrAmount().compareTo(new BigDecimal("8.00")),
                "识别出的金额要落库 —— 后台靠它与应付额比对，找出对不上的那几条");
        assertNotNull(proof.getOcrText(), "原文要落库，管理员复核时能看到图里到底有什么字");
    }

    @Test
    @DisplayName("提交：换图重交 → 识别结果换成新图的，不留旧的")
    void submit_replacesOcrFieldsOnResubmit() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);

        ProofSubmitRequest first = request(PaymentTargetType.ORDER, order.getId());
        first.setOcrPaymentNo("4200001111111111111111111111");
        first.setOcrAmount(new BigDecimal("22.00"));
        service.submit(USER_ID, first);

        ProofSubmitRequest second = request(PaymentTargetType.ORDER, order.getId());
        second.setProofUrl("/uploads/proof/another.jpg");
        second.setOcrPaymentNo("4200002222222222222222222222");
        second.setOcrAmount(new BigDecimal("8.00"));
        service.submit(USER_ID, second);

        PaymentProof proof = proofMapper.find(PaymentTargetType.ORDER.name(), order.getId());
        assertEquals("4200002222222222222222222222", proof.getOcrPaymentNo(),
                "旧图的识别结果已经不对应任何东西了 —— 留着它，后台就会拿第一张图的数据"
                        + "去核第二张图，而管理员看不出来");
        assertEquals(0, proof.getOcrAmount().compareTo(new BigDecimal("8.00")),
                "金额同理要换成新图的");
    }

    @Test
    @DisplayName("提交：识别结果为空 → 三列写成 null，不写空串")
    void submit_blankOcrFieldsBecomeNull() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);
        ProofSubmitRequest req = request(PaymentTargetType.ORDER, order.getId());
        // 前端在没识别出东西时会回传空串或干脆不传，两种都要归一成 null
        req.setOcrPaymentNo("  ");
        req.setOcrText("");

        service.submit(USER_ID, req);

        PaymentProof proof = proofMapper.find(PaymentTargetType.ORDER.name(), order.getId());
        assertNull(proof.getOcrPaymentNo(), "「没识别出」应当是 null，与列上的 DEFAULT NULL 一致");
        assertNull(proof.getOcrText(), "空串会让前台多一种情形要判，没有任何好处");
    }

    @Test
    @DisplayName("提交：识别结果超长 → 截断到列宽，不让插入报错")
    void submit_truncatesOverlongOcrFields() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);
        ProofSubmitRequest req = request(PaymentTargetType.ORDER, order.getId());
        // 这三个值由客户端回传，服务端无从核对 —— 有人直接构造请求体就能塞任意长度进来
        req.setOcrText("字".repeat(5000));
        req.setOcrPaymentNo("9".repeat(200));

        BizResult<ProofSubmitVo> result = service.submit(USER_ID, req);

        assertTrue(result.isSuccess(),
                "辅助字段没对上格式，不该让「提交付款凭证」整个失败 —— "
                        + "用户完全无法自救：他既没填过这些字段，也改不了它们");
        PaymentProof proof = proofMapper.find(PaymentTargetType.ORDER.name(), order.getId());
        assertEquals(OcrTextParser.MAX_TEXT_LENGTH, proof.getOcrText().length(),
                "按 ocr_text 的列宽截断。不截的话 MySQL 会在插入时报 Data too long，"
                        + "而那已经是用户提交之后了");
        assertEquals(64, proof.getOcrPaymentNo().length(), "单号按 ocr_payment_no 的列宽截断");
    }

    @Test
    @DisplayName("提交：识别金额超出合理区间 → 丢弃，不钳到边界")
    void submit_dropsUnreasonableOcrAmount() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);
        ProofSubmitRequest req = request(PaymentTargetType.ORDER, order.getId());
        req.setOcrAmount(new BigDecimal("99999999999.99"));

        service.submit(USER_ID, req);

        PaymentProof proof = proofMapper.find(PaymentTargetType.ORDER.name(), order.getId());
        assertNull(proof.getOcrAmount(),
                "丢掉而不是钳成 99999.99 —— 钳出来的那个数是凭空编的，"
                        + "而 null（「没识别出金额」）是诚实且无害的");
    }

    // ==================================================================
    // 复核
    // ==================================================================

    @Test
    @DisplayName("复核通过：包场此刻才落账，并生成邀请令牌")
    void confirm_bookingSettlesOnReview() {
        Booking booking = seedPendingBooking(USER_ID);
        seedUser(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.BOOKING, booking.getId()));
        PaymentProof proof = proofMapper.find(PaymentTargetType.BOOKING.name(), booking.getId());

        BizResult<Void> result = service.confirm(proof.getId(), ADMIN_ID);

        assertTrue(result.isSuccess(), "复核应当成功");
        assertEquals(BookingStatus.PAID.name(), bookingMapper.get(booking.getId()).getStatus(),
                "包场交付发生在复核这一刻，不是提交那一刻");
        assertNotNull(bookingMapper.get(booking.getId()).getInviteToken(),
                "邀请令牌随之生成 —— 它就是包场排他性的载体");
        assertEquals(PaymentProofStatus.CONFIRMED.name(),
                proofMapper.get(proof.getId()).getVerifyStatus());
        assertEquals(ADMIN_ID, proofMapper.get(proof.getId()).getConfirmedBy(),
                "记下是哪位管理员确认的");
        assertEquals(0, userMapper.asMapper().selectById(USER_ID).getOrderPaid().compareTo(BOOKING_PRICE),
                "包场费记进 order_paid，与房间使用费同类");
    }

    @Test
    @DisplayName("复核通过：月卡此刻才发卡")
    void confirm_cardIssuedOnReview() {
        MonthlyCardOrder order = seedPendingCardOrder();
        seedUser(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.MONTHLY_CARD, order.getId()));
        PaymentProof proof = proofMapper.find(PaymentTargetType.MONTHLY_CARD.name(), order.getId());

        BizResult<Void> result = service.confirm(proof.getId(), ADMIN_ID);

        assertTrue(result.isSuccess(), "复核应当成功");
        assertEquals(CardOrderStatus.PAID.name(), cardOrderMapper.get(order.getId()).getStatus());
        assertEquals(1, cardMapper.size(), "复核那一刻才发卡 —— 早一刻都不行");
        assertEquals(0, userMapper.asMapper().selectById(USER_ID).getCardPaid().compareTo(CARD_PRICE),
                "卡费记进 card_paid，与房间使用费的 order_paid 是两个口径");
    }

    @Test
    @DisplayName("复核通过：订单早已落账，复核只是登记，不重复累加")
    void confirm_orderDoesNotDoubleCount() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.ORDER, order.getId()));
        PaymentProof proof = proofMapper.find(PaymentTargetType.ORDER.name(), order.getId());

        service.confirm(proof.getId(), ADMIN_ID);

        assertEquals(0, userMapper.asMapper().selectById(USER_ID).getOrderPaid().compareTo(AMOUNT),
                "订单在提交那一刻就落过账了，复核不能再记一次");
        assertEquals(PaymentProofStatus.CONFIRMED.name(),
                proofMapper.get(proof.getId()).getVerifyStatus(), "但结论要留下");
    }

    @Test
    @DisplayName("复核：凭证不存在 → 404")
    void confirm_missingProof() {
        assertEquals(ErrorCode.PAYMENT_PROOF_NOT_FOUND, service.confirm(9999L, ADMIN_ID).getError());
    }

    @Test
    @DisplayName("复核：已被别人处理过 → 状态冲突（并发双击）")
    void confirm_twiceConflicts() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.ORDER, order.getId()));
        PaymentProof proof = proofMapper.find(PaymentTargetType.ORDER.name(), order.getId());
        service.confirm(proof.getId(), ADMIN_ID);

        BizResult<Void> second = service.confirm(proof.getId(), ADMIN_ID);

        assertEquals(ErrorCode.PAYMENT_PROOF_STATUS_INVALID, second.getError(),
                "两位管理员同时点确认时，后点的那个要被告知「已经处理过了」，"
                        + "而不是静默成功");
    }

    @Test
    @DisplayName("驳回：写下原因，且不改动目标状态")
    void reject_recordsReasonWithoutTouchingTarget() {
        Booking booking = seedPendingBooking(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.BOOKING, booking.getId()));
        PaymentProof proof = proofMapper.find(PaymentTargetType.BOOKING.name(), booking.getId());

        BizResult<Void> result = service.reject(proof.getId(), ADMIN_ID, "截图上显示 8 元，本单应付 500 元");

        assertTrue(result.isSuccess(), "驳回应当成功");
        PaymentProof saved = proofMapper.get(proof.getId());
        assertEquals(PaymentProofStatus.REJECTED.name(), saved.getVerifyStatus());
        assertEquals("截图上显示 8 元，本单应付 500 元", saved.getRejectReason());
        assertEquals(BookingStatus.PENDING_PAYMENT.name(),
                bookingMapper.get(booking.getId()).getStatus(),
                "驳回不改变目标状态 —— 系统不会替人做「这笔不认了」之后的处置");
    }

    @Test
    @DisplayName("驳回 → 发一条通知事件，用户才可能知道自己的凭证没过")
    void reject_publishesNotification() {
        Booking booking = seedPendingBooking(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.BOOKING, booking.getId()));
        PaymentProof proof = proofMapper.find(PaymentTargetType.BOOKING.name(), booking.getId());
        // 提交本身不发事件，这里清一次是防御性的：将来提交若也要播报，
        // 本用例不该跟着变红
        publishedEvents.clear();

        service.reject(proof.getId(), ADMIN_ID, "看不清金额");

        assertEquals(1, publishedEvents.size(),
                "驳回必须发声 —— 订单与商品是「先交付后复核」，驳回不回退订单状态，"
                        + "用户端看到的仍是「已支付」。少了这条事件，"
                        + "「管理员不认这笔钱」这个结论永远到不了当事人那里，复核等于白设");
        PaymentProofRejectedEvent event = (PaymentProofRejectedEvent) publishedEvents.get(0);
        assertEquals(USER_ID, event.userId(), "要能定位到人 —— qqbot 靠它查 QQ 号来 @");
        assertEquals("看不清金额", event.reason(), "原因要带给用户，否则他不知道该改什么");
        assertEquals(PaymentTargetType.BOOKING.name(), event.targetType(),
                "带上类型是为了日志里分得清是哪一类收款");
    }

    @Test
    @DisplayName("驳回：订单退回待支付、支付字段清空、累计消费冲减")
    void reject_revertsOrderDelivery() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.ORDER, order.getId()));

        assertEquals(OrderStatus.PAID.name(), orderMapper.get(order.getId()).getStatus(),
                "订单是「提交即交付」，提交完就该是已支付");
        assertTrue(userMapper.get(USER_ID).getTotalPaid().compareTo(BigDecimal.ZERO) > 0,
                "提交即交付，累计消费当场就记上了");

        PaymentProof proof = proofMapper.find(PaymentTargetType.ORDER.name(), order.getId());
        service.reject(proof.getId(), ADMIN_ID, "金额对不上");

        Order reverted = orderMapper.get(order.getId());
        assertEquals(OrderStatus.REJECTED.name(), reverted.getStatus(),
                "驳回要把已交付的那一步退回去，而且退成 **REJECTED 而不是 PENDING_PAYMENT** —— "
                        + "后者的处置是「去支付」，用户看到它会再付一次钱。"
                        + "**这一条同时就是「禁用开门」的实现**：下单前的欠费校验"
                        + "（selectUnsettledByUser）把 REJECTED 也算作未了结，"
                        + "他开不了新单，必须先处理这一笔");
        assertNull(reverted.getPaidAt(), "支付字段要一并清掉：一份「待支付」挂着支付时间自相矛盾");
        assertNull(reverted.getPaymentNo(), "流水号并不丢，它在凭证表里留着");
        assertEquals(0, userMapper.get(USER_ID).getTotalPaid().compareTo(BigDecimal.ZERO),
                "累计消费要冲减回去 —— 这笔钱系统不再认了，留着会把月度优惠门槛算高");
    }

    @Test
    @DisplayName("驳回后重交：走得通（订单已经不在已支付上了）")
    void resubmitAfterRejectForOrder() {
        Order order = seedPendingOrder(USER_ID);
        seedUser(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.ORDER, order.getId()));
        PaymentProof proof = proofMapper.find(PaymentTargetType.ORDER.name(), order.getId());
        service.reject(proof.getId(), ADMIN_ID, "截图看不清");

        ProofSubmitRequest again = request(PaymentTargetType.ORDER, order.getId());
        again.setProofUrl("/uploads/proof/clearer.jpg");
        BizResult<ProofSubmitVo> result = service.submit(USER_ID, again);

        assertTrue(result.isSuccess(),
                "订单被退回待支付之后，重交走的是正常的待支付路径 —— "
                        + "submit 里那道「已支付 + 已驳回 → 拒绝重交」自然走不到了。"
                        + "这正是「退状态」比「单独开一条重交通道」省事的地方");
        assertEquals(OrderStatus.PAID.name(), orderMapper.get(order.getId()).getStatus(),
                "重交之后当场又转已支付（提交即交付）");
    }

    @Test
    @DisplayName("驳回商品：只退状态，不还库存 —— 货多半已被取走")
    void reject_revertsProductWithoutRestoringStock() {
        // 先往商品表里放一件货：seedPendingProductOrder 只指定 productId=1，
        // 而「扣库存」那一步要真的找得到商品
        Product product = new Product();
        product.setId(1L);
        product.setName("可乐");
        product.setStock(10);
        productMapper.seed(product);

        ProductOrder productOrder = seedPendingProductOrder();
        seedUser(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.PRODUCT, productOrder.getId()));
        int stockAfterPay = productMapper.get(1L).getStock();

        PaymentProof proof = proofMapper.find(PaymentTargetType.PRODUCT.name(), productOrder.getId());
        service.reject(proof.getId(), ADMIN_ID, "金额对不上");

        assertEquals(ProductOrderStatus.REJECTED.name(),
                productOrderMapper.get(productOrder.getId()).getStatus(),
                "购买单标成「凭证未通过」，用户能重新上传 —— "
                        + "与订单同一条理由：不能退成「待支付」，"
                        + "那会让用户以为要再下一次单");
        assertEquals(stockAfterPay, productMapper.get(1L).getStock(),
                "**库存不还** —— 无人值守店里付了钱自己取，而驳回发生在管理员有空复核时，"
                        + "那时货多半已经不在货架上了。还回去等于记一笔假账，"
                        + "而账实不符比少记一件更难查");
    }

    @Test
    @DisplayName("驳回后重交：状态翻回待复核，旧结论清空")
    void resubmitAfterReject() {
        Booking booking = seedPendingBooking(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.BOOKING, booking.getId()));
        PaymentProof proof = proofMapper.find(PaymentTargetType.BOOKING.name(), booking.getId());
        service.reject(proof.getId(), ADMIN_ID, "看不清");

        ProofSubmitRequest again = request(PaymentTargetType.BOOKING, booking.getId());
        again.setProofUrl("/uploads/proof/newer.jpg");
        BizResult<ProofSubmitVo> result = service.submit(USER_ID, again);

        assertTrue(result.isSuccess(), "包场等复核，驳回后用户可以重交（订单那类则要人工处置）");
        PaymentProof saved = proofMapper.find(PaymentTargetType.BOOKING.name(), booking.getId());
        assertEquals(PaymentProofStatus.SUBMITTED.name(), saved.getVerifyStatus(),
                "重交之后要回到待复核 —— 不然管理员再也看不到它");
        assertNull(saved.getRejectReason(), "旧原因要清掉，否则会误导下一次复核");
        assertEquals("/uploads/proof/newer.jpg", saved.getProofUrl());
    }

    // ==================================================================
    // 后台列表
    // ==================================================================

    @Test
    @DisplayName("后台列表：补上提交人昵称与收款码名，风险条排最前")
    void listForAdmin_enrichesAndSorts() {
        seedUser(USER_ID);
        PayQr qr = seedPayQr();

        Order withQr = seedPendingOrder(USER_ID);
        ProofSubmitRequest a = request(PaymentTargetType.ORDER, withQr.getId());
        a.setPayQrId(qr.getId());
        a.setPaymentNo("WX-TX-DUP");
        service.submit(USER_ID, a);

        Order plain = seedPendingOrder(USER_ID);
        ProofSubmitRequest b = request(PaymentTargetType.ORDER, plain.getId());
        b.setPaymentNo("WX-TX-DUP");
        service.submit(USER_ID, b);

        PageResult<AdminProofVo> page = service.listForAdmin(1, 10, null);

        assertEquals(2, page.getTotal(), "两条都要在");
        AdminProofVo first = page.getRecords().get(0);
        assertTrue(first.isRisk(), "有风险的排最前 —— 那是最该先看一眼的一类");
        assertEquals("user" + USER_ID, first.getUserNickname(),
                "昵称要批量查出来填上，管理员认人靠它而不是 ID");
        assertEquals("微信收款码",
                page.getRecords().stream()
                        .filter(v -> v.getPayQrName() != null)
                        .findFirst().orElseThrow().getPayQrName(),
                "凭证上记的是收款码 ID，列表里要补上名字 —— "
                        + "店里有多个收款账号时，靠它才知道钱进了谁的口袋");
        assertEquals("订单", first.getTargetTypeLabel(),
                "类型中文名由枚举给，前端不再各写一张表");
        assertTrue(first.isDelivered(),
                "订单是「提交即交付」—— 后台要靠这个标记提示「驳回需人工回退」");
    }

    @Test
    @DisplayName("后台列表：按状态筛选只返回那一种")
    void listForAdmin_filtersByStatus() {
        seedUser(USER_ID);
        Order order = seedPendingOrder(USER_ID);
        service.submit(USER_ID, request(PaymentTargetType.ORDER, order.getId()));
        PaymentProof proof = proofMapper.find(PaymentTargetType.ORDER.name(), order.getId());

        assertEquals(1, service.listForAdmin(1, 10, PaymentProofStatus.SUBMITTED.name()).getTotal());
        assertEquals(0, service.listForAdmin(1, 10, PaymentProofStatus.CONFIRMED.name()).getTotal());

        proofMapper.confirm(proof.getId(), ADMIN_ID);

        assertEquals(0, service.listForAdmin(1, 10, PaymentProofStatus.SUBMITTED.name()).getTotal(),
                "复核过的不该再出现在待办里");
        assertEquals(1, service.listForAdmin(1, 10, PaymentProofStatus.CONFIRMED.name()).getTotal());
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /**
     * 造一份提交请求。
     *
     * <p><b>刻意没有金额参数</b>：凭证上的金额取自目标本身
     * （{@code target.getAmount()}），不由请求体决定 —— 那正是不能被
     * 客户端左右的东西。要改金额的用例该去改目标，不是改请求。
     *
     * @param type     收款类型
     * @param targetId 目标 ID
     * @return 请求对象
     */
    private static ProofSubmitRequest request(PaymentTargetType type, Long targetId) {
        ProofSubmitRequest request = new ProofSubmitRequest();
        request.setTargetType(type);
        request.setTargetId(targetId);
        request.setProofUrl(OK_URL);
        return request;
    }

    /**
     * 预置一条待支付的订单。
     *
     * @param userId 用户 ID
     * @return 订单
     */
    private Order seedPendingOrder(Long userId) {
        Order order = new Order();
        // 单号要唯一：同一个用例里可能造两条，而 selectByOrderNo 取的是第一条命中的
        order.setOrderNo("OD" + System.nanoTime() + userId);
        order.setUserId(userId);
        order.setStoreId(STORE_ID);
        order.setStatus(OrderStatus.PENDING_PAYMENT.name());
        order.setStartTime(LocalDateTime.now().minusHours(2));
        order.setEndTime(LocalDateTime.now());
        order.setPayableAmount(AMOUNT);
        order.setTotalAmount(AMOUNT);
        return orderMapper.seed(order);
    }

    /** 预置一场待付款的包场 */
    private Booking seedPendingBooking(Long hostUserId) {
        Booking booking = new Booking();
        booking.setBookingNo("BK" + System.nanoTime());
        booking.setStoreId(STORE_ID);
        booking.setHostUserId(hostUserId);
        booking.setStartAt(LocalDateTime.now().plusHours(1));
        booking.setEndAt(LocalDateTime.now().plusHours(5));
        booking.setPrice(BOOKING_PRICE);
        booking.setStatus(BookingStatus.PENDING_PAYMENT.name());
        return bookingMapper.seed(booking);
    }

    /** 预置一张待付款的月卡购买单 */
    private MonthlyCardOrder seedPendingCardOrder() {
        MonthlyCardOrder order = new MonthlyCardOrder();
        order.setOrderNo(MonthlyCardNo.generate());
        order.setUserId(USER_ID);
        order.setCardType(MonthlyCardType.ALL_DAY.name());
        order.setPrice(CARD_PRICE);
        order.setStatus(CardOrderStatus.PENDING_PAYMENT.name());
        return cardOrderMapper.seed(order);
    }

    /** 预置一张待付款的商品购买单 */
    private ProductOrder seedPendingProductOrder() {
        ProductOrder order = new ProductOrder();
        order.setOrderNo("PD" + System.nanoTime());
        order.setUserId(USER_ID);
        order.setProductId(1L);
        order.setProductName("可乐");
        order.setUnitPrice(AMOUNT);
        order.setQuantity(1);
        order.setAmount(AMOUNT);
        order.setStatus(ProductOrderStatus.PENDING_PAYMENT.name());
        return productOrderMapper.seed(order);
    }

    /** 预置一张启用的收款码 */
    private PayQr seedPayQr() {
        PayQr qr = new PayQr();
        qr.setStoreId(STORE_ID);
        qr.setChannel(PayQrChannel.WXPAY.name());
        qr.setName("微信收款码");
        qr.setImageUrl("/uploads/payqr/abc.png");
        qr.setEnabled(1);
        qr.setSort(0);
        return payQrMapper.seed(qr);
    }

    /**
     * 预置一个用户。
     *
     * @param userId 用户 ID
     */
    private void seedUser(Long userId) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("user" + userId);
        user.setNickname("user" + userId);
        user.setOrderPaid(BigDecimal.ZERO);
        user.setCardPaid(BigDecimal.ZERO);
        user.setTotalPaid(BigDecimal.ZERO);
        user.setTokenVersion(1);
        userMapper.seed(user);
    }
}
