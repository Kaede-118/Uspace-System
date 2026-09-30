package com.kaede.uspace.space.dto;

import com.kaede.uspace.space.entity.Booking;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 包场记录视图。
 *
 * <p>仅供运营后台使用。
 *
 * <p><b>只给出 {@code hostUserId}，不含包场人昵称</b>：昵称在 {@code sys_user} 表里，
 * 填充它要么让查询变成连表，要么在列表接口里逐条查产生 N+1。
 * 包场是低频业务（一店一月也就几场），后台一次只看一页，
 * 这一点查询开销可以接受 —— 等运营后台前端真正需要时，
 * 再在 Service 层批量查一次填充即可，接口契约无需改动。
 *
 * <p><b>不含 {@code inviteToken}</b>：那是被邀请者的准入凭据，
 * 让它出现在后台列表接口的响应里，等于给了它一条不必要的泄露路径。
 * 包场人自己取邀请链接应当走单独的接口（模块 8）。
 */
@Data
public class BookingVo {

    /** 包场 ID */
    private Long id;

    /** 包场单号，对外展示 */
    private String bookingNo;

    /** 包场人用户 ID */
    private Long hostUserId;

    /** 包场开始时刻 */
    private LocalDateTime startAt;

    /** 包场结束时刻 */
    private LocalDateTime endAt;

    /** 包场价格（元） */
    private BigDecimal price;

    /** 状态：PENDING_PAYMENT / PAID / CANCELLED / CLOSED，见 {@code BookingStatus} */
    private String status;

    /** 支付通道，未付款时为空 */
    private String paymentMethod;

    /** 支付完成时刻，未付款时为空。也是邀请链接开始可用的时刻 */
    private LocalDateTime paidAt;

    /** 备注，可为空 */
    private String remark;

    /** 安排人（管理员用户 ID），可为空 */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /**
     * 由实体构造 VO。
     *
     * @param booking 包场实体
     * @return 包场视图；入参为 null 时返回 null
     */
    public static BookingVo from(Booking booking) {
        if (booking == null) {
            return null;
        }
        BookingVo vo = new BookingVo();
        vo.fillFrom(booking);
        return vo;
    }

    /**
     * 把实体的字段填进当前实例。
     *
     * <p><b>抽出来是给子类复用的</b>：{@link BookingInviteVo} 要在同样这些字段
     * 之上再加一个参与者名单。若它自己重写一遍这十二行赋值，将来本类加了字段，
     * 子类那份就会悄悄漏掉，且不报任何错 —— 表现为「邀请页少了某个字段」，
     * 而列表页一切正常。
     *
     * @param booking 包场实体，不可为 null
     */
    protected void fillFrom(Booking booking) {
        setId(booking.getId());
        setBookingNo(booking.getBookingNo());
        setHostUserId(booking.getHostUserId());
        setStartAt(booking.getStartAt());
        setEndAt(booking.getEndAt());
        setPrice(booking.getPrice());
        setStatus(booking.getStatus());
        setPaymentMethod(booking.getPaymentMethod());
        setPaidAt(booking.getPaidAt());
        setRemark(booking.getRemark());
        setCreatedBy(booking.getCreatedBy());
        setCreatedAt(booking.getCreatedAt());
    }
}
