package com.kaede.uspace.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 实体商品，对应 {@code biz_product} 表。
 *
 * <p>这是<b>资产的定义</b>（卖什么、多少钱、还有几件），不是交易本身 ——
 * 交易在 {@link ProductOrder}。与月卡的两张表同构：定义与交易分开。
 *
 * <p><b>本表是「目录」而不是「库存流水」</b>：{@code stock} 是一个当前值，
 * 由支付回调上的条件 UPDATE 就地扣减。没有进销存台账，
 * 也补不了「这批货什么时候进的、卖给谁了」—— 单店自营不需要，
 * 真要做得另开一张流水表。
 *
 * <p><b>下架（{@code enabled = 0}）与删除（{@code deleted = 1}）是两回事</b>：
 * 下架是「暂时不卖」，商品仍在后台列表里、随时可以重新上架；
 * 删除是「这个东西没了」，从后台列表里也消失。
 * 两者都不影响已售出的订单 —— 那些单子上存的是下单时的名称与价格快照。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_product")
public class Product extends BaseEntity {

    /** 主键 */
    @TableId
    private Long id;

    /** 商品名称 */
    private String name;

    /** 封面图地址（站内相对路径，如 {@code /uploads/product/xxx.jpg}）。可为空 */
    private String cover;

    /** 商品描述 */
    private String description;

    /** 售价（元）。<b>下单时会快照进购买单</b>，事后调价不影响已售出的单 */
    private BigDecimal price;

    /**
     * 当前库存。
     *
     * <p><b>它在支付成功时才扣</b>（条件 UPDATE，见
     * {@code ProductMapper#deductStock}），不是下单时预占 ——
     * 取「支付时才扣」的固有风险是两人同时买最后一件都可能下单成功，
     * 缓解办法是下单时按「可售量 = 库存 − 未支付的待支付单数」做一次软检查。
     * 那一整套权衡写在 {@code ProductService#createOrder} 的注释里。
     *
     * <p>取值用 {@code Integer} 而不是 {@code Boolean}：库列是
     * {@code TINYINT} 而非 {@code TINYINT(1)}，JDBC 驱动不会把它当布尔映射。
     */
    private Integer stock;

    /**
     * 是否上架：1=上架 0=下架。
     *
     * <p>下架的商品<b>不出现在用户端的商品列表里</b>，也不能下单，
     * 但它的详情仍可打开（用户的订单里可能还指着它），
     * 后台列表也照常列出以便重新上架。
     *
     * <p>类型同 {@link #stock}：{@code Integer} 而非 {@code Boolean}。
     */
    private Integer enabled;

    /** 排序值，越小越靠前。同值时按 ID 升序兜底，避免翻页时顺序抖动 */
    private Integer sortNo;

    /**
     * 算这件商品当前还能卖几件。
     *
     * <p>= {@code stock − pendingCount}，下限为 0。
     *
     * <p><b>为什么放在实体上</b>：这个数有两个使用点 —— 用户端列表的
     * 「是否售罄」标记（{@code ProductVo#from}）与下单时的软检查
     * （{@code ProductService#createOrder}）。两处各写一遍的话，
     * 迟早出现「列表说还有货、下单却说售罄」—— 而两个地方看起来都对。
     *
     * <p>夹到 0 不是防御性编程的客套：软检查不锁库存，
     * 两人同时买最后一件时确实可能双双下单成功，占用量超过库存。
     * 负数显示给用户看会像是系统坏了。
     *
     * @param pendingQuantity 被未支付的待支付单占掉的<b>件数</b>
     *                        （不是单据笔数 —— 一笔买 5 件的单占 5 件）
     * @return 可售数量，最小为 0
     */
    public int availableStock(int pendingQuantity) {
        int current = stock == null ? 0 : stock;
        return Math.max(0, current - pendingQuantity);
    }
}
