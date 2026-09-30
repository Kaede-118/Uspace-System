package com.kaede.uspace.promotion;

import com.kaede.uspace.billing.CardScope;

import java.util.Arrays;

/**
 * 月卡类型。取值与建表脚本里 {@code biz_monthly_card.card_type} 的注释一一对应。
 *
 * <p>两种卡的区别只在<b>覆盖哪些时段</b>，价格与有效期天数都是运营参数，
 * 放在 {@link PromotionProperties} 里随时可调。
 *
 * <p><b>覆盖范围为什么不放进配置</b>：那样就允许把「全天卡」配成只免夜场、
 * 或者两张卡配成一模一样的覆盖范围 —— 这种配置没有任何意义，
 * 却会实实在在地卖出去。它是卡种的定义，属于代码事实。
 *
 * <p><b>这里只定义「免哪些时段」，不定义「哪几天免」</b> ——
 * 后者每张卡各不相同，由卡上的生效与失效日期给出，
 * 两者在计费侧合起来才是完整的
 * {@link com.kaede.uspace.billing.CardCoverage 覆盖范围}。
 */
public enum MonthlyCardType {

    /** 全天月卡：不限时段，在有效期内订单的所有段都免费 */
    ALL_DAY("全天月卡", CardScope.ALL),

    /**
     * 夜间月卡：只覆盖夜场时段。
     *
     * <p>夜场的起止时刻不在这里写死 —— 它沿用
     * {@code uspace.billing.day-start} / {@code day-end}，
     * 与计费本身共用同一套边界。若在别处再定义一次「什么算夜场」，
     * 两套边界迟早漂移，而漂移的后果是顾客账单与月卡权益对不上。
     */
    NIGHT("夜间月卡", CardScope.NIGHT);

    /** 面向用户的中文名称，供前端展示 */
    private final String label;

    /** 该卡种覆盖哪些计费时段，供计费侧判定 */
    private final CardScope scope;

    MonthlyCardType(String label, CardScope scope) {
        this.label = label;
        this.scope = scope;
    }

    /**
     * 取中文名称。
     *
     * @return 卡种的中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 取该卡种覆盖的计费时段范围。
     *
     * @return 计费侧的时段范围枚举（不含日期）
     */
    public CardScope getScope() {
        return scope;
    }

    /**
     * 判断一个字符串是否为合法卡种名。
     *
     * <p>用于校验接口入参与库里读出来的历史值 —— 卡种列是 {@code VARCHAR}
     * 而非 {@code ENUM}，库不会替我们挡住非法值。
     *
     * @param name 待校验的卡种名，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(t -> t.name().equals(name));
    }

    /**
     * 把卡种名转成中文名称。
     *
     * <p>认不出的名字<b>原样返回</b>，让异常数据在界面上一眼看得出来 ——
     * 伪装成一个正常的中文名称反而会把它藏起来。
     *
     * @param name 卡种名，可为 null
     * @return 中文名称；认不出时原样返回
     */
    public static String labelOf(String name) {
        return isValid(name) ? valueOf(name).getLabel() : name;
    }
}
