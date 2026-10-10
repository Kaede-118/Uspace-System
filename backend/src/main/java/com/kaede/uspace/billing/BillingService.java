package com.kaede.uspace.billing;

import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.billing.dto.NextChange;
import com.kaede.uspace.billing.dto.SegmentBill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 计费服务（模块 7）。
 *
 * <p>按使用时长与消费时段计算费用。规则详见 CLAUDE.md「计费规则」一节，
 * 核心是：
 *
 * <pre>
 *   段费用 = ceil( (段时长分钟 − 宽限) ÷ 单位时长 ) × 单价(时段, 是否优惠)
 *            再与该时段该优惠状态下的封顶取小
 *   总费用 = 各段费用之和
 * </pre>
 *
 * <p>参数（日夜场单价与封顶、月度优惠单价与封顶、单位时长、宽限、时段划分）
 * 全部来自 {@link BillingProperties}，不在代码中写死。
 *
 * <p>2026-09-26 定价改版加入两项：
 * <ul>
 *   <li><b>日夜场差异化定价</b>：日场 4 元/半小时，夜场 3.5 元/半小时</li>
 *   <li><b>月度累计消费优惠</b>：当月已支付实付额达到门槛后，本单整单按优惠价。
 *       判定在订单粒度，不逐档、不追溯</li>
 * </ul>
 *
 * <p>2026-09-28 调整：<b>档位起点（宽限）由 7 分钟改为 5 分钟</b>，
 * 档位边界随之落在 5、35、65… 分钟。单价与封顶金额不变，
 * 但「金额达到封顶所需时长」由 4 小时 38 分提前到 4 小时 36 分。
 * 本类代码无需改动 —— 宽限是配置项，这正是把规则外置的收益。
 *
 * <p><b>为什么按段分别套公式而不是整单算一次</b>：跨时段订单要求分段展示与分段封顶，
 * 各段独立计算才能让账单逐段可核对。副作用是每段各自享有一次宽限，
 * 使得同长的跨时段订单与不跨时段订单价格可能不同 —— 这是已确认接受的规则形态，
 * 详见 CLAUDE.md。
 *
 * <p>2026-09-29 加入<b>月卡段级免费</b>：被月卡覆盖的段金额置 0，
 * 覆盖范围由调用方以 {@link CardCoverage} 传入。计费侧只认识「哪些段免费」，
 * 不认识「月卡」这个业务名词 —— 于是加卡种、改覆盖范围这类促销形态的变化
 * 不会波及计价规则本身，而日夜场的边界也仍然只有本类一处定义。
 *
 * <p>2026-09-30 把月卡的<b>有效日期</b>也纳入覆盖判定（{@link CardCoverage}
 * 因此从「只有时段维度」的枚举变成带日期的对象）：卡的有效期边界与日夜场边界
 * 一样，是一条<b>切分线</b> —— 否则跨过零点的订单会整单落在卡内或卡外，
 * 两头都不对。代价与跨时段切段是同一类：每多切一段就多享一次宽限，
 * 所以切分只在卡的有效期边界真正落在订单区间内时发生，
 * 无卡时一段也不多切（见 {@link #splitByPeriod}）。
 *
 * <p>2026-10-10 的规则变化：<b>封顶按半场累计</b>。同一个半场（日场
 * 10:00-22:00、夜场 22:00-次日 10:00）里，无论被包场剪成几截、被活动或月卡
 * 切成几段，实收合计不超过一个封顶价 —— 起因是「有活动反而更贵」：
 * 10:00-22:00 无活动时整段封顶 40 元，被活动切出一段后各段分别封顶，
 * 反而能收 56 元。累计跨调用进行（{@link HalfPeriodUsage} 由调用方持有），
 * 因为包场剪切发生在订单模块、每一次剪切各调一次本类。
 *
 * <p><b>本类为纯计算，不访问数据库、不依赖其他模块</b>，因此可直接单元测试。
 * 月度优惠所需的「当月累计实付额」由调用方查好后经参数传入 ——
 * 这样 Web 结算与 QQ 机器人两条链路能复用同一份计费逻辑，
 * 也避免了计费服务反向依赖订单表。
 */
@Slf4j
@Service
public class BillingService {

    /** 计费规则参数 */
    private final BillingProperties properties;

    /**
     * 构造方法。
     *
     * <p>启动时把解析后的完整规则打一行日志。作用有两个：一是配置文件里写错的键
     * （比如未随改版更新的旧键名）会在此处暴露，而不是等到结算时算出错价才发现；
     * 二是答辩演示时可以直接指出「这些参数全部来自配置，改运营规则不用改代码」。
     *
     * @param properties 计费规则配置
     */
    public BillingService(BillingProperties properties) {
        this.properties = properties;

        BillingProperties.MonthlyDiscount discount = properties.getMonthlyDiscount();
        log.info("[计费] 规则已加载：日场 {} 元/{} 分钟（封顶 {} 元），夜场 {} 元/{} 分钟（封顶 {} 元）；"
                        + "免费宽限 {} 分钟；月度优惠 {}（门槛 {} 元，优惠价 日 {} 元/封顶 {}，夜 {} 元/封顶 {}）",
                properties.getDayUnitPrice(), properties.getUnitMinutes(), properties.getDayCap(),
                properties.getNightUnitPrice(), properties.getUnitMinutes(), properties.getNightCap(),
                properties.getGraceMinutes(),
                discount.isEnabled() ? "开启" : "关闭", discount.getThreshold(),
                discount.getDayUnitPrice(), discount.getDayCap(),
                discount.getNightUnitPrice(), discount.getNightCap());
    }

    /**
     * 计算一次消费的费用，不适用月度累计优惠。
     *
     * <p>适用于无法确定用户当月累计消费的场景（如管理员手动试算、
     * 或明确希望看到原价时）。等价于传入门槛以下的累计额。
     *
     * @param startTime 开始使用时间（用户点击「开门」的时刻）
     * @param endTime   结束使用时间（用户离场或订单结算时刻）
     * @return 含分段明细与总金额的计费结果
     * @throws IllegalArgumentException 当时间为空、或结束时间早于开始时间时抛出
     */
    public BillingResult calculate(LocalDateTime startTime, LocalDateTime endTime) {
        return calculate(startTime, endTime, BigDecimal.ZERO);
    }

    /**
     * 计算一次消费的费用，并判定是否适用月度累计优惠（不含月卡）。
     *
     * <p>等价于 {@code calculate(startTime, endTime, monthSpent, null)}。
     *
     * @param startTime  开始使用时间（用户点击「开门」的时刻）
     * @param endTime    结束使用时间（用户离场或订单结算时刻）
     * @param monthSpent 该用户<b>本月已支付订单的实付额之和</b>（元），
     *                   由调用方查询后传入，用于判定本单是否走优惠价；
     *                   传 null 视同 0（不优惠）
     * @return 含分段明细、总金额与优惠信息的计费结果
     * @throws IllegalArgumentException 当时间为空、或结束时间早于开始时间时抛出
     */
    public BillingResult calculate(LocalDateTime startTime, LocalDateTime endTime,
                                   BigDecimal monthSpent) {
        return calculate(startTime, endTime, monthSpent, null);
    }

    /**
     * 计算一次消费的费用，含月度累计优惠与月卡免费。
     *
     * <p><b>两种优惠并行存在、互不重叠，各自管各自的范围</b>：
     * <ul>
     *   <li><b>月度优惠</b>是整单粒度的 —— 门槛达到了，未免费的段整段走优惠价</li>
     *   <li><b>月卡</b>是段粒度的 —— 被覆盖的段直接免费，没覆盖的段照常计费。
     *       夜间卡用户的日场段因此仍参与月度优惠判定（那是正常付费消费），
     *       而不是「有卡就整单不打折」</li>
     * </ul>
     * 月卡的「段」同时也是<b>日期维度</b>上的一段：卡最后一天 23:00 进店、
     * 次日 01:00 离场时，这一段会被零点切成「卡内」与「卡外」两段，
     * 前者免费、后者照常计价 —— 而不是按订单开始时刻一刀切成全免或全收。
     * 这样安排还有一个实际好处：账单上「日场段按优惠价收 7 元、夜场段月卡免费」
     * 是算得出来的，而不是把两者混成一个说不清的数字。
     *
     * @param startTime    开始使用时间（用户点击「开门」的时刻）
     * @param endTime      结束使用时间（用户离场或订单结算时刻）
     * @param monthSpent   该用户本月已支付订单的实付额之和（元），null 视同 0
     * @param cardCoverage 月卡的覆盖范围（时段 + 有效日期区间）；无卡传 null
     * @return 含分段明细、总金额与三种优惠金额的计费结果
     * @throws IllegalArgumentException 当时间为空、或结束时间早于开始时间时抛出
     */
    public BillingResult calculate(LocalDateTime startTime, LocalDateTime endTime,
                                   BigDecimal monthSpent, CardCoverage cardCoverage) {
        return calculate(startTime, endTime, monthSpent, cardCoverage, null);
    }

    /**
     * 计算一次消费的费用，含月度累计优惠、月卡免费与免费活动。
     *
     * <p><b>三种优惠并行存在、互不重叠，各自管各自的范围</b>：
     * <ul>
     *   <li><b>月度优惠</b>整单粒度 —— 门槛达到了，未免费的段整段走优惠价</li>
     *   <li><b>月卡</b>段粒度 —— 被覆盖的段免费；夜间卡用户的日场段仍照常计费</li>
     *   <li><b>免费活动</b>也是段粒度，与月卡走同一条路（段保留、金额置 0）——
     *       账单因此说得清「这段为什么免费、这场活动省了多少」。
     *       ⚠️ 与包场不同：包场是把区间整段剪掉、根本不产生分段</li>
     * </ul>
     *
     * <p>⚠️ <b>三者都必须在「按原价重算」时一起传下去</b>（见方法体里的警告）——
     * 漏传任何一个，那份差额都会落进 {@code discountAmount}，
     * 被记成「月度优惠的效果」。
     *
     * @param startTime    开始使用时间（用户点击「开门」的时刻）
     * @param endTime      结束使用时间（用户离场或订单结算时刻）
     * @param monthSpent   该用户本月已支付订单的实付额之和（元），null 视同 0
     * @param cardCoverage 月卡的覆盖范围（时段 + 有效日期区间）；无卡传 null
     * @param freeRanges   免费活动的区间（任意起止时刻，可跨日夜场与零点）；
     *                     没有活动传 null 或空列表
     * @return 含分段明细、总金额与三种优惠金额的计费结果
     * @throws IllegalArgumentException 当时间为空、或结束时间早于开始时间时抛出
     */
    public BillingResult calculate(LocalDateTime startTime, LocalDateTime endTime,
                                   BigDecimal monthSpent, CardCoverage cardCoverage,
                                   List<FreeRange> freeRanges) {
        return calculate(startTime, endTime, monthSpent, cardCoverage, freeRanges,
                new HalfPeriodUsage());
    }

    /**
     * 同上，但<b>半场额度由调用方持有、可跨多次调用累计</b>。
     *
     * <p><b>为什么需要这个重载</b>：一个订单的计费区间可能被包场剪成多截
     *（见 {@code OrderService#billableRanges}），每截调一次本方法。
     * 封顶自 2026-10-10 起按<b>半场</b>累计 —— 同一半场的几截共享一个封顶额度，
     * 累计器因此必须活在这些调用的外面，由调用方创建并逐次传入。
     * 单截区间直接用上面那个 5 参版本即可，它内部新建一个累计器。
     *
     * <p>⚠️ <b>两条计费线共用一个累计器、各记各的账</b>：月度优惠用户算
     * 「本单省了多少」时要按原价把整单重算一遍，优惠价与原价各用一条线
     *（{@link HalfPeriodUsage} 内部按优惠状态分桶）。共用一条线的话，
     * 原价重算会读到优惠价用掉的额度，差额被记进
     * {@link BillingResult#getDiscountAmount()} —— 封顶减免被当成月度优惠，不报任何错。
     *
     * @param usage 半场额度累计器；跨多次调用同一组区间时必须传同一个对象
     */
    public BillingResult calculate(LocalDateTime startTime, LocalDateTime endTime,
                                   BigDecimal monthSpent, CardCoverage cardCoverage,
                                   List<FreeRange> freeRanges, HalfPeriodUsage usage) {
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("计费的开始时间与结束时间不能为空");
        }
        if (endTime.isBefore(startTime)) {
            throw new IllegalArgumentException(
                    "结束时间不能早于开始时间：start=" + startTime + ", end=" + endTime);
        }

        // 优惠在订单粒度判定：整单走优惠价，或整单走原价，不存在一单内两套单价
        boolean discounted = isDiscounted(monthSpent);

        // 无活动时归一成空列表：下面的判定与切段因此不必到处判 null
        List<FreeRange> frees = freeRanges == null ? List.of() : freeRanges;

        List<SegmentBill> segments = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal originalTotal = BigDecimal.ZERO;
        BigDecimal cardFreeAmount = BigDecimal.ZERO;
        BigDecimal activityFreeAmount = BigDecimal.ZERO;

        for (TimeSegment segment : splitByPeriod(startTime, endTime, cardCoverage, frees)) {
            SegmentBill bill = billSegment(segment, discounted, cardCoverage, frees, usage);
            segments.add(bill);
            totalAmount = totalAmount.add(bill.getAmount());
            cardFreeAmount = cardFreeAmount.add(bill.getCardFreeAmount());
            activityFreeAmount = activityFreeAmount.add(bill.getActivityFreeAmount());

            if (discounted) {
                // 优惠单额外按原价再算一遍，用于得出「本单省了多少」。
                // 相比在 billSegment 里同时维护两套金额，重算一遍的路径更短、
                // 更不容易算错；计费是纯计算，多跑一遍的代价可以忽略。
                //
                // ⚠️ cardCoverage 与 frees 必须一起传下去。漏传任何一个，
                // 被它覆盖的段在重算里都会算出一份并不存在的原价，
                // 差额全部落进 discountAmount —— 一笔「月卡免了 40 元」的单
                // 会被记成「月度优惠省了 40 元」，不报任何错，
                // 统计报表还会把它当成优惠活动的效果。
                // ⚠️ usage 要一并传下去：原价重算走的是累计器的【另一条线】
                //（discounted=false 那个桶），两条线互不污染。
                // 少了它，原价重算会把优惠价已经用掉的封顶额度再吃一遍，
                // 差额落进 discountAmount —— 与漏传 cardCoverage 是同一类错误
                originalTotal = originalTotal.add(
                        billSegment(segment, false, cardCoverage, frees, usage).getAmount());
            }
        }

        BillingResult result = new BillingResult();
        result.setStartTime(startTime);
        result.setEndTime(endTime);
        result.setTotalMinutes(Duration.between(startTime, endTime).toMinutes());
        result.setSegments(segments);
        result.setTotalAmount(totalAmount);
        result.setMonthSpentBefore(monthSpent == null ? BigDecimal.ZERO : monthSpent);
        result.setDiscounted(discounted);
        result.setDiscountAmount(discounted
                ? originalTotal.subtract(totalAmount)
                : BigDecimal.ZERO);
        result.setCardFreeAmount(cardFreeAmount);
        result.setActivityFreeAmount(activityFreeAmount);

        log.info("[计费] {} ~ {}，共 {} 分钟，合计 {} 元，分 {} 段{}{}{}",
                startTime, endTime, result.getTotalMinutes(), totalAmount, segments.size(),
                discounted ? "，已享月度优惠（结算前当月累计 " + result.getMonthSpentBefore()
                        + " 元，本单省 " + result.getDiscountAmount() + " 元）" : "",
                cardCoverage == null ? ""
                        : "，月卡覆盖 " + cardCoverage.describe() + "（免 " + cardFreeAmount + " 元）",
                activityFreeAmount.signum() == 0 ? ""
                        : "，免费活动（免 " + activityFreeAmount + " 元）");
        return result;
    }

    // ==================================================================
    // 私有实现
    // ==================================================================

    /**
     * 判断某个累计消费额是否已达到月度优惠门槛。
     *
     * <p>判定口径：当月<b>已支付</b>订单的实付额之和达到门槛。
     * 本单自身不计入（结算时尚未支付），因此不存在「本单算完把自己顶过门槛」
     * 的循环依赖；已经结算过的订单也不因为后来达标而追溯退款。
     *
     * <p><b>公开出来供模块 9 查询用户的优惠资格</b>（「还差多少才能享优惠价」），
     * 免得那个接口自己再写一遍门槛比较 —— 两处实现迟早会在某个边界上分岔，
     * 而分岔的表现是「页面说已达标、结算却没优惠」这种说不清的问题。
     *
     * @param monthSpent 结算前的当月累计实付额，可为 null（视同未达标）
     * @return true 表示按优惠价计费
     */
    public boolean isDiscounted(BigDecimal monthSpent) {
        BillingProperties.MonthlyDiscount discount = properties.getMonthlyDiscount();
        if (!discount.isEnabled() || monthSpent == null) {
            return false;
        }
        return monthSpent.compareTo(discount.getThreshold()) >= 0;
    }

    /**
     * 取月度优惠的门槛金额（元）。
     *
     * <p>供模块 9 在「本月累计消费」接口里展示「还差多少」，
     * 与 {@link #isDiscounted} 读的是同一个配置项。
     *
     * @return 门槛金额
     */
    public BigDecimal discountThreshold() {
        return properties.getMonthlyDiscount().getThreshold();
    }

    // ==================================================================
    // 跳档预告
    // ==================================================================

    /**
     * 预告「下一次账单变化」—— 什么时候变、会变成什么。
     *
     * <p>用户看着计时器时最想知道的就是这个：现在停，还是再玩一会儿。
     * 首页的进行中卡片与结账预览页共用本方法。
     *
     * <p><b>为什么必须后端算</b>：档位边界是 {@code units × 30 + 5 + 1} 分钟，
     * 其中的 30 与 5 都是配置项。前端算等于把计费规则复制一份 ——
     * 改配置时前端静默算错，且不报任何错。
     *
     * <p><b>四种结果</b>：
     * <ol>
     *   <li><b>段内跳档（TIER）</b> —— 下一个档位边界落在当前时段之内。
     *       文本「进入下一档 ¥20.00」</li>
     *   <li><b>跨时段（PERIOD）</b> —— 下一个档位边界落在当前时段之外，
     *       先发生的是时段边界。文本「跨入夜场，按夜场重新计价」。
     *       <b>这一档不能省</b>：只做 TIER 的话，21:50 的卡片会写
     *       「还有 40 分钟进入下一档」，而用户实际在 22:00 就跨进夜场重新起了一段 ——
     *       展示一个错误的时间预期比不展示更糟</li>
     *   <li><b>月卡到期（CARD）</b> —— 当前段被月卡覆盖，但卡在本段结束前失效。
     *       文本「月卡即将到期，之后按时长计费」。<b>与上一条同理不能省</b>：
     *       只报「月卡免费」的话，23:50 的用户会看到一句静态的「免费」，
     *       然后在零点金额毫无预兆地开始涨</li>
     *   <li><b>免费活动到期（FREE）</b> —— 当前段在活动区间内，但活动在本段结束前结束。
     *       与上一条完全对称：不给这一支的话，活动最后一小时的用户
     *       同样会看着一句静态的「免费」</li>
     *   <li><b>段内不再变化</b> —— 已达封顶、或当前段整段都被月卡 / 活动覆盖，
     *       返回说明文字、不给秒数</li>
     * </ol>
     *
     * <p><b>跨时段后不再涨价这件事不等于「不用提示」</b>：跨段那一刻旧段定格、
     * 新段从 0 分钟起算，新段自身还要再积累到达下一档才涨价。但<b>计价规则变了</b>
     * （夜场更便宜），这正是用户该知道的。
     *
     * @param startTime     计费起点，通常传当前计费段的起点（已剪掉包场）
     * @param now           计算截止时刻
     * @param monthSpent    当月累计实付额，用于取正确的单价（优惠价还是原价）
     * @param cardCoverage  月卡的时段覆盖范围，无卡传 null
     * @param freeRanges    免费活动的区间，无活动传 null
     * @param halfUsedBefore 本段之前、同一半场已收的金额（元），可为 null（视同 0）——
     *                       封顶按半场累计，预告的「下一档金额」必须扣掉它，
     *                       否则会报出一个到不了的数字。调用方用
     *                       {@link #halfPeriodUsedBefore} 从账单里算好传入
     * @return 预告；参数缺失或 {@code now} 早于 {@code startTime} 时返回 null
     */
    public NextChange nextChange(LocalDateTime startTime, LocalDateTime now,
                                 BigDecimal monthSpent, CardCoverage cardCoverage,
                                 List<FreeRange> freeRanges, BigDecimal halfUsedBefore) {
        return nextChangeInternal(startTime, now, monthSpent, cardCoverage, freeRanges,
                halfUsedBefore);
    }

    /**
     * 不带半场已收额度的预告 —— 等价于 {@code halfUsedBefore = 0}。
     *
     * <p>留给「确定没有历史收钱、也不在本单中途」的场景：
     * 纯计费的单元测试、以及任何只想知道「从这一刻起段内怎么变」的调用方。
     * 生产路径（订单模块的结账预览）走上面那个 6 参版本 ——
     * 它会先 {@link #halfPeriodUsedBefore 算出本段之前的同半场已收}，
     * 半场额度用掉一部分后，预告的「下一档金额」才不会报出一个到不了的数。
     */
    public NextChange nextChange(LocalDateTime startTime, LocalDateTime now,
                                 BigDecimal monthSpent, CardCoverage cardCoverage,
                                 List<FreeRange> freeRanges) {
        return nextChangeInternal(startTime, now, monthSpent, cardCoverage, freeRanges, null);
    }

    private NextChange nextChangeInternal(LocalDateTime startTime, LocalDateTime now,
                                          BigDecimal monthSpent, CardCoverage cardCoverage,
                                          List<FreeRange> freeRanges,
                                          BigDecimal halfUsedBefore) {
        if (startTime == null || now == null) {
            throw new IllegalArgumentException("跳档预告的计费起点与当前时刻不能为空");
        }
        // 时钟回拨、或调用方传错参数时不猜，直接说「没有可预告的」——
        // 预告是锦上添花的信息，为它抛异常会把结算预览整个拖下水
        if (now.isBefore(startTime)) {
            return null;
        }

        List<FreeRange> frees = freeRanges == null ? List.of() : freeRanges;
        TimeSegment current = currentSegment(startTime, now, cardCoverage, frees);
        boolean discounted = isDiscounted(monthSpent);
        BillingPeriod period = current.period();
        // 下一个时段边界。提前算出来 —— 月卡那一支也要靠它判断卡是不是在本段内到期
        LocalDateTime periodEnd = nextBoundary(now, period);

        // ① 当前段被月卡覆盖：实收恒为 0，段内不会再有金额变化。
        //    但若卡在本段结束前失效，那一刻起要开始计费 —— 跨零点的用户
        //    否则会看到「当前时段月卡免费」，然后金额毫无预兆地从 0 开始涨
        if (isFreeByCard(period, current.start(), cardCoverage)) {
            LocalDateTime cardEnd = cardCoverage.getValidUntil();
            // 进到这一支说明 now 仍在卡内（covers 为 true），所以 cardEnd 必然晚于 now，
            // 只需判它是否早于时段边界 —— 不早于就说明本段内卡不会失效
            if (cardEnd.isBefore(periodEnd)) {
                return NextChange.at(secondsBetween(now, cardEnd), "月卡即将到期，之后按时长计费");
            }
            return NextChange.none("当前时段月卡免费");
        }

        // ② 当前段在免费活动区间内：实收恒为 0，段内不会再有金额变化。
        //    ⚠️ 放在月卡那一支【之后】：与 billSegment 的互斥口径一致
        //   （被卡覆盖的段不记活动）。两处若反了，账单说「月卡免费」、
        //    预告说「活动免费」，同一件事两个说法。
        FreeRange covering = coveringFreeRange(current.start(), frees);
        if (covering != null) {
            // 活动在本段结束前结束的话，那一刻起要开始计费 ——
            // 与月卡到期同一条理由：否则用户看着一句静态的「免费」，
            // 然后金额毫无预兆地开始涨
            if (covering.to().isBefore(periodEnd)) {
                return NextChange.at(secondsBetween(now, covering.to()),
                        "免费活动即将结束，之后按时长计费");
            }
            return NextChange.none("当前时段免费活动");
        }

        int units = unitsOf(Duration.between(current.start(), now).toMinutes());
        BigDecimal unitPrice = unitPriceOf(period, discounted);
        BigDecimal cap = capOf(period, discounted);

        // ② 已达封顶：段内金额不再增长。
        //    ⚠️ 这里的「封顶」要看【半场剩余额度】而不是本段自己的封顶：
        //    封顶自 2026-10-10 起按半场累计，本段能收的上限是
        //    「封顶 − 本半场此前已收」。只看 cap 的话，同半场前面几段收掉的
        //    钱会被忽略 —— 预告会报出一个到不了的数字
        //    （而账单那边是对的，两边差距只有用户对照时才会被发现）。
        //    用「原始金额 ≥ 剩余额度」而不是段上的 capped 标志 —— 那个要到
        //    第 11 档（5 小时 6 分）才为 true，而金额不再增长从第 10 档
        //    （4 小时 36 分）就开始了，靠它会漏报。
        //
        //    此刻不给倒计时：跨段虽然终会到来（新段会重新计费），但它可能在
        //    十余小时之后，而「已到封顶价」才是用户现在真正需要知道的事。
        //    真到了跨段那一刻，预览接口会重新算出新段的预告。
        BigDecimal halfUsed = halfUsedBefore == null ? BigDecimal.ZERO : halfUsedBefore;
        BigDecimal remaining = cap.subtract(halfUsed).max(BigDecimal.ZERO);
        if (unitPrice.multiply(BigDecimal.valueOf(units)).compareTo(remaining) >= 0) {
            return NextChange.none("当前已到封顶价");
        }

        // ③ 段内的下一个档位边界。取时长是【向下取整】的（billSegment 同款口径），
        //    所以边界落在整分钟上，算出来的时刻是精确值而不是估算。
        LocalDateTime tierAt = current.start().plusMinutes(nextTierMinutes(units));

        if (tierAt.isBefore(periodEnd)) {
            // 「下一档时本段会收多少」同样受半场剩余额度约束 ——
            // 与账单里的 amount = min(原始金额, 封顶, 剩余额度) 是同一个公式
            BigDecimal nextAmount = unitPrice.multiply(BigDecimal.valueOf(units + 1))
                    .min(remaining);
            return NextChange.at(secondsBetween(now, tierAt), "进入下一档 " + money(nextAmount));
        }

        // ④ 先发生的是时段边界。新时段取 periodOf(边界时刻)，
        //    而不是「不是日场就是夜场」—— 那样写的话，将来配置里多一个时段就错了
        BillingPeriod next = periodOf(periodEnd);
        return NextChange.at(secondsBetween(now, periodEnd),
                "跨入" + next.getLabel() + "，按" + next.getLabel() + "重新计价");
    }

    /**
     * 找出某时刻所处的计费段。
     *
     * <p>复用 {@link #splitByPeriod} 而不是另写一套「往回找时段起点」的逻辑 ——
     * 段的划分只应有一处定义，两处一旦分岔，「下一档还有多久」会与账单上的档数对不上。
     *
     * <p><b>coverage 必须一起传下去</b>：卡的有效期边界也是一条切分线。
     * 漏传的话，卡失效之后的那一段会被当成「从订单起点一路算到现在」，
     * 于是预告说「已到 ¥17.50」而账单只收 ¥3.50 —— 两边各自看都合理，却对不上。
     *
     * @param startTime  计费起点
     * @param now        当前时刻
     * @param coverage   月卡的覆盖范围，无卡传 null
     * @param freeRanges 免费活动的区间，无活动传空列表
     * @return 当前所处的段（end 即 {@code now}）
     */
    private TimeSegment currentSegment(LocalDateTime startTime, LocalDateTime now,
                                       CardCoverage coverage, List<FreeRange> freeRanges) {
        List<TimeSegment> done = splitByPeriod(startTime, now, coverage, freeRanges);
        BillingPeriod period = periodOf(now);

        if (done.isEmpty()) {
            // now 恰好等于计费起点：还没有任何一段走完，当前段就是刚起头的这一段
            return new TimeSegment(period, startTime, now);
        }

        TimeSegment last = done.get(done.size() - 1);
        if (last.period() != period) {
            // now 恰好落在时段边界上（如 22:00:00 整）：上一段在边界处结束，
            // 属于新时段的只有这一刻。按「0 分钟的新段」算，用户看到的是
            // 「还有 6 分钟进入下一档」，而不是一段已经结束的旧账
            return new TimeSegment(period, now, now);
        }
        return last;
    }

    /**
     * 算某个时长落在第几档。
     *
     * <p>档数用整数向上取整除法 {@code (可计费分钟 + 单位 − 1) / 单位}，避免浮点运算。
     * 可计费分钟为「时长 − 宽限」，非正数时档数直接为 0。
     *
     * <p><b>账单与本方法必须调同一个它</b>：两处各写一份的话，卡片上「还有 12 分钟
     * 进入下一档」会与结账时的档数对不上，而两边看起来都合理。
     *
     * @param minutes 时长（分钟）
     * @return 档数
     */
    private int unitsOf(long minutes) {
        long billableMinutes = minutes - properties.getGraceMinutes();
        if (billableMinutes <= 0) {
            return 0;
        }
        return (int) ((billableMinutes + properties.getUnitMinutes() - 1)
                / properties.getUnitMinutes());
    }

    /**
     * 推断下一个档位起点距当前段开始有多少分钟。
     *
     * <p>推导：{@code units = ceil((minutes − grace) ÷ unit)} 时，
     * 下一档出现在 {@code minutes = units × unit + grace + 1}。
     * 例如宽限 5 分钟、每档 30 分钟时，档位边界依次落在 6 / 36 / 66 … 分钟。
     *
     * <p><b>宽限期内（units = 0）下一次是第 6 分钟而不是第 36 分钟</b> ——
     * 加 1 而不是加整个单位时长，是因为档位边界是「刚好越过宽限」的那一刻。
     *
     * @param units 当前档数
     * @return 下一个档位边界的相对分钟数
     */
    private int nextTierMinutes(int units) {
        return units * properties.getUnitMinutes() + properties.getGraceMinutes() + 1;
    }

    /**
     * 判断某个计费段是否被月卡覆盖。
     *
     * <p>账单与跳档预告共用本方法：两处若各判一次，「月卡段还会不会涨价」
     * 就会在某个卡种上给出相反的答案。
     *
     * <p><b>判定完全交给 {@link CardCoverage#covers}</b>：时段与日期两个条件
     * 都由它一处表达。本方法只负责 null 判断 —— 无卡时没有任何段被覆盖。
     *
     * @param period   该段所属的时段
     * @param at       该段的起点时刻
     * @param coverage 覆盖范围，可为 null（无卡）
     * @return 该段免费返回 true
     */
    private static boolean isFreeByCard(BillingPeriod period, LocalDateTime at,
                                        CardCoverage coverage) {
        return coverage != null && coverage.covers(period, at);
    }

    /**
     * 取两个时刻之间的整秒数。
     *
     * @param from 起点
     * @param to   终点
     * @return 秒数
     */
    private static long secondsBetween(LocalDateTime from, LocalDateTime to) {
        return Duration.between(from, to).getSeconds();
    }

    /**
     * 把金额格式化成账单上显示的样子（两位小数带符号）。
     *
     * <p>用 {@code setScale} 而不是 {@code stripTrailingZeros}：后者对 40 元会给出
     * {@code 4E+1} 这种科学计数法的字符串，金额栏上显示成「¥4E+1」。
     *
     * @param amount 金额
     * @return 如「¥20.00」
     */
    private static String money(BigDecimal amount) {
        return "¥" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 把一次消费切成若干段。
     *
     * <p><b>切分线有三条</b>：
     * <ol>
     *   <li>{@link #nextBoundary 时段边界} —— 配置的日场起止时刻。
     *       例如 21:30 – 23:30 会被切成日场 21:30–22:00 与夜场 22:00–23:30 两段</li>
     *   <li><b>月卡的有效期边界</b>（仅当有卡）—— 卡的生效时刻与失效时刻。
     *       段不能被它劈成两半：「整段免费」的判定要求段完整落在卡的区间内，
     *       所以跨过零点的订单必须在这里切开，卡内那段免、卡外那段照常计价</li>
     *   <li><b>免费活动的区间边界</b>（仅当有活动）—— 与月卡同理：
     *       活动区间内那段免、区间外那段照常计价</li>
     * </ol>
     *
     * <p><b>多切一段就多享一次宽限</b>，这是分段计算的固有代价（与跨时段切段
     * 是同一类副作用，CLAUDE.md 记着它的几个例子）。所以卡那条切分线
     * <b>只在边界真的落在订单区间内时才切</b> —— 无卡时一段也不多切，
     * 卡覆盖整单时也一段都不多切。
     *
     * <p>时长为 0 的段不会产生（恰好从边界开始时不会多出一个空段）。
     *
     * @param startTime  起始时间
     * @param endTime    结束时间
     * @param coverage   月卡的覆盖范围，无卡传 null
     * @param freeRanges 免费活动的区间，无活动传空列表
     * @return 按时间先后排列的时段片段
     */
    private List<TimeSegment> splitByPeriod(LocalDateTime startTime, LocalDateTime endTime,
                                            CardCoverage coverage, List<FreeRange> freeRanges) {
        List<TimeSegment> segments = new ArrayList<>();
        LocalDateTime cursor = startTime;

        while (cursor.isBefore(endTime)) {
            BillingPeriod period = periodOf(cursor);
            LocalDateTime boundary = nextBoundary(cursor, period);
            // 段末取「下一个时段边界」与「订单结束时间」中较早的那个
            LocalDateTime segmentEnd = boundary.isBefore(endTime) ? boundary : endTime;

            // 卡的有效期边界还要更早的话，就以它收段
            LocalDateTime cardBoundary = nextCardBoundary(cursor, endTime, coverage);
            if (cardBoundary != null && cardBoundary.isBefore(segmentEnd)) {
                segmentEnd = cardBoundary;
            }

            // 免费活动的边界同理：段必须完整落在区间内或区间外，
            // 否则「整段免费」的判定无从下手（片段跨界时按起点判会整段免、按终点判会整段收）
            LocalDateTime freeBoundary = nextFreeBoundary(cursor, endTime, freeRanges);
            if (freeBoundary != null && freeBoundary.isBefore(segmentEnd)) {
                segmentEnd = freeBoundary;
            }

            segments.add(new TimeSegment(period, cursor, segmentEnd));
            cursor = segmentEnd;
        }
        return segments;
    }

    /**
     * 求从某个时刻起的下一个「月卡有效期边界」。
     *
     * <p><b>两条边界都要看</b>：生效时刻（它之前的那段要收费）与失效时刻
     * （它之后的那段要收费）。两头都处理，规则才闭合 —— 只做失效那一头的话，
     * 卡生效当天凌晨进店的订单会在生效前那段白玩。
     *
     * <p>返回的时刻<b>严格晚于</b> {@code cursor}：相等时说明游标已经站在这条
     * 边界上、这一段早已切开，再返回它就会切出一个时长为 0 的段、游标原地不动。
     *
     * @param cursor   当前游标
     * @param endTime  订单结束时刻
     * @param coverage 覆盖范围，无卡传 null
     * @return 边界时刻；无卡、或区间内没有边界时返回 null
     */
    private static LocalDateTime nextCardBoundary(LocalDateTime cursor, LocalDateTime endTime,
                                                  CardCoverage coverage) {
        if (coverage == null) {
            return null;
        }
        LocalDateTime from = coverage.getValidFrom();
        if (from.isAfter(cursor) && from.isBefore(endTime)) {
            return from;
        }
        LocalDateTime until = coverage.getValidUntil();
        if (until.isAfter(cursor) && until.isBefore(endTime)) {
            return until;
        }
        return null;
    }

    /**
     * 求从某个时刻起的下一个「免费活动边界」。
     *
     * <p>与月卡那条线同理，<b>每段活动的两头都要看</b>：活动开始时（之前那段要收费）
     * 与活动结束时（之后那段要收费）。只做一个方向的话规则就不闭合 ——
     * 只砍「结束」那一头的话，活动开始前那段会被误判成免费。
     *
     * <p>返回的时刻<b>严格晚于</b> {@code cursor}：相等时说明游标已经站在这条边界上
     * （这一段早切开了），再返回它就会切出一个时长为 0 的段、游标原地不动 ——
     * 那是个死循环，而不是一次算错。
     *
     * <p>多场活动时取<b>最近</b>的那条边界。
     *
     * @param cursor     当前游标
     * @param endTime    订单结束时刻
     * @param freeRanges 免费活动的区间，可为空列表
     * @return 最近的边界时刻；所有活动都不沾订单区间时返回 null
     */
    private static LocalDateTime nextFreeBoundary(LocalDateTime cursor, LocalDateTime endTime,
                                                  List<FreeRange> freeRanges) {
        LocalDateTime nearest = null;
        for (FreeRange range : freeRanges) {
            nearest = earlier(nearest, inWindow(range.from(), cursor, endTime));
            nearest = earlier(nearest, inWindow(range.to(), cursor, endTime));
        }
        return nearest;
    }

    /** 边界落在「严格晚于 cursor 且严格早于 endTime」的窗口内就返回它，否则返回 null。 */
    private static LocalDateTime inWindow(LocalDateTime edge, LocalDateTime cursor,
                                          LocalDateTime endTime) {
        return edge.isAfter(cursor) && edge.isBefore(endTime) ? edge : null;
    }

    /** 取两个候选里较早的那个（null 表示这一侧没有候选）。 */
    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    /**
     * 判断某个时刻属于哪个时段。
     *
     * @param time 待判断的时刻
     * @return 日场或夜场
     */
    private BillingPeriod periodOf(LocalDateTime time) {
        LocalTime localTime = time.toLocalTime();
        boolean inDay = !localTime.isBefore(properties.getDayStart())
                && localTime.isBefore(properties.getDayEnd());
        return inDay ? BillingPeriod.DAY : BillingPeriod.NIGHT;
    }

    /**
     * 求从当前时刻起的下一个时段边界。
     *
     * @param cursor 当前时刻
     * @param period 当前时刻所属时段
     * @return 下一个时段开始的时刻
     */
    private LocalDateTime nextBoundary(LocalDateTime cursor, BillingPeriod period) {
        LocalDate date = cursor.toLocalDate();

        if (period == BillingPeriod.DAY) {
            // 日场中 → 边界是当天日场结束时刻
            return LocalDateTime.of(date, properties.getDayEnd());
        }

        // 夜场中：22:00 之后属于当日深夜，边界是次日日场开始时刻；
        // 凌晨（早于日场开始）则边界是当天日场开始时刻
        if (!cursor.toLocalTime().isBefore(properties.getDayEnd())) {
            return LocalDateTime.of(date.plusDays(1), properties.getDayStart());
        }
        return LocalDateTime.of(date, properties.getDayStart());
    }

    /**
     * 计算单个时段的费用。
     *
     * <p>档数由 {@link #unitsOf} 算出（整数向上取整除法，避免浮点运算）。
     * 可计费分钟为「段时长 − 宽限」，非正数时档数为 0 ——
     * 「首 N 分钟内免费出场」正是由此自然得出，无需单独判断。
     *
     * <p><b>被月卡或免费活动覆盖的段照常算档数、单价、封顶与封顶前金额，
     * 只把实收置 0。</b>这几个中间结果正是账单页解释「这段为什么免费」的依据 ——
     * 用户看到的是「夜场 1.5 小时 · 原价 10.5 元 · 月卡免费」，
     * 而不是一个没有来由的 0 元。
     *
     * @param segment      时段片段
     * @param discounted   本单是否按月度优惠价计费（影响单价与封顶）
     * @param cardCoverage 月卡的覆盖范围（时段 + 有效日期），null 表示无卡
     * @param freeRanges   免费活动的区间，无活动传空列表
     * @return 该段的计费明细
     */
    private SegmentBill billSegment(TimeSegment segment, boolean discounted,
                                    CardCoverage cardCoverage, List<FreeRange> freeRanges,
                                    HalfPeriodUsage usage) {
        long minutes = Duration.between(segment.start(), segment.end()).toMinutes();
        // 档数与跳档预告共用 unitsOf —— 两处各写一份的话，「还有多久到下一档」
        // 会与账单上的档数对不上，而两边看起来都合理
        int units = unitsOf(minutes);

        BigDecimal unitPrice = unitPriceOf(segment.period(), discounted);
        BigDecimal rawAmount = unitPrice.multiply(BigDecimal.valueOf(units));
        BigDecimal cap = capOf(segment.period(), discounted);

        // 半场累计额度：本段能收的上限是「封顶 − 本半场此前已收」。
        // usedBefore 为 0 时退化成段级封顶 min(原始金额, 封顶) —— 那是
        // 半场第一段的常态，也是 2026-10-10 规则变化之前的唯一形态
        LocalDateTime halfStart = halfPeriodStart(segment.period(), segment.start());
        BigDecimal usedBefore = usage.usedIn(halfStart, discounted);
        BigDecimal remaining = cap.subtract(usedBefore).max(BigDecimal.ZERO);
        BigDecimal amount = rawAmount.min(cap).min(remaining);
        // 本段因半场累计少收的部分 = 段级封顶后的金额 − 实收。
        // 与「段级封顶减免」分开记：capped 说「本段原始金额超顶了」，
        // 本字段说「其中多少是因为半场前面已经收过钱」
        BigDecimal cutAmount = rawAmount.min(cap).subtract(amount);

        // 免费判定：时段在卡种范围内、且起点落在卡的有效区间内。
        // 传起点而不是终点 —— 段已按卡的有效期边界切过，不会跨越它，
        // 所以起点在此区间内即整段都在（见 splitByPeriod）。
        // 与跳档预告共用 isFreeByCard —— 否则「月卡段还会不会涨价」在
        // 某个卡种上会给出相反的答案
        boolean freeByCard = isFreeByCard(segment.period(), segment.start(), cardCoverage);

        // ⚠️ 两者【互斥】：被月卡覆盖的段只记月卡。月卡用户本来就免费，
        // 活动并没有为他省下什么 —— 两个都记会把同一笔钱算两遍，
        // 复盘一场活动时「送出去多少」虚高，且不报任何错。
        boolean freeByActivity = !freeByCard && isInFreeRange(segment.start(), freeRanges);
        boolean free = freeByCard || freeByActivity;

        // 额度记的是【实收】：免掉的段记 0（等于不消耗额度）——
        // 月卡用户本来就免费，让他「用掉」额度等于替他少免了后面的钱
        BigDecimal actualAmount = free ? BigDecimal.ZERO : amount;
        usage.add(halfStart, discounted, actualAmount);

        SegmentBill bill = new SegmentBill();
        bill.setPeriod(segment.period());
        bill.setStartTime(segment.start());
        bill.setEndTime(segment.end());
        bill.setMinutes(minutes);
        bill.setUnits(units);
        bill.setUnitPrice(unitPrice);
        bill.setCapAmount(cap);
        bill.setRawAmount(rawAmount);
        bill.setAmount(actualAmount);
        bill.setCapped(rawAmount.compareTo(cap) > 0);
        bill.setHalfPeriodUsedBefore(usedBefore);
        bill.setHalfPeriodCutAmount(free ? BigDecimal.ZERO : cutAmount);
        bill.setFreeByCard(freeByCard);
        bill.setCardFreeAmount(freeByCard ? amount : BigDecimal.ZERO);
        bill.setFreeByActivity(freeByActivity);
        bill.setActivityFreeAmount(freeByActivity ? amount : BigDecimal.ZERO);
        return bill;
    }

    /**
     * 求某个段所属半场的起点时刻。
     *
     * <p><b>半场的定义</b>：日场半场 = 当天 {@code dayStart} 起至 {@code dayEnd}；
     * 夜场半场 = 当天 {@code dayEnd} 起至次日 {@code dayStart}。
     * 夜场跨零点，所以凌晨时刻要归到<b>前一天</b>的夜场 ——
     * 用日期做键会把 10/8 深夜与 10/9 凌晨拆成两个半场，
     * 而它们本是同一份封顶额度。
     *
     * <p>⚠️ 它与 {@link #nextBoundary} 是同一个时段划分的两种表达
     *（一个求「还有多久结束」、一个求「从哪儿开始」），
     * 改动时段划分时两处一起看。
     *
     * @param period 段所属时段
     * @param at     段内任意时刻（调用方传段的起点）
     * @return 半场起点
     */
    private LocalDateTime halfPeriodStart(BillingPeriod period, LocalDateTime at) {
        LocalDate date = at.toLocalDate();
        if (period == BillingPeriod.DAY) {
            return LocalDateTime.of(date, properties.getDayStart());
        }
        // 夜场：日场结束时刻之后属于当晚；凌晨（早于日场开始）属于前一晚
        return at.toLocalTime().isBefore(properties.getDayStart())
                ? LocalDateTime.of(date.minusDays(1), properties.getDayEnd())
                : LocalDateTime.of(date, properties.getDayEnd());
    }

    /**
     * 求某段之前、同一半场已收的金额（<b>本单内 + 历史订单</b>）——
     * 供跳档预告判断「窗口剩余额度」。
     *
     * <p>与 {@code billSegment} 内部读的是同一套半场划分
     *（{@link #halfPeriodStart}）：调用方（订单模块）拿到账单后把它传给
     * {@link #nextChange}，预告与账单因此不会各算各的。
     *
     * @param segments   一份已算好的账单的分段（按时间先后）
     * @param current    当前正在进行的段（通常是最后一个）
     * @param discounted 本单是否走优惠价（决定从哪条线取历史预置）
     * @param seeded     {@link #seedUsage} 预置好的历史额度；没有历史时传 null
     * @return 该段起点之前、同一半场的已收之和；没有则为 0
     */
    public BigDecimal halfPeriodUsedBefore(List<SegmentBill> segments, SegmentBill current,
                                           boolean discounted, HalfPeriodUsage seeded) {
        LocalDateTime halfStart = halfPeriodStart(current.getPeriod(), current.getStartTime());
        BigDecimal fromBill = segments.stream()
                .filter(s -> s.getStartTime().isBefore(current.getStartTime()))
                .filter(s -> halfPeriodStart(s.getPeriod(), s.getStartTime()).equals(halfStart))
                .map(SegmentBill::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fromHistory = seeded == null
                ? BigDecimal.ZERO : seeded.usedIn(halfStart, discounted);
        return fromBill.add(fromHistory);
    }

    /**
     * 求某个时刻所在半场的起点 —— 供订单模块算「历史订单查询范围」。
     *
     * <p>它公开出来只有一个理由：{@code OrderService} 要拿它查「同一半场内
     * 还有哪些已支付订单」（封顶<b>跨订单</b>累计，见 {@link #seedUsage}），
     * 而半场的划分只应有这一处定义。
     *
     * @param at 任意时刻
     * @return 该时刻所属半场的起点
     */
    public LocalDateTime halfPeriodStartAt(LocalDateTime at) {
        return halfPeriodStart(periodOf(at), at);
    }

    /**
     * 求某个时刻所在半场的终点（半开区间 [起点, 终点)）。
     *
     * <p>复用 {@link #nextBoundary}：它就是「当前时段的下一个边界」，
     * 与账单切段用的是同一处定义。
     *
     * @param at 任意时刻
     * @return 该时刻所属半场的终点
     */
    public LocalDateTime halfPeriodEndAt(LocalDateTime at) {
        return nextBoundary(halfPeriodStartAt(at), periodOf(at));
    }

    /**
     * 把历史订单的实收（别的订单的段）预置进累计器。
     *
     * <p><b>这是「封顶跨订单累计」的入口</b>：用户玩一段、结算、再开新单
     * （随时结算再重开是正常操作），拆单不能绕过半场封顶 ——
     * 本次计费要先把同一半场里<b>已经付过的钱</b>记上，额度只剩余数。
     *
     * <p>⚠️ <b>两条线都预置同一个数</b>（优惠价线与原价线都记实收额）：
     * 现金额度只有一份，与优惠状态无关。原价线理论上应记「历史如果按原价收
     * 会是多少」，但那要求把历史订单集体重算一遍 —— 本机制不付这个代价，
     * 偏差只影响「本单优惠金额」的展示（有界、不报错）。
     *
     * <p>段按各自的半场归位（{@link #halfPeriodStart}），落在别的半场的段
     * 只进别的桶、不影响本次 —— 调用方因此可以把查询范围放宽一点，
     * 多捞无害，<b>漏掉才会少收钱</b>。金额为 0 的段（被月卡 / 活动免掉的）
     * 不占额度。
     *
     * @param usage    待预置的累计器
     * @param segments 历史订单快照里的分段
     */
    public void seedUsage(HalfPeriodUsage usage, List<SegmentBill> segments) {
        for (SegmentBill segment : segments) {
            BigDecimal amount = segment.getAmount();
            if (amount == null || amount.signum() == 0) {
                continue;
            }
            LocalDateTime halfStart = halfPeriodStart(segment.getPeriod(), segment.getStartTime());
            usage.add(halfStart, true, amount);
            usage.add(halfStart, false, amount);
        }
    }

    /**
     * 判断账单里每个半场是否都已收满封顶价 —— 预览页「当前已到封顶价」的依据。
     *
     * <p>口径是<b>按半场分组求和</b>，而不是「逐段比对各自的封顶」：
     * 同一半场可能被包场剪成多段、被活动切成多段，单看某一段
     *（如第一段只收了 28 元）会漏报「这个半场其实已经收满」。
     * 同半场各段的封顶值必然相同（单价与优惠状态整单一致），取哪个都一样。
     *
     * <p>没有计费段时返回 false：整段被包场覆盖的账单是 0 元，
     * 但「封顶」在这里不适用（包场结束后照样会重新计费）。
     *
     * @param segments 账单分段
     * @return 每个半场的实收合计都达到该半场封顶返回 true
     */
    public boolean allHalfPeriodsCapped(List<SegmentBill> segments) {
        if (segments.isEmpty()) {
            return false;
        }
        Map<LocalDateTime, BigDecimal> cumulative = new HashMap<>();
        Map<LocalDateTime, BigDecimal> caps = new HashMap<>();
        for (SegmentBill segment : segments) {
            LocalDateTime halfStart = halfPeriodStart(segment.getPeriod(), segment.getStartTime());
            cumulative.merge(halfStart, segment.getAmount(), BigDecimal::add);
            caps.put(halfStart, segment.getCapAmount());
        }
        return cumulative.entrySet().stream()
                .allMatch(e -> e.getValue().compareTo(caps.get(e.getKey())) >= 0);
    }

    /**
     * 某个时刻是否落在任意一段免费活动区间内。
     *
     * <p>传的是<b>段的起点</b>：段已按活动的边界切过（见 {@link #splitByPeriod}），
     * 不会跨越它，所以起点在内即整段都在 —— 与 {@link #isFreeByCard} 同一个道理。
     *
     * @param at         段的起点时刻
     * @param freeRanges 免费活动的区间
     * @return 落在某段区间内返回 true
     */
    private static boolean isInFreeRange(LocalDateTime at, List<FreeRange> freeRanges) {
        for (FreeRange range : freeRanges) {
            if (range.covers(at)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取覆盖某个时刻的那段免费活动区间（都不命中返回 null）。
     *
     * <p>与 {@link #isInFreeRange} 的区别只在返回值：那个回答「免不免」，
     * 这个要拿到区间<b>本身</b> —— 跳档预告得知道活动什么时候结束，
     * 才能说「之后按时长计费」。两处若各写一份遍历，
     * 「此刻免费」与「什么时候不免费」会各自漂移。
     *
     * @param at         待判断的时刻
     * @param freeRanges 免费活动的区间
     * @return 命中的区间；都不命中返回 null
     */
    private static FreeRange coveringFreeRange(LocalDateTime at, List<FreeRange> freeRanges) {
        for (FreeRange range : freeRanges) {
            if (range.covers(at)) {
                return range;
            }
        }
        return null;
    }

    /**
     * 取某个时段在当前优惠状态下的单价。
     *
     * @param period     时段
     * @param discounted 是否走月度优惠价
     * @return 对应单价（元 / 计费单位）
     */
    private BigDecimal unitPriceOf(BillingPeriod period, boolean discounted) {
        if (discounted) {
            BillingProperties.MonthlyDiscount discount = properties.getMonthlyDiscount();
            return period == BillingPeriod.DAY
                    ? discount.getDayUnitPrice()
                    : discount.getNightUnitPrice();
        }
        return period == BillingPeriod.DAY
                ? properties.getDayUnitPrice()
                : properties.getNightUnitPrice();
    }

    /**
     * 取某个时段在当前优惠状态下的封顶金额。
     *
     * <p>优惠是整列降价：封顶同样降到「10 档 × 优惠单价」，
     * 而不是只降单价、封顶照旧。
     *
     * @param period     时段
     * @param discounted 是否走月度优惠价
     * @return 该时段该优惠状态下的封顶金额
     */
    private BigDecimal capOf(BillingPeriod period, boolean discounted) {
        if (discounted) {
            BillingProperties.MonthlyDiscount discount = properties.getMonthlyDiscount();
            return period == BillingPeriod.DAY ? discount.getDayCap() : discount.getNightCap();
        }
        return period == BillingPeriod.DAY ? properties.getDayCap() : properties.getNightCap();
    }

    /**
     * 一个时段片段。
     *
     * @param period 所属时段
     * @param start  片段起始时间
     * @param end    片段结束时间
     */
    private record TimeSegment(BillingPeriod period, LocalDateTime start, LocalDateTime end) {
    }
}
