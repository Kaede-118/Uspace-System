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
 *
 * <p>2026-09-26 定价改版后共有四组单价与封顶：日夜场各自区分原价与月度优惠价
 * （优惠参数见 {@link MonthlyDiscount}）。四组封顶一律等于「10 档 × 对应单价」，
 * 也就是「5 小时价格」—— 这条关系是计费规则的骨架，调价时四个数字要一起改。
 */
@Data
@Component
@ConfigurationProperties(prefix = "uspace.billing")
public class BillingProperties {

    /** 计费单位的时长（分钟）。默认 30 分钟一档 */
    private int unitMinutes = 30;

    /** 日场单价（元 / 计费单位）。默认 4 元 / 30 分钟，即 8 元/小时 */
    private BigDecimal dayUnitPrice = new BigDecimal("4");

    /**
     * 夜场单价（元 / 计费单位）。默认 3.5 元 / 30 分钟，即 7 元/小时。
     *
     * <p>夜场比日场便宜 1 元/小时 —— 夜间（22:00–次日 10:00）是共享娱乐空间
     * 闲置率最高的时段，用价格换利用率。
     */
    private BigDecimal nightUnitPrice = new BigDecimal("3.5");

    /**
     * 结账误差宽限（分钟）。默认 5 分钟。
     *
     * <p>计费公式为 {@code ceil((时长 − 宽限) ÷ 单位) × 单价}。这个宽限有两个作用：
     * <ol>
     *   <li>让档位边界落在 5、35、65… 分钟 —— 从整点进场时，整点与半点出门
     *       恰好都落在整档的末尾，不会因为多玩一两分钟就跳档</li>
     *   <li>隐含了「首 5 分钟内免费出场」—— 时长等于宽限时档数为 0，
     *       不需要额外的减免分支</li>
     * </ol>
     *
     * <p><b>为什么是 5 分钟</b>：一局舞萌（街机音游）的下限约 6 分钟
     * （最低 3 首 × 每首约 2 分钟）。<b>在房间里的时间不等于打机时间</b> ——
     * 投币、选曲、进出场、离场结算都要花时间，因此<b>完整打完一局必然超过 5 分钟，
     * 会正常计费</b>。这 5 分钟覆盖的只有「进来看看就走」「一首都没打完成」的场景，
     * 连最短的一局都覆盖不到，<b>不存在「送一局」的空间</b>。
     * 相比先前的 7 分钟、10 分钟，刻意反复进出场套利的收益被进一步压缩。
     *
     * <p><b>改这个数会连带改变什么</b>：档位边界（段费用跳档的时长）、
     * 免费时长、余数进位线、以及「金额达到封顶所需的时长」四件事都由它推导出来。
     * 例如从 7 改成 5 后，第 10 档（封顶档）的起点从 4 小时 38 分提前到 4 小时 36 分。
     * 单价与封顶金额本身不受影响。
     */
    private int graceMinutes = 5;

    /** 日场封顶金额（元）。默认 40 元 = 10 档 × 日场单价，即 5 小时价格 */
    private BigDecimal dayCap = new BigDecimal("40");

    /** 夜场封顶金额（元）。默认 35 元 = 10 档 × 夜场单价，即 5 小时价格 */
    private BigDecimal nightCap = new BigDecimal("35");

    /** 日场开始时间。该时刻起算日场 */
    private LocalTime dayStart = LocalTime.of(10, 0);

    /** 日场结束时间。该时刻起算夜场，直到次日 {@link #dayStart} */
    private LocalTime dayEnd = LocalTime.of(22, 0);

    /**
     * 月度累计消费优惠参数。
     *
     * <p>刻意声明为 {@code final} 且就地初始化：Lombok 不会为 final 字段生成 setter，
     * 于是「这个对象永远不为 null」由编译期保证 —— 否则计费逻辑里任何一处
     * {@code getMonthlyDiscount()} 都要防着 NPE。
     * Spring Boot 对嵌套配置的绑定是在这个既有对象上设属性，不需要外层 setter。
     */
    private final MonthlyDiscount monthlyDiscount = new MonthlyDiscount();

    /**
     * 月度累计消费优惠。
     *
     * <p>规则：结算某单时，若该用户<b>当月已支付订单的实付额之和</b>达到
     * {@link #threshold}，则本单<b>整单</b>按优惠价计费。
     *
     * <p><b>判定在订单粒度，不逐档、不追溯</b>：一单要么全按优惠价、要么全按原价，
     * 不会出现同一单里前几档原价、后几档优惠的情况。这样账单上只有一个单价，
     * 用户看得懂，代码也不必维护逐档推进的游标。已经结算过的订单不因后来达标而退款。
     *
     * <p>优惠价同样遵循「封顶 = 10 档 × 单价 = 5 小时价格」的关系，
     * 因此优惠后封顶随之下降（日 40 → 35，夜 35 → 30）——
     * 优惠是整列降价，不是只降单价、封顶照旧。
     */
    @Data
    public static class MonthlyDiscount {

        /** 是否启用月度累计优惠。关闭后所有订单一律按原价，便于临时停用活动 */
        private boolean enabled = true;

        /** 触发优惠的当月累计实付额门槛（元）。默认 200 元 */
        private BigDecimal threshold = new BigDecimal("200");

        /** 优惠后的日场单价（元 / 计费单位）。默认 3.5 元 / 30 分钟 */
        private BigDecimal dayUnitPrice = new BigDecimal("3.5");

        /** 优惠后的夜场单价（元 / 计费单位）。默认 3 元 / 30 分钟 */
        private BigDecimal nightUnitPrice = new BigDecimal("3");

        /** 优惠后的日场封顶（元）= 10 档 × 优惠日场单价 */
        private BigDecimal dayCap = new BigDecimal("35");

        /** 优惠后的夜场封顶（元）= 10 档 × 优惠夜场单价 */
        private BigDecimal nightCap = new BigDecimal("30");
    }
}
