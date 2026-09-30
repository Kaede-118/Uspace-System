package com.kaede.uspace.billing;

/**
 * 月卡对计费时段的覆盖范围（模块 7 侧的表达）。
 *
 * <p><b>为什么计费侧不直接用月卡的类型枚举</b>：计费只认识「哪些段免费」这个抽象，
 * 不认识「月卡」这个业务名词 —— 否则计费规则就与某一种促销形态绑死了。
 * 月卡类型到本枚举的映射由 {@code promotion} 包负责
 * （全天卡 → {@link #ALL}，夜间卡 → {@link #NIGHT}），
 * 将来若出现「周末卡」这类新形态，本包一行都不用改。
 *
 * <p><b>不覆盖任何时段时传 {@code null}</b>，不必为「无卡」再造一个枚举值 ——
 * 那个值没有任何行为，只会让每个使用点都多一个分支。
 *
 * <p>时段边界不在这里定义：日场与夜场的划分沿用 {@link BillingProperties}
 * 的 {@code day-start} / {@code day-end}，与计费本身共用同一套边界。
 * 若在别处再定义一次「什么算夜场」，两套边界迟早漂移。
 */
public enum CardCoverage {

    /** 覆盖全部时段（全天卡） */
    ALL("全天"),

    /** 只覆盖夜场时段（夜间卡）。日场段照常计费 */
    NIGHT("仅夜场");

    /** 中文说明，用于账单展示与日志 */
    private final String label;

    CardCoverage(String label) {
        this.label = label;
    }

    /**
     * 取中文说明。
     *
     * @return 覆盖范围的中文名称
     */
    public String getLabel() {
        return label;
    }
}
