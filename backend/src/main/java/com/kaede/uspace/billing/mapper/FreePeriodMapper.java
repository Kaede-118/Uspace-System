package com.kaede.uspace.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.billing.entity.FreePeriod;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 免费时段（活动）的数据访问接口。
 *
 * <p>五个查询分服务四件事，<b>区间判断口径完全一致</b> —— 都是半开区间
 * {@code [start_at, end_at)}，交集判定用 {@code start_a < end_b AND end_a > start_b}：
 * <ul>
 *   <li>{@link #selectPageByStore} —— 后台列表</li>
 *   <li>{@link #countOverlapping} —— 新增 / 修改时校验两场活动不打架</li>
 *   <li>{@link #selectCoveringAt} —— 「此刻是否在免费区间内」（单点）</li>
 *   <li>{@link #selectOverlapping} —— <b>计费侧</b>：把与订单区间有交集的活动全部取出来，
 *       交给 {@code BillingService} 做段级置零</li>
 *   <li>{@link #selectUpcoming} —— 用户端「近期免费活动」</li>
 * </ul>
 *
 * <p><b>手写 SQL 必须自己带 {@code deleted = 0}</b>：全局逻辑删除配置只作用于
 * MyBatis-Plus 自己生成的方法，对注解里手写的 SQL 不生效。
 * 漏掉的后果是「删掉的活动还在免单」—— 从界面上完全看不出来，
 * 只会在对账时发现怎么少收了钱。
 */
public interface FreePeriodMapper extends BaseMapper<FreePeriod> {

    /**
     * 分页查询某门店的活动。
     *
     * <p>按开始时间倒序 —— 与停业记录同一个理由：运营看这张表多半想知道
     * 「接下来哪天有活动」以及「上一次办的是什么」，最近的在最前面最顺手。
     *
     * @param page    分页参数，由 MyBatis-Plus 的分页插件处理
     * @param storeId 门店 ID
     * @return 分页结果
     */
    @Select("""
            SELECT *
              FROM biz_free_period
             WHERE deleted = 0
               AND store_id = #{storeId}
             ORDER BY start_at DESC
            """)
    IPage<FreePeriod> selectPageByStore(IPage<FreePeriod> page, @Param("storeId") Long storeId);

    /**
     * 统计与给定时段有交集的活动数。
     *
     * <p>用于新增与修改时校验。两场活动重叠虽然不致命（都是免费），
     * 但会让后台列表出现含义重复的记录 —— 运营看到两条「跨年活动」
     * 也不知道该改哪一条，而计费侧还要多切一刀（多切一段就多享一次宽限）。
     * 因此直接拒绝，让管理员合并成一条。
     *
     * @param storeId   门店 ID
     * @param startAt   待校验时段的开始时刻
     * @param endAt     待校验时段的结束时刻
     * @param excludeId 要排除的记录 ID（修改自己时传自己的 ID，新增时传 null）
     * @return 有交集的活动数；0 表示不冲突
     */
    @Select("""
            SELECT COUNT(*)
              FROM biz_free_period
             WHERE deleted = 0
               AND store_id = #{storeId}
               AND start_at < #{endAt}
               AND end_at > #{startAt}
               AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    int countOverlapping(@Param("storeId") Long storeId,
                         @Param("startAt") LocalDateTime startAt,
                         @Param("endAt") LocalDateTime endAt,
                         @Param("excludeId") Long excludeId);

    /**
     * 查覆盖某个时刻的活动。
     *
     * <p>与停业那张表同款：报价与营业状态展示用它回答「此刻免不免费」，
     * 不必把整张表拉回来自己判。
     *
     * @param storeId 门店 ID
     * @param time    待判断的时刻
     * @return 命中的活动；不在任何活动区间内时返回 null
     */
    @Select("""
            SELECT *
              FROM biz_free_period
             WHERE deleted = 0
               AND store_id = #{storeId}
               AND start_at <= #{time}
               AND end_at > #{time}
             ORDER BY start_at DESC
             LIMIT 1
            """)
    FreePeriod selectCoveringAt(@Param("storeId") Long storeId, @Param("time") LocalDateTime time);

    /**
     * 查与给定区间有交集的所有活动（<b>计费侧用</b>）。
     *
     * <p>返回的是「活动本身」，<b>不是</b>「活动与订单区间的交集」——
     * 裁剪交给 {@code BillingService} 的切段逻辑做。在这里先裁的话，
     * 边界口径就散成了两处，而两边都「看起来合理」。
     *
     * <p>按 {@code start_at} 升序：切段时按时间顺序找边界更直观。
     *
     * @param storeId 门店 ID
     * @param from    订单计费区间起点
     * @param to      订单计费区间终点
     * @return 有交集的活动；没有时返回空列表
     */
    @Select("""
            SELECT *
              FROM biz_free_period
             WHERE deleted = 0
               AND store_id = #{storeId}
               AND start_at < #{to}
               AND end_at > #{from}
             ORDER BY start_at
            """)
    List<FreePeriod> selectOverlapping(@Param("storeId") Long storeId,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    /**
     * 查未来的活动（用户端「近期免费活动」用）。
     *
     * <p>{@code end_at > from} 而不是 {@code start_at > from}：<b>正在进行的活动也要显示</b> ——
     * 「今晚 20:00–次日 02:00 免费」这条在 21:00 打开首页时正是最该看见的。
     *
     * @param storeId 门店 ID
     * @param from    起始时刻（通常是「现在」）
     * @param limit   最多返回几条
     * @return 尚未结束的活动，按开始时间升序
     */
    @Select("""
            SELECT *
              FROM biz_free_period
             WHERE deleted = 0
               AND store_id = #{storeId}
               AND end_at > #{from}
             ORDER BY start_at
             LIMIT #{limit}
            """)
    List<FreePeriod> selectUpcoming(@Param("storeId") Long storeId,
                                    @Param("from") LocalDateTime from,
                                    @Param("limit") int limit);

    /**
     * 更新活动的时段与名称。
     *
     * <p><b>为什么不走 {@code updateById}</b>：MyBatis-Plus 的默认字段策略会
     * <b>跳过 null 字段</b>，而「把活动名称清空」在本表是完全正常的操作 ——
     * 走 {@code updateById} 的话它做不到，且不报任何错
     * （接口 200、刷新后旧名称还在）。模块 1 / 3 / 4 与公告包都踩过同一个坑，
     * 这里从一开始就用显式 SQL。
     *
     * @param id      活动 ID
     * @param startAt 新的开始时刻
     * @param endAt   新的结束时刻
     * @param reason  活动名称，可为 null（清空）
     * @return 受影响行数；0 表示记录不存在或已删除
     */
    @Update("""
            UPDATE biz_free_period
               SET start_at   = #{startAt},
                   end_at     = #{endAt},
                   reason     = #{reason},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updatePeriod(@Param("id") Long id,
                     @Param("startAt") LocalDateTime startAt,
                     @Param("endAt") LocalDateTime endAt,
                     @Param("reason") String reason);
}
