package com.kaede.uspace.product.dto;

import com.kaede.uspace.product.ProductOrderStatus;
import com.kaede.uspace.product.entity.ProductOrder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一笔商品购买单。
 *
 * <p>价格与名称都取<b>下单时的快照</b>（{@code unitPrice} / {@code productName}），
 * 不是商品表里的当前值 —— 商品调价或改名之后，历史订单仍要显示当时的数字，
 * 否则用户会看到一个自己从没同意过的金额。
 *
 * <p><b>不含封面图</b>：那需要回查商品表，而商品可能已被删除。
 * 列表上用商品名与数量就足以认出是哪一单。
 */
@Data
public class ProductOrderVo {

    /** 购买单 ID，发起支付时作为 targetId */
    private Long id;

    /** 购买单号，同时是商户订单号 */
    private String orderNo;

    /** 购买人用户 ID */
    private Long userId;

    /**
     * 购买人昵称（昵称未填时回落登录名）。
     *
     * <p><b>只有后台列表接口（{@code /api/admin/products/orders}）填充它</b> ——
     * 后台的流水要一眼看出「这是谁买的」；用户端查自己的订单时它恒为 null
     * （那条路不带、也不必带，见 {@code ProductService#listOrders}）。
     *
     * <p>用户已注销或确实查不到时为 null，前端回落成「用户 #ID」。
     */
    private String userNickname;

    /** 商品 ID */
    private Long productId;

    /** 商品名称（下单时的快照） */
    private String productName;

    /** 下单时的单价（元） */
    private BigDecimal unitPrice;

    /** 数量 */
    private Integer quantity;

    /** 应付金额（元）= 单价 × 数量 */
    private BigDecimal amount;

    /** 状态名 */
    private String status;

    /** 状态中文名 */
    private String statusLabel;

    /** 支付通道，未支付时为空 */
    private String paymentMethod;

    /** 支付完成时刻，未支付时为空 */
    private LocalDateTime paidAt;

    /** 下单时刻 */
    private LocalDateTime createdAt;

    /**
     * 把购买单实体转成视图。
     *
     * @param order 购买单实体，可为 null
     * @return 购买单视图；入参为 null 时返回 null
     */
    public static ProductOrderVo from(ProductOrder order) {
        if (order == null) {
            return null;
        }
        ProductOrderVo vo = new ProductOrderVo();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setUserId(order.getUserId());
        // userNickname 不在这里填：它要查用户表，只有后台列表那条路需要（见字段注释）
        vo.setProductId(order.getProductId());
        vo.setProductName(order.getProductName());
        vo.setUnitPrice(order.getUnitPrice());
        vo.setQuantity(order.getQuantity());
        vo.setAmount(order.getAmount());
        vo.setStatus(order.getStatus());
        vo.setStatusLabel(ProductOrderStatus.labelOf(order.getStatus()));
        vo.setPaymentMethod(order.getPaymentMethod());
        vo.setPaidAt(order.getPaidAt());
        vo.setCreatedAt(order.getCreatedAt());
        return vo;
    }
}
