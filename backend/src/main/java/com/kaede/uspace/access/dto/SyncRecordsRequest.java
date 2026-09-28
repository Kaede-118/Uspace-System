package com.kaede.uspace.access.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步开门记录的请求（管理员在运营后台手动触发）。
 *
 * <p><b>为什么是手动触发而不是自动同步</b>：通通锁开放平台不提供「开门记录推送」
 * 这类回调，只能主动去拉；而免费额度是 30000 次/月。做成定时轮询的话，
 * 哪怕每 10 分钟拉一次，一把锁一个月也要 4300 多次，多几把锁就爆了额度。
 * 因此本接口<b>只在管理员点击时调用一次</b>，且每次调用只消耗一次额度
 * （拉取结果为空时会再探活一次，见 {@code SyncRecordsVo#cloudCalls}）。
 *
 * <p>时间区间为<b>半开</b> {@code [from, to)}：{@code 10:00–12:00} 与
 * {@code 12:00–14:00} 相邻而不重叠，因此管理员可以按整点边界把一整天切成几段同步，
 * 不会出现某条记录被同步两次（即便真重叠了，判重也会挡住）。
 */
@Data
public class SyncRecordsRequest {

    /** 锁 ID。通通锁的开门记录按锁维度查询，所以锁 ID 必填 */
    @NotNull(message = "锁 ID 不能为空")
    private Long lockId;

    /** 同步起始时刻（含） */
    @NotNull(message = "同步起始时间不能为空")
    private LocalDateTime from;

    /** 同步结束时刻（不含）。必须晚于起始时刻，且与起始时刻的跨度不超过 31 天 */
    @NotNull(message = "同步结束时间不能为空")
    private LocalDateTime to;
}
