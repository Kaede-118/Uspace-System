package com.kaede.uspace.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kaede.uspace.promotion.entity.MonthlyCardOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 月卡购买单的数据访问接口。
 *
 * <p>它是月卡支付链路的落点：支付回调按 {@link #selectByOrderNo} 反查，
 * 由 {@link #markPaid} 转已支付，随后由处理器往月卡表插一张卡。
 *
 * <p><b>手写 SQL 必须自己带 {@code deleted = 0}</b>，理由同月卡表。
 */
public interface MonthlyCardOrderMapper extends BaseMapper<MonthlyCardOrder> {

    /**
     * 按购买单号查单。
     *
     * <p>供支付回调使用 —— 回调来自支付平台，没有「当前用户」这个概念，
     * 身份由<b>验签</b>保证而不是由归属校验保证。
     *
     * @param orderNo 购买单号（即商户订单号）
     * @return 购买单；不存在时返回 null
     */
    @Select("""
            SELECT *
              FROM biz_monthly_card_order
             WHERE order_no = #{orderNo}
               AND deleted = 0
            """)
    MonthlyCardOrder selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 锁定某用户的待支付购买单，供购卡前的占用校验使用。
     *
     * <p><b>{@code FOR UPDATE} 是并发保护的关键，而它锁的是本表不是月卡表</b>：
     * 要挡的竞态是「同一用户的两个购买请求同时通过校验、各插一条待支付单」，
     * 而插入发生在<b>本表</b>上。锁月卡表挡不住本表的插入。
     *
     * <p>查不到行时，InnoDB 会在 {@code idx_user_status} 的对应位置上加间隙锁，
     * 并发的第二个请求会阻塞到本事务提交，之后重新读就能看到刚落的那条单。
     *
     * <p>⚠️ <b>调用方必须在事务里</b>（{@code @Transactional}）。没有事务时
     * {@code FOR UPDATE} 会因为自动提交而<b>静默地不加锁</b>，并发保护消失，
     * 且不会有任何报错。
     *
     * @param userId 用户 ID
     * @return 该用户的待支付购买单，按 ID 升序；没有则返回空列表
     */
    @Select("""
            SELECT *
              FROM biz_monthly_card_order
             WHERE deleted = 0
               AND user_id = #{userId}
               AND status  = 'PENDING_PAYMENT'
             ORDER BY id
             FOR UPDATE
            """)
    List<MonthlyCardOrder> selectPendingForUpdate(@Param("userId") Long userId);

    /**
     * 查某用户最近的一张待支付购买单（不加锁）。
     *
     * <p>供「我的卡包」展示「有一笔待支付的月卡」用，前端据此显示「去支付」。
     *
     * @param userId 用户 ID
     * @return 最近的待支付购买单；没有则返回 null
     */
    @Select("""
            SELECT *
              FROM biz_monthly_card_order
             WHERE deleted = 0
               AND user_id = #{userId}
               AND status  = 'PENDING_PAYMENT'
             ORDER BY id DESC
             LIMIT 1
            """)
    MonthlyCardOrder selectPendingByUser(@Param("userId") Long userId);

    /**
     * 查某用户最近的一张购买单（不加锁），不限状态。
     *
     * @param userId 用户 ID
     * @return 最近的购买单；没有则返回 null
     */
    @Select("""
            SELECT *
              FROM biz_monthly_card_order
             WHERE deleted = 0
               AND user_id = #{userId}
             ORDER BY id DESC
             LIMIT 1
            """)
    MonthlyCardOrder selectLatestByUser(@Param("userId") Long userId);

    /**
     * 支付成功：转入已支付并写全支付字段。
     *
     * <p><b>{@code AND status = 'PENDING_PAYMENT'} 是幂等的关键一道</b>：
     * 支付平台会重推通知，两条回调同时进来时只有一个能拿到 1 行受影响，
     * 另一个拿到 0 —— 调用方据此判定「已被处理过」，不再重复发卡、
     * 也不再重复累加用户的卡费。
     *
     * <p>用相对更新之外的手段保证幂等是必要的：本方法返回 0 时，
     * 调用方绝不能再去插卡。
     *
     * @param id            购买单 ID
     * @param paymentMethod 支付通道名
     * @param paymentNo     支付平台交易号
     * @param paidAt        支付完成时刻
     * @return 受影响行数；0 表示单子不是待支付状态，或记录不存在
     */
    @Update("""
            UPDATE biz_monthly_card_order
               SET status         = 'PAID',
                   payment_method = #{paymentMethod},
                   payment_no     = #{paymentNo},
                   paid_at        = #{paidAt},
                   updated_at     = NOW()
             WHERE id = #{id}
               AND status = 'PENDING_PAYMENT'
               AND deleted = 0
            """)
    int markPaid(@Param("id") Long id,
                 @Param("paymentMethod") String paymentMethod,
                 @Param("paymentNo") String paymentNo,
                 @Param("paidAt") LocalDateTime paidAt);

    /**
     * 关闭待支付的购买单。
     *
     * <p>两个触发场景：用户主动取消，以及超过存活时长后由下次购卡顺手关掉。
     *
     * <p>状态守卫 {@code status = 'PENDING_PAYMENT'} 不能少：没有它，
     * 一个已经支付成功的单子会被「取消」掉，而卡已经发出去了 ——
     * 于是出现「单子关闭、卡还在生效」的不一致。
     *
     * @param id 购买单 ID
     * @return 受影响行数；0 表示单据不是待支付状态，或记录不存在
     */
    @Update("""
            UPDATE biz_monthly_card_order
               SET status     = 'CLOSED',
                   updated_at = NOW()
             WHERE id = #{id}
               AND status = 'PENDING_PAYMENT'
               AND deleted = 0
            """)
    int closePending(@Param("id") Long id);
}
