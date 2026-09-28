package com.kaede.uspace.space.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 门店实体，对应 {@code biz_store} 表。
 *
 * <p><b>为什么是「门店」而不是「房间」</b>：本系统的空间是共享的 ——
 * 平时多组顾客同时在店内各玩各的，不存在「一间房被一组人占用、别人进不来」
 * 的语义。而真实的扩张路径是<b>开分店</b>：一个品牌下多个门店，
 * 每个门店就是一个共享娱乐空间。详见 {@code docs/sql/schema.sql} 中的表注释。
 *
 * <p><b>本实体刻意只有四个业务字段</b>，没有任何状态位：
 * 「今天维护不对外营业」与「某时段被包场」都是<b>时间维度</b>的准入规则，
 * 分别在 {@link Closure} 与 {@link Booking} 两张表里，不压成门店的 status ——
 * 压成状态位就表达不了「明天 10:00–14:00 维护」这种提前安排。
 *
 * <p>由于没有状态位，本表在运营中几乎是只读的：门店名字与地址偶尔改一次，
 * 因此不提供新增与删除接口（单门店下没有这个场景，开分店时再加）。
 *
 * @see Closure 停业记录
 * @see Booking 包场
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_store")
public class Store extends BaseEntity {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /** 门店名称，对外展示。有唯一索引，不允许重名 */
    private String name;

    /** 门店地址。供用户端展示与导航，可为空 */
    private String address;

    /** 门店说明，如「6 台音游机，可同时容纳 12 人」。可为空 */
    private String description;
}
