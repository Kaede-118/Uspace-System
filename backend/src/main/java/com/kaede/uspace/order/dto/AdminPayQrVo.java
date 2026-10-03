package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PayQrChannel;
import com.kaede.uspace.order.entity.PayQr;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台列表里的一张收款码。
 *
 * <p>与用户端的 {@link PayQrVo} <b>刻意分成两个类</b>：本类多出
 * {@code enabled}（启停）与 {@code sort}（排序），而用户端不该看到它们 ——
 * 停用的码根本不会出现在收银台上，「这个字段恒为 1」对前端是纯噪音。
 * 这与 {@code NoticeVo} 的白名单思路一致：不声明，就泄漏不出去。
 *
 * <p>{@code updatedAt} 也在这里给：收款码是运营手工维护的数据，
 * 「上次动它是什么时候」是管理员列表里会看的一眼。
 */
@Data
public class AdminPayQrVo {

    /** 收款码 ID */
    private Long id;

    /** 渠道取值，见 {@link PayQrChannel} */
    private String channel;

    /** 渠道中文名 */
    private String channelLabel;

    /** 显示名 */
    private String name;

    /** 图片站内路径 */
    private String imageUrl;

    /** 是否启用：1 启用 / 0 停用 */
    private Integer enabled;

    /** 排序值，升序 */
    private Integer sort;

    /** 上次更新时间。序列化格式由 common 的 Jackson 配置统一指定 */
    private LocalDateTime updatedAt;

    /**
     * 由实体构造视图对象。
     *
     * @param entity 收款码实体，不可为 null
     * @return 后台视图对象
     */
    public static AdminPayQrVo from(PayQr entity) {
        AdminPayQrVo vo = new AdminPayQrVo();
        vo.setId(entity.getId());
        vo.setChannel(entity.getChannel());
        vo.setChannelLabel(PayQrChannel.labelOf(entity.getChannel()));
        vo.setName(entity.getName());
        vo.setImageUrl(entity.getImageUrl());
        vo.setEnabled(entity.getEnabled());
        vo.setSort(entity.getSort());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
