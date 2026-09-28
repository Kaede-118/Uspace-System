package com.kaede.uspace.access.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步开门记录的结果。
 *
 * <p><b>把「拉回多少、落库多少、跳过多少」拆开报出来，而不是只给一个总数。</b>
 * 管理员把同一区间同步第二遍时，看到的是 {@code fetched=10 inserted=0 skipped=10} ——
 * 一眼就能确认幂等生效、数据没有重复。若只回一个数字，这个结论根本看不出来，
 * 而「重复同步会不会写重」恰恰是这个接口最需要被验证的性質。
 */
@Data
public class SyncRecordsVo {

    /** 本次同步的锁 ID */
    private Long lockId;

    /** 本次同步的区间起点（含） */
    private LocalDateTime from;

    /** 本次同步的区间终点（不含） */
    private LocalDateTime to;

    /** 门锁云返回、且落在区间内的记录条数。等于 {@code inserted + skipped} */
    private int fetched;

    /** 本次实际写入数据库的条数 */
    private int inserted;

    /** 因库中已存在同一 {@code (lock_id, open_time)} 而跳过的条数 */
    private int skipped;

    /**
     * 因缺少开门时刻而被丢弃的条数。
     *
     * <p>{@code open_time} 是 {@code NOT NULL} 列，云端返回的记录若缺这个字段就是脏数据。
     * 丢弃而不是报错 —— 一条脏数据不该让整批同步失败，但也不能悄悄吞掉，
     * 所以在这里如实报出来供管理员留察。
     */
    private int discarded;

    /**
     * 本次消耗的门锁云调用次数。
     *
     * <p>正常是 1 次（拉取记录）；若拉取结果为空，会再探活一次确认
     * 「锁在线」还是「云端不可达」，那就变成 2 次。
     *
     * <p><b>把额度成本放进响应体</b>而不是只写进日志，是为了让调用方直接看到
     * 「这个按钮每次点下去要花掉几次配额」—— 额度是本系统的硬约束（30000 次/月）。
     */
    private int cloudCalls;
}
