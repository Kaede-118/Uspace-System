package com.kaede.uspace.space.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.entity.BookingParticipant;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 包场参与者 Mapper。
 *
 * <p>本表是「谁在这场包场里」的权威来源，两类查询都从这里出发：
 * 按场次查（参与者名单、人数）与按人查（我参与的包场、下单时该挂哪一场）。
 *
 * <p><b>手写 SQL 必须自带 {@code deleted = 0}</b>：{@code BaseEntity} 的逻辑删除
 * 只对 MyBatis-Plus 自动生成的语句生效，手写的不走它。理由同 {@code BookingMapper}。
 *
 * <p>与 {@code BookingMapper} 一样，SQL 里的状态名是<b>字面量</b>而不是从枚举取 ——
 * 注解里的字符串在编译期无法拼接枚举常量。改 {@code BookingStatus} 的取值时，
 * 记得回来看这几条 SQL。
 */
public interface BookingParticipantMapper extends BaseMapper<BookingParticipant> {

    // ==================================================================
    // 按场次查
    // ==================================================================

    /**
     * 判断某人是不是这场包场的参与者（包场人也算）。
     *
     * <p>返回 {@code int} 而不是 {@code boolean}：MyBatis 对 {@code COUNT(*)}+boolean
     * 的映射依赖驱动行为，用 int 更直白。调用方判 {@code > 0} 即可。
     *
     * <p>这是下单准入、包场开始的清场、以及结算时的包场时段剪切三处共用的判定 ——
     * 它把「被邀请者」从一个只存在于分享链接里的身份，变成了一个可查询的事实。
     *
     * @param bookingId 包场 ID
     * @param userId    用户 ID
     * @return 命中行数，0 表示不是参与者
     */
    @Select("""
            SELECT COUNT(*)
              FROM biz_booking_participant
             WHERE deleted = 0
               AND booking_id = #{bookingId}
               AND user_id    = #{userId}
            """)
    int countByBookingAndUser(@Param("bookingId") Long bookingId,
                              @Param("userId") Long userId);

    /**
     * 统计某场包场的参与者人数（含包场人）。
     *
     * <p>用于加入接口返回的 {@code participantCount}。
     *
     * @param bookingId 包场 ID
     * @return 人数
     */
    @Select("""
            SELECT COUNT(*)
              FROM biz_booking_participant
             WHERE deleted = 0
               AND booking_id = #{bookingId}
            """)
    int countByBooking(@Param("bookingId") Long bookingId);

    /**
     * 查某场包场的参与者名单。
     *
     * <p><b>发起人固定排在最前</b>，其余按加入时刻升序。
     * 排序刻意用 {@code CASE WHEN} 而不是直接 {@code ORDER BY role} ——
     * 后者依赖「HOST 的字母序在 PARTICIPANT 之前」这个巧合，
     * 将来加一个以 G 开头的角色（如 GUEST），发起人就会被挤到后面去，
     * 而且不会有任何报错。
     *
     * <p>只返回参与者行本身，昵称与头像由 Service 批量补 ——
     * 连表查会把 {@code sys_user} 的字段混进这个 Mapper 的职责里，
     * 而用户被逻辑删除时连表还会让整行消失（那时我们只想让昵称为空）。
     *
     * @param bookingId 包场 ID
     * @return 参与者行，按「发起人优先、加入时刻升序」排列
     */
    @Select("""
            SELECT *
              FROM biz_booking_participant
             WHERE deleted = 0
               AND booking_id = #{bookingId}
             ORDER BY CASE WHEN role = 'HOST' THEN 0 ELSE 1 END, joined_at, id
            """)
    List<BookingParticipant> selectByBookingId(@Param("bookingId") Long bookingId);

    // ==================================================================
    // 按人查
    // ==================================================================

    /**
     * 分页查某人以某个角色参与的包场。
     *
     * <p>「我参与的」（{@code PARTICIPANT}）列表用它。
     * 注意<b>「我创建的」不走本方法</b> —— 那个列表要包含待付款的场次，
     * 而 {@code HOST} 行在付款成功时才写入，查本表会漏掉它们，
     * 包场人也就点不到付款入口。详见 {@code BookingService#listHostBookings}。
     *
     * @param page   分页参数
     * @param userId 用户 ID
     * @param role   角色名，见 {@code BookingParticipantRole}
     * @return 分页的包场，按开始时间倒序
     */
    @Select("""
            SELECT b.*
              FROM biz_booking b
              JOIN biz_booking_participant p
                ON p.booking_id = b.id
               AND p.deleted    = 0
             WHERE b.deleted = 0
               AND p.user_id = #{userId}
               AND p.role    = #{role}
             ORDER BY b.start_at DESC
            """)
    IPage<Booking> selectPageByUserRole(IPage<Booking> page,
                                        @Param("userId") Long userId,
                                        @Param("role") String role);

    /**
     * 查某人参与的、<b>尚未结束</b>的已付款包场，取最近的一场。
     *
     * <p><b>供下单时决定该往订单上挂哪个 {@code bookingId}</b>：
     * 参与者可能比准入窗口更早到店（那时包场还没进窗口，准入判定走的是「普通」分支），
     * 若不主动挂上，包场开始时他会被当散客清场、结算时还会被重复计费。
     * 下单那一刻把他参与的场次记进订单，后面清场与计费两条链路就都能认出他 ——
     * 那两处读的都是订单上的 {@code bookingId}，一行都不用改。
     *
     * <p><b>不限定 {@code role}</b>：包场人也要挂上自己那场。他提前到店时
     * 订单同样不会命中准入窗口，虽然后续有 {@code host_user_id} 回查兜着，
     * 但让两条路径拿到同样的结果，比依赖一条兜底路径更稳。
     *
     * <p><b>挂上一场还没到时间的包场是无害的</b>：计费剪区间时会先把包场区间
     * 夹到订单区间内，夹完为空就整段跳过（见 {@code OrderService#billableRanges}），
     * 所以「今天下午来玩、订单挂着明天晚上的包场」不会少收一分钱。
     *
     * <p>「未结束」按 {@code end_at > now} 判定，正在进行中的也算 ——
     * 虽然包场进行中时准入一定命中、轮不到这条查询兜底，但留着它更稳。
     *
     * @param storeId 门店 ID
     * @param userId  用户 ID
     * @param now     当前时刻
     * @return 最近的一场；没有则返回 null
     */
    @Select("""
            SELECT b.*
              FROM biz_booking b
              JOIN biz_booking_participant p
                ON p.booking_id = b.id
               AND p.deleted    = 0
             WHERE b.deleted  = 0
               AND b.store_id = #{storeId}
               AND b.status   = 'PAID'
               AND p.user_id  = #{userId}
               AND b.end_at   > #{now}
             ORDER BY b.start_at
             LIMIT 1
            """)
    Booking selectUpcomingByParticipant(@Param("storeId") Long storeId,
                                        @Param("userId") Long userId,
                                        @Param("now") LocalDateTime now);
}
