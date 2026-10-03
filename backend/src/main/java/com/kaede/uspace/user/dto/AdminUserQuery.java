package com.kaede.uspace.user.dto;

import lombok.Data;

/**
 * 运营后台用户列表的查询条件（模块 1）。
 *
 * <p>每个字段为 null 都表示「这一维不筛」。<b>刻意不给默认值</b> ——
 * 默认「全部」是由 null 表达的，给成空串或 0 会让「不筛」与
 * 「筛一个恰好为空的值」变得无法区分。
 *
 * <p>⚠️ <b>排序不在这个类里</b>：排序字段要拼进 SQL（{@code ORDER BY} 的位置
 * 不能用占位符），所以由 Service 做白名单映射后再单独传给 Mapper，
 * 不跟着用户可控的查询条件一起走。
 */
@Data
public class AdminUserQuery {

    /**
     * 关键字，同时匹配登录名、昵称与 QQ 号。
     *
     * <p>不提供「按哪个字段搜」的选项：运营通常记得住其中一个，
     * 让他先选再搜是多余的一步。三个字段都没有索引，但用户表规模在千级以内。
     */
    private String keyword;

    /** 角色筛选：{@code USER} / {@code ADMIN}。为 null 时不筛 */
    private String role;

    /** 状态筛选：{@code 1}=正常 / {@code 0}=禁用。为 null 时不筛 */
    private Integer status;

    /**
     * 是否只看「当前有生效月卡」的用户：{@code true}=只看有，{@code false}=只看没有，
     * {@code null}=不筛。
     *
     * <p>用 {@code Boolean} 而不是基本类型，正是为了留出第三种「不筛」的状态 ——
     * 用 {@code boolean} 的话「不筛」与「只看没有」都是 false，两者混在一起。
     */
    private Boolean hasCard;

    /**
     * 卡种筛选：{@code ALL_DAY} / {@code NIGHT}。为 null 时不管卡种。
     *
     * <p>它只在 {@link #hasCard} 为 {@code true} 时有意义 ——
     * 「没有月卡」的用户自然谈不上卡种。Service 会在 hasCard 不为 true 时把它丢掉，
     * 免得前端传了个卡种却因为 hasCard 没传而筛不出任何东西。
     */
    private String cardType;
}
