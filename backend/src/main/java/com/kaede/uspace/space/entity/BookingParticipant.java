package com.kaede.uspace.space.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 包场参与者实体，对应 {@code biz_booking_participant} 表。
 *
 * <p><b>它补的是哪个缺口</b>：在此之前，「被邀请者」这个身份没有任何地方存着 ——
 * 唯一的凭证是包场人分享出去的那条令牌链接，链接进不了 SQL。于是：
 * <ul>
 *   <li>下单准入只能靠请求体里带令牌才认得出他</li>
 *   <li>包场开始清场时认不出他，会把他当散客结算离场（若他比准入窗口更早到店，
 *       订单上没挂 {@code bookingId}，见 {@code OrderService#isBookingParticipant}）</li>
 *   <li>结算时也认不出他，包场时段会被重复计费</li>
 * </ul>
 * 本表把这三种判定统一到一条查询上。
 *
 * <p><b>唯一键 {@code uk_booking_user} 是防重的最终防线</b>：同一人重复点邀请链接，
 * 表现是撞唯一键而不是插入第二行。应用层的「先查再插」挡不住并发
 * （两人同时点，都查到「还没加入」），唯一键挡得住 —— 撞上不是异常，
 * 正是「你已经加入过了」这个答案，见 {@code BookingService#joinByBooking}。
 *
 * <p>本表<b>只增不删</b>：包场取消或改期都不清理它（改期影响的是时段，
 * 不影响「谁在这场里」）。逻辑删除列留着是为将来的「退出包场」这类需求，
 * 当前没有任何路径会写它。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_booking_participant")
public class BookingParticipant extends BaseEntity {

    /** 主键 */
    @TableId
    private Long id;

    /** 包场 ID */
    private Long bookingId;

    /** 参与人用户 ID */
    private Long userId;

    /** 角色。取值见 {@link com.kaede.uspace.space.BookingParticipantRole} */
    private String role;

    /**
     * 加入时刻。
     *
     * <p>{@code HOST} 行取<b>包场付款时刻</b>（不是创建排期的时刻）——
     * 那是包场从「安排」变成「事实」的唯一时刻，邀请令牌也在同一事务里生成。
     * {@code PARTICIPANT} 行取<b>点邀请链接的时刻</b>。
     *
     * <p>参与者名单按本列升序，于是列表读起来就是一个自然的先后顺序。
     */
    private LocalDateTime joinedAt;
}
