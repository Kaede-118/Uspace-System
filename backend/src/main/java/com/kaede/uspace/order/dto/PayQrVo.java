package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PayQrChannel;
import com.kaede.uspace.order.entity.PayQr;
import lombok.Data;

/**
 * 收银台上的一张收款码（用户端视图）。
 *
 * <p>只带顾客扫码付款需要的东西：<b>没有 {@code enabled}、没有 {@code sort}、
 * 也没有 {@code storeId}</b>。这些是运营侧的字段，露出来只会让前端多几个
 * 「拿到了但不知道怎么用」的值 —— 与 {@code NoticeVo} 的白名单思路一致。
 *
 * <p>后台列表用的是另一个视图（{@code AdminPayQrVo}），它才带启停与排序。
 */
@Data
public class PayQrVo {

    /** 收款码 ID。用户提交付款凭证时原样回传，记在凭证的 {@code pay_qr_id} 上 */
    private Long id;

    /** 渠道取值，见 {@link PayQrChannel} */
    private String channel;

    /** 渠道中文名（「微信」「支付宝」），由后端给，前端不必再维护一张映射表 */
    private String channelLabel;

    /** 显示名。并排展示多张码时靠它区分，如「微信收款码」「老板娘的支付宝」 */
    private String name;

    /** 收款码图片的站内路径，直接给 {@code <img :src>} 用 */
    private String imageUrl;

    /**
     * 由实体构造视图对象。
     *
     * @param entity 收款码实体，不可为 null
     * @return 用户端视图对象
     */
    public static PayQrVo from(PayQr entity) {
        PayQrVo vo = new PayQrVo();
        vo.setId(entity.getId());
        vo.setChannel(entity.getChannel());
        vo.setChannelLabel(PayQrChannel.labelOf(entity.getChannel()));
        vo.setName(entity.getName());
        vo.setImageUrl(entity.getImageUrl());
        return vo;
    }
}
