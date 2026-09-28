package com.kaede.uspace.space.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kaede.uspace.space.entity.Store;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 门店表的数据访问接口。
 *
 * <p>通过 {@code @MapperScan("com.kaede.uspace.**.mapper")} 自动注册，无需 {@code @Mapper} 注解。
 *
 * <p>本表在运营中几乎是只读的 —— 门店名字与地址偶尔改一次，
 * 因此这里只有「查当前门店」与「改门店信息」两个方法，
 * 新增与删除走建表脚本（单门店下没有新增场景，开分店时再加）。
 */
public interface StoreMapper extends BaseMapper<Store> {

    /**
     * 取当前门店。
     *
     * <p><b>单门店运营期间，「当前门店」= 表里唯一的那一条</b>，
     * 因此这里取 {@code ORDER BY id LIMIT 1}。门店表设计上支持多门店，
     * 但现阶段数据只有一条，业务代码也就没有「用户在哪家店」这个上下文。
     *
     * <p>将来开分店时，这个方法的语义要改成「按传入的 storeId 查」——
     * 调用方（订单、门锁）届时必须能说清自己属于哪家店。
     * 之所以现在不预留 {@code storeId} 参数，是因为单门店下每个调用方
     * 都会传同一个值，那个参数只会变成噪音。
     *
     * @return 当前门店；表为空时返回 null（此时系统无法下单，需先执行建表脚本）
     */
    @Select("SELECT * FROM biz_store WHERE deleted = 0 ORDER BY id LIMIT 1")
    Store selectCurrent();

    /**
     * 取当前门店的 ID。
     *
     * <p>与 {@link #selectCurrent()} 是同一行，只是少查几个字段 ——
     * 大多数调用方（停业、包场、订单）只需要 ID 来做关联，
     * 并不关心门店叫什么、地址在哪。
     *
     * <p>分开一个方法而不是让调用方去 {@code selectCurrent().getId()}，
     * 是为了让「门店不存在」这个情形在调用点上看得见：本方法直接返回
     * {@code null}，调用方必须显式处理，而不是在某处撞上空指针。
     *
     * @return 当前门店 ID；表为空时返回 null（系统未初始化）
     */
    @Select("SELECT id FROM biz_store WHERE deleted = 0 ORDER BY id LIMIT 1")
    Long selectCurrentId();

    /**
     * 统计同名门店数。
     *
     * <p>用于改名前的查重，给出「该名称已被使用」这样的友好提示。
     * 真正保证唯一的是库上的唯一索引 {@code uk_name} ——
     * 应用层查重与插入之间存在竞态窗口，并发时由索引兜底。
     *
     * @param name      待查的名称
     * @param excludeId 要排除的门店 ID，传当前门店自己的 ID（改回原名时不该算冲突）
     * @return 同名门店数；0 表示可用
     */
    @Select("""
            SELECT COUNT(*)
              FROM biz_store
             WHERE deleted = 0
               AND name = #{name}
               AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    int countByName(@Param("name") String name, @Param("excludeId") Long excludeId);

    /**
     * 更新门店信息。
     *
     * <p>用显式 SQL 而非 {@code updateById}，是为了配合 PUT 的全量替换语义：
     * 传 null 即清空该字段（如撤销地址）。{@code updateById} 的默认策略
     * 会忽略 null 字段，那样地址就永远删不掉了。
     *
     * @param id          门店 ID
     * @param name        门店名称
     * @param address     门店地址，可为 null
     * @param description 门店说明，可为 null
     * @return 受影响行数；0 表示门店不存在或已删除
     */
    @Update("""
            UPDATE biz_store
               SET name        = #{name},
                   address     = #{address},
                   description = #{description},
                   updated_at  = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateStore(@Param("id") Long id,
                    @Param("name") String name,
                    @Param("address") String address,
                    @Param("description") String description);
}
