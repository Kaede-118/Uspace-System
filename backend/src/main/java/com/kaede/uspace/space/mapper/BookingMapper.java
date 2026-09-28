package com.kaede.uspace.space.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.space.entity.Booking;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

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
                       @Param("price") java.math.BigDecimal price,
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
}
