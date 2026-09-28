package com.kaede.uspace.space.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 门店当前营业状态，供用户端首页展示。
 *
 * <p>三种状态对应三种准入规则（见 {@code CLAUDE.md} 的「准入模型」一节）：
 *
 * <table border="1">
 *   <caption>状态与准入</caption>
 *   <tr><th>状态</th><th>谁能进</th></tr>
 *   <tr><td>{@code OPEN}</td><td>任何人，正常下单</td></tr>
 *   <tr><td>{@code BOOKED}</td><td>仅包场人与被邀请者</td></tr>
 *   <tr><td>{@code CLOSED}</td><td>无人，一律拒绝新下单</td></tr>
 * </table>
 *
 * <p><b>停业的原因不对外披露</b>：「设备维护」「员工休假」这类信息属于运营内务，
 * 用户端只显示「暂停营业」。要查原因请走运营后台的停业记录接口。
 *
 * <p><b>包场时也不披露包场人是谁</b>：同理，普通用户只需要知道
 * 「这个时段进不去、什么时候能进」，不需要知道是谁包的场。
 */
@Data
public class StoreStatusVo {

    /** 营业中：任何人可下单 */
    public static final String STATUS_OPEN = "OPEN";

    /** 包场中：仅包场人与被邀请者可进 */
    public static final String STATUS_BOOKED = "BOOKED";

    /** 停业中：一律拒绝新下单 */
    public static final String STATUS_CLOSED = "CLOSED";

    /** 状态码：{@link #STATUS_OPEN} / {@link #STATUS_BOOKED} / {@link #STATUS_CLOSED} */
    private String status;

    /** 状态的中文文案，供前端直接展示，省得前端再维护一份映射表 */
    private String statusText;

    /**
     * 当前状态的结束时刻，供前端展示「14:00 恢复营业」。
     *
     * <p>停业时为停业结束时刻，包场时为包场结束时刻；
     * {@link #STATUS_OPEN} 时为 null（营业中没有「结束时刻」可言）。
     */
    private LocalDateTime statusEndAt;

    /** 门店基本信息 */
    private StoreVo store;

    /**
     * 构造「营业中」状态。
     *
     * @param store 门店信息
     * @return 状态视图
     */
    public static StoreStatusVo open(StoreVo store) {
        return build(STATUS_OPEN, "营业中", null, store);
    }

    /**
     * 构造「包场中」状态。
     *
     * @param store     门店信息
     * @param bookedEnd 包场结束时刻
     * @return 状态视图
     */
    public static StoreStatusVo booked(StoreVo store, LocalDateTime bookedEnd) {
        return build(STATUS_BOOKED, "当前时段已被包场，仅限受邀者入场", bookedEnd, store);
    }

    /**
     * 构造「停业中」状态。
     *
     * @param store    门店信息
     * @param resumeAt 停业结束时刻，即恢复营业的时刻
     * @return 状态视图
     */
    public static StoreStatusVo closed(StoreVo store, LocalDateTime resumeAt) {
        return build(STATUS_CLOSED, "暂停营业", resumeAt, store);
    }

    /**
     * 统一的构造入口，避免三个工厂方法各写一遍赋值。
     *
     * @param status      状态码
     * @param statusText  中文文案
     * @param statusEndAt 状态结束时刻，可为 null
     * @param store       门店信息
     * @return 状态视图
     */
    private static StoreStatusVo build(String status, String statusText,
                                       LocalDateTime statusEndAt, StoreVo store) {
        StoreStatusVo vo = new StoreStatusVo();
        vo.setStatus(status);
        vo.setStatusText(statusText);
        vo.setStatusEndAt(statusEndAt);
        vo.setStore(store);
        return vo;
    }
}
