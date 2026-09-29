package com.kaede.uspace.space.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.space.entity.Booking;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 包场的数据访问接口。
 *
 * <p><b>注意「占时段」与「已生效」是两个不同的口径</b>，本接口里各有一处用到：
 * <ul>
 *   <li>{@link #countOverlapping} 用 {@code PENDING_PAYMENT + PAID} ——
 *       尚未付款的包场也已经把时段许出去了，若允许再排一场重叠的，
 *       等两笔都付款就会撞车</li>
 *   <li>{@link #selectCoveringAt} 只用 {@code PAID} ——
 *       准入判断必须严格：还没付款的包场不产生任何排他性</li>
 * </ul>
 *
 * <p>两处都用字面量写死状态名，而不是从 {@link com.kaede.uspace.space.BookingStatus}
 * 取 —— 注解 SQL 里无法拼接枚举。改动状态取值时，
 * 记得同时检查这里与 {@code BookingStatus#occupiesSlot}。
 *
 * <p><b>手写 SQL 必须自己带 {@code deleted = 0}</b>，理由同 {@code ClosureMapper}。
 */
public interface BookingMapper extends BaseMapper<Booking> {

    /**
     * 分页查询某门店的包场记录。
     *
     * <p>按开始时间倒序，与停业列表一致。
     *
     * @param page    分页参数，由 MyBatis-Plus 的分页插件处理
     * @param storeId 门店 ID
     * @return 分页结果
     */
    @Select("""
            SELECT *
              FROM biz_booking
             WHERE deleted = 0
               AND store_id = #{storeId}
             ORDER BY start_at DESC
            """)
    IPage<Booking> selectPageByStore(IPage<Booking> page, @Param("storeId") Long storeId);

    /**
     * 统计与给定时段重叠、且仍在占用时段的包场数。
     *
     * <p>「仍在占用时段」= {@code PENDING_PAYMENT}（待付款）或 {@code PAID}（已付款）。
     * 已取消与已结束的不算，它们不再占用时段。
     *
     * <p>与停业时段也要错开 —— 两者都由运营安排，重叠属于排期失误，
     * 校验放在 Service 层（需要同时查两张表），这里只负责包场内部的重叠。
     *
     * @param storeId   门店 ID
     * @param startAt   待校验时段的开始时刻
     * @param endAt     待校验时段的结束时刻
     * @param excludeId 要排除的记录 ID（修改自己时传自己的 ID，新增时传 null）
     * @return 重叠的记录数；0 表示不冲突
     */
    @Select("""
            SELECT COUNT(*)
              FROM biz_booking
             WHERE deleted = 0
               AND store_id = #{storeId}
               AND status IN ('PENDING_PAYMENT', 'PAID')
               AND start_at <  #{endAt}
               AND end_at   >  #{startAt}
               AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    int countOverlapping(@Param("storeId") Long storeId,
                         @Param("startAt") LocalDateTime startAt,
                         @Param("endAt") LocalDateTime endAt,
                         @Param("excludeId") Long excludeId);

    /**
     * 查询覆盖给定时刻、且已付款生效的包场。
     *
     * <p>供准入判断使用：<b>只有 {@code PAID} 的包场才产生排他性</b> ——
     * 管理员排了期但包场人还没付款的，不该把散客挡在门外。
     *
     * <p>正常情况下同一时刻至多命中一条（排期时已校验不重叠），
     * 若真出现多条，取开始时间最晚的一条（最近排的那场）。
     *
     * @param storeId 门店 ID
     * @param time    待判断的时刻
     * @return 覆盖该时刻的已付款包场；没有则返回 null
     */
    @Select("""
            SELECT *
              FROM biz_booking
             WHERE deleted = 0
               AND store_id = #{storeId}
               AND status = 'PAID'
               AND start_at <= #{time}
               AND end_at   >  #{time}
             ORDER BY start_at DESC
             LIMIT 1
            """)
    Booking selectCoveringAt(@Param("storeId") Long storeId, @Param("time") LocalDateTime time);

    /**
     * 查询「准入窗口」内命中的已付款包场。
     *
     * <p>与 {@link #selectCoveringAt} 的区别是<b>把窗口往前挪了一段</b>：后者只认
     * 「此刻正在包场中」，本方法还认「包场即将开始」—— 开始前的一段提前量内就要停止
     * 接待新顾客，否则顾客刚付钱进场就被清场，钱花了却没玩尽兴。
     *
     * <p><b>两个方法不要合并</b>：对外的营业状态里「包场中」是从 {@code start_at}
     * 才开始的，提前显示会让散客误以为今天不能来 —— 准入窗口是内部判定，
     * 营业状态的语义是给顾客看的，两者受众不同。
     *
     * <p>窗口的右端由调用方算好传入（而非在 SQL 里做 {@code DATE_ADD}）：
     * 提前量是配置项，放在 Java 侧便于单测直接验证边界，
     * 也避免 MySQL 与假 Mapper 各写一份日期运算、语义漂移。
     *
     * @param storeId   门店 ID
     * @param time      当前时刻
     * @param windowEnd 提前量的右端，通常为 {@code time} 加上提前分钟数
     * @return 命中窗口的已付款包场；没有则返回 null
     */
    @Select("""
            SELECT *
              FROM biz_booking
             WHERE deleted = 0
               AND store_id = #{storeId}
               AND status = 'PAID'
               AND start_at <= #{windowEnd}
               AND end_at   >  #{time}
             ORDER BY start_at DESC
             LIMIT 1
            """)
    Booking selectAdmissionAt(@Param("storeId") Long storeId,
                              @Param("time") LocalDateTime time,
                              @Param("windowEnd") LocalDateTime windowEnd);

    /**
     * 按包场单号查询。
     *
     * <p>供模块 8 的支付回调使用 —— 回调按商户订单号定位记录。
     *
     * @param bookingNo 包场单号
     * @return 包场记录；不存在时返回 null
     */
    @Select("SELECT * FROM biz_booking WHERE booking_no = #{bookingNo} AND deleted = 0")
    Booking selectByBookingNo(@Param("bookingNo") String bookingNo);

    /**
     * 按邀请令牌查询。
     *
     * <p>供模块 8 的被邀请者入口使用 —— 被邀请者点开分享链接，
     * 前端拿链接里的令牌来查「这是谁包的场、几点的、在本店吗」。
     *
     * <p>令牌有唯一索引 {@code uk_invite_token}，所以最多返回一条。
     * <b>只查得到已付款的场</b>：令牌是付款成功那一刻才生成的，
     * 未付款的包场 {@code invite_token} 为 null，不会被本方法命中。
     *
     * @param inviteToken 邀请令牌
     * @return 包场记录；令牌无效时返回 null
     */
    @Select("SELECT * FROM biz_booking WHERE invite_token = #{inviteToken} AND deleted = 0")
    Booking selectByInviteToken(@Param("inviteToken") String inviteToken);

    /**
     * 更新包场信息（改期与改价）。
     *
     * <p><b>只改排期相关的字段</b>，状态与支付字段一律不动 ——
     * 它们由模块 8 的支付回调负责。这条边界让「改期」与「付款」
     * 两件事不会互相覆盖：管理员改期时，即使包场人同时在付款，
     * 也不会因为一次全量更新把 {@code paidAt} 抹掉。
     *
     * @param id      包场 ID
     * @param startAt 包场开始时刻
     * @param endAt   包场结束时刻
     * @param price   包场价格
     * @param remark  备注，可为 null
     * @return 受影响行数；0 表示记录不存在或已删除
     */
    @Update("""
            UPDATE biz_booking
               SET start_at   = #{startAt},
                   end_at     = #{endAt},
                   price      = #{price},
                   remark     = #{remark},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateSchedule(@Param("id") Long id,
                       @Param("startAt") LocalDateTime startAt,
                       @Param("endAt") LocalDateTime endAt,
                       @Param("price") BigDecimal price,
                       @Param("remark") String remark);

    /**
     * 更新包场状态。
     *
     * <p>模块 3 只用它来取消（{@code → CANCELLED}）；模块 8 用它来标记付款与结束。
     * 单独一个方法而不是走 {@code updateById}，是为了避免全量更新误伤其他字段。
     *
     * @param id     包场 ID
     * @param status 目标状态名，取值见 {@link com.kaede.uspace.space.BookingStatus}
     * @return 受影响行数；0 表示记录不存在或已删除
     */
    @Update("""
            UPDATE biz_booking
               SET status     = #{status},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    // ==================================================================
    // 以下供模块 8（订单 / 支付）调用
    // ==================================================================

    /**
     * 包场付款成功：一次性写入状态与全部支付字段。
     *
     * <p><b>为什么连状态带令牌一起写、而不是分成两条 SQL</b>：付款与令牌生成
     * 必须原子 —— 若先转 {@code PAID} 再写令牌而写令牌失败，就会留下一个
     * 「已生效但没人拿得到邀请链接」的包场，排他性生效了、被邀请者却进不来。
     * 合成一条后，要么全成要么全不成。
     *
     * <p><b>{@code AND status = 'PENDING_PAYMENT'} 是并发守卫</b>：
     * 支付回调可能重推，两条回调同时进来时只有一个能把状态从待付款翻过去，
     * 另一个拿到 0 行受影响 —— 调用方据此判定「已被处理过」，实现幂等。
     *
     * <p>{@code invite_token} 有唯一索引 {@code uk_invite_token} 兜底：
     * 撞索引会抛 {@code DuplicateKeyException}，调用方重新生成令牌重试。
     *
     * @param id            包场 ID
     * @param paymentMethod 支付通道名，取值同 {@code biz_order.payment_method}
     * @param paymentNo     支付平台交易号
     * @param paidAt        支付完成时刻
     * @param inviteToken   邀请令牌；为 null 时不覆盖原值（普通订单不走本方法，包场恒非 null）
     * @return 受影响行数；0 表示该场已不是待付款状态，或记录不存在
     */
    @Update("""
            UPDATE biz_booking
               SET status         = 'PAID',
                   payment_method = #{paymentMethod},
                   payment_no     = #{paymentNo},
                   paid_at        = #{paidAt},
                   invite_token   = #{inviteToken},
                   updated_at     = NOW()
             WHERE id = #{id}
               AND status = 'PENDING_PAYMENT'
               AND deleted = 0
            """)
    int markPaid(@Param("id") Long id,
                 @Param("paymentMethod") String paymentMethod,
                 @Param("paymentNo") String paymentNo,
                 @Param("paidAt") LocalDateTime paidAt,
                 @Param("inviteToken") String inviteToken);

    /**
     * 分页查询某人作为包场人的场次（含待付款的）。
     *
     * <p>供模块 8 的用户端使用：包场人要能看到自己名下的场、对未付款的发起支付。
     * 被邀请者不在本方法的返回范围内 —— 他不记在 {@code host_user_id} 上，
     * 只能凭邀请链接查看（见模块 8 的邀请令牌入口）。
     *
     * @param page       分页参数，由 MyBatis-Plus 的分页插件处理
     * @param hostUserId 包场人用户 ID
     * @return 分页结果，按开始时间倒序
     */
    @Select("""
            SELECT *
              FROM biz_booking
             WHERE deleted = 0
               AND host_user_id = #{hostUserId}
             ORDER BY start_at DESC
            """)
    IPage<Booking> selectPageByHost(IPage<Booking> page, @Param("hostUserId") Long hostUserId);

    /**
     * 查询某人在给定区间内作为包场人的、已付款生效的包场。
     *
     * <p>供模块 8 结算时剪切计费区间使用，且是<b>回退路径</b>：
     * {@code biz_order.booking_id} 只在「进店时正处包场时段」才会被写上，
     * 而包场人可能提前到店 —— 那时包场还没开始，订单没挂包场 ID。
     * 少了这条回退，他会被重复计费：既付了包场费，又在包场时段内按分钟被收一次钱。
     *
     * <p>区间相交用半开区间判断（{@code start_at < to AND end_at > from}），
     * 与全项目口径一致。返回列表而非单条：一条长时间不结算的订单理论上
     * 可能横跨同一个人名下的多场包场（虽然现实中极少），返回值留出这个余地，
     * 由调用方用游标算法逐段剪掉。
     *
     * @param storeId    门店 ID
     * @param hostUserId 包场人用户 ID
     * @param from       订单计费区间的开始时刻
     * @param to         订单计费区间的结束时刻
     * @return 相交的已付款包场，按开始时间升序；没有则返回空列表
     */
    @Select("""
            SELECT *
              FROM biz_booking
             WHERE deleted = 0
               AND store_id     = #{storeId}
               AND host_user_id = #{hostUserId}
               AND status       = 'PAID'
               AND start_at < #{to}
               AND end_at   > #{from}
             ORDER BY start_at
            """)
    List<Booking> selectHostBookingsInRange(@Param("storeId") Long storeId,
                                            @Param("hostUserId") Long hostUserId,
                                            @Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);
}
