package com.kaede.uspace.billing;

/**
 * 计费时段。
 *
 * <p>时段的具体起止时间、单价与封顶金额由 {@link BillingProperties} 配置，
 * 不写死在本枚举中 —— 将来调整营业时段（如增加「早场」）只需改配置。
 *
 * <p>当前划分（见 CLAUDE.md「计费规则」）：
 * <ul>
 *   <li>日场 10:00 – 22:00，原价 4 元/30 分、封顶 40 元；月度优惠价 3.5 元/30 分、封顶 35 元</li>
 *   <li>夜场 22:00 – 次日 10:00，原价 3.5 元/30 分、封顶 35 元；月度优惠价 3 元/30 分、封顶 30 元</li>
 * </ul>
 *
 * <p>四组封顶都等于「10 档 × 对应单价」，即 5 小时价格 —— 调价时单价与封顶要一起改。
 */
public enum BillingPeriod {

    /** 日场：10:00 – 22:00 */
    DAY("日场"),

    /** 夜场：22:00 – 次日 10:00 */
    NIGHT("夜场");

    /** 中文名称，用于账单展示与日志 */
    private final String label;

    /**
     * 构造方法。
     *
     * @param label 中文名称
     */
    BillingPeriod(String label) {
        this.label = label;
    }

    /**
     * 获取中文名称。
     *
     * @return 如「日场」「夜场」
     */
    public String getLabel() {
        return label;
    }
}
