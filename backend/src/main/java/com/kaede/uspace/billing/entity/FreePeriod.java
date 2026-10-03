package com.kaede.uspace.billing.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 免费时段（活动）实体，对应 {@code biz_free_period} 表。
 *
 * <p>运营者排一段「不计费」的时间（如跨年 20:00 – 次日 02:00 全场免费），
 * 区间内所有订单的实收为 0。
 *
 * <p>⚠️ <b>它与停业（{@code biz_closure}）性质不同，别混</b>：
 * 停业管<b>准入</b>（能不能进），本表管<b>计费</b>（要不要钱）——
 * 店里照常营业、人照进、门照开，只是账单算 0。
 * 两者形态相同（时间区间 + 后台增删改查），放在一起便于对照，
 * 但改动其中一张时不要顺手改另一张：它们的生效位置根本不在一条链路上。
 *
 * <p>⚠️ <b>为什么归 {@code billing} 包而不是 {@code space} 包</b>：
 * 它是<b>计费规则</b>，与「停业 / 包场」那两条准入规则不是一类东西。
 * 归这里之后依赖方向也顺：{@code order → billing} 是既有方向，
 * {@code space} 不必参与进来。
 *
 * <p>区间是<b>半开区间</b> {@code [startAt, endAt)}：从 {@code startAt} 起不计费、
 * 到 {@code endAt} 起恢复收费。与停业、包场同一口径 ——
 * 两场活动首尾相接（18:00–20:00 与 20:00–22:00）因此不算重叠。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_free_period")
public class FreePeriod extends BaseEntity {

    /** 主键 */
    @TableId
    private Long id;

    /** 所属门店 ID */
    private Long storeId;

    /** 免费开始时刻（含） */
    private LocalDateTime startAt;

    /** 免费结束时刻（不含） */
    private LocalDateTime endAt;

    /** 活动名称，如「跨年活动」。可为空 */
    private String reason;

    /** 登记人（管理员用户 ID）。用于事后追溯「是谁排的这场活动」 */
    private Long createdBy;
}
