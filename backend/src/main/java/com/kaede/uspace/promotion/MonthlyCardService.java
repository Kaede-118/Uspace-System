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
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /**
     * 用户表。后台开卡要用它办两件事：确认这个人存在、把这笔卡费累加进
     * {@code card_paid}（与线上购买的累加点同一个方法，口径不会分叉）。
     */
    private final SysUserMapper sysUserMapper;

    public MonthlyCardService(MonthlyCardMapper cardMapper,
                              MonthlyCardOrderMapper orderMapper,
                              PromotionProperties properties,
                              BillingProperties billingProperties,
                              ApplicationEventPublisher events,
                              SysUserMapper sysUserMapper) {
        this.cardMapper = cardMapper;
        this.orderMapper = orderMapper;
        this.properties = properties;
        this.billingProperties = billingProperties;
        this.events = events;
        this.sysUserMapper = sysUserMapper;
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
        // ⚠️ 非在售卡种（STAFF 卡）一律拒绝：它只在后台发放，
        // 允许走购买接口的话，任何登录用户填个卡种名就能给自己发一张永久免费卡
        if (!cardType.isOnSale()) {
            return BizResult.fail(ErrorCode.PARAM_INVALID,
                    "该卡种不支持购买，请联系管理员办理");
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
    // 后台发放与收回（面对面收款场景）
    // ==================================================================

    /**
     * 后台给指定用户开一张卡 —— 面对面收款（现金 / 当面转账）之后的补录。
     *
     * <p><b>它补的是「用户自己线上购买」之外的那条路</b>：顾客在店里当面付了钱，
     * 却让他掏出手机在商城再下一次单、再传一张截图给管理员复核，
     * 是把一个已经完成的交易重新演一遍。管理员在这里一次点完即可。
     * 测试时也走它 —— 不必真的走一遍收银台。
     *
     * <h3>三条按卡种分岔的规则</h3>
     *
     * <ul>
     *   <li><b>STAFF 卡（{@link MonthlyCardType#STAFF}）</b>：无价格（强制 0）、
     *       到期日填远期日期（{@link MonthlyCardType#STAFF_END_DATE}），
     *       <b>不校验「已有生效卡」</b> —— 给一个自己买过月卡的店员
     *       发STAFF 卡是正常操作，挡住他只会让管理员束手无策（月卡不能退，
     *       STAFF 卡也就永远发不出来）。两者并存时计费取STAFF 卡，
     *       见 {@code MonthlyCardMapper#selectActiveAt} 的排序</li>
     *   <li><b>普通卡</b>：校验该用户没有生效中的卡 —— 与线上购买同一口径
     *       （{@code CARD_ALREADY_ACTIVE}），不给人开第二张重叠的卡；
     *       有效期从今天起算，与线上购买一致</li>
     *   <li><b>金额</b>：普通卡可在入参里覆盖（当面交易可能打折或抹零），
     *       不传则按配置价；<b>大于 0 才累加 {@code card_paid}</b>，
     *       与线上支付记的是同一个方法（{@link SysUserMapper#addCardPaidAmount}），
     *       两个口径不会分叉</li>
     * </ul>
     *
     * <p>⚠️ <b>这笔钱不进对账系统</b>：对账勾稽的是「扫码转账 + 用户传截图」
     * 那条路上的付款凭证，而面对面收款发生在店内，本来就没有凭证。
     * 若管理员收的是扫店内收款码转来的钱，那笔流水会出现在微信账单里、
     * 对账时作为「账单里有、系统无凭证」列出来 —— 那是预期行为，
     * 核对时知道「这条是开卡收的」即可（本条日志里有单号与金额）。
     *
     * @param userId   持卡人
     * @param cardType 卡种
     * @param price    实收金额（元），可为 null（按配置价）；STAFF 卡忽略此参数
     * @return 成功时返回新卡视图；用户不存在或已有生效卡时返回对应错误码
     */
    @Transactional
    public BizResult<MonthlyCardVo> issueCard(Long userId, MonthlyCardType cardType,
                                              BigDecimal price) {
        if (cardType == null) {
            throw new IllegalArgumentException("卡种不能为空");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return BizResult.fail(ErrorCode.USER_NOT_FOUND);
        }
        if (price != null && price.signum() < 0) {
            return BizResult.fail(ErrorCode.PARAM_INVALID, "实收金额不能为负数");
        }

        LocalDate today = LocalDate.now();
        // 含首尾：30 天 → end = start + 29
        LocalDate endDate = today.plusDays(properties.getMonthlyCard().getValidDays() - 1L);

        MonthlyCard card = new MonthlyCard();
        card.setCardNo(MonthlyCardNo.generate());
        card.setUserId(userId);
        card.setCardType(cardType.name());
        card.setStatus(MonthlyCardStatus.ACTIVE.name());
        // 线下交易没有线上通道；payOrderNo 留空即「这张卡不是任何一张购买单开出来的」
        card.setPaymentMethod(null);
        card.setPayOrderNo(null);
        card.setPaidAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));

        BigDecimal amount;
        if (cardType == MonthlyCardType.STAFF) {
            // STAFF 卡：无价格、起止日都是远期/远古日期（合起来表达「一直有效」，
            // 各自为什么这么填见那两个常量的注释 —— 起始日填开卡当日会让
            // 「先开门后开卡」的人在在店列表里看不到卡，2026-10-10 踩过）。
            // 金额强制 0，累加那一步自然跳过
            amount = BigDecimal.ZERO;
            card.setStartDate(MonthlyCardType.STAFF_START_DATE);
            card.setEndDate(MonthlyCardType.STAFF_END_DATE);
        } else {
            if (cardMapper.selectActiveAt(userId, today) != null) {
                return BizResult.fail(ErrorCode.CARD_ALREADY_ACTIVE,
                        "该用户已有生效中的卡，到期后再办；如需更换请先在下方停用");
            }
            amount = price == null ? properties.getMonthlyCard().priceOf(cardType) : price;
            card.setStartDate(today);
            card.setEndDate(endDate);
        }
        card.setPrice(amount);
        cardMapper.insert(card);

        // 收得到钱才记账：0 元开卡（测试、赠送）不该往累计消费里加一笔假数
        if (amount.signum() > 0) {
            sysUserMapper.addCardPaidAmount(userId, amount);
        }

        log.info("[优惠] 后台开卡 卡号={} 用户={} 卡种={} 有效期 {} ~ {} 实收={} 元",
                card.getCardNo(), userId, cardType,
                card.getStartDate(), card.getEndDate(), amount);
        return BizResult.ok(MonthlyCardVo.from(card, today));
    }

    /**
     * 后台停用一张卡（收回权益）—— <b>只停卡，不动钱</b>。
     *
     * <p>两个真实场景：STAFF 卡随人离职要收回；发错了对象（选错用户、选错卡种）
     * 要立刻止住 —— 而它在系统里的效果是「下一笔结算起不再免单」，
     * 已经结算过的订单一分不动（金额在结算时就落了库）。
     *
     * <p>⚠️ <b>状态守卫取 {@code ATOMIC} 的写法</b>（SQL 里带
     * {@code status = 'ACTIVE'}）：重复点两下不会二次生效，同时天然挡住
     * 对已过期 / 已退款 / 已停用卡的重复操作 —— 返回 0 就说明状态不对。
     *
     * <p><b>钱的事不在这里</b>：已收的卡费要不要退、退多少，是线下的事，
     * 系统不替它做决定（退款政策仍未拍板，见 {@code docs/月卡设计草案.md}）。
     * 这也是 {@code REVOKED} 与 {@code REFUNDED} 分开的原因 ——
     * 「卡停了」与「钱退了」是两个可以各自成立的结论。
     *
     * @param cardId 卡 ID
     * @return 成功时返回停用后的卡视图；卡不存在或不是生效中状态时返回对应错误码
     */
    @Transactional
    public BizResult<MonthlyCardVo> revokeCard(Long cardId) {
        MonthlyCard card = cardMapper.selectById(cardId);
        if (card == null) {
            return BizResult.fail(ErrorCode.CARD_NOT_FOUND);
        }
        if (cardMapper.revokeActive(cardId) == 0) {
            return BizResult.fail(ErrorCode.CARD_STATUS_INVALID,
                    "该卡不是生效中状态，无法停用");
        }
        // 内存里的实体同步改一下状态，让返回的视图直接是停用后的样子
        card.setStatus(MonthlyCardStatus.REVOKED.name());

        log.info("[优惠] 后台停用月卡 卡号={} 用户={} 卡种={}",
                card.getCardNo(), card.getUserId(), card.getCardType());
        return BizResult.ok(MonthlyCardVo.from(card, LocalDate.now()));
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 查我的卡包：生效中的卡、待支付的购买单、历史卡。
     *
     * <p>⚠️ <b>「哪张是生效中的那张」与计费同一个查询</b>（{@code selectActiveAt}）——
     * 一个人可以同时有两张生效的卡（STAFF 卡不挡已有月卡，见 {@link #issueCard}），
     * 而计费取的是最宽的那张（{@code end_date DESC}）。
     * 卡包若另有一套「遍历取最后一张」的口径，就会出现
     * 「卡包说他是夜间月卡、账单却全免」这种对不上的情形。
     *
     * <p>并存时 Active 位只放最宽的那张（UI 只有一个位置），其余生效卡进历史列表 ——
     * 它们的 {@code statusLabel} 仍是「生效中」，看得出「这张卡还在、只是当前按另一张免」。
     * 并存是罕见场景（店主给已有月卡的店员发 STAFF 卡），不值得为它改契约成多张。
     *
     * @param userId 当前登录用户
     * @return 卡包视图
     */
    public BizResult<CardWalletVo> wallet(Long userId) {
        LocalDate today = LocalDate.now();

        CardWalletVo vo = new CardWalletVo();
        vo.setPending(CardPurchaseVo.from(orderMapper.selectPendingByUser(userId),
                properties.getMonthlyCard().getPendingTimeout()));

        MonthlyCard active = cardMapper.selectActiveAt(userId, today);
        Long activeId = active == null ? null : active.getId();

        List<MonthlyCardVo> history = new ArrayList<>();
        for (MonthlyCard card : cardMapper.selectByUser(userId)) {
            MonthlyCardVo cardVo = MonthlyCardVo.from(card, today);
            if (card.getId().equals(activeId)) {
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
            // 非在售卡种（STAFF 卡）不列出来：这个接口是购买页的数据源，
            // 列出它等于在商城上架一张不该卖的卡 —— 而且 priceOf 对它直接抛，
            // 走进下面那行会 500（这也是一道「别把STAFF 卡当商品」的保险）
            if (!type.isOnSale()) {
                continue;
            }
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
            // 同样按无卡处理 —— 理由同上，且绝不能让计费侧拿到一个空区间。
            // 留一行日志：静默按无卡处理的话，顾客会在「明明有卡」的情况下被照常收费
            log.warn("[优惠] 卡 {} 缺少起止日期，按无卡处理（表数据异常？）", card.getCardNo());
            return null;
        }
        // STAFF 卡走的就是这条路：它的到期日在 2099，日期区间天然覆盖所有订单，
        // 计费侧一行特判都不需要（见 MonthlyCardType#STAFF_END_DATE）
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
