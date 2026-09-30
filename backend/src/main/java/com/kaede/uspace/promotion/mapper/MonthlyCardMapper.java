package com.kaede.uspace.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * 月卡的数据访问接口。
 *
 * <p><b>手写 SQL 必须自己带 {@code deleted = 0}</b> —— 全局逻辑删除配置
 * 只管 MyBatis-Plus 自己生成的方法，管不到注解里的 SQL。漏写的后果不是报错，
 * 而是「删掉的卡又冒出来」，所以每一条都要带上。
 *
 * <p>状态取值在注解 SQL 里只能写字面量（无法拼接枚举）。改动
 * {@link com.kaede.uspace.promotion.MonthlyCardStatus} 的取值时，
 * 记得同步检查本接口里的每一处。
 */
public interface MonthlyCardMapper extends BaseMapper<MonthlyCard> {

    /**
     * 查某个用户在指定日期生效的月卡。
     *
     * <p><b>四个条件缺一不可</b>，每一个挡掉一类静默的错账：
     * <ul>
     *   <li>{@code status = 'ACTIVE'} —— 挡掉已退款的卡。只判日期的话，
     *       管理员把卡退掉之后它仍然免单，钱一路免下去且没人会发现</li>
     *   <li>{@code start_date <= 日期} —— 挡掉还没生效的卡</li>
     *   <li>{@code end_date >= 日期} —— 挡掉已过期的卡。<b>含当日</b>，
     *       所以用 {@code >=} 而不是 {@code >}</li>
     *   <li>{@code deleted = 0} —— 挡掉逻辑删除的卡</li>
     * </ul>
     * 日期条件必须在，不能只信状态：到期翻转是定时任务做的，
     * 服务停过就会漏跑，那时过期卡的状态仍是 {@code ACTIVE}。
     *
     * <p>同一用户理论上不会有两张同时生效的卡（购卡时已校验），
     * 真出现了取最新的一张 —— 此时按最宽的那张算，对顾客有利。
     *
     * @param userId 用户 ID
     * @param date   判定日期
     * @return 生效中的月卡；没有则返回 null
     */
    @Select("""
            SELECT *
              FROM biz_monthly_card
             WHERE deleted = 0
               AND user_id    = #{userId}
               AND status     = 'ACTIVE'
               AND start_date <= #{date}
               AND end_date   >= #{date}
             ORDER BY id DESC
             LIMIT 1
            """)
    MonthlyCard selectActiveAt(@Param("userId") Long userId, @Param("date") LocalDate date);

    /**
     * 查某个用户的全部月卡，最近的在前。
     *
     * <p>供「我的卡包」使用：生效中的、已过期的、已退款的都要给用户看到，
     * 前端自己按状态分组。含待支付购买单的查询走另一个 Mapper。
     *
     * @param userId 用户 ID
     * @return 该用户的月卡列表，按 ID 倒序
     */
    @Select("""
            SELECT *
              FROM biz_monthly_card
             WHERE deleted = 0
               AND user_id = #{userId}
             ORDER BY id DESC
            """)
    List<MonthlyCard> selectByUser(@Param("userId") Long userId);

    /**
     * 把已过有效期的生效中卡翻转为已过期。
     *
     * <p>供定时任务调用。它<b>只让状态列与事实保持一致</b>（供列表展示与
     * 统计口径），免单判定本身同时校验状态与日期，所以这个任务漏跑
     * （服务停过）也不会出现「过期卡还在免单」。
     *
     * <p>条件是严格的 {@code end_date < 今天} —— {@code end_date = 今天}
     * 仍算有效，与 {@link #selectActiveAt} 的 {@code >=} 口径一致。
     *
     * <p>天然幂等：翻转过的卡不再是 {@code ACTIVE}，下一轮查不出来，
     * 因此不必记录「哪些处理过了」。
     *
     * @param today 今天的日期
     * @return 受影响行数
     */
    @Update("""
            UPDATE biz_monthly_card
               SET status     = 'EXPIRED',
                   updated_at = NOW()
             WHERE status = 'ACTIVE'
               AND end_date < #{today}
               AND deleted = 0
            """)
    int expireBefore(@Param("today") LocalDate today);

    /**
     * 分页查询月卡，供运营后台使用。
     *
     * <p>三个筛选条件都可空，空则不过滤 —— 用
     * {@code #{x} IS NULL OR ...} 的写法而不是动态标签，
     * 与用户列表的查询保持一致。
     *
     * @param page     分页参数，由 MyBatis-Plus 的分页插件处理
     * @param userId   持卡用户 ID，可空
     * @param status   状态，可空
     * @param cardType 卡类型，可空
     * @return 分页结果
     */
    @Select("""
            SELECT *
              FROM biz_monthly_card
             WHERE deleted = 0
               AND (#{userId}   IS NULL OR user_id   = #{userId})
               AND (#{status}   IS NULL OR #{status}   = '' OR status    = #{status})
               AND (#{cardType} IS NULL OR #{cardType} = '' OR card_type = #{cardType})
             ORDER BY id DESC
            """)
    IPage<MonthlyCard> selectPageBy(IPage<MonthlyCard> page,
                                    @Param("userId") Long userId,
                                    @Param("status") String status,
                                    @Param("cardType") String cardType);
}
