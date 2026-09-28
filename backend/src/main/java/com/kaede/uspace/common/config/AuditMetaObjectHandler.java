package com.kaede.uspace.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充。
 *
 * <p>插入时填 {@code created_at} 与 {@code updated_at}，更新时填 {@code updated_at}，
 * 业务代码不必手工赋值。
 *
 * <p><b>为什么必须自动填而不是各业务方法自己写</b>：这两列在库表里是
 * {@code NOT NULL} 且<b>没有默认值</b>，任何一处漏填都会在插入时抛
 * 「Column 'created_at' cannot be null」。靠人记着写，迟早会漏；
 * 交给框架统一处理，漏的可能性就从「有多少个插入方法」降到零。
 *
 * <p><b>时间取应用服务器的时钟，不用 SQL 的 {@code NOW()}</b>：
 * 应用与数据库可能不在同一台机器，时钟未必一致；
 * 统一取一处，排查问题时看到的时刻才与日志对得上。
 *
 * <p>用 {@code strictInsertFill} / {@code strictUpdateFill} 而非
 * {@code setFieldValByName}：前者只在实体字段确实标注了对应填充策略时才生效，
 * 因此对不继承 {@code BaseEntity} 的实体（如进出记录）完全无副作用，
 * 不会凭空给它们塞一个库里并不存在的列。
 */
@Slf4j
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入时填充：创建时间与更新时间都取当前时刻。
     *
     * @param metaObject 待插入的实体元对象
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }

    /**
     * 更新时填充：只更新更新时间。
     *
     * <p>创建时间不参与更新 —— 它记录的是「这行数据何时产生」，
     * 任何修改都不该改变这个事实。
     *
     * @param metaObject 待更新的实体元对象
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
