package com.kaede.uspace.order.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条「进出事件」—— 后台进出时间线上的一张卡片。
 *
 * <p><b>为什么是事件而不是订单</b>（2026-10-16 按使用者反馈改的）：
 * 一张卡片装一条订单的「到店 + 离店」两端时，读起来既不是到店也不是离店 ——
 * 一屏扫过去看不出「刚才谁走了」。拆成事件之后，时间线才真的是一条线：
 * 每条只说一件事（某人到店 / 某人离店），并带上<b>那一刻店里的名册</b>。
 *
 * <p><b>口径</b>：到店 = 开始计时（`start_time`），离店 = 停止计时（`end_time`）。
 * 读的是订单，不是门锁开门记录 —— 封闭空间的出门是机械推杠、不产生门锁记录。
 *
 * <p>{@link #instore} 是<b>该事件发生之后</b>的在店名册：
 * 到店那条含他本人，离店那条不含（半开区间 —— 离店那一刻他已经不在店里了）。
 * 于是「某人离店、店里还剩谁」就是这一张卡片上的一个字段，不必再去别处拼。
 *
 * <p><b>名册是推算的</b>：按订单的开始 / 结束时刻反推，历史任意时刻都能还原；
 * 代价是人工调整过离店时刻的单子，受影响的快照会跟着变。
 */
@Data
public class OrderTrafficEventVo {

    /** 事件类型：{@code ARRIVE} 到店 / {@code LEAVE} 离店 */
    private String eventType;

    /** 事件类型的中文：到店 / 离店。与 {@link #eventType} 一并给出，前端筛选用枚举名、展示用中文 */
    private String eventLabel;

    /** 事件时刻 */
    private LocalDateTime eventTime;

    /** 订单 ID */
    private Long orderId;

    /** 对外单号 */
    private String orderNo;

    /** 当事人用户 ID */
    private Long userId;

    /** 当事人昵称；用户记录查不到时回落为「用户{ID}」 */
    private String nickname;

    /** 当事人角色：{@code USER} / {@code ADMIN}。供前端挂 STAFF 徽章，不承载权限判断 */
    private String role;

    /** 订单状态枚举名 */
    private String status;

    /** 订单状态中文名 */
    private String statusLabel;

    /**
     * 该事件之后的在店名册（含到店者本人；离店那条不含刚走的那位）。
     *
     * <p>这两条语义差别是刻意的：到店卡片回答「他进来时店里还有谁」，
     * 离店卡片回答「他走后店里还剩谁」—— 人工复盘要的正是后一句。
     */
    private InstoreSnapshotVo instore;
}
