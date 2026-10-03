package com.kaede.uspace.order.dto;

import lombok.Data;

/**
 * 收款码图片上传的结果。
 *
 * <p>与商品封面（{@code ProductCoverVo}）同一套语义：<b>上传只落盘、只返回路径，
 * 不写任何数据库</b>。前端拿到 {@code imageUrl} 填进表单，
 * 随新增 / 修改收款码一起提交。
 *
 * <p>三条理由与商品封面完全一致：新增时还没有记录 ID 可写、
 * 表单是「填一半可以取消」的语义、传错可以反复换一张。
 * 代价同样是孤儿文件 —— 那是这条语义的固有结果，不是 bug。
 *
 * <p>字段名取 {@code imageUrl} 而不是 {@code url}：前端 {@code <img :src>} 里
 * 一眼能看出这是个地址，且与请求体 {@code PayQrSaveRequest.imageUrl} 同名，
 * 中间不必做一次重命名。
 */
@Data
public class PayQrImageVo {

    /** 图片的站内路径，形如 {@code /uploads/payqr/xxxx.png} */
    private String imageUrl;

    /**
     * 构造上传结果。
     *
     * @param imageUrl 图片的站内路径
     * @return 视图对象
     */
    public static PayQrImageVo of(String imageUrl) {
        PayQrImageVo vo = new PayQrImageVo();
        vo.setImageUrl(imageUrl);
        return vo;
    }
}
