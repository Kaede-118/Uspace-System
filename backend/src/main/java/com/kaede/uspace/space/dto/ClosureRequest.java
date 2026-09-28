package com.kaede.uspace.space.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新增或修改停业记录的请求。
 *
 * <p>新增与修改共用同一个请求体：两者的字段完全相同，
 * 分成两个类只会多一份重复的校验注解。
 *
 * <p><b>时段的先后关系不在这里校验</b>（即 {@code endAt} 必须晚于 {@code startAt}）——
 * 单一的字段注解表达不了跨字段规则，硬塞进来要写自定义校验器，
 * 而这条规则在 Service 里只是一行 if。报错信息也更容易写清楚。
 */
@Data
public class ClosureRequest {

    /**
     * 停业开始时刻，必填。
     *
     * <p>允许填过去的时间 —— 管理员可能在事后补录一条停业记录
     * （如「昨天临时停业了半天，补一下」），拒绝历史时刻会让这件事做不了。
     */
    @NotNull(message = "停业开始时间不能为空")
    private LocalDateTime startAt;

    /** 停业结束时刻（<b>不含</b>），必填。到这一刻即恢复营业 */
    @NotNull(message = "停业结束时间不能为空")
    private LocalDateTime endAt;

    /** 停业原因，如「设备维护」「春节休假」。选填 */
    @Size(max = 255, message = "停业原因不能超过 255 个字符")
    private String reason;
}
