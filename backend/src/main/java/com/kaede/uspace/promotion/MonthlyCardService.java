package com.kaede.uspace.promotion;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.billing.BillingProperties;
import com.kaede.uspace.billing.CardCoverage;
import com.kaede.uspace.billing.CardScope;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.trade.TradeSource;
import com.kaede.uspace.promotion.dto.CardPurchaseVo;
import com.kaede.uspace.promotion.dto.CardTypeVo;
import com.kaede.uspace.promotion.dto.CardWalletVo;
import com.kaede.uspace.promotion.dto.MonthlyCardVo;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.promotion.entity.MonthlyCardOrder;
import com.kaede.uspace.promotion.event.CardPurchaseCancelledEvent;
import com.kaede.uspace.promotion.mapper.MonthlyCardMapper;
import com.kaede.uspace.promotion.mapper.MonthlyCardOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 月卡服务（模块 9）。
 *
 * <p>职责：月卡的<b>购买发起、取消、查询、生效判定与到期翻转</b>。
 * 收款不在这里 —— 创建出来的购买单处于待支付状态，
 * 付款成功后由模块 8 的支付回调来发卡（{@code MonthlyCardPaymentTargetHandler}），
 * 这条边界与包场一致：排期归模块 3、收款归模块 8，月卡是「售卖归模块 9、收款归模块 8」。
 *
 * <p><b>免单不在这里算</b>。本服务只回答「这个人在这一天有没有卡、卡覆盖哪些时段」
 * （{@link #findCoverageAt}），把答案交给计费侧逐段处理 ——
 * 计费是模块 7 的纯计算，不查库，两边通过参数衔接。
 */
@Slf4j
@Service
public class MonthlyCardService {

    /** 月卡数据访问 */
    private final MonthlyCardMapper cardMapper;

    /** 购买单数据访问 */
    private final MonthlyCardOrderMapper orderMapper;

    /** 优惠配置：价格、有效期天数、待支付存活时长 */
    private final PromotionProperties properties;

    /** 计费配置。只为组装「夜场是几点到几点」这类展示文案，不参与任何计算 */
    private final BillingProperties billingProperties;

    /** 事件发布器：未付款的购买单被取消时发一条，由 {@code order} 包记进交易流水 */
    private final ApplicationEventPublisher events;

    public MonthlyCardService(MonthlyCardMapper cardMapper,
                              MonthlyCardOrderMapper orderMapper,
                              PromotionProperties properties,
                              BillingProperties billingProperties,
                              ApplicationEventPublisher events) {
        this.cardMapper = cardMapper;
        this.orderMapper = orderMapper;
        this.properties = properties;
        this.billingProperties = billingProperties;
        this.events = events;
    }

    // ==================================================================
    // 购买
    // ==================================================================

    /**
     * 发起购买月卡，落一条待支付购买单。
     *
     * <p>付款走模块 8 的统一支付入口（{@code POST /api/payments}，
     * {@code targetType = MONTHLY_CARD}），支付成功后回调会转已支付并发卡。
     *
     * <p><b>两道占用校验，目的是不让人同时持有两张重叠的卡</b>：
     * <ol>
     *   <li>已有生效中的卡 → 拒绝。续费要在旧卡到期之后 ——
     *       <b>已过期的卡不挡</b>，那正是续费的主场景</li>
     *   <li>已有未超时的待支付单 → 拒绝，前端引导回到收银台。
     *       <b>已超时的单先关掉再放行</b>，用户不会因为「上次点了没付」而被永久卡住</li>
     * </ol>
     *
     * <p>另有主动取消接口，用户不必干等存活时长过去。
     *
     * <p>⚠️ <b>{@code @Transactional} 不是可选项</b>：{@code SELECT ... FOR UPDATE}
     * 依赖事务才有意义。少了它，语句会因自动提交而静默不加锁 ——
     * 并发保护消失，且不会有任何报错。
     *
     * @param userId   购买人
     * @param cardType 卡种
     * @return 成功时返回购买单（含单号与应付金额）；失败时返回对应错误码
     */
    @Transactional
    public BizResult<CardPurchaseVo> purchase(Long userId, MonthlyCardType cardType) {
        if (cardType == null) {
            throw new IllegalArgumentException("月卡类型不能为空");
        }
        if (!properties.getMonthlyCard().isEnabled()) {
            return BizResult.fail(ErrorCode.BUSINESS_REJECTED, "月卡购买暂未开放，请稍后再试");
        }

        LocalDate today = LocalDate.now();
        MonthlyCard active = cardMapper.selectActiveAt(userId, today);
        if (active != null) {
            return BizResult.fail(ErrorCode.CARD_ALREADY_ACTIVE,
                    "你已有一张" + MonthlyCardType.labelOf(active.getCardType())
                            + "，有效期至 " + active.getEndDate() + "，到期后可再次购买");
        }

        // 截断到秒与数据库列精度对齐：库列是 DATETIME，亚秒会被 MySQL 四舍五入，
        // 不截断会让内存里的时刻与查出来的值差最多 1 秒（模块 6 起沿用的习惯）
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Duration timeout = properties.getMonthlyCard().getPendingTimeout();

        // 加锁读该用户的待支付单：既是占用校验，也是并发保护 ——
        // 要挡的竞态是「两个购买请求同时通过检查、各插一条待支付单」，
        // 而插入发生在本表上，所以锁的必须是本表
        boolean hasFreshPending = false;
        for (MonthlyCardOrder pending : orderMapper.selectPendingForUpdate(userId)) {
            if (isTimedOut(pending, now, timeout)) {
                orderMapper.closePending(pending.getId());
                log.info("[优惠] 关闭超时的月卡购买单 {}（用户 {}）", pending.getOrderNo(), userId);
            } else {
                hasFreshPending = true;
            }
        }
        if (hasFreshPending) {
            return BizResult.fail(ErrorCode.CARD_PENDING_PAYMENT_EXISTS);
        }

        MonthlyCardOrder order = new MonthlyCardOrder();
        order.setOrderNo(MonthlyCardNo.generate());
        order.setUserId(userId);
        order.setCardType(cardType.name());
        // 价格在这里快照，之后调价不影响这一单
        order.setPrice(properties.getMonthlyCard().priceOf(cardType));
        order.setStatus(CardOrderStatus.PENDING_PAYMENT.name());
        orderMapper.insert(order);

        log.info("[优惠] 用户 {} 发起购买{}，单号 {} 金额 {} 元",
                userId, cardType.getLabel(), order.getOrderNo(), order.getPrice());
        return BizResult.ok(CardPurchaseVo.from(order, timeout));
    }

    /**
     * 取消一笔待支付的购买。
     *
     * <p><b>这不是退款</b> —— 那笔钱从来没付过。取消只是把购买单关掉，
     * 让用户可以立刻重新选择卡种，不必等存活时长过去。
     *
     * <p>非本人的订单一律按「不存在」处理，不用 403：403 等于承认
     * 「这个单子存在，只是不归你」，可以被用来枚举单号。
     *
     * <p>成功之后发一条 {@link CardPurchaseCancelledEvent} —— 由
     * {@code order} 包的 {@code TradeLogListener} 记进交易流水。本方法本来
     * 就带 {@code @Transactional}，那条流水因此与这次关闭同生共死。
     *
     * @param userId     当前登录用户
     * @param purchaseId 购买单 ID
     * @param source     来源渠道（网页端 / 群内），只进流水，不参与任何判断
     * @return 成功返回空数据；单子不存在、不归本人、或已不是待支付状态时返回对应错误码
     */
    @Transactional
    public BizResult<Void> cancelPurchase(Long userId, Long purchaseId, TradeSource source) {
        MonthlyCardOrder order = orderMapper.selectById(purchaseId);
        if (order == null || !order.getUserId().equals(userId)) {
            return BizResult.fail(ErrorCode.CARD_NOT_FOUND);
        }
        if (orderMapper.closePending(order.getId()) == 0) {
            return BizResult.fail(ErrorCode.CARD_STATUS_INVALID, "该购买单不是待支付状态，无法取消");
        }
        log.info("[优惠] 用户 {} 取消了月卡购买单 {}", userId, order.getOrderNo());
        events.publishEvent(new CardPurchaseCancelledEvent(
                order.getOrderNo(), userId, order.getPrice(), source));
        return BizResult.ok(null);
    }

    /**
     * 按<b>单号</b>取消未付款的购买单 —— 群里的 {@code fw取消 <单号>} 走这条。
     *
     * <p>与商品那条同构：网页端按 ID 取消，群里只有单号可填。
     * 两条最终汇进 {@link #cancelPurchase}，守卫与流水只有一份。
     *
     * <p><b>本方法自己带 {@code @Transactional}</b>：内部调
     * {@link #cancelPurchase} 属于同类自调用，那个方法的事务注解不会生效 ——
     * 少了这一层，「取消成功、流水插入失败」会分成两个独立事务。
     *
     * @param userId  当前用户（必须是这张单的主人）
     * @param orderNo 购买单号
     * @param source  来源渠道（网页端 / 群内），只进流水
     * @return 成功返回空数据；单号不存在、不归本人、或已不是待支付状态时返回对应错误码
     */
    @Transactional
    public BizResult<Void> cancelPurchaseByNo(Long userId, String orderNo, TradeSource source) {
        MonthlyCardOrder order = orderMapper.selectByOrderNo(orderNo);
        if (order == null || !order.getUserId().equals(userId)) {
            return BizResult.fail(ErrorCode.CARD_NOT_FOUND);
        }
        return cancelPurchase(userId, order.getId(), source);
    }

    /**
     * 查某人当前待支付的月卡购买单 —— 群里的 {@code fw未付款} 用。
     *
     * <p>至多一笔：{@code createPurchase} 挡着「已有待支付购买单」的重复下单
     *（{@code CARD_PENDING_PAYMENT_EXISTS}），月卡又是「一人一卡」，
     * 所以复用单数查询就够，不必另开列表版。
     *
     * @param userId 用户 ID
     * @return 待支付的购买单；没有时 {@code data} 为 null
     */
    public BizResult<CardPurchaseVo> findPendingPurchase(Long userId) {
        return BizResult.ok(CardPurchaseVo.from(orderMapper.selectPendingByUser(userId),
                properties.getMonthlyCard().getPendingTimeout()));
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 查我的卡包：生效中的卡、待支付的购买单、历史卡。
     *
     * @param userId 当前登录用户
     * @return 卡包视图
     */
    public BizResult<CardWalletVo> wallet(Long userId) {
        LocalDate today = LocalDate.now();

        CardWalletVo vo = new CardWalletVo();
        vo.setPending(CardPurchaseVo.from(orderMapper.selectPendingByUser(userId),
                properties.getMonthlyCard().getPendingTimeout()));

        List<MonthlyCardVo> history = new ArrayList<>();
        for (MonthlyCard card : cardMapper.selectByUser(userId)) {
            MonthlyCardVo cardVo = MonthlyCardVo.from(card, today);
            if (isEffectiveNow(card, today)) {
                vo.setActive(cardVo);
            } else {
                history.add(cardVo);
            }
        }
        vo.setHistory(history);
        return BizResult.ok(vo);
    }

    /**
     * 列出在售卡种及其价格，供购买页展示。
     *
     * <p>价格每张卡都从配置现取，所以调价之后前端重新拉一次即可，
     * 不必等发版。
     *
     * @return 卡种列表
     */
    public BizResult<List<CardTypeVo>> cardTypes() {
        List<CardTypeVo> result = new ArrayList<>();
        for (MonthlyCardType type : MonthlyCardType.values()) {
            CardTypeVo vo = new CardTypeVo();
            vo.setCardType(type.name());
            vo.setLabel(type.getLabel());
            vo.setPrice(properties.getMonthlyCard().priceOf(type));
            vo.setCoverageLabel(type.getScope().getLabel());
            vo.setPeriodText(periodTextOf(type));
            vo.setValidDays(properties.getMonthlyCard().getValidDays());
            result.add(vo);
        }
        return BizResult.ok(result);
    }

    /**
     * 分页查询月卡，供运营后台使用。
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param userId   持卡用户 ID，可空
     * @param status   状态，可空
     * @param cardType 卡类型，可空
     * @return 分页结果
     */
    public BizResult<PageResult<MonthlyCardVo>> listCards(long pageNum, long pageSize,
                                                          Long userId, String status,
                                                          String cardType) {
        String statusFilter = trimToNull(status);
        String typeFilter = trimToNull(cardType);
        if (statusFilter != null && !MonthlyCardStatus.isValid(statusFilter)) {
            return BizResult.fail(ErrorCode.PARAM_INVALID, "月卡状态取值不合法");
        }
        if (typeFilter != null && !MonthlyCardType.isValid(typeFilter)) {
            return BizResult.fail(ErrorCode.PARAM_INVALID, "月卡类型取值不合法");
        }

        IPage<MonthlyCard> page = cardMapper.selectPageBy(
                new Page<>(pageNum, pageSize), userId, statusFilter, typeFilter);
        LocalDate today = LocalDate.now();
        return BizResult.ok(PageResult.of(page, card -> MonthlyCardVo.from(card, today)));
    }

    // ==================================================================
    // 供其他模块调用
    // ==================================================================

    /**
     * 取某用户在某日生效的月卡覆盖范围，供订单结算调用。
     *
     * <p>返回 {@code null} 表示无卡或卡不覆盖任何时段，计费侧据此按普通订单计价。
     *
     * <p><b>带回来的是这张卡的【完整有效期】，不只是用来查询的那一天</b> ——
     * 计费侧要把有效期的两个端点当作切分线，跨过零点的订单才能被正确切成
     * 「卡内免费」与「卡外收费」两段。只给一个日期的话，卡最后一天 23:00 进店、
     * 次日 01:00 离场会被整单免掉（等于多送一个多小时），卡生效当天凌晨进店的
     * 又会整单都不免。
     *
     * <p><b>用哪一天去查由调用方指定，且应当是订单的开始日期</b>，不是「现在」——
     * 与月累计消费的口径一致：跨零点结算的夜单不会因为跨了一天就换一套判定，
     * 管理员事后修正时长也不会让历史订单的免单结论漂移。
     *
     * @param userId 用户 ID
     * @param date   判定日期，决定取哪一张卡
     * @return 覆盖范围（时段 + 该卡的完整有效期）；无卡时返回 null
     */
    public CardCoverage findCoverageAt(Long userId, LocalDate date) {
        MonthlyCard card = findActiveCard(userId, date);
        if (card == null || !MonthlyCardType.isValid(card.getCardType())) {
            // 卡种名认不出说明数据被人改过，按无卡处理 ——
            // 宁可少免一次，也不能因为一个脏值让计费抛异常、结不了账
            return null;
        }
        if (card.getStartDate() == null || card.getEndDate() == null) {
            // 两列都是 NOT NULL，理论上到不了这里。真出现说明表结构被人改过，
            // 同样按无卡处理 —— 理由同上，且绝不能让计费侧拿到一个空区间
            return null;
        }
        return CardCoverage.of(MonthlyCardType.valueOf(card.getCardType()).getScope(),
                card.getStartDate(), card.getEndDate());
    }

    /**
     * 查某用户在某日生效的月卡。
     *
     * <p>供订单结算与 QQ 机器人播报（「是否月卡」）使用。
     *
     * @param userId 用户 ID
     * @param date   判定日期
     * @return 生效中的月卡；没有则返回 null
     */
    public MonthlyCard findActiveCard(Long userId, LocalDate date) {
        return cardMapper.selectActiveAt(userId, date);
    }

    /**
     * 把已过有效期的生效中卡翻转为已过期。
     *
     * <p>供定时任务调用，返回受影响行数。它<b>只是让状态列与事实保持一致</b>：
     * 免单判定同时校验状态与日期，所以这个任务漏跑（服务停过）也不会
     * 出现「过期卡还在免单」，最多是列表上显示的状态晚一会儿才变。
     *
     * @param today 今天的日期
     * @return 本次翻转的卡数
     */
    public int expireOutdated(LocalDate today) {
        return cardMapper.expireBefore(today);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 判断一张卡此刻是否真正生效。
     *
     * <p>与 {@code MonthlyCardMapper#selectActiveAt} 的口径一致：状态为生效中，
     * 且今天落在生效与失效日期之间（两端都含）。两处判定同源，
     * 否则会出现「列表说生效、结算说不生效」这种自相矛盾。
     *
     * @param card  月卡
     * @param today 今天的日期
     * @return 生效返回 true
     */
    private static boolean isEffectiveNow(MonthlyCard card, LocalDate today) {
        return MonthlyCardStatus.ACTIVE.name().equals(card.getStatus())
                && card.getStartDate() != null && card.getEndDate() != null
                && !card.getStartDate().isAfter(today)
                && !card.getEndDate().isBefore(today);
    }

    /**
     * 判断一张待支付单是否已超过存活时长。
     *
     * @param order   购买单
     * @param now     当前时刻（已截断到秒）
     * @param timeout 存活时长
     * @return 已超时返回 true
     */
    private static boolean isTimedOut(MonthlyCardOrder order, LocalDateTime now, Duration timeout) {
        return order.getCreatedAt() != null
                && !order.getCreatedAt().plus(timeout).isAfter(now);
    }

    /**
     * 取卡种覆盖时段的展示文案。
     *
     * <p>夜间卡的起止时刻取自计费配置，与计费本身共用同一套边界 ——
     * 在这里另写死「22:00」的话，改了营业时段就会出现
     * 「月卡说夜间从 22:00 起、账单却按另一个时刻切段」。
     *
     * @param type 卡种
     * @return 时段说明
     */
    private String periodTextOf(MonthlyCardType type) {
        if (type.getScope() == CardScope.ALL) {
            return "不限时段";
        }
        return billingProperties.getDayEnd() + " – 次日 " + billingProperties.getDayStart();
    }

    /**
     * 去除首尾空白，空串归一为 null。
     *
     * @param value 原始字符串，可为 null
     * @return 去空白后的字符串；空白串返回 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
