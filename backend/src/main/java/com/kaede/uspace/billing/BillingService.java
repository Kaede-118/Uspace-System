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
 *   段费用 = ceil( (段时长分钟 − 宽限) ÷ 单位时长 ) × 单价     再与时段封顶取小
 *   总费用 = 各段费用之和
 * </pre>
 *
 * <p>参数（单价、单位时长、宽限、封顶、时段划分）全部来自 {@link BillingProperties}，
 * 不在代码中写死。
 *
 * <p><b>为什么按段分别套公式而不是整单算一次</b>：跨时段订单要求分段展示与分段封顶，
 * 各段独立计算才能让账单逐段可核对。副作用是每段各自享有一次宽限，
 * 使得同长的跨时段订单与不跨时段订单价格可能不同 —— 这是已确认接受的规则形态，
 * 详见 CLAUDE.md。
 *
 * <p>本类为纯计算，不访问数据库、不依赖其他模块，因此可直接单元测试。
 */
@Slf4j
@Service
public class BillingService {

    /** 计费规则参数 */
    private final BillingProperties properties;

    /**
     * 构造方法。
     *
     * @param properties 计费规则配置
     */
    public BillingService(BillingProperties properties) {
        this.properties = properties;
    }

    /**
     * 计算一次消费的费用。
     *
     * @param startTime 开始使用时间（用户开门进场时刻）
     * @param endTime   结束使用时间（用户离场或订单结算时刻）
     * @return 含分段明细与总金额的计费结果
     * @throws IllegalArgumentException 当时间为空、或结束时间早于开始时间时抛出
     */
    public BillingResult calculate(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("计费的开始时间与结束时间不能为空");
        }
        if (endTime.isBefore(startTime)) {
            throw new IllegalArgumentException(
                    "结束时间不能早于开始时间：start=" + startTime + ", end=" + endTime);
        }

        List<SegmentBill> segments = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (TimeSegment segment : splitByPeriod(startTime, endTime)) {
            SegmentBill bill = billSegment(segment);
            segments.add(bill);
            totalAmount = totalAmount.add(bill.getAmount());
        }

        BillingResult result = new BillingResult();
        result.setStartTime(startTime);
        result.setEndTime(endTime);
        result.setTotalMinutes(Duration.between(startTime, endTime).toMinutes());
        result.setSegments(segments);
        result.setTotalAmount(totalAmount);

        log.info("[计费] {} ~ {}，共 {} 分钟，合计 {} 元，分 {} 段",
                startTime, endTime, result.getTotalMinutes(), totalAmount, segments.size());
        return result;
    }

    // ==================================================================
    // 私有实现
    // ==================================================================

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
     * @param segment 时段片段
     * @return 该段的计费明细
     */
    private SegmentBill billSegment(TimeSegment segment) {
        long minutes = Duration.between(segment.start(), segment.end()).toMinutes();

        long billableMinutes = minutes - properties.getGraceMinutes();
        int units = 0;
        if (billableMinutes > 0) {
            units = (int) ((billableMinutes + properties.getUnitMinutes() - 1)
                    / properties.getUnitMinutes());
        }

        BigDecimal rawAmount = properties.getUnitPrice().multiply(BigDecimal.valueOf(units));
        BigDecimal cap = capOf(segment.period());
        BigDecimal amount = rawAmount.min(cap);

        SegmentBill bill = new SegmentBill();
        bill.setPeriod(segment.period());
        bill.setStartTime(segment.start());
        bill.setEndTime(segment.end());
        bill.setMinutes(minutes);
        bill.setUnits(units);
        bill.setRawAmount(rawAmount);
        bill.setAmount(amount);
        bill.setCapped(rawAmount.compareTo(cap) > 0);
        return bill;
    }

    /**
     * 取某个时段的封顶金额。
     *
     * @param period 时段
     * @return 该时段的封顶金额
     */
    private BigDecimal capOf(BillingPeriod period) {
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
