package com.kaede.uspace.space.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 停业记录实体，对应 {@code biz_closure} 表。
 *
 * <p>记录「某段时间不对外营业」—— 维护、休假等。停业区间内一律拒绝新下单，
 * 已经在店内的顾客不受影响（停业挡的是「新的人进来」，不是「赶人走」）。
 *
 * <p><b>为什么是独立一张表，而不是门店表上的一个状态字段</b>：
 * 状态字段只能表达「此刻开 / 此刻关」，而运营需要的是
 * 「明天 10:00–14:00 维护」这种<b>提前安排</b>。做成时段记录之后，
 * 到点自动生效、无需人工盯守，事后也能查到当时为什么停业、是谁登记的。
 *
 * <p>区间是<b>半开区间</b> {@code [startAt, endAt)}：从 {@code startAt} 那一刻起
 * 不对外营业，到 {@code endAt} 那一刻即恢复。之所以不用闭区间，
 * 是为了让「10:00–12:00 停业」与「12:00–14:00 停业」能自然相邻 ——
 * 闭区间下 12:00 那一刻被两段同时覆盖，会判成重叠而拒绝，
 * 运营排班时得为了避让一秒钟去挪时间，很别扭。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_closure")
public class Closure extends BaseEntity {

    /** 主键 */
    @TableId
    private Long id;

    /** 所属门店 ID */
    private Long storeId;

    /** 停业开始时刻（含） */
    private LocalDateTime startAt;

    /** 停业结束时刻（含） */
    private LocalDateTime endAt;

    /** 停业原因，如「设备维护」「春节休假」。可为空 */
    private String reason;

    /** 登记人（管理员用户 ID）。用于事后追溯「是谁排的停业」 */
    private Long createdBy;
}
