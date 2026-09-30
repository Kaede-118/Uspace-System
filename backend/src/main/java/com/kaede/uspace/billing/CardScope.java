package com.kaede.uspace.billing;

/**
 * 月卡覆盖的<b>时段范围</b>（模块 7 侧的表达）—— 只回答「免哪些时段」，
 * 不回答「哪几天免」。
 *
 * <p><b>为什么把「时段」与「日期」拆成两个维度</b>：它们是两件独立的事，
 * 各自的取值范围也完全不同。时段只有日场 / 夜场两种分法（由
 * {@link BillingProperties} 的 {@code day-start} / {@code day-end} 定义），
 * 而日期是每张卡各不相同的一段区间。合成一个枚举就得为「全天卡且 9/30 到期」
 * 这类组合逐个造常量，卡一多就爆炸。
 *
 * <p>于是分工是：本枚举说「免什么时段」，{@link CardCoverage} 把它与
 * 一段具体日期绑在一起，成为「这张卡在什么时候免哪些段」的完整答案。
 *
 * <p><b>本包不认识「月卡」这个业务名词</b>：卡种到本枚举的映射由
 * {@code promotion} 包负责，将来若出现「周末卡」这类新形态，
 * 本包一行都不用改。
 */
public enum CardScope {

    /** 覆盖全部时段（全天卡） */
    ALL("全天"),

    /** 只覆盖夜场时段（夜间卡）。日场段照常计费 */
    NIGHT("仅夜场");

    /** 中文说明，用于账单展示与日志 */
    private final String label;

    CardScope(String label) {
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

    /**
     * 判断某个时段是否落在这个范围内。
     *
     * <p><b>只判时段，不判日期</b> —— 日期那一半由 {@link CardCoverage#covers} 负责。
     * 分开是为了让「夜间卡在日场段收费」这类判断不必每次都把日期也带上。
     *
     * @param period 计费时段
     * @return 该时段被本范围覆盖时返回 true
     */
    public boolean coversPeriod(BillingPeriod period) {
        return this == ALL || period == BillingPeriod.NIGHT;
    }
}
