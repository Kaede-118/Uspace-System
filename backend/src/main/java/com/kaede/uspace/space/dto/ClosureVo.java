package com.kaede.uspace.space.dto;

import com.kaede.uspace.space.entity.Closure;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 停业记录视图。
 *
 * <p>仅供运营后台使用 —— 停业原因（如「设备维护」）属于运营信息，
 * 不对外披露。用户端看到的只是「暂停营业」四个字，
 * 由 {@link StoreStatusVo} 表达。
 */
@Data
public class ClosureVo {

    /** 记录 ID */
    private Long id;

    /** 停业开始时刻（含） */
    private LocalDateTime startAt;

    /** 停业结束时刻（含） */
    private LocalDateTime endAt;

    /** 停业原因，可为空 */
    private String reason;

    /** 登记人（管理员用户 ID），可为空 */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;

    /**
     * 由实体构造 VO。
     *
     * @param closure 停业记录实体
     * @return 停业记录视图；入参为 null 时返回 null
     */
    public static ClosureVo from(Closure closure) {
        if (closure == null) {
            return null;
        }
        ClosureVo vo = new ClosureVo();
        vo.setId(closure.getId());
        vo.setStartAt(closure.getStartAt());
        vo.setEndAt(closure.getEndAt());
        vo.setReason(closure.getReason());
        vo.setCreatedBy(closure.getCreatedBy());
        vo.setCreatedAt(closure.getCreatedAt());
        vo.setUpdatedAt(closure.getUpdatedAt());
        return vo;
    }
}
