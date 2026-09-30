package com.kaede.uspace.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品购买单，对应 {@code biz_product_order} 表。
 *
 * <p>记录「用户想买哪件商品」这件事，从发起到支付成功或关闭。
 *
 * <p><b>本表而不是商品表充当支付目标</b>：支付回调按商户订单号反查，
 * 要求目标先落库。所以流程是：
 * <pre>
 *   本表落一条 PENDING_PAYMENT（order_no 即 out_trade_no）
 *          │
 *     支付回调（同一事务两跳）
 *          ├─ ① 本表 → PAID（带 status 守卫，幂等第一道）
 *          └─ ② 条件 UPDATE 扣减 biz_product.stock
 * </pre>
 *
 * <p><b>{@code productName} 与 {@code unitPrice} 是快照、不是冗余</b>：
 * 商品改名或调价之后，历史订单仍要显示当时的名字与价格 ——
 * 与 {@code biz_monthly_card.price} 同一个理由。
 *
 * <p><b>不生成 {@code biz_order}</b>：那是「门店使用」的订单，
 * 与商品是两码事，混在一张表里会让计费与统计口径互相污染。
 * 支付流水号就记在本表上。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_product_order")
public class ProductOrder extends BaseEntity {

    /** 主键 */
    @TableId
    private Long id;

    /**
     * 购买单号。
     *
     * <p>支付时充当<b>商户订单号</b>（微信/支付宝的 {@code out_trade_no}），
     * 回调靠它做幂等，因此有唯一索引。前缀 {@code PD} 是回调路由的依据。
     */
    private String orderNo;

    /** 购买人用户 ID */
    private Long userId;

    /** 商品 ID。商品被删除后这里仍指向原 ID，名称靠 {@link #productName} 快照还原 */
    private Long productId;

    /** 商品名称快照 —— 商品改名后历史订单仍显示当时的名字 */
    private String productName;

    /** 下单时的单价快照（元） */
    private BigDecimal unitPrice;

    /** 数量 */
    private Integer quantity;

    /** 应付金额（元）= {@code unitPrice × quantity}，下单时算好存下 */
    private BigDecimal amount;

    /** 状态。取值见 {@link com.kaede.uspace.product.ProductOrderStatus} */
    private String status;

    /** 支付通道。取值同订单的 payment_method */
    private String paymentMethod;

    /** 支付平台交易号（微信 transaction_id / 支付宝 trade_no） */
    private String paymentNo;

    /** 支付完成时刻 */
    private LocalDateTime paidAt;
}
