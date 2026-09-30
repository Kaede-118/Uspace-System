package com.kaede.uspace.billing;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 一次月卡覆盖的完整答案：<b>免哪些时段</b>（{@link CardScope}）+
 * <b>哪一段日期内免</b>（{@link #getValidFrom()} ~ {@link #getValidUntil()}）。
 *
 * <p>两个维度缺一不可。只有时段维度的话，卡最后一天 23:00 进店、玩到次日 01:00，
 * 整单都会被免掉 —— 等于多送了一个多小时；反过来，卡还没生效时进店的整单
 * 也一律不免。两头都靠「订单开始时刻」定，规则不闭合。
 *
 * <p><b>有效区间是半开区间</b> {@code [startDate 00:00, endDate+1 00:00)} ——
 * 与库里的 {@code start_date} / {@code end_date}（含首尾两个整天）等价，
 * 只是换成时刻上的表达：{@code end_date} 那一天整天的使用仍算数，
 * 次日零点起开始收费。
 *
 * <p><b>为什么计费侧不直接用月卡的类型枚举</b>：计费只认识「哪些段免费」这个抽象，
 * 不认识「月卡」这个业务名词 —— 否则计费规则就与某一种促销形态绑死了。
 * 卡种到本对象的装配由 {@code promotion} 包负责，
 * 将来若出现「周末卡」这类新形态，本包一行都不用改。
 *
 * <p><b>不覆盖任何时段时传 {@code null}</b>，不必为「无卡」再造一个实例 ——
 * 那个实例没有任何行为，只会让每个使用点都多一个分支。
 *
 * <p>时段边界不在这里定义：日场与夜场的划分沿用 {@link BillingProperties}
 * 的 {@code day-start} / {@code day-end}，与计费本身共用同一套边界。
 * 若在别处再定义一次「什么算夜场」，两套边界迟早漂移。
 */
public final class CardCoverage {

    /** 覆盖的时段范围 */
    private final CardScope scope;

    /** 生效日（含当日） */
    private final LocalDate startDate;

    /** 失效日（含当日） */
    private final LocalDate endDate;

    /** 生效时刻 = 生效日零点，含 */
    private final LocalDateTime validFrom;

    /** 失效时刻 = 失效日次日零点，<b>不含</b>（半开区间的上界） */
    private final LocalDateTime validUntil;

    /**
     * 私有构造，统一走 {@link #of} 做参数校验。
     *
     * @param scope     覆盖的时段范围
     * @param startDate 生效日
     * @param endDate   失效日
     */
    private CardCoverage(CardScope scope, LocalDate startDate, LocalDate endDate) {
        this.scope = scope;
        this.startDate = startDate;
        this.endDate = endDate;
        this.validFrom = startDate.atStartOfDay();
        this.validUntil = endDate.plusDays(1).atStartOfDay();
    }

    /**
     * 构造一个覆盖对象。
     *
     * <p>日期区间<b>含首尾两个整天</b>：30 天的卡传
     * {@code startDate} 与 {@code startDate.plusDays(29)}。
     *
     * @param scope     覆盖的时段范围
     * @param startDate 生效日，不能为空
     * @param endDate   失效日，不能早于生效日
     * @return 覆盖对象
     * @throws IllegalArgumentException 参数为空或首尾颠倒时抛出
     */
    public static CardCoverage of(CardScope scope, LocalDate startDate, LocalDate endDate) {
        Objects.requireNonNull(scope, "覆盖的时段范围不能为空");
        Objects.requireNonNull(startDate, "月卡生效日不能为空");
        Objects.requireNonNull(endDate, "月卡失效日不能为空");
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "月卡失效日不能早于生效日：start=" + startDate + ", end=" + endDate);
        }
        return new CardCoverage(scope, startDate, endDate);
    }

    /**
     * 判断某个时刻所属的段是否被本卡覆盖。
     *
     * <p><b>两个条件都要满足</b>：时段在 {@link CardScope} 的范围内，
     * 且该时刻落在有效区间内。
     *
     * <p>调用方传入的应当是<b>段的起点</b>：计费侧已把卡的有效期边界作为
     * 一条切分线（见 {@code BillingService#splitByPeriod}），所以一个段
     * 不可能跨越卡的边界 —— 要么整段在内、要么整段在外，用起点判定即准确。
     *
     * @param period 该段所属的计费时段
     * @param at     段的起点时刻
     * @return 该段免费时返回 true
     */
    public boolean covers(BillingPeriod period, LocalDateTime at) {
        if (period == null || at == null) {
            return false;
        }
        return scope.coversPeriod(period)
                && !at.isBefore(validFrom) && at.isBefore(validUntil);
    }

    /**
     * 取覆盖的时段范围。
     *
     * @return 时段范围
     */
    public CardScope getScope() {
        return scope;
    }

    /**
     * 取生效日。
     *
     * @return 生效日（含当日）
     */
    public LocalDate getStartDate() {
        return startDate;
    }

    /**
     * 取失效日。
     *
     * @return 失效日（含当日）
     */
    public LocalDate getEndDate() {
        return endDate;
    }

    /**
     * 取生效时刻（生效日零点）。
     *
     * <p>供计费侧切段用：它是一条要切开的边界。
     *
     * @return 生效时刻
     */
    public LocalDateTime getValidFrom() {
        return validFrom;
    }

    /**
     * 取失效时刻（失效日次日零点）。
     *
     * <p><b>这个时刻本身已经不在覆盖范围内</b>（半开区间），
     * 供计费侧切段与跳档预告（「月卡即将到期」）使用。
     *
     * @return 失效时刻
     */
    public LocalDateTime getValidUntil() {
        return validUntil;
    }

    /**
     * 取中文说明。
     *
     * @return 覆盖范围的中文名称，如「全天」「仅夜场」
     */
    public String getLabel() {
        return scope.getLabel();
    }

    /**
     * 取含日期区间的完整描述，用于日志。
     *
     * <p>排查「这段为什么没免」时，日志里能直接看到当时认定的有效期是哪一段 ——
     * 只打「全天」的话，看到日志也不知道系统以为卡到哪天为止。
     *
     * @return 如「全天 2026-09-01 ~ 2026-09-30」
     */
    public String describe() {
        return scope.getLabel() + " " + startDate + " ~ " + endDate;
    }
}
