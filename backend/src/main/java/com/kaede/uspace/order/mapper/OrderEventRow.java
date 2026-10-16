package com.kaede.uspace.order.mapper;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条「进出事件」的查询结果行（后台进出时间线用）。
 *
 * <p>它由一条 {@code UNION ALL} 查询产出：到店事件取 {@code start_time}、
 * 离店事件取 {@code end_time}，两类在 SQL 里被拍成同一个形状，
 * 于是「按时间排一条线」只靠一句 {@code ORDER BY event_time} 就够了 ——
 * 不必在 Java 里合并两个列表（那样分页会算不准，页边界上的事件会重复或丢失）。
 *
 * <p><b>它是查询行而不是视图</b>：对外的返回体是
 * {@link com.kaede.uspace.order.dto.OrderTrafficEventVo}，那里多了昵称、
 * 名册快照与中文标签 —— 那些要另外查，不该由 Mapper 操心。
 */
@Data
public class OrderEventRow {

    /** 订单 ID */
    private Long id;

    /** 对外单号（复盘时按它翻监控） */
    private String orderNo;

    /** 当事人用户 ID */
    private Long userId;

    /** 订单状态（卡片上那个标签用） */
    private String status;

    /**
     * 事件类型：{@code ARRIVE} 到店 / {@code LEAVE} 离店。
     *
     * <p>值是 SQL 里的字符串字面量，与 {@code OrderTrafficEventVo} 的中文标签
     * 由同一个枚举翻译（见 {@code OrderTrafficEventVo.of}），不在两处各写一份。
     */
    private String eventType;

    /** 事件时刻：到店事件是开始计时，离店事件是停止计时 */
    private LocalDateTime eventTime;
}
