package com.kaede.uspace.billing;

import com.kaede.uspace.billing.dto.BillingRulesVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BillingRulesVo} 的单元测试。
 *
 * <p><b>不启动 Spring</b>：直接 new 一个 {@link BillingProperties}（字段默认值就是
 * {@code application.properties} 里那套配置），走 {@code BillingRulesVo.from(...)}。
 *
 * <p>最要紧的是「元 / 小时」的换算 —— 它是本 VO 里唯一一处<b>计算</b>，
 * 而配置里存的是「元 / 计费单位」。算错了页面会挂出一个错价格，
 * 但那只是一个数字，<b>不会有任何报错</b>。
 */
class BillingRulesVoTests {

    /** 按默认值组装一份 —— 与线上配置一致的那一套 */
    private BillingRulesVo defaultRules() {
        return BillingRulesVo.from(new BillingProperties());
    }

    /**
     * BigDecimal 的比较要用 compareTo。
     *
     * <p>⚠️ {@code equals} 比的是「值 + 标度」：{@code 8} 与 {@code 8.00} 不相等。
     * 而本 VO 的换算结果统一保留 2 位小数（{@code 8.00}），断言里若写
     * {@code assertEquals(new BigDecimal("8"), ...)} 会失败 —— 且失败原因
     * （标度不同）与「算错了」长得一模一样。
     */
    private void assertMoney(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message);
    }

    @Test
    @DisplayName("元/小时换算：日场 4 元/30 分 → 8 元/小时，夜场 3.5 → 7")
    void convertsUnitPriceToHourly() {
        BillingRulesVo rules = defaultRules();

        assertMoney("8", rules.getDayPricePerHour(),
                "日场 4 元 / 30 分钟 = 8 元 / 小时");
        assertMoney("7", rules.getNightPricePerHour(),
                "夜场 3.5 元 / 30 分钟 = 7 元 / 小时");
    }

    @Test
    @DisplayName("优惠价的元/小时换算：日 3.5 → 7，夜 3 → 6")
    void convertsDiscountUnitPriceToHourly() {
        BillingRulesVo rules = defaultRules();

        assertMoney("7", rules.getDiscountDayPricePerHour(),
                "优惠后的日场 3.5 元 / 30 分钟 = 7 元 / 小时 —— "
                        + "⚠️ 与夜场原价同为「7 元/小时」，两个 7 含义不同，别在文案里搞混");
        assertMoney("6", rules.getDiscountNightPricePerHour(),
                "优惠后的夜场 3 元 / 30 分钟 = 6 元 / 小时");
    }

    @Test
    @DisplayName("其余字段原样透传自配置，不做任何加工")
    void passesThroughRawConfig() {
        BillingRulesVo rules = defaultRules();

        assertEquals(30, rules.getUnitMinutes());
        assertEquals(5, rules.getGraceMinutes(),
                "宽限就是「首 N 分钟免费」的那个 N，也是档位边界的起点");
        assertEquals("10:00", rules.getDayStart());
        assertEquals("22:00", rules.getDayEnd());

        assertMoney("4", rules.getDayUnitPrice(), "单价给的是【元 / 计费单位】的原始值");
        assertMoney("3.5", rules.getNightUnitPrice(), "");
        assertMoney("40", rules.getDayCap(), "");
        assertMoney("35", rules.getNightCap(), "");

        assertTrue(rules.isDiscountEnabled());
        assertMoney("200", rules.getDiscountThreshold(), "门槛是配置项，前端不得写死");
        assertMoney("3.5", rules.getDiscountDayUnitPrice(), "");
        assertMoney("3", rules.getDiscountNightUnitPrice(), "");
        assertMoney("35", rules.getDiscountDayCap(), "");
        assertMoney("30", rules.getDiscountNightCap(), "");
    }

    @Test
    @DisplayName("优惠关掉之后：enabled 为 false，但其余优惠字段照样给值")
    void keepsDiscountFieldsWhenDisabled() {
        BillingProperties props = new BillingProperties();
        props.getMonthlyDiscount().setEnabled(false);

        BillingRulesVo rules = BillingRulesVo.from(props);

        assertFalse(rules.isDiscountEnabled(), "活动停了");
        assertMoney("200", rules.getDiscountThreshold(),
                "⚠️ 关掉的是【活动】，不是配置 —— 门槛与优惠价照样返回，"
                        + "由前端决定展不展示；后端在这里替前端做决定的话，"
                        + "「活动停了但页面还写着优惠价」这类不一致就没人拦得住了");
        assertMoney("7", rules.getDiscountDayPricePerHour(), "");
    }
}
