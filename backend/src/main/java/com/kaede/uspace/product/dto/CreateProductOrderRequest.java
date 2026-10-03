package com.kaede.uspace.product.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 下单买商品的请求。
 *
 * <p><b>只有商品 ID 与数量，没有价格</b>：价格由后端按下单那一刻的商品记录算，
 * 前端传价格来的话，改一改请求体就能用 1 分钱买走任何东西。
 * 与月卡购买接口同一条原则 —— 价格永远是服务端说了算。
 *
 * <p>数量上限取 99：真实场景下没人会在共享娱乐空间一次买下上百件商品，
 * 而上限不设的话，一个构造的请求就能把库存数字推到溢出边缘。
 */
@Data
public class CreateProductOrderRequest {

    /**
     * 单次购买的数量上限。
     *
     * <p><b>它是一个常量而不是写在注解里的字面量</b>：{@code ProductService#createOrder}
     * 也要用它 —— 群里下单那条路<b>绕过了 Web 层的 Bean Validation</b>
     * （注解只在 Controller 入参上生效），只靠这里的 {@code @Max} 挡不住
     * {@code /可乐-100}，而一个负数数量会算出一笔负金额的订单。
     * 两处各写一份 99 的话，改一处漏一处不会有任何报错。
     */
    public static final int MAX_QUANTITY = 99;

    /** 商品 ID */
    @NotNull(message = "请选择商品")
    private Long productId;

    /** 数量。不传按 1 件算 */
    @NotNull(message = "请填写购买数量")
    @Min(value = 1, message = "购买数量至少为 1")
    @Max(value = MAX_QUANTITY, message = "单次最多购买 " + MAX_QUANTITY + " 件")
    private Integer quantity;
}
