package com.kaede.uspace.order.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条「进出记录」（后台管理员视图，模块 6）。
 *
 * <p><b>口径（2026-10-16 由用户定）：到店 = 开始计时，离店 = 停止计时。</b>
 * 因此这一行读的是订单的 {@code start_time} 与 {@code end_time}，
 * <b>不是</b>门锁的开门记录 —— 封闭空间的出门是机械推杠、不产生门锁记录，
 * 拿门锁记录当「离店」会永远等不到那一条。门锁记录在同页另一个 tab 里，
 * 它的定位是<b>旁证</b>：证明「有人在门口输对了密码」，不证明谁进谁出。
 *
 * <p>每一端都带一份<b>当时的在店名册</b>（{@link InstoreSnapshotVo}）：
 * <ul>
 *   <li>{@link #instoreOnArrive} —— 他到店那一刻，店里还有谁（<b>含他本人</b>）</li>
 *   <li>{@link #instoreAfterLeave} —— 他离店之后，店里还剩谁（<b>不含他本人</b>）</li>
 * </ul>
 * 记录本身只说明一个人的动作，名册才说明当时现场是什么样子 ——
 * 人工复盘要的正是后者（「Kaede 离店了，店里还剩 3 人：…」）。
 */
@Data
public class OrderTrafficVo {

    /** 订单 ID */
    private Long orderId;

    /** 订单号（对外单号，复盘时按它翻监控） */
    private String orderNo;

    /** 用户 ID */
    private Long userId;

    /** 昵称。用户记录查不到时为兜底显示名（见 {@code InstoreService}） */
    private String nickname;

    /** 用户角色：{@code USER} / {@code ADMIN}。供前端挂 STAFF 徽章，不承载权限判断 */
    private String role;

    /** 到店时刻 = 开始计时的时刻 */
    private LocalDateTime arriveTime;

    /** 离店时刻 = 停止计时的时刻；<b>仍在店时为 null</b> */
    private LocalDateTime leaveTime;

    /** 在店时长（分钟），与 {@code biz_order.stay_minutes} 同一口径；仍在店时为 null */
    private Integer stayMinutes;

    /** 订单状态枚举名 */
    private String status;

    /** 订单状态中文名 */
    private String statusLabel;

    /** 到店那一刻的在店名册（含本人） */
    private InstoreSnapshotVo instoreOnArrive;

    /** 离店之后的在店名册（不含本人）；仍在店时为 null */
    private InstoreSnapshotVo instoreAfterLeave;
}
