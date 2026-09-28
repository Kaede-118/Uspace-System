package com.kaede.uspace.space.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.space.entity.Closure;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 停业记录的数据访问接口。
 *
 * <p>三个查询方法分别服务于三件事，注意它们的<b>区间判断口径一致</b>——
 * 都是半开区间 {@code [start, end)}，重叠判定用
 * {@code start_a < end_b AND end_a > start_b}：
 * <ul>
 *   <li>{@link #selectPageByStore} —— 后台列表展示</li>
 *   <li>{@link #countOverlapping} —— 新增/修改时校验时段不打架</li>
 *   <li>{@link #countCovering} —— 下单时判断「此刻是否停业」</li>
 * </ul>
 *
 * <p><b>手写 SQL 必须自己带 {@code deleted = 0}</b>：全局逻辑删除配置只作用于
 * MyBatis-Plus 自己生成的方法，对注解里手写的 SQL 不生效。
 * 漏掉的后果是「删掉的停业记录还在拦下单」——这种 bug 从界面上完全看不出来。
 */
public interface ClosureMapper extends BaseMapper<Closure> {

    /**
     * 分页查询某门店的停业记录。
     *
     * <p>按开始时间倒序 —— 运营看这张表多半是想知道「接下来哪天不开门」
     * 以及「上一次为什么停」，最近的在最前面最顺手。
     *
     * @param page    分页参数，由 MyBatis-Plus 的分页插件处理
     * @param storeId 门店 ID
     * @return 分页结果
     */
    @Select("""
            SELECT *
              FROM biz_closure
             WHERE deleted = 0
               AND store_id = #{storeId}
             ORDER BY start_at DESC
            """)
    IPage<Closure> selectPageByStore(IPage<Closure> page, @Param("storeId") Long storeId);

    /**
     * 统计与给定时段重叠的停业记录数。
     *
     * <p>用于新增与修改时校验：两段停业重叠本身不致命（都是「不营业」），
     * 但会让后台列表出现含义重复的记录，运营看到两份「为什么停业」也不知道信哪个。
     * 因此直接拒绝，让管理员合并成一条。
     *
     * @param storeId   门店 ID
     * @param startAt   待校验时段的开始时刻
     * @param endAt     待校验时段的结束时刻
     * @param excludeId 要排除的记录 ID（修改自己时传自己的 ID，新增时传 null）
     * @return 重叠的记录数；0 表示不冲突
     */
    @Select("""
            SELECT COUNT(*)
              FROM biz_closure
             WHERE deleted = 0
               AND store_id = #{storeId}
               AND start_at <  #{endAt}
               AND end_at   >  #{startAt}
               AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    int countOverlapping(@Param("storeId") Long storeId,
                         @Param("startAt") LocalDateTime startAt,
                         @Param("endAt") LocalDateTime endAt,
                         @Param("excludeId") Long excludeId);

    /**
     * 查询覆盖给定时刻的停业记录。
     *
     * <p>供下单链路判断「此刻是否停业」，以及用户端展示「几点恢复营业」——
     * 后者需要停业的<b>结束时刻</b>，所以这里返回整条记录而不是一个计数。
     *
     * <p>停业原因虽然也一并查了出来，但<b>不对外披露</b>：
     * 用户端只显示「暂停营业」，「设备维护」「员工休假」这类原因属于运营内务。
     * 调用方自行把握哪一层用它。
     *
     * <p>排期时已校验不重叠，正常情况下至多命中一条。
     *
     * @param storeId 门店 ID
     * @param time    待判断的时刻
     * @return 覆盖该时刻的停业记录；没有则返回 null
     */
    @Select("""
            SELECT *
              FROM biz_closure
             WHERE deleted = 0
               AND store_id = #{storeId}
               AND start_at <= #{time}
               AND end_at   >  #{time}
             ORDER BY start_at DESC
             LIMIT 1
            """)
    Closure selectCoveringAt(@Param("storeId") Long storeId, @Param("time") LocalDateTime time);

    /**
     * 更新停业记录。
     *
     * <p>用显式 SQL 而非 {@code updateById}，理由同 {@code StoreMapper#updateStore}：
     * 配合 PUT 的全量替换语义，允许把原因清空。
     *
     * @param id      记录 ID
     * @param startAt 停业开始时刻
     * @param endAt   停业结束时刻
     * @param reason  停业原因，可为 null
     * @return 受影响行数；0 表示记录不存在或已删除
     */
    @Update("""
            UPDATE biz_closure
               SET start_at   = #{startAt},
                   end_at     = #{endAt},
                   reason     = #{reason},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateClosure(@Param("id") Long id,
                      @Param("startAt") LocalDateTime startAt,
                      @Param("endAt") LocalDateTime endAt,
                      @Param("reason") String reason);
}
