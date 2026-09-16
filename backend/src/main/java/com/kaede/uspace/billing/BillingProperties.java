package com.kaede.uspace.billing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 计费规则配置，对应配置文件中的 {@code uspace.billing.*}。
 *
 * <p>把计费参数外置的原因：这类规则在真实运营中几乎必然会被调整
 * （调价、改封顶、改营业时段），写在代码里每次都要改代码重新部署。
 *
 * <p><b>金额一律使用 {@link BigDecimal}</b>，禁止用 double / float ——
 * 浮点运算在金额累加时会产生精度误差。
 */
@Data
@Component
@ConfigurationProperties(prefix = "uspace.billing")
public class BillingProperties {

    /** 计费单位的时长（分钟）。默认 30 分钟一档 */
    private int unitMinutes = 30;

    /** 每个计费单位的单价（元）。默认 4 元 / 30 分钟，即 8 元/小时 */
    private BigDecimal unitPrice = new BigDecimal("4");

    /**
     * 结账误差宽限（分钟）。默认 10 分钟。
     *
     * <p>计费公式为 {@code ceil((时长 − 宽限) ÷ 单位) × 单价}。这个宽限有两个作用：
     * <ol>
     *   <li>让档位边界落在 10、40、70… 分钟，使每个整点恰好是 8 元/小时</li>
     *   <li>隐含了「首 10 分钟内免费出场」—— 时长等于宽限时档数为 0</li>
     * </ol>
     */
    private int graceMinutes = 10;

    /** 日场封顶金额（元） */
    private BigDecimal dayCap = new BigDecimal("40");

    /** 夜场封顶金额（元） */
    private BigDecimal nightCap = new BigDecimal("30");

    /** 日场开始时间。该时刻起算日场 */
    private LocalTime dayStart = LocalTime.of(10, 0);

    /** 日场结束时间。该时刻起算夜场，直到次日 {@link #dayStart} */
    private LocalTime dayEnd = LocalTime.of(22, 0);
}
