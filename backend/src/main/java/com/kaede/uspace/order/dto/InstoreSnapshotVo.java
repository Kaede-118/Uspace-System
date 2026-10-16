package com.kaede.uspace.order.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 某一时刻的「在店名册」快照（后台进出记录用）。
 *
 * <p><b>它是推算出来的，不是当时存下来的。</b> 系统里没有一张「名册历史表」——
 * 名册由订单的开始 / 结束时刻反推：{@code start_time <= at AND (end_time IS NULL OR end_time > at)}。
 * 这样做的好处是<b>历史记录一次都不用补</b>，任何一天的任何时刻都能还原；
 * 代价是它是「按今天的账重放当时」：管理员事后人工调整过某单的离店时刻，
 * 那么受影响的那几份快照会跟着变。这一点在页面上如实写明，别让读的人以为
 * 它是不可变的现场录像。
 *
 * <p><b>只给昵称，不给金额</b>：与 {@link InstoreUserVo} 同一条边界 ——
 * 「当时谁在店里」是事实，「谁那一单花了多少」是个人消费信息。
 */
@Data
public class InstoreSnapshotVo {

    /** 快照对应的时刻 */
    private LocalDateTime at;

    /** 当时在店人数，等于 {@link #names} 的长度 */
    private int count;

    /**
     * 当时在店的昵称列表，按到店时刻升序。
     *
     * <p>查不到用户记录的人用「用户{ID}」兜底而不是丢掉 ——
     * 被逻辑删除的账号，当时人确实在店里坐着。
     */
    private List<String> names;

    /**
     * 构造一份快照。
     *
     * @param at    时刻
     * @param names 当时的在店昵称列表
     * @return 快照视图
     */
    public static InstoreSnapshotVo of(LocalDateTime at, List<String> names) {
        InstoreSnapshotVo vo = new InstoreSnapshotVo();
        vo.setAt(at);
        vo.setNames(names);
        vo.setCount(names == null ? 0 : names.size());
        return vo;
    }
}
