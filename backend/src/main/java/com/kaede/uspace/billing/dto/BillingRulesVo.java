package com.kaede.uspace.billing.dto;

import com.kaede.uspace.billing.BillingProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

/**
 * 计费规则（面向顾客的价目表）。
 *
 * <p>给「计费规则」页面与「我的」页面的优惠提示用。全部值取自
 * {@link BillingProperties} —— <b>前端一个数字都不写死</b>：调价之后
 * 页面重新拉一次就跟着变，不必改前端。
 *
 * <p>⚠️ <b>为什么还要给「元/小时」的派生值</b>：页面与文案展示的都是
 * 「8 元/小时」这种口径，而配置里存的是「4 元 / 30 分钟」。
 * 让前端去做 {@code 4 × 60 ÷ 30} 这个除法，金额就落到 JS 的浮点数上了 ——
 * 后端用 {@link BigDecimal} 算好再给，前端只管显示。
 *
 * <p>⚠️ <b>有两个「7 元/小时」不要搞混</b>：优惠后的<b>日间</b>价是
 * 3.5 元/半小时 = 7 元/小时，而<b>夜间原价</b>也是 3.5 元/半小时 = 7 元/小时。
 * 数字一样、含义不同，页面上的优惠表要分行写清楚。
 */
@Data
public class BillingRulesVo {

    /** 计费单位（分钟）：每满这么久计一档 */
    private int unitMinutes;

    /**
     * 结账误差宽限（分钟）。
     *
     * <p>它在顾客侧的两个表现：<b>首 N 分钟内免费出场</b>，以及
     * <b>档位边界落在进场后第 N、N+30、N+60… 分钟</b>
     *（⚠️ 是<b>从进场时刻起算</b>，不是「每小时的 :0N」——
     * 只有整点进场时两者才恰好重合；10:20 进场的话边界在 10:26、10:56）。
     */
    private int graceMinutes;

    /** 日场开始时刻（"10:00"）。夜间是 {@code dayEnd} 到次日 {@code dayStart} */
    private String dayStart;

    /** 日场结束时刻（"22:00"） */
    private String dayEnd;

    /* ---------- 原价 ---------- */

    /** 日场单价（元 / 计费单位） */
    private BigDecimal dayUnitPrice;

    /** 夜场单价（元 / 计费单位） */
    private BigDecimal nightUnitPrice;

    /** 日场封顶（元）= 10 档 × 日场单价 */
    private BigDecimal dayCap;

    /** 夜场封顶（元）= 10 档 × 夜场单价 */
    private BigDecimal nightCap;

    /** 日场价（元 / 小时），由单价换算而来 */
    private BigDecimal dayPricePerHour;

    /** 夜场价（元 / 小时），由单价换算而来 */
    private BigDecimal nightPricePerHour;

    /* ---------- 月度累计消费优惠 ---------- */

    /**
     * 优惠是否启用。
     *
     * <p>⚠️ 关掉时下面几个字段<b>照样给值</b>（它们是配置里写着的数），
     * 由前端决定展不展示 —— 后端在这里替前端做决定的话，
     * 「活动停了但页面还写着优惠价」这类不一致就没人拦得住了。
     */
    private boolean discountEnabled;

    /** 触发优惠的当月累计实付额门槛（元） */
    private BigDecimal discountThreshold;

    /** 优惠后的日场单价（元 / 计费单位） */
    private BigDecimal discountDayUnitPrice;

    /** 优惠后的夜场单价（元 / 计费单位） */
    private BigDecimal discountNightUnitPrice;

    /** 优惠后的日场封顶（元） */
    private BigDecimal discountDayCap;

    /** 优惠后的夜场封顶（元） */
    private BigDecimal discountNightCap;

    /** 优惠后的日场价（元 / 小时） */
    private BigDecimal discountDayPricePerHour;

    /** 优惠后的夜场价（元 / 小时） */
    private BigDecimal discountNightPricePerHour;

    /** 时刻的展示格式。与 {@code CardTypeVo.periodText} 里的写法保持一致 */
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 从计费配置组装。
     *
     * <p>写成静态工厂而不是让 Controller 自己拼：这样它可以脱离 Spring 容器单测
     *（与 {@code BillingService} 的取向一致），「元/小时」的换算也就有了专门的用例钉着。
     *
     * @param props 计费配置（由 Spring 注入的那个实例）
     * @return 价目表
     */
    public static BillingRulesVo from(BillingProperties props) {
        BillingRulesVo vo = new BillingRulesVo();

        vo.unitMinutes = props.getUnitMinutes();
        vo.graceMinutes = props.getGraceMinutes();
        vo.dayStart = props.getDayStart().format(HH_MM);
        vo.dayEnd = props.getDayEnd().format(HH_MM);

        vo.dayUnitPrice = props.getDayUnitPrice();
        vo.nightUnitPrice = props.getNightUnitPrice();
        vo.dayCap = props.getDayCap();
        vo.nightCap = props.getNightCap();
        vo.dayPricePerHour = perHour(props.getDayUnitPrice(), props.getUnitMinutes());
        vo.nightPricePerHour = perHour(props.getNightUnitPrice(), props.getUnitMinutes());

        BillingProperties.MonthlyDiscount discount = props.getMonthlyDiscount();
        vo.discountEnabled = discount.isEnabled();
        vo.discountThreshold = discount.getThreshold();
        vo.discountDayUnitPrice = discount.getDayUnitPrice();
        vo.discountNightUnitPrice = discount.getNightUnitPrice();
        vo.discountDayCap = discount.getDayCap();
        vo.discountNightCap = discount.getNightCap();
        vo.discountDayPricePerHour = perHour(discount.getDayUnitPrice(), props.getUnitMinutes());
        vo.discountNightPricePerHour = perHour(discount.getNightUnitPrice(), props.getUnitMinutes());

        return vo;
    }

    /**
     * 把「元 / 计费单位」换算成「元 / 小时」。
     *
     * <p>用 {@link BigDecimal} 且保留 2 位小数（金额的既定标度）——
     * 当前配置（30 分钟一档）都是整数倍，但档位若改成 45 分钟就会出现
     * 循环小数，那时的取舍是四舍五入到分，而不是让页面显示一长串。
     *
     * @param unitPrice   单价（元 / 计费单位）
     * @param unitMinutes 一个计费单位的分钟数
     * @return 每小时的价格
     */
    private static BigDecimal perHour(BigDecimal unitPrice, int unitMinutes) {
        return unitPrice.multiply(BigDecimal.valueOf(60))
                .divide(BigDecimal.valueOf(unitMinutes), 2, RoundingMode.HALF_UP);
    }
}
