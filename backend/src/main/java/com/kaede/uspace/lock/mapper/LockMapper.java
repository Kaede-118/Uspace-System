package com.kaede.uspace.lock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kaede.uspace.lock.entity.Lock;
import org.apache.ibatis.annotations.Select;

/**
 * 门锁表的数据访问接口。
 *
 * <p>通过 {@code @MapperScan("com.kaede.uspace.**.mapper")} 自动注册，无需 {@code @Mapper} 注解。
 *
 * <p>本接口随模块 8 补上，当前只有一个方法 —— 单门店、单把锁，
 * 运营中不需要增删改（接入真实锁时改数据库即可）。将来开分店或做门锁管理后台时，
 * 再按需补 {@code selectByStore} 一类的查询。
 */
public interface LockMapper extends BaseMapper<Lock> {

    /**
     * 取当前门锁的 ID。
     *
     * <p><b>「当前门锁」= 表里唯一的那一条</b>，与 {@code StoreMapper#selectCurrentId()}
     * 是完全对称的写法（那边取门店，这边取锁）。单门店运营期间每个调用方
     * 都会传同一个值，因此不预留 {@code storeId} 参数 —— 那只会变成噪音。
     * 开分店时两者一起改成按门店查。
     *
     * <p>只查 ID 不查整行，是因为调用方（模块 8 的下单链路）只需要一个
     * {@code lockId} 去调门锁云，而本表还带着三个敏感字段（见 {@link Lock} 的说明）——
     * 少查一次就少一次把它们带进内存的机会。
     *
     * @return 当前门锁 ID；表为空时返回 null（此时无法下单，需先执行建表脚本）
     */
    @Select("SELECT id FROM biz_lock WHERE deleted = 0 ORDER BY id LIMIT 1")
    Long selectCurrentId();
}
