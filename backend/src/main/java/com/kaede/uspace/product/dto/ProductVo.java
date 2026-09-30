package com.kaede.uspace.product.dto;

import com.kaede.uspace.product.entity.Product;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 一件商品，用户端与运营后台共用。
 *
 * <p><b>同时给出「库存」与「可售量」两个数，它们不是一回事</b>：
 * <ul>
 *   <li>{@code stock} 是实际库存，后台改的就是它</li>
 *   <li>{@code availableStock} = 库存 − 被未支付的待支付单占掉的部分。
 *       用户端展示、下单校验用的都是它</li>
 * </ul>
 * 只给库存的话，会出现「页面说还有货、下单却说售罄」——
 * 那个不一致不是竞态造成的，是展示口径偷懒造成的，可以避免。
 *
 * <p>{@code soldOut} 由后端算好而不是让前端拿可售量自己比：
 * 「怎样算售罄」的口径要与下单校验完全一致，只能有一处定义。
 */
@Data
public class ProductVo {

    /** 商品 ID */
    private Long id;

    /** 商品名称 */
    private String name;

    /** 封面图地址（站内相对路径）。可为空，前端要有占位图 */
    private String cover;

    /** 商品描述 */
    private String description;

    /** 售价（元） */
    private BigDecimal price;

    /** 实际库存 */
    private Integer stock;

    /** 可售量 = 库存 − 未支付的待支付单数，下限为 0 */
    private Integer availableStock;

    /** 是否已售罄（可售量为 0）。前端据此把卡片置灰 */
    private Boolean soldOut;

    /** 是否上架。下架的商品不在用户端列表里，也不能下单 */
    private Boolean enabled;

    /** 排序值，越小越靠前 */
    private Integer sortNo;

    /**
     * 把商品实体转成视图。
     *
     * @param product         商品实体，可为 null
     * @param pendingQuantity 该商品被未支付的待支付单占掉的<b>件数</b>；无占用传 0
     * @return 商品视图；入参为 null 时返回 null
     */
    public static ProductVo from(Product product, int pendingQuantity) {
        if (product == null) {
            return null;
        }
        ProductVo vo = new ProductVo();
        vo.setId(product.getId());
        vo.setName(product.getName());
        vo.setCover(product.getCover());
        vo.setDescription(product.getDescription());
        vo.setPrice(product.getPrice());

        int stock = product.getStock() == null ? 0 : product.getStock();
        vo.setStock(stock);
        int available = product.availableStock(pendingQuantity);
        vo.setAvailableStock(available);
        vo.setSoldOut(available <= 0);

        vo.setEnabled(product.getEnabled() != null && product.getEnabled() == 1);
        vo.setSortNo(product.getSortNo());
        return vo;
    }
}
