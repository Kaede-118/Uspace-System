package com.kaede.uspace.product;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 商品购买单号的生成器。
 *
 * <p>格式 {@code PD + yyyyMMddHHmmss + 4 位随机数}，如
 * {@code PD202609301430251739}，共 20 位 —— 与订单（{@code OD}）、
 * 包场（{@code BK}）、月卡（{@code MC}）同一套规则。时间戳保证递增可读，
 * 随机后缀避免同秒并发碰撞；万一仍然撞上，唯一索引会拦下，
 * 不会写出两条同号的记录。
 *
 * <p><b>购买单号会充当支付接口的商户订单号</b>（{@code out_trade_no}），
 * 支付回调靠它的前缀路由回本模块，所以前缀只有这一处定义 ——
 * {@code order.PaymentTargetType.PRODUCT} 直接引用本常量，
 * 不在别处再写一份字面量。改了单号格式而漏改路由，回调会静默地找不到目标。
 *
 * <p><b>前缀为什么定义在 product 而不是 order 包</b>：与月卡同一个理由 ——
 * order 包要引用本模块的购买单（支付处理器住在那儿），依赖方向只能是
 * 单向的 {@code order → product}。前缀反过来定义在 order 包，
 * 就成了两个方向同时成立的<b>包级循环</b>。
 *
 * <p>取 {@code PD} 而不是 {@code PR}：后者容易被读成 Promotion，
 * 而月卡已经占着 {@code MC} 这一域，同一个缩写指向两样东西迟早出岔子。
 */
public final class ProductNo {

    /** 单号前缀。支付回调按它路由到商品处理器 */
    public static final String PREFIX = "PD";

    /** 单号中的时间戳格式 */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 工具类不允许实例化 */
    private ProductNo() {
    }

    /**
     * 生成一个新的商品购买单号。
     *
     * @return 20 位的单号
     */
    public static String generate() {
        return PREFIX
                + LocalDateTime.now().format(FORMATTER)
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }
}
