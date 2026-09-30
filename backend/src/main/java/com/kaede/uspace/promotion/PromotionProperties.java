package com.kaede.uspace.promotion;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 优惠配置，对应配置文件中的 {@code uspace.promotion.*}。
 *
 * <p>把价格与有效期外置的原因：这两样在真实运营中几乎必然会被调整
 * （调价、做活动、改赠送天数），写在代码里每次都要改代码重新部署。
 *
 * <p><b>但「哪些时段免费」不是配置项</b> —— 它由 {@link MonthlyCardType} 定义。
 * 允许配置的话，就允许把「全天卡」配成只免夜场这种没有任何意义、
 * 却会实实在在卖出去的组合。
 *
 * <p>金额一律使用 {@link BigDecimal}，禁止用 double / float ——
 * 浮点运算在金额累加时会产生精度误差。
 */
@Data
@Component
@ConfigurationProperties(prefix = "uspace.promotion")
public class PromotionProperties {

    /**
     * 月卡参数。
     *
     * <p>刻意声明为 {@code final} 且就地初始化：Lombok 不会为 final 字段生成 setter，
     * 于是「这个对象永远不为 null」由编译期保证 —— 否则每个使用点都要防着 NPE。
     * Spring Boot 对嵌套配置的绑定是在这个既有对象上设属性，不需要外层 setter。
     */
    private final MonthlyCard monthlyCard = new MonthlyCard();

    /**
     * 月卡参数。
     */
    @Data
    public static class MonthlyCard {

        /**
         * 是否开放购买。
         *
         * <p>关掉之后购卡接口一律拒绝，<b>已售出的卡照常生效</b> ——
         * 用于临时停售（比如要调价、或系统维护期间不想产生新订单），
         * 而不是让老顾客的卡失效。
         */
        private boolean enabled = true;

        /**
         * 有效期天数，<b>含首尾</b>。
         *
         * <p>默认 30 天 → {@code end_date = start_date + 29}。
         * 按购买日起算而非自然月：月初买与月末买拿到的天数一样多，
         * 否则月末几天买卡的用户只买到两三天，同样的钱买到的东西差一大截。
         */
        private int validDays = 30;

        /** 全天月卡价格（元） */
        private BigDecimal allDayPrice = new BigDecimal("600");

        /** 夜间月卡价格（元） */
        private BigDecimal nightPrice = new BigDecimal("320");

        /**
         * 待支付购买单的存活时长。
         *
         * <p>超过它之后，用户下次购卡时会顺手把旧单置为已关闭，用户无感；
         * 没超时则拒绝重复购买并提示回到收银台。用户也可以随时主动取消，
         * 不必干等这段时间。
         *
         * <p><b>生产环境要按最短的支付通道寿命来对齐这个值</b>：
         * JSAPI 凭据 2 小时、H5 链接只有 5 分钟，默认的 30 分钟落在两者之间。
         */
        private Duration pendingTimeout = Duration.ofMinutes(30);

        /**
         * 取某个卡种的单价。
         *
         * <p>用穷尽 switch 而不是 if-else：新增卡种时漏配价格会<b>编译不过</b>，
         * 而不是静默地按 0 元卖出去。
         *
         * @param type 卡种
         * @return 该卡种的价格（元）
         */
        public BigDecimal priceOf(MonthlyCardType type) {
            return switch (type) {
                case ALL_DAY -> allDayPrice;
                case NIGHT -> nightPrice;
            };
        }
    }
}
