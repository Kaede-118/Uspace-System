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

    /** 商品 ID */
    @NotNull(message = "请选择商品")
    private Long productId;

    /** 数量。不传按 1 件算 */
    @NotNull(message = "请填写购买数量")
    @Min(value = 1, message = "购买数量至少为 1")
    @Max(value = 99, message = "单次最多购买 99 件")
    private Integer quantity;
}
