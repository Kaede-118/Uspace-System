package com.kaede.uspace.promotion;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 月卡相关单号的生成器（卡号与购买单号共用）。
 *
 * <p>格式 {@code MC + yyyyMMddHHmmss + 4 位随机数}，如
 * {@code MC202609291430251739}，共 20 位。时间戳保证递增可读，
 * 随机后缀避免同秒并发碰撞；万一仍然撞上，唯一索引会拦下，
 * 不会写出两条同号的记录。
 *
 * <p><b>购买单号会充当支付接口的商户订单号</b>（{@code out_trade_no}），
 * 支付回调靠它的前缀路由回本模块，所以前缀只有这一处定义 ——
 * {@code order.PaymentTargetType.MONTHLY_CARD} 直接引用本常量，
 * 不在别处再写一份字面量。改了单号格式而漏改路由，回调会静默地找不到目标。
 *
 * <p><b>前缀为什么定义在 promotion 而不是 order 包</b>：本模块生成单号时要引它，
 * 而 order 包的 {@code OrderService} 又要引本模块的 {@code MonthlyCardService}
 * 查月卡覆盖。若前缀定义在 order 包，就成了 {@code promotion → order} 与
 * {@code order → promotion} 同时成立的<b>包级循环</b> —— 正是
 * {@code PaymentTargetType} 的注释里明令避免的那种结构。反过来由 order 引用本常量，
 * 依赖方向固定为单向的 {@code order → promotion}。
 *
 * <p>卡号与购买单号各自独立生成、共用前缀：前者是给用户看的凭证号，
 * 后者是支付流水号，语义不同但同属「月卡」这一域，
 * 各由自己的唯一索引（{@code uk_card_no} / {@code uk_order_no}）保证不重号。
 */
public final class MonthlyCardNo {

    /** 单号前缀。支付回调按它路由到月卡处理器 */
    public static final String PREFIX = "MC";

    /** 单号中的时间戳格式 */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 工具类不允许实例化 */
    private MonthlyCardNo() {
    }

    /**
     * 生成一个新的月卡单号（卡号或购买单号）。
     *
     * @return 20 位的单号
     */
    public static String generate() {
        return PREFIX
                + LocalDateTime.now().format(FORMATTER)
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }
}
