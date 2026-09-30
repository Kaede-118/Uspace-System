package com.kaede.uspace.order;

import com.kaede.uspace.product.ProductNo;
import com.kaede.uspace.promotion.MonthlyCardNo;

import java.util.Arrays;

/**
 * 支付目标的类型。
 *
 * <p><b>为什么需要它</b>：本系统里需要收钱的东西不止一种。普通订单（{@code biz_order}）
 * 与包场（{@code biz_booking}）是两张表、两套字段，但走的是同一套支付通道、
 * 同一个回调链路、同一套幂等规则。用一个统一的「发起支付」入口
 * （{@code POST /api/payments}）收口，靠这个类型区分钱是哪一笔的。
 *
 * <p>相比之下，若把支付挂在 {@code /api/orders/{id}/payment} 这样的订单子路径上，
 * 包场的付款就只能由 {@code space} 包提供接口，而 {@code space} 又要反过来依赖
 * {@code order}（模块 8 的支付服务），形成一个包级循环依赖。
 * 统一入口把依赖方向固定成单向的 {@code order → space}。
 *
 * <p>回调侧同样以它路由：支付平台回调只带得回一个商户订单号，
 * 系统按单号前缀判断这是订单还是包场，再交给对应的处理器 ——
 * 处理器由 Spring 按 {@code List<PaymentTargetHandler>} 自动收集，
 * 加一类支付目标不必改动回调代码。
 */
public enum PaymentTargetType {

    /** 普通订单（{@code biz_order}）。商户订单号 = {@code order_no}，前缀 {@code OD} */
    ORDER("OD"),

    /** 包场（{@code biz_booking}）。商户订单号 = {@code booking_no}，前缀 {@code BK} */
    BOOKING("BK"),

    /**
     * 月卡购买单（{@code biz_monthly_card_order}）。商户订单号 = {@code order_no}，
     * 前缀引用自 {@link com.kaede.uspace.promotion.MonthlyCardNo#PREFIX}。
     *
     * <p>支付目标是<b>购买单</b>而不是月卡本身：回调按单号反查，要求目标先落库，
     * 而还没付钱的卡不该出现在月卡表里。付款成功时会同时生成一张卡。
     *
     * <p><b>前缀常量为什么定义在 promotion 包</b>：本枚举所在的 order 包要引用
     * promotion 的 {@code MonthlyCardService} 查月卡覆盖，若前缀反过来定义在这里，
     * 就成了 {@code promotion → order} 与 {@code order → promotion} 同时成立的
     * 包级循环。字面量仍然只有一处，只是放在被引用方 —— 别把它挪回来。
     */
    MONTHLY_CARD(MonthlyCardNo.PREFIX),

    /**
     * 商品购买单（{@code biz_product_order}）。商户订单号 = {@code order_no}，
     * 前缀引用自 {@link com.kaede.uspace.product.ProductNo#PREFIX}。
     *
     * <p>前缀常量定义在 {@code product} 包，理由与月卡同款：
     * 本枚举所在的 order 包要引用 {@code product} 的购买单（
     * {@code ProductPaymentTargetHandler} 住在这里），依赖方向固定为
     * 单向的 {@code order → product}。前缀反过来定义在这里，
     * 就成了两个方向同时成立的包级循环。
     */
    PRODUCT(ProductNo.PREFIX);

    /**
     * 单号前缀。
     *
     * <p><b>它是支付回调路由的依据</b>：回调只带得回一个商户订单号，
     * 系统按前缀判断这号是订单还是包场，再交给对应的处理器。
     * 前缀一共只有两处定义（这里是唯一的一处，生成单号时引它），
     * 不在别处写字面量 —— 否则改了单号格式而漏改路由，回调就会静默地找不到目标。
     */
    private final String orderNoPrefix;

    PaymentTargetType(String orderNoPrefix) {
        this.orderNoPrefix = orderNoPrefix;
    }

    /**
     * 取单号前缀。
     *
     * @return 两位大写字母的前缀
     */
    public String getOrderNoPrefix() {
        return orderNoPrefix;
    }

    /**
     * 按单号前缀推断目标类型。
     *
     * @param orderNo 商户订单号，可为 null
     * @return 匹配的类型；认不出时返回 null（调用方应记 error 日志）
     */
    public static PaymentTargetType fromOrderNo(String orderNo) {
        if (orderNo == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(t -> orderNo.startsWith(t.orderNoPrefix))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断一个字符串是否为合法类型名。
     *
     * @param name 待校验的类型名，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(t -> t.name().equals(name));
    }
}
