package com.kaede.uspace.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.order.entity.Order;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单表的数据访问接口。
 *
 * <p>通过 {@code @MapperScan("com.kaede.uspace.**.mapper")} 自动注册，无需 {@code @Mapper} 注解。
 *
 * <p><b>手写 SQL 必须自己带 {@code deleted = 0}</b>：全局逻辑删除配置只作用于
 * MyBatis-Plus 自己生成的方法（{@code selectById} 等），对注解里手写的 SQL 不生效。
 * 漏掉的后果是已删除的订单还能被查到、还会被算进月度累计额里。
 *
 * <p><b>状态名一律写字面量</b>（{@code 'PAID'} / {@code 'IN_USE'}），
 * 因为注解 SQL 里拼不了枚举。改动 {@link com.kaede.uspace.order.OrderStatus}
 * 的取值时，记得逐个检查本接口。
 *
 * <p><b>更新方法一律带状态守卫</b>（{@code AND status = ...}）：支付回调会重推、
 * 用户会连点，两条请求同时进来时只有一个能把状态翻过去，另一个拿到 0 行受影响。
 * 调用方据此判定「已被处理过」，这是幂等的关键一道 —— 比「先查状态再更新」
 * 可靠，因为查与更新之间存在竞态窗口。
 */
public interface OrderMapper extends BaseMapper<Order> {

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 按单号查询。
     *
     * <p>供支付回调使用 —— 回调带着商户订单号（{@code out_trade_no}）回来，
     * 系统按它定位到具体订单。与 {@code BookingMapper#selectByBookingNo} 对称。
     *
     * @param orderNo 订单号
     * @return 订单；不存在时返回 null
     */
    @Select("SELECT * FROM biz_order WHERE order_no = #{orderNo} AND deleted = 0")
    Order selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 查询某人当前进行中的订单（{@code IN_USE}）。
     *
     * <p><b>这个方法挡的是「连点两下开门」</b>：点一次开门就会创建订单并开始计费，
     * 少了这道校验，手指抖一下就会产生两个密码、两条并行计费的订单，
     * 用户要付两份钱。同时它也供用户端首页判断「当前有没有在玩」。
     *
     * <p>理论上一个用户同时只该有一条 {@code IN_USE} 订单（下单时已挡住），
     * 这里仍取 {@code ORDER BY id DESC LIMIT 1} 兜底 —— 万一历史数据里有两条，
     * 返回最新的那条总好过抛异常。
     *
     * @param userId 用户 ID
     * @return 进行中的订单；没有则返回 null
     */
    @Select("""
            SELECT *
              FROM biz_order
             WHERE deleted = 0
               AND user_id = #{userId}
               AND status  = 'IN_USE'
             ORDER BY id DESC
             LIMIT 1
            """)
    Order selectActiveByUser(@Param("userId") Long userId);

    /**
     * 查询某门店当前所有进行中的订单（{@code IN_USE}）。
     *
     * <p>供包场开始时的清场使用：包场一开始，仍在店里的非参与者要被结算离场。
     * 与 {@link #selectActiveByUser} 的「一人至多一单」不同，这里返回的是<b>一批</b> ——
     * 平时的共享模式下，同一时刻店里可能有好几组顾客各玩各的。
     *
     * <p>按 {@code start_time} 升序：先来的先结算，日志读起来与店内实际发生的顺序一致，
     * 排查问题时不必再对着时间戳排序。
     *
     * @param storeId 门店 ID
     * @return 进行中的订单；没有则返回空列表
     */
    @Select("""
            SELECT *
              FROM biz_order
             WHERE deleted = 0
               AND store_id = #{storeId}
               AND status   = 'IN_USE'
             ORDER BY start_time
            """)
    List<Order> selectActiveByStore(@Param("storeId") Long storeId);

    /**
     * 统计某人某月「已支付订单」的实付额之和。
     *
     * <p>这是月度累计消费优惠的判定依据，口径有几条硬要求，逐条都容易踩坑：
     * <ul>
     *   <li><b>只算 {@code PAID}</b> —— 欠着费不算「消费」，否则用户可以靠不付款堆高累计</li>
     *   <li><b>按 {@code start_time} 归集</b>，不是 {@code paid_at} ——
     *       跨零点结算的夜单不会跳到下个月，管理员事后修正时长也不会让历史订单的优惠判定漂移</li>
     *   <li><b>不含月卡充值</b> —— 这是天然成立的：月卡购买不生成 {@code biz_order} 行。
     *       月卡本身已是独立优惠，再顶满门槛等于一笔钱吃两次优惠</li>
     *   <li><b>本单不计入</b> —— 同样是天然成立的：结算时本单还是 {@code PENDING_PAYMENT}。
     *       这避开了「本单算完把自己顶过门槛」的循环依赖</li>
     * </ul>
     *
     * <p>区间是<b>半开</b> {@code [from, to)}，与全项目的区间口径一致，
     * 调用方传「月初」与「下月初」。
     *
     * <p>{@code COALESCE(..., 0)} 让没有记录时返回 {@code 0} 而不是 {@code null} ——
     * 前者可以直接参与比较，后者会 NPE。
     *
     * <p><b>注意与 {@code sys_user} 那三列累计消费的区别</b>：那三列是<b>终生累计</b>、
     * 含月卡充值，用途是前端展示与老客回馈筛选；本方法算的是<b>当月</b>、
     * 不含月卡，用途是优惠门槛判定。两者用途与口径都不同，不可互相替代。
     *
     * @param userId 用户 ID
     * @param from   区间起点（含），通常是当月 1 日 00:00
     * @param to     区间终点（不含），通常是次月 1 日 00:00
     * @return 实付额之和（元）；无记录时返回 0
     */
    @Select("""
            SELECT COALESCE(SUM(payable_amount), 0)
              FROM biz_order
             WHERE deleted = 0
               AND user_id    = #{userId}
               AND status     = 'PAID'
               AND start_time >= #{from}
               AND start_time <  #{to}
            """)
    BigDecimal selectMonthPaidAmount(@Param("userId") Long userId,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    /**
     * 分页查询某人的订单。
     *
     * <p>可选条件用 {@code (#{x} IS NULL OR ...)} 的写法表达，而不是动态 SQL 的
     * {@code <if>} —— 与 {@code SysUserMapper#selectPageByKeyword} 同一套路，
     * 好处是假 Mapper 只需实现一个确定的方法名，不必模拟动态 SQL 的拼接过程。
     *
     * @param page   分页参数，由 MyBatis-Plus 的分页插件处理
     * @param userId 用户 ID
     * @param status 状态筛选，为 null 或空串时不过滤
     * @return 分页结果，按 id 倒序（新订单在前）
     */
    @Select("""
            SELECT *
              FROM biz_order
             WHERE deleted = 0
               AND user_id = #{userId}
               AND (#{status} IS NULL OR #{status} = '' OR status = #{status})
             ORDER BY id DESC
            """)
    IPage<Order> selectPageByUser(IPage<Order> page,
                                  @Param("userId") Long userId,
                                  @Param("status") String status);

    /**
     * 后台分页查询订单。
     *
     * <p>四个筛选条件都可空，与 {@link #selectPageByUser} 同一套写法。
     * 时间筛选的是 {@code start_time}（计费起点），因为运营查单多半是从
     * 「某天谁来过」这个角度切进去的。区间半开 {@code [from, to)}。
     *
     * <p>{@code adjusted} 传 1 可以筛出「所有被人工改过时长的订单」——
     * 这个列表值得运营定期过一眼，它是「用户忘记点结束」的高发信号。
     *
     * @param page     分页参数
     * @param userId   用户 ID 筛选，可空
     * @param status   状态筛选，可空
     * @param from     计费起点下界（含），可空
     * @param to       计费起点上界（不含），可空
     * @param adjusted 是否经人工调整：0/1，可空
     * @return 分页结果，按 id 倒序
     */
    @Select("""
            SELECT *
              FROM biz_order
             WHERE deleted = 0
               AND (#{userId}   IS NULL OR user_id = #{userId})
               AND (#{status}   IS NULL OR #{status} = '' OR status = #{status})
               AND (#{from}     IS NULL OR start_time >= #{from})
               AND (#{to}       IS NULL OR start_time <  #{to})
               AND (#{adjusted} IS NULL OR adjusted = #{adjusted})
             ORDER BY id DESC
            """)
    IPage<Order> selectPageForAdmin(IPage<Order> page,
                                    @Param("userId") Long userId,
                                    @Param("status") String status,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to,
                                    @Param("adjusted") Integer adjusted);

    // ==================================================================
    // 更新
    // ==================================================================

    /**
     * 续期密码：换一个新的有效期窗口，密码本身可能不变。
     *
     * <p>正常路径下密码数字<b>不变</b>（通通锁的 {@code changePasscode} 只改有效期），
     * 只有当续期失败降级为重新下发时，{@code passcode} 才会变成新的一串。
     * 两种情形调用同一个方法。
     *
     * @param id         订单 ID
     * @param passcode   密码（续期时与原值相同，重新下发时为新值）
     * @param startTime  新的生效时间
     * @param endTime    新的失效时间
     * @return 受影响行数；0 表示订单不是使用中状态，或记录不存在
     */
    @Update("""
            UPDATE biz_order
               SET passcode       = #{passcode},
                   passcode_start = #{startTime},
                   passcode_end   = #{endTime},
                   updated_at     = NOW()
             WHERE id = #{id}
               AND status = 'IN_USE'
               AND deleted = 0
            """)
    int updatePasscode(@Param("id") Long id,
                       @Param("passcode") String passcode,
                       @Param("startTime") LocalDateTime startTime,
                       @Param("endTime") LocalDateTime endTime);

    /**
     * 写入结算结果（用户点「结束使用」）。
     *
     * <p>金额各项都取自计费服务算出的分段结果，且都已是<b>实收</b>
     * （已封顶、已含优惠）—— 见 {@link Order} 类注释里的口径说明。
     * 各段缺失时传 0 而不是 null，让账单永远有两个可展示的段。
     *
     * <p>{@code status} 由调用方给：通常转 {@code PENDING_PAYMENT}，
     * 金额为 0 时直接给 {@code PAID}（0 元单没有可支付的通道）。
     *
     * @param id             订单 ID
     * @param endTime        离场时刻
     * @param dayMinutes     日场时长（分钟）
     * @param dayAmount      日场实收
     * @param nightMinutes   夜场时长（分钟）
     * @param nightAmount    夜场实收
     * @param totalAmount    实收合计
     * @param discountAmount 本单优惠金额（说明性，已含在合计中）
     * @param payableAmount  应付金额
     * @param status         目标状态名
     * @return 受影响行数；0 表示订单不是使用中状态，或记录不存在
     */
    @Update("""
            UPDATE biz_order
               SET end_time        = #{endTime},
                   day_minutes     = #{dayMinutes},
                   day_amount      = #{dayAmount},
                   night_minutes   = #{nightMinutes},
                   night_amount    = #{nightAmount},
                   total_amount    = #{totalAmount},
                   discount_amount = #{discountAmount},
                   payable_amount  = #{payableAmount},
                   status          = #{status},
                   updated_at      = NOW()
             WHERE id = #{id}
               AND status = 'IN_USE'
               AND deleted = 0
            """)
    int updateSettlement(@Param("id") Long id,
                         @Param("endTime") LocalDateTime endTime,
                         @Param("dayMinutes") Integer dayMinutes,
                         @Param("dayAmount") BigDecimal dayAmount,
                         @Param("nightMinutes") Integer nightMinutes,
                         @Param("nightAmount") BigDecimal nightAmount,
                         @Param("totalAmount") BigDecimal totalAmount,
                         @Param("discountAmount") BigDecimal discountAmount,
                         @Param("payableAmount") BigDecimal payableAmount,
                         @Param("status") String status);

    /**
     * 管理员人工调整时长并重算金额。
     *
     * <p>与 {@link #updateSettlement} 的区别有三处，都是刻意的：
     * <ol>
     *   <li><b>状态守卫放宽到「未付款」两种</b> —— 使用中的订单说明顾客已经走了
     *       却没人点结束（这正是人工调整的典型场景），待支付的则是账单已出、金额要改</li>
     *   <li><b>同时写下四个调整字段</b>，让「这单被人改过」在数据里留痕</li>
     *   <li>可把状态从 {@code IN_USE} 推到 {@code PENDING_PAYMENT}</b> ——
     *       故事线是「顾客已经走了、账单要出来收款」，留在使用中会让一条
     *       永远不会被点结束的订单永远收不到钱</li>
     * </ol>
     *
     * @param id             订单 ID
     * @param endTime        核实后的离场时刻
     * @param dayMinutes     日场时长（分钟）
     * @param dayAmount      日场实收
     * @param nightMinutes   夜场时长（分钟）
     * @param nightAmount    夜场实收
     * @param totalAmount    实收合计
     * @param discountAmount 本单优惠金额
     * @param payableAmount  应付金额
     * @param status         目标状态名
     * @param adjustedBy     调整人（管理员 ID）
     * @param adjustReason   调整原因
     * @return 受影响行数；0 表示订单已支付/已删除，或记录不存在
     */
    @Update("""
            UPDATE biz_order
               SET end_time        = #{endTime},
                   day_minutes     = #{dayMinutes},
                   day_amount      = #{dayAmount},
                   night_minutes   = #{nightMinutes},
                   night_amount    = #{nightAmount},
                   total_amount    = #{totalAmount},
                   discount_amount = #{discountAmount},
                   payable_amount  = #{payableAmount},
                   status          = #{status},
                   adjusted        = 1,
                   adjusted_by     = #{adjustedBy},
                   adjusted_at     = NOW(),
                   adjust_reason   = #{adjustReason},
                   updated_at      = NOW()
             WHERE id = #{id}
               AND status IN ('IN_USE', 'PENDING_PAYMENT')
               AND deleted = 0
            """)
    int updateAdjustment(@Param("id") Long id,
                         @Param("endTime") LocalDateTime endTime,
                         @Param("dayMinutes") Integer dayMinutes,
                         @Param("dayAmount") BigDecimal dayAmount,
                         @Param("nightMinutes") Integer nightMinutes,
                         @Param("nightAmount") BigDecimal nightAmount,
                         @Param("totalAmount") BigDecimal totalAmount,
                         @Param("discountAmount") BigDecimal discountAmount,
                         @Param("payableAmount") BigDecimal payableAmount,
                         @Param("status") String status,
                         @Param("adjustedBy") Long adjustedBy,
                         @Param("adjustReason") String adjustReason);

    /**
     * 支付成功：转入已支付并写全支付字段。
     *
     * <p><b>{@code AND status = 'PENDING_PAYMENT'} 是幂等的关键一道</b>：
     * 支付回调会重推，两条回调同时进来时只有一个能拿到 1 行受影响，
     * 另一个拿到 0 —— 调用方据此判定「已被处理过」，不再重复累加用户消费额。
     *
     * <p>{@code confirmedBy} 为空表示系统自动确认（线上回调或 0 元结清），
     * 非空表示管理员人工核销。
     *
     * @param id            订单 ID
     * @param paymentMethod 支付通道名，人工核销传 {@code QR_UPLOAD}
     * @param paymentNo     支付平台交易号，人工核销时可空
     * @param paidAt        支付完成时刻
     * @param confirmedBy   核销管理员 ID，系统自动确认时传 null
     * @return 受影响行数；0 表示订单不是待支付状态，或记录不存在
     */
    @Update("""
            UPDATE biz_order
               SET status         = 'PAID',
                   payment_method = #{paymentMethod},
                   payment_no     = #{paymentNo},
                   paid_at        = #{paidAt},
                   confirmed_by   = #{confirmedBy},
                   updated_at     = NOW()
             WHERE id = #{id}
               AND status = 'PENDING_PAYMENT'
               AND deleted = 0
            """)
    int markPaid(@Param("id") Long id,
                 @Param("paymentMethod") String paymentMethod,
                 @Param("paymentNo") String paymentNo,
                 @Param("paidAt") LocalDateTime paidAt,
                 @Param("confirmedBy") Long confirmedBy);

    /**
     * 用户提交支付凭证（人工核销的降级路径）。
     *
     * <p>上传截图这个动作本身就等于「声明走人工核销」，所以顺手把
     * {@code payment_method} 标成 {@code QR_UPLOAD}。若用户之后又走线上支付成功，
     * {@link #markPaid} 会把它覆盖成实际通道，这里写的值自然失效 —— 无害。
     *
     * <p>订单仍留在 {@code PENDING_PAYMENT}，等管理员核销才转 {@code PAID}。
     *
     * @param id           订单 ID
     * @param paymentProof 截图存储路径
     * @return 受影响行数；0 表示订单不是待支付状态，或记录不存在
     */
    @Update("""
            UPDATE biz_order
               SET payment_proof  = #{paymentProof},
                   payment_method = 'QR_UPLOAD',
                   updated_at     = NOW()
             WHERE id = #{id}
               AND status = 'PENDING_PAYMENT'
               AND deleted = 0
            """)
    int updatePaymentProof(@Param("id") Long id, @Param("paymentProof") String paymentProof);
}
