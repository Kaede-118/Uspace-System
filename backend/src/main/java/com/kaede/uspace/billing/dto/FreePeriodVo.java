package com.kaede.uspace.billing.dto;

import com.kaede.uspace.billing.entity.FreePeriod;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 免费时段（活动）视图对象。
 *
 * <p>用户端与后台共用同一份字段 —— 活动是公开信息（首页就要显示），
 * 没有哪一列需要藏起来。与 {@code ClosureVo} 同形。
 */
@Data
public class FreePeriodVo {

    /** 主键 */
    private Long id;

    /** 免费开始时刻（含） */
    private LocalDateTime startAt;

    /** 免费结束时刻（不含） */
    private LocalDateTime endAt;

    /** 活动名称。可为空 */
    private String reason;

    /** 登记人（管理员用户 ID） */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /**
     * 由实体转换。
     *
     * @param period 实体
     * @return 视图对象
     */
    public static FreePeriodVo from(FreePeriod period) {
        FreePeriodVo vo = new FreePeriodVo();
        vo.setId(period.getId());
        vo.setStartAt(period.getStartAt());
        vo.setEndAt(period.getEndAt());
        vo.setReason(period.getReason());
        vo.setCreatedBy(period.getCreatedBy());
        vo.setCreatedAt(period.getCreatedAt());
        return vo;
    }
}
