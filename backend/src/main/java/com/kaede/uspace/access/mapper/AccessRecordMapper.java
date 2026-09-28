package com.kaede.uspace.access.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.access.entity.AccessRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 开门记录的数据访问接口。
 *
 * <p><b>⚠️ 本接口是项目里唯一一个手写 SQL「不能」带 {@code deleted = 0} 的 Mapper。</b>
 * {@code biz_access_record} 是 append-only 的事件日志，表上没有 {@code deleted} 列
 * （也没有 {@code updated_at}）。而项目里其余 Mapper（门店、包场、停业、用户）
 * <b>全都要写</b> —— 因为全局逻辑删除配置只作用于 MyBatis-Plus 自己生成的方法，
 * 管不到注解里手写的 SQL。照抄别处的写法会直接
 * {@code Unknown column 'deleted' in 'where clause'}，且只有真跑到这条 SQL 才炸。
 *
 * <p><b>时间区间一律半开 {@code [from, to)}</b>，与 {@code space} 包的口径一致
 * （{@code 10:00–12:00} 与 {@code 12:00–14:00} 相邻而不重叠）。注意上游
 * {@code LockService#listRecords} 是<b>闭区间</b>（两端都含），多出来的那条由
 * {@code AccessRecordService} 负责过滤。改这里的比较符前先看 Service 的注释。
 */
public interface AccessRecordMapper extends BaseMapper<AccessRecord> {

    /**
     * 分页查询某门店的开门记录（运营后台）。
     *
     * <p><b>排序必须带第二键 {@code id}</b>：同一秒可能有不止一条开门记录
     * （同一把锁连续开两次），只按 {@code open_time} 排序时数据库不保证同值行的相对顺序，
     * 翻页会在边界出现「同一行重复出现」或「整行漏掉」。
     *
     * @param page    分页参数，由 MyBatis-Plus 的分页插件处理
     * @param storeId 门店 ID
     * @param userId  按开门人过滤，传 null 表示不限
     * @param from    时间范围起点（含），传 null 表示不限
     * @param to      时间范围终点（不含），传 null 表示不限
     * @return 分页结果，按开门时刻倒序
     */
    @Select("""
            SELECT *
              FROM biz_access_record
             WHERE store_id = #{storeId}
               AND (#{userId} IS NULL OR user_id = #{userId})
               AND (#{from} IS NULL OR open_time >= #{from})
               AND (#{to} IS NULL OR open_time <  #{to})
             ORDER BY open_time DESC, id DESC
            """)
    IPage<AccessRecord> selectPageByStore(IPage<AccessRecord> page,
                                          @Param("storeId") Long storeId,
                                          @Param("userId") Long userId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /**
     * 分页查询某用户自己的开门记录（用户端）。
     *
     * <p><b>刻意不复用 {@link #selectPageByStore}</b>：那个方法的 {@code userId} 可空，
     * 传 null 的语义是「查全表」。用户端必须走一个<b>结构上不可能越权</b>的方法 ——
     * 这里的 {@code userId} 是必填参数，压根不存在「传空就查到别人记录」这个入口。
     *
     * @param page   分页参数
     * @param userId 用户 ID，必填
     * @return 分页结果，按开门时刻倒序
     */
    @Select("""
            SELECT *
              FROM biz_access_record
             WHERE user_id = #{userId}
             ORDER BY open_time DESC, id DESC
            """)
    IPage<AccessRecord> selectPageByUser(IPage<AccessRecord> page, @Param("userId") Long userId);

    /**
     * 查询某把锁在给定区间内已有的记录，按开门时刻升序。
     *
     * <p><b>两种场景共用本方法</b>，因此返回整行而不是只取 {@code open_time} 一列：
     * <ul>
     *   <li><b>批量同步判重</b> —— 一次取出窗口内所有已有时刻装进 Set，
     *       逐条比对，避免「每条记录查一次库」</li>
     *   <li><b>单条落库判重</b> —— 传一个只有一秒的窄窗口，取出的就是该秒的已有记录，
     *       可以直接原样返回给调用方</li>
     * </ul>
     * 两处若各写一条 SQL，就成了「改一处要改另一处」的隐患 ——
     * 半开区间的比较符尤其容易改漏，而改漏的后果是边界记录被重复写入或整条丢失，
     * 两种都不会报错。
     *
     * <p>走 {@code idx_lock_time (lock_id, open_time)} 索引，区间为半开 {@code [from, to)}。
     *
     * <p><b>调用方必须先把时刻截断到秒</b>再拿来比对 —— 原因见
     * {@code AccessRecord#openTime} 的字段注释（亚秒精度会被数据库四舍五入，
     * 导致比对永远不相等、判重静默失效）。
     *
     * @param lockId 锁 ID
     * @param from   区间起点（含）
     * @param to     区间终点（不含）
     * @return 区间内的记录，按开门时刻升序；没有则返回空列表
     */
    @Select("""
            SELECT *
              FROM biz_access_record
             WHERE lock_id = #{lockId}
               AND open_time >= #{from}
               AND open_time <  #{to}
             ORDER BY open_time ASC, id ASC
            """)
    List<AccessRecord> selectInWindow(@Param("lockId") Long lockId,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);
}
