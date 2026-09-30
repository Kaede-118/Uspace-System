package com.kaede.uspace.product.mapper;

import lombok.Data;

/**
 * 「某件商品被未支付的待支付单占掉多少件」的查询结果载体。
 *
 * <p>它不是对外的视图对象（那在 {@code dto} 包），只是
 * {@link ProductOrderMapper#countPendingByProduct} 的返回类型 ——
 * 那条 SQL 用了 {@code GROUP BY}，MyBatis 需要一个能同时接住
 * {@code product_id} 与聚合值的类型。
 *
 * <p><b>⚠️ 统计的是件数，不是单据笔数</b>：一笔「买 5 件」的待支付单
 * 占掉的是 5 件库存，而不是 1 件。这条 SQL 因此必须是
 * {@code SUM(quantity)} 而不是 {@code COUNT(*)} ——
 * 写成后者不会有任何报错，只会让可售量比实际多出一大截，
 * 表现为「明明下单成功了，付款后却说没货」。
 * 单测里有一条专门钉住它。
 *
 * <p>没有待支付单的商品<b>不会出现在结果里</b>（{@code GROUP BY} 的本性），
 * 调用方取值时要按「查不到即 0」处理，而不是拿 null 去参与运算。
 */
@Data
public class ProductPendingCount {

    /** 商品 ID */
    private Long productId;

    /** 该商品当前被未超时的待支付单占掉的<b>件数</b>（{@code SUM(quantity)}） */
    private Integer pendingQuantity;
}
