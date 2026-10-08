package com.kaede.uspace.space.event;

import java.time.LocalDateTime;

/**
 * 停业时段被新增或撤销（模块 3 发布，模块 11 的 QQ 群播报监听）。
 *
 * <h3>为什么这个类住在 space 包</h3>
 *
 * <p>包依赖是单向的：{@code qqbot} 可以调其他模块，其他模块<b>禁止</b>认识 {@code qqbot}。
 * 发布点是 {@code ClosureService}（住在 space 包），所以事件也住在 space 包，
 * 由监听方反向 import —— 与 {@link BookingActivatedEvent} 同一条规矩。
 *
 * <h3>为什么撤销也要播</h3>
 *
 * <p>停业是<b>时间维度的准入规则</b>：群里看到「明天 10:00–14:00 不营业」之后，
 * 若管理员因故撤销了它，而群里一条消息都没有 —— 顾客仍然按旧消息安排行程，
 * 到了门口才发现能进（或反过来，白等一场）。播报的成对性比单条消息的内容更重要。
 *
 * <h3>为什么带上 reason</h3>
 *
 * <p>与机台公告刻意不带备注（{@code NoticeContents} 不接受备注参数）不同：
 * 停业原因（「设备维护」「休假」）是运营填给顾客看的那句话 ——
 * 字段名就叫 reason，说的是「为什么不营业」。
 * 只说时段不说原因的话，群里读到的人只会困惑「为什么突然不开」。
 *
 * @param closureId 停业记录 ID
 * @param startAt   停业开始时刻
 * @param endAt     停业结束时刻
 * @param reason    停业原因，可为 null（后台表单可不填）
 * @param action    新增还是撤销
 */
public record ClosureChangedEvent(
        Long closureId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        String reason,
        ClosureChangeAction action) {
}
