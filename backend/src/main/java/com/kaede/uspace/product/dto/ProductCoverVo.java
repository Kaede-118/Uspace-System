package com.kaede.uspace.product.dto;

import lombok.Data;

/**
 * 上传商品封面的结果（模块 8 的「商品」）。
 *
 * <p>只有一个字段，仍然用一个对象包着而不是直接返回一个字符串 ——
 * 与其它 VO 保持同一种形态（{@code PasscodeVo} 是同样的先例），
 * 也给将来留了口子（缩略图、宽高）。
 *
 * <p>⚠️ 这里只有 {@code cover} 一个字段是刻意的：上传时落盘的
 * {@code StoredImage.path}（服务器磁盘上的绝对路径）<b>绝不能出网</b>。
 */
@Data
public class ProductCoverVo {

    /** 封面图的站内相对路径，如 {@code /uploads/product/9f3c8a….jpg}。前端把它填进表单，随商品一起提交 */
    private String cover;

    /**
     * 构造上传结果。
     *
     * @param url 图片的站内相对路径
     * @return 结果对象
     */
    public static ProductCoverVo of(String url) {
        ProductCoverVo vo = new ProductCoverVo();
        vo.setCover(url);
        return vo;
    }
}
