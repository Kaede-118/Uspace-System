package com.kaede.uspace.billing;

import com.kaede.uspace.billing.dto.BillingResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 月度累计优惠开关关闭时的计费测试。
 *
 * <p>运营方临时停用优惠活动时，即使老用户当月累计早已远超门槛，
 * 也必须一律按原价计费 —— 开关要能真正兜住。
 *
 * <p>本类**不用 Spring 上下文**，直接 new 出配置与服务：{@link BillingService} 本就只依赖
 * {@link BillingProperties} 这一个 POJO，手工装配既省去容器启动的开销，
 * 也让「换个配置再测一遍」变成改一行 setter 的事 ——
 * 这正是计费服务「纯计算、可脱离容器单测」这一设计的实际收益。
 */
class BillingServiceDiscountDisabledTests {

    /** 关掉了月度优惠开关的计费服务 */
    private final BillingService billingService;

    /**
     * 构造测试用的计费服务：取全部默认计费参数，只把优惠开关关掉。
     */
    BillingServiceDiscountDisabledTests() {
        BillingProperties properties = new BillingProperties();
        properties.getMonthlyDiscount().setEnabled(false);
        this.billingService = new BillingService(properties);
    }

    @Test
    @DisplayName("优惠开关关闭：当月累计远超门槛，仍按原价计费")
    void disabled_chargesOriginalPriceEvenAboveThreshold() {
        BillingResult result = billingService.calculate(
                LocalDateTime.of(2026, 9, 16, 10, 0),
                LocalDateTime.of(2026, 9, 16, 11, 0),
                new BigDecimal("9999"));

        // 2 档 × 4 元 = 8 元（原价），而非优惠价 7 元
        assertEquals(0, new BigDecimal("8").compareTo(result.getTotalAmount()),
                "关闭优惠后应一律按原价，实际 " + result.getTotalAmount() + " 元");
        assertFalse(result.isDiscounted());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getDiscountAmount()));
    }

    @Test
    @DisplayName("优惠开关关闭：夜场同样按原价，段单价记 3.5 元")
    void disabled_nightChargesOriginalPrice() {
        BillingResult result = billingService.calculate(
                LocalDateTime.of(2026, 9, 16, 22, 0),
                LocalDateTime.of(2026, 9, 16, 23, 0),
                new BigDecimal("9999"));

        assertEquals(0, new BigDecimal("7").compareTo(result.getTotalAmount()),
                "夜场 2 档 × 3.5 元 = 7 元");
        assertEquals(0, new BigDecimal("3.5").compareTo(
                result.getSegments().get(0).getUnitPrice()));
    }
}
