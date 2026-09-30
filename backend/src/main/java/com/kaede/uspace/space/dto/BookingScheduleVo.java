package com.kaede.uspace.space.dto;

import com.kaede.uspace.space.entity.Booking;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 包场时间表上的一行，供用户端首页展示。
 *
 * <p><b>只有时段，没有包场人</b> —— 这不是遗漏，是刻意的边界。
 * 普通顾客只需要知道「这个时段进不去、什么时候能进」，
 * 不需要知道是谁包的场。{@code StoreStatusVo} 的类注释把这条写得很死
 * （「包场时也不披露包场人是谁」），本 VO 遵守同一套规矩：
 * <b>它连 {@code hostUserId} 这个字段都没有</b>，想泄露也没处放。
 *
 * <p><b>为什么不复用 {@code BookingVo}</b>：那个是给包场人自己与被邀请者看的，
 * 带 {@code hostUserId}、{@code bookingNo}、{@code price} 这些
 * 只该让当事人知道的东西。让它出现在匿名接口上，
 * 是把「谁能看到什么」的边界交给调用方自觉 —— 而白名单式的新 VO
 * 让这件事在结构上就不可能出错。理由同 {@code DeviceDisplayVo} 与
 * {@code NoticeVo}。
 *
 * <p><b>为什么不做成一个消息流里的公告</b>：包场是「未来的安排」，
 * 机台维护是「已经发生的事」—— 两者混在一条消息流里，
 * 用户分不清哪条是通知、哪条是日程；而且日程会随改期变动，
 * 消息流是只增不改的，改期后旧的那条就永远对不上了。
 */
@Data
public class BookingScheduleVo {

    /** 包场开始时刻 */
    private LocalDateTime startAt;

    /** 包场结束时刻 */
    private LocalDateTime endAt;

    /**
     * 此刻是否正在进行中。
     *
     * <p>由后端算好，不让前端拿两个时间自己比当前时刻 ——
     * 客户端时钟不可信（用户可能改过手机时间），
     * 而且「进行中」的判定口径要与准入规则一致，只能有一处定义。
     *
     * <p>前端据此把这一行标成「进行中」：用户当场进不去时，
     * 看到时间表上那一行亮着，就知道原因，而不是以为门坏了。
     */
    private boolean ongoing;

    /**
     * 由实体构造视图。
     *
     * @param booking 包场实体
     * @param now     判定「进行中」用的时刻，由调用方传入（便于测试）
     * @return 时间表的一行
     */
    public static BookingScheduleVo from(Booking booking, LocalDateTime now) {
        BookingScheduleVo vo = new BookingScheduleVo();
        vo.setStartAt(booking.getStartAt());
        vo.setEndAt(booking.getEndAt());
        // 半开区间 [startAt, endAt)：与全项目的时段口径一致 ——
        // 10:00–12:00 与 12:00–14:00 可以相邻而不算重叠
        vo.setOngoing(!booking.getStartAt().isAfter(now) && booking.getEndAt().isAfter(now));
        return vo;
    }
}
