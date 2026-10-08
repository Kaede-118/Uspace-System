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
     * <p><b>只给 {@code GET /api/orders/current} 用</b> —— 用户端首页靠它判断
     * 「当前有没有在玩」，进而决定按钮是「开门」还是「查看密码」。
     * 正因为这个用途，它<b>只认 {@code IN_USE}</b>：把已结算的 {@code PENDING_PAYMENT}
     * 也塞进来的话，用户会看到一个「查看密码」，而那时密码早随结算撤销了。
     *
     * <p><b>下单前的防连点不再用它，改用 {@link #selectUnsettledByUser}</b> ——
     * 那个要连欠费的订单一起拦。两个方法不要合并：
     * 合并后无论迁就哪一边，另一边都是错的，而且错得不报任何错。
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
     * 查询某人最近一条「尚未结清」的订单（{@code IN_USE} 或 {@code PENDING_PAYMENT}）。
     *
     * <p><b>这个方法挡的是两件事</b>，它们的共同点是「上一单还没了结，就别开新单」：
     * <ol>
     *   <li><b>连点两下开门</b> —— 手指抖一下就会产生两个密码、两条并行计费的订单，
     *       用户要付两份钱（这是 {@link #selectActiveByUser} 原先挡的那件）</li>
     *   <li><b>欠费再进场</b> —— 结算了但不付款的账号可以再开一单，欠着费接着玩。
     *       早先只查 {@code IN_USE}，这条路是通的</li>
     * </ol>
     * 调用方（{@code OrderService#createOrder}）按返回订单的状态分辨是哪种情形，
     * 给出各自的错误码与提示 —— 一个引导去「结束使用」，一个引导去「去支付」。
     *
     * <p><b>三种状态都算「未了结」</b>：{@code IN_USE}（还在玩）、
     * {@code PENDING_PAYMENT}（玩完没付）、{@code REJECTED}（付了但凭证被驳回）。
     * {@code PAID} 已经了结，不拦；库表注释里那两个保留值
     *（{@code CREATED} / {@code CANCELLED}）当前流程不产生，真出现了也不该拦着他。
     *
     * <p>⚠️ <b>{@code REJECTED} 漏不得</b>：有被驳回的账挂着的人是潜在的欠费者，
     * 得先把那笔处理掉才能再开单。漏掉它的表现是「凭证被驳回之后他照样进店玩」，
     * 而那笔钱永远悬着 —— 且不会有任何报错。
     *
     * <p>取 {@code ORDER BY id DESC LIMIT 1} 而不是要求至多一条：
     * 历史上可能同时存在一笔使用中与一笔待支付的（正是本次修复前的漏洞造成的），
     * 返回最新的那条即可，抛异常反而会让用户彻底下不了单。
     *
     * @param userId 用户 ID
     * @return 最近一条未结清的订单；都没有则返回 null
     */
    @Select("""
            SELECT *
              FROM biz_order
             WHERE deleted = 0
               AND user_id = #{userId}
               AND status IN ('IN_USE', 'PENDING_PAYMENT', 'REJECTED')
             ORDER BY id DESC
             LIMIT 1
            """)
    Order selectUnsettledByUser(@Param("userId") Long userId);

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
     * 统计某人的累计在店时长（分钟），含全部历史。
     *
     * <p>与 {@link #selectMonthPaidAmount} 并列的另一半口径：那个答「花了多少钱」，
     * 本方法答「玩了多久」。「我的」页把两个数字并排显示。
     *
     * <p><b>只算 {@code PAID}</b>，与消费口径一致。把 {@code IN_USE} 那一单算进去的话，
     * 数字每次刷新都会往上跳，而且它还没定局（用户随时可能结束使用）。
     *
     * <p><b>用 {@code stay_minutes} 而不是 {@code day_minutes + night_minutes}</b>：
     * 后两者是计费时长，包场时段被剪掉了、宽限的 5 分钟也不计入 ——
     * 包场用户会看到「累计时长 0 分钟」。两个口径的差别见
     * {@link Order#getStayMinutes()}。
     *
     * <p>COALESCE 让没有任何记录时返回 0 而不是 null，前者可以直接参与算术。
     *
     * @param userId 用户 ID
     * @return 累计在店分钟数；无记录时返回 0
     */
    @Select("""
            SELECT COALESCE(SUM(stay_minutes), 0)
              FROM biz_order
             WHERE deleted = 0
               AND user_id = #{userId}
               AND status  = 'PAID'
            """)
    Long selectTotalStayMinutes(@Param("userId") Long userId);

    /**
     * 统计某人某月的在店时长（分钟）。
     *
     * <p><b>归月字段与 {@link #selectMonthPaidAmount} 逐字一致</b>（同样是
     * {@code start_time} 的<b>半开</b>区间 {@code [from, to)}）——
     * 两个数字在「我的」页并排显示，归月口径一旦有出入，跨月那一刻就会出现
     * 「消费算上月、时长算本月」的错位，而且不报任何错。
     * 调用方传「月初」与「下月初」，与那个方法共用同一处区间计算。
     *
     * <p>按 {@code start_time} 而不是 {@code end_time} 归月：跨零点结算的夜单
     * 不该跳到下个月，这与月度优惠的归集口径也是同一条。
     *
     * @param userId 用户 ID
     * @param from   区间起点（含），通常是当月 1 日 00:00
     * @param to     区间终点（不含），通常是次月 1 日 00:00
     * @return 该月累计在店分钟数；无记录时返回 0
     */
    @Select("""
            SELECT COALESCE(SUM(stay_minutes), 0)
              FROM biz_order
             WHERE deleted = 0
               AND user_id    = #{userId}
               AND status     = 'PAID'
               AND start_time >= #{from}
               AND start_time <  #{to}
            """)
    Long selectMonthStayMinutes(@Param("userId") Long userId,
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
     * 记下最新一串一次性密码（模块 11 的群指令 {@code /开门} 用）。
     *
     * <p>每次 {@code /开门} 都会取一串新的并<b>覆盖</b>这里 —— 不做复用，
     * 因为「判断旧的那串还在不在」同样要花一次门锁云调用，与直接生成成本相同
     * （详见 {@code OneTimePasscodeService} 的类注释）。
     *
     * <p>写它只为两件事：<b>结算时知道该撤哪一串</b>、以及排障时查得到
     * （列值在结算后不清空，与 {@code passcode} 三列同构）。
     *
     * <p>与 {@link #updatePasscode} 一样带 {@code status = 'IN_USE'} 守卫：
     * 已结算的订单不该再被写进新密码。<b>用显式 SQL 而不是 {@code updateById}</b> ——
     * 后者的「跳过 null 字段」语义写不出清空，这个坑本项目已经踩过数次。
     *
     * @param id       订单 ID
     * @param passcode 一次性密码
     * @param endTime  失效时刻（生成起 6 小时）
     * @return 受影响行数；0 表示订单不是使用中状态，或记录不存在
     */
    @Update("""
            UPDATE biz_order
               SET one_time_passcode     = #{passcode},
                   one_time_passcode_end = #{endTime},
                   updated_at            = NOW()
             WHERE id = #{id}
               AND status = 'IN_USE'
               AND deleted = 0
            """)
    int updateOneTimePasscode(@Param("id") Long id,
                              @Param("passcode") String passcode,
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
     * @param stayMinutes    在店时长（分钟），见 {@link Order#getStayMinutes()}
     * @param dayMinutes     日场时长（分钟）
     * @param dayAmount      日场实收
     * @param nightMinutes   夜场时长（分钟）
     * @param nightAmount    夜场实收
     * @param totalAmount    实收合计（已扣月卡免除）
     * @param discountAmount 月度优惠金额（说明性，已含在合计中）
     * @param cardFreeAmount 月卡免掉的金额（说明性，已从合计中扣除）
     * @param activityFreeAmount 活动免掉的金额（说明性，已从合计中扣除）
     * @param payableAmount  应付金额
     * @param status         目标状态名
     * @param billSnapshot   分段账单快照（JSON）。序列化失败时传 null ——
     *                       快照是展示用的副本，不该因为它写不进去而让结算失败
     * @return 受影响行数；0 表示订单不是使用中状态，或记录不存在
     */
    @Update("""
            UPDATE biz_order
               SET end_time         = #{endTime},
                   stay_minutes     = #{stayMinutes},
                   day_minutes      = #{dayMinutes},
                   day_amount       = #{dayAmount},
                   night_minutes    = #{nightMinutes},
                   night_amount     = #{nightAmount},
                   total_amount     = #{totalAmount},
                   discount_amount  = #{discountAmount},
                   card_free_amount = #{cardFreeAmount},
                   activity_free_amount = #{activityFreeAmount},
                   payable_amount   = #{payableAmount},
                   bill_snapshot    = #{billSnapshot},
                   status           = #{status},
                   updated_at       = NOW()
             WHERE id = #{id}
               AND status = 'IN_USE'
               AND deleted = 0
            """)
    int updateSettlement(@Param("id") Long id,
                         @Param("endTime") LocalDateTime endTime,
                         @Param("stayMinutes") Integer stayMinutes,
                         @Param("dayMinutes") Integer dayMinutes,
                         @Param("dayAmount") BigDecimal dayAmount,
                         @Param("nightMinutes") Integer nightMinutes,
                         @Param("nightAmount") BigDecimal nightAmount,
                         @Param("totalAmount") BigDecimal totalAmount,
                         @Param("discountAmount") BigDecimal discountAmount,
                         @Param("cardFreeAmount") BigDecimal cardFreeAmount,
                         @Param("activityFreeAmount") BigDecimal activityFreeAmount,
                         @Param("payableAmount") BigDecimal payableAmount,
                         @Param("status") String status,
                         @Param("billSnapshot") String billSnapshot);

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
     * @param stayMinutes    在店时长（分钟），按调整后的离场时刻重算
     * @param dayMinutes     日场时长（分钟）
     * @param dayAmount      日场实收
     * @param nightMinutes   夜场时长（分钟）
     * @param nightAmount    夜场实收
     * @param totalAmount    实收合计（已扣月卡免除）
     * @param discountAmount 月度优惠金额
     * @param cardFreeAmount 月卡免掉的金额
     * @param activityFreeAmount 活动免掉的金额
     * @param payableAmount  应付金额
     * @param status         目标状态名
     * @param adjustedBy     调整人（管理员 ID）
     * @param adjustReason   调整原因
     * @param billSnapshot   分段账单快照（JSON）。人工调整会重算账单，
     *                       因此快照要【跟着重写】—— 否则详情页展示的还是调整前那一份
     * @return 受影响行数；0 表示订单已支付/已删除，或记录不存在
     */
    @Update("""
            UPDATE biz_order
               SET end_time         = #{endTime},
                   stay_minutes     = #{stayMinutes},
                   day_minutes      = #{dayMinutes},
                   day_amount       = #{dayAmount},
                   night_minutes    = #{nightMinutes},
                   night_amount     = #{nightAmount},
                   total_amount     = #{totalAmount},
                   discount_amount  = #{discountAmount},
                   card_free_amount = #{cardFreeAmount},
                   activity_free_amount = #{activityFreeAmount},
                   payable_amount   = #{payableAmount},
                   bill_snapshot    = #{billSnapshot},
                   status           = #{status},
                   adjusted         = 1,
                   adjusted_by      = #{adjustedBy},
                   adjusted_at      = NOW(),
                   adjust_reason    = #{adjustReason},
                   updated_at       = NOW()
             WHERE id = #{id}
               AND status IN ('IN_USE', 'PENDING_PAYMENT')
               AND deleted = 0
            """)
    int updateAdjustment(@Param("id") Long id,
                         @Param("endTime") LocalDateTime endTime,
                         @Param("stayMinutes") Integer stayMinutes,
                         @Param("dayMinutes") Integer dayMinutes,
                         @Param("dayAmount") BigDecimal dayAmount,
                         @Param("nightMinutes") Integer nightMinutes,
                         @Param("nightAmount") BigDecimal nightAmount,
                         @Param("totalAmount") BigDecimal totalAmount,
                         @Param("discountAmount") BigDecimal discountAmount,
                         @Param("cardFreeAmount") BigDecimal cardFreeAmount,
                         @Param("activityFreeAmount") BigDecimal activityFreeAmount,
                         @Param("payableAmount") BigDecimal payableAmount,
                         @Param("status") String status,
                         @Param("adjustedBy") Long adjustedBy,
                         @Param("adjustReason") String adjustReason,
                         @Param("billSnapshot") String billSnapshot);

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
     * @return 受影响行数；0 表示订单不在可落账的状态上（既不是待支付、
     *         也不是「凭证未通过」），或记录不存在
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
               AND status IN ('PENDING_PAYMENT', 'REJECTED')
               AND deleted = 0
            """)
    int markPaid(@Param("id") Long id,
                 @Param("paymentMethod") String paymentMethod,
                 @Param("paymentNo") String paymentNo,
                 @Param("paidAt") LocalDateTime paidAt,
                 @Param("confirmedBy") Long confirmedBy);

    /**
     * 把一笔已支付的订单标记为「凭证未通过」（管理员驳回付款凭证）。
     *
     * <p><b>目标状态是 {@code REJECTED} 而不是 {@code PENDING_PAYMENT}</b>：
     * 那会让一个状态承载两件处置方式完全不同的事 ——「他还付没付钱」与
     * 「他付过了但凭证没通过」。前者的处置是「去支付」，后者是「重新上传一张截图」。
     * 合并的话，用户看到「待支付 ¥8.00」很可能再付一次钱。
     *
     * <p><b>支付四列一并清空</b>：一份「凭证未通过」却挂着 {@code paid_at}
     * 的单子自相矛盾，而且它已经把状态让给了「被驳回」这件事 ——
     * 留着那些字段只会让查询结果说不清。付款时的流水号并不丢，
     * 它在凭证表（{@code biz_payment_proof.payment_no}）里还留着，
     * 那才是可追溯的凭据。
     *
     * <p><b>状态守卫是这个方法的全部要害</b>（{@code AND status = 'PAID'}）：
     * 两个管理员同时点驳回时只有一个能改成功，另一个拿到 0 行，
     * 调用方据此跳过「减累计消费」那一步。少了它，同一笔钱会被减两次。
     *
     * @param id 订单 ID
     * @return 受影响行数；0 表示订单不是已支付状态，或记录不存在
     */
    @Update("""
            UPDATE biz_order
               SET status         = 'REJECTED',
                   payment_method = NULL,
                   payment_no     = NULL,
                   paid_at        = NULL,
                   confirmed_by   = NULL,
                   updated_at     = NOW()
             WHERE id = #{id}
               AND status = 'PAID'
               AND deleted = 0
            """)
    int markRejected(@Param("id") Long id);
}
