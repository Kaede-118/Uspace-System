package com.kaede.uspace.billing;

import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.billing.dto.SegmentBill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

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
     * 计算一次消费的费用，并判定是否适用月度累计优惠。
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
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("计费的开始时间与结束时间不能为空");
        }
        if (endTime.isBefore(startTime)) {
            throw new IllegalArgumentException(
                    "结束时间不能早于开始时间：start=" + startTime + ", end=" + endTime);
        }

        // 优惠在订单粒度判定：整单走优惠价，或整单走原价，不存在一单内两套单价
        boolean discounted = isDiscounted(monthSpent);

        List<SegmentBill> segments = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal originalTotal = BigDecimal.ZERO;

        for (TimeSegment segment : splitByPeriod(startTime, endTime)) {
            SegmentBill bill = billSegment(segment, discounted);
            segments.add(bill);
            totalAmount = totalAmount.add(bill.getAmount());

            if (discounted) {
                // 优惠单额外按原价再算一遍，用于得出「本单省了多少」。
                // 相比在 billSegment 里同时维护两套金额，重算一遍的路径更短、
                // 更不容易算错；计费是纯计算，多跑一遍的代价可以忽略。
                originalTotal = originalTotal.add(billSegment(segment, false).getAmount());
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

        log.info("[计费] {} ~ {}，共 {} 分钟，合计 {} 元，分 {} 段{}",
                startTime, endTime, result.getTotalMinutes(), totalAmount, segments.size(),
                discounted ? "，已享月度优惠（结算前当月累计 " + result.getMonthSpentBefore()
                        + " 元，本单省 " + result.getDiscountAmount() + " 元）" : "");
        return result;
    }

    // ==================================================================
    // 私有实现
    // ==================================================================

    /**
     * 判断本单是否适用月度累计优惠。
     *
     * <p>判定口径：当月<b>已支付</b>订单的实付额之和达到门槛。
     * 本单自身不计入（结算时尚未支付），因此不存在「本单算完把自己顶过门槛」
     * 的循环依赖；已经结算过的订单也不因为后来达标而追溯退款。
     *
     * @param monthSpent 结算前的当月累计实付额，可为 null
     * @return true 表示本单整单按优惠价计费
     */
    private boolean isDiscounted(BigDecimal monthSpent) {
        BillingProperties.MonthlyDiscount discount = properties.getMonthlyDiscount();
        if (!discount.isEnabled() || monthSpent == null) {
            return false;
        }
        return monthSpent.compareTo(discount.getThreshold()) >= 0;
    }

    /**
     * 把一次消费按时段边界切分成若干段。
     *
     * <p>切分点是配置的日场起止时刻。例如 21:30 – 23:30 会被切成
     * 日场 21:30–22:00 与夜场 22:00–23:30 两段。
     *
     * <p>时长为 0 的段不会产生（恰好从边界开始时不会多出一个空段）。
     *
     * @param startTime 起始时间
     * @param endTime   结束时间
     * @return 按时间先后排列的时段片段
     */
    private List<TimeSegment> splitByPeriod(LocalDateTime startTime, LocalDateTime endTime) {
        List<TimeSegment> segments = new ArrayList<>();
        LocalDateTime cursor = startTime;

        while (cursor.isBefore(endTime)) {
            BillingPeriod period = periodOf(cursor);
            LocalDateTime boundary = nextBoundary(cursor, period);
            // 段末取「下一个时段边界」与「订单结束时间」中较早的那个
            LocalDateTime segmentEnd = boundary.isBefore(endTime) ? boundary : endTime;

            segments.add(new TimeSegment(period, cursor, segmentEnd));
            cursor = segmentEnd;
        }
        return segments;
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
     * <p>档数采用整数向上取整除法 {@code (可计费分钟 + 单位 − 1) / 单位}，
     * 避免浮点运算。可计费分钟为「段时长 − 宽限」，非正数时档数直接为 0 ——
     * 「首 N 分钟内免费出场」正是由此自然得出，无需单独判断。
     *
     * @param segment    时段片段
     * @param discounted 本单是否按月度优惠价计费（影响单价与封顶）
     * @return 该段的计费明细
     */
    private SegmentBill billSegment(TimeSegment segment, boolean discounted) {
        long minutes = Duration.between(segment.start(), segment.end()).toMinutes();

        long billableMinutes = minutes - properties.getGraceMinutes();
        int units = 0;
        if (billableMinutes > 0) {
            units = (int) ((billableMinutes + properties.getUnitMinutes() - 1)
                    / properties.getUnitMinutes());
        }

        BigDecimal unitPrice = unitPriceOf(segment.period(), discounted);
        BigDecimal rawAmount = unitPrice.multiply(BigDecimal.valueOf(units));
        BigDecimal cap = capOf(segment.period(), discounted);
        BigDecimal amount = rawAmount.min(cap);

        SegmentBill bill = new SegmentBill();
        bill.setPeriod(segment.period());
        bill.setStartTime(segment.start());
        bill.setEndTime(segment.end());
        bill.setMinutes(minutes);
        bill.setUnits(units);
        bill.setUnitPrice(unitPrice);
        bill.setCapAmount(cap);
        bill.setRawAmount(rawAmount);
        bill.setAmount(amount);
        bill.setCapped(rawAmount.compareTo(cap) > 0);
        return bill;
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
