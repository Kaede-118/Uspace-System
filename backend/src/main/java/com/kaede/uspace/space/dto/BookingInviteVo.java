package com.kaede.uspace.space.dto;

import com.kaede.uspace.space.entity.Booking;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 邀请落地页的包场视图：包场信息 + 参与者名单。
 *
 * <p><b>为什么不给 {@link BookingVo} 加一个 participants 字段</b>：那个 VO
 * 被后台列表、包场时间表、以及「我创建的 / 我参与的」两个列表共用，
 * 给它加一个只在邀请页有值的字段，等于让另外几处响应里都多出一个恒为
 * {@code null} 的成员 —— 读接口文档的人会以为那里本该有名单。
 * 单独成一个类，语义就清楚了：<b>这是「凭链接看一场包场」的视图</b>。
 *
 * <p>字段与 {@link BookingVo} 完全一致（没有增删），只在最后多一个名单 ——
 * 所以前端从落地页跳到普通详情页时，读的是同一批字段名。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BookingInviteVo extends BookingVo {

    /** 参与者名单，<b>发起人固定排在最前</b>，其后按加入时刻升序 */
    private List<BookingParticipantVo> participants;

    /**
     * 由实体与名单构造视图。
     *
     * @param booking      包场实体
     * @param participants 参与者名单，可为空列表
     * @return 邀请页视图；{@code booking} 为 null 时返回 null
     */
    public static BookingInviteVo of(Booking booking, List<BookingParticipantVo> participants) {
        if (booking == null) {
            return null;
        }
        BookingInviteVo vo = new BookingInviteVo();
        vo.fillFrom(booking);
        vo.setParticipants(participants);
        return vo;
    }
}
