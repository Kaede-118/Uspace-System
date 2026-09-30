package com.kaede.uspace.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 后台新增或修改商品的请求。
 *
 * <p><b>全量替换语义</b>：没传的字段按「清空 / 默认值」处理，
 * 而不是保持原值 —— 与 {@code PUT /api/user/me} 同一套约定。
 * 前端做编辑时应当先把整条记录读出来、改完再整体提交。
 *
 * <p>例外的只有 {@link #enabled} 与 {@link #sortNo} 两个可空字段：
 * 不传时分别按「保持原值」与「0」处理，理由写在各自字段上。
 */
@Data
public class ProductSaveRequest {

    /** 商品名称 */
    @NotBlank(message = "商品名称不能为空")
    @Size(max = 100, message = "商品名称不能超过 100 个字")
    private String name;

    /**
     * 封面图地址（站内相对路径）。
     *
     * <p>图片本身走模块 1 的上传接口拿到路径，这里只存那个路径 ——
     * 与头像、banner 的做法一致。
     */
    @Size(max = 255, message = "封面图地址过长")
    private String cover;

    /** 商品描述 */
    @Size(max = 500, message = "商品描述不能超过 500 个字")
    private String description;

    /** 售价（元） */
    @NotNull(message = "售价不能为空")
    @DecimalMin(value = "0", message = "售价不能为负数")
    private BigDecimal price;

    /**
     * 库存。
     *
     * <p>这里填的是<b>新的库存值</b>，不是扣减量 —— 后台改库存是
     * 「盘点后把数字改成实际数量」，而不是「又卖了几件」。
     * 真正按数量扣减的只有支付回调那一条路径（{@code ProductMapper#deductStock}）。
     */
    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    /**
     * 是否上架。不传视为上架。
     *
     * <p>与设备状况那条一样，这里是<b>全量替换语义的唯一例外</b>：
     * 传 null 时保持原值。改个名字顺手把商品下架了、而管理员毫无察觉，
     * 是比「多写一个字段」严重得多的问题。
     */
    private Integer enabled;

    /** 排序值，越小越靠前。不传按 0 处理 */
    private Integer sortNo;
}
