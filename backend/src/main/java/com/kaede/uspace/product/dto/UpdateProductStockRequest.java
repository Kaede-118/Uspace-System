package com.kaede.uspace.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 后台「只改库存」的请求。
 *
 * <p><b>刻意只有库存一个字段</b>：补货与盘点是运营里最高频的动作之一，
 * 而商品的全量替换（{@code PUT /api/admin/products/{id}}）要求把名称、封面、
 * 描述、价格一并带上 —— 拿它来做补货，得先读整条记录再原样传回，
 * 中间若有别人改了名称就会被静默覆盖。与模块 4「机台单独改状况」
 * （{@code PUT /api/admin/devices/{id}/status}）是同一个取舍。
 *
 * <p>这里填的是<b>新的库存值</b>，不是增减量 —— 与 {@code ProductSaveRequest.stock}
 * 同义：后台改库存是「盘点后把数字改成实际数量」，而不是「又卖了几件」。
 */
@Data
public class UpdateProductStockRequest {

    /** 新的库存值。0 表示售罄，合法 */
    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;
}
